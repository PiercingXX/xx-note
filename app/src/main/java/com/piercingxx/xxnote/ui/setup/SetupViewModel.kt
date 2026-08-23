package com.piercingxx.xxnote.ui.setup

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.piercingxx.xxnote.data.CredentialEntity
import com.piercingxx.xxnote.data.SettingEntity
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.net.CredentialVault
import com.piercingxx.xxnote.net.HttpError
import com.piercingxx.xxnote.net.KeystoreKeyOps
import com.piercingxx.xxnote.sync.SyncEngine
import java.io.IOException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the seven-step wizard over [SetupLogic]. Every network or disk call
 * runs on Dispatchers.IO; state mutations land on the flow between hops.
 *
 * Secret discipline (R9/§15): the plaintext password lives in one volatile
 * field only while steps 2–7 need it to sign requests. It leaves this class
 * the instant it is sealed into Room at step 7 — or immediately on an auth
 * refusal, which reopens the account step pre-filled EXCEPT the secret. It
 * never enters [SetupState] past the account text field and never enters a
 * message line.
 *
 * The engine is constructed fresh per attempt (NOT via SyncGraph's cached
 * singleton): an auth failure followed by a corrected password must re-read
 * the credential row, and a cached engine would keep signing with the old
 * secret forever.
 */
class SetupViewModel(private val appContext: Context) : ViewModel() {

    private val _state = MutableStateFlow(SetupState(deviceName = Build.MODEL?.trim().orEmpty()))
    val state: StateFlow<SetupState> = _state.asStateFlow()

    /** Step 1 result, cached once validated. */
    private var endpoint: SetupLogic.Endpoint? = null

    /** The DSM password, held only while the wizard needs to sign requests. */
    @Volatile
    private var secret: String? = null

    // ---- field edits ---------------------------------------------------------

    fun editHost(value: String) = _state.update { it.copy(host = value) }

    fun editPort(value: String) =
        _state.update { it.copy(port = value.filter(Char::isDigit).take(5)) }

    fun editUser(value: String) = _state.update { it.copy(user = value) }

    fun editPassword(value: String) = _state.update { it.copy(password = value) }

    fun editNewFolder(value: String) = _state.update { it.copy(newFolder = value) }

    fun editDeviceName(value: String) = _state.update { it.copy(deviceName = value) }

    // ---- navigation ------------------------------------------------------------

    fun back() {
        val s = _state.value
        if (s.busy || s.step == SetupStep.HOST) return
        val previous = SetupStep.entries[s.step.ordinal - 1]
        _state.update { it.copy(step = previous, message = emptyList()) }
    }

    // ---- step 1: host + port -------------------------------------------------------

    fun continueHost() {
        val s = _state.value
        if (s.busy) return
        val problem = SetupLogic.endpointProblem(s.host, s.port)
        if (problem != null) {
            _state.update { it.copy(message = listOf(problem)) }
            return
        }
        endpoint = SetupLogic.endpoint(s.host, s.port)
        _state.update {
            it.copy(
                step = SetupStep.ACCOUNT,
                message = emptyList(),
                probeLines = emptyList(),
                folderRows = emptyList(),
                pickedPath = null,
                foundMd = 0,
                idLessMd = 0,
                syncLines = emptyList(),
                done = false,
            )
        }
    }

    // ---- step 2: account --------------------------------------------------------------

    fun continueAccount() {
        val s = _state.value
        if (s.busy) return
        if (s.user.isBlank()) {
            _state.update { it.copy(message = listOf("type the username")) }
            return
        }
        if (s.password.isNotEmpty()) secret = s.password
        if (secret == null) {
            _state.update { it.copy(message = listOf("type the password")) }
            return
        }
        _state.update {
            it.copy(user = s.user.trim(), password = "", step = SetupStep.TEST, message = emptyList())
        }
        runTest()
    }

    // ---- step 3: test --------------------------------------------------------------------

    fun runTest() {
        val s = _state.value
        val ep = endpoint ?: return
        val password = secret ?: return
        if (s.busy) return
        _state.update {
            it.copy(busy = true, message = listOf("asking ${ep.host}:${ep.port}…"), probeLines = emptyList())
        }
        viewModelScope.launch {
            val client = SetupLogic.buildClient(ep, basePath = "", user = s.user, password = password)
            val reach = withContext(Dispatchers.IO) { SetupLogic.reach(client) }
            val lines = buildList {
                when (reach) {
                    is SetupLogic.Reach.Reachable -> add(reach.verbatim)
                    is SetupLogic.Reach.Refused -> reach.verbatim?.let(::add)
                    is SetupLogic.Reach.TlsFailure -> reach.verbatim?.let(::add)
                    is SetupLogic.Reach.Unreachable -> reach.verbatim?.let(::add)
                }
                add(SetupLogic.describe(reach, ep.host))
            }
            _state.update { it.copy(busy = false, probeLines = lines) }
        }
    }

    fun continueTest() {
        if (_state.value.busy) return
        _state.update { it.copy(step = SetupStep.FOLDER, message = emptyList()) }
        browse()
    }

    // ---- step 4: browse ----------------------------------------------------------------------

    fun browse() {
        val s = _state.value
        val ep = endpoint ?: return
        val password = secret ?: return
        if (s.busy) return
        _state.update {
            it.copy(busy = true, message = listOf("listing candidate folders…"), folderRows = emptyList())
        }
        viewModelScope.launch {
            val client = SetupLogic.buildClient(ep, basePath = "", user = s.user, password = password)
            val probes = withContext(Dispatchers.IO) { SetupLogic.probePrefixes(client) }
            val rows = SetupLogic.PREFIX_CANDIDATES.map { path ->
                rowFor(path, probes[path])
            }
            val noneAnswered = rows.none { it.reachable }
            _state.update {
                it.copy(
                    busy = false,
                    folderRows = rows,
                    message = if (noneAnswered) {
                        listOf(
                            "none of the usual folders answered — " +
                                "check the address and account at earlier steps",
                        )
                    } else {
                        emptyList()
                    },
                )
            }
        }
    }

    private fun rowFor(path: String, reach: SetupLogic.Reach?): FolderRow = when (reach) {
        is SetupLogic.Reach.Reachable ->
            FolderRow(path, reachable = true, mdCount = SetupLogic.countMarkdown(reach.entries), note = "exists")
        is SetupLogic.Reach.Refused ->
            FolderRow(path, reachable = false, mdCount = 0, note = "HTTP ${reach.status}")
        is SetupLogic.Reach.TlsFailure ->
            FolderRow(path, reachable = false, mdCount = 0, note = "TLS refused")
        is SetupLogic.Reach.Unreachable ->
            FolderRow(path, reachable = false, mdCount = 0, note = "no answer")
        null -> FolderRow(path, reachable = false, mdCount = 0, note = "not probed")
    }

    /** Pick an existing folder from the probed rows. */
    fun pickExisting(path: String) {
        val s = _state.value
        if (s.busy) return
        val normalized = SetupLogic.normalizeBasePath(path)
        if (normalized == null) {
            _state.update { it.copy(message = listOf("$path does not work as a folder name")) }
            return
        }
        confirmChosen(normalized)
    }

    /** Type a new folder; MKCOL it level by level, then confirm. */
    fun createTypedFolder() {
        val s = _state.value
        val ep = endpoint ?: return
        val password = secret ?: return
        if (s.busy) return
        val normalized = SetupLogic.normalizeBasePath(s.newFolder)
        if (normalized == null) {
            _state.update { it.copy(message = listOf("type a folder path like Drive/Notes")) }
            return
        }
        _state.update {
            it.copy(busy = true, message = listOf("creating ${SetupLogic.displayPath(normalized)}…"))
        }
        viewModelScope.launch {
            val client = SetupLogic.buildClient(ep, basePath = "", user = s.user, password = password)
            val created = withContext(Dispatchers.IO) { SetupLogic.ensureFolders(client, normalized) }
            if (!created) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = listOf("the server refused to create ${SetupLogic.displayPath(normalized)}"),
                    )
                }
                return@launch
            }
            confirmChosen(normalized)
        }
    }

    // ---- step 5: confirm ---------------------------------------------------------------------------

    private fun confirmChosen(normalized: String) {
        val s = _state.value
        val ep = endpoint ?: return
        val password = secret ?: return
        _state.update {
            it.copy(busy = true, message = listOf("counting what is in ${SetupLogic.displayPath(normalized)}…"))
        }
        viewModelScope.launch {
            val client = SetupLogic.buildClient(ep, basePath = "", user = s.user, password = password)
            try {
                val scan = withContext(Dispatchers.IO) { SetupLogic.confirmFolder(client, normalized) }
                _state.update {
                    it.copy(
                        busy = false,
                        pickedPath = normalized,
                        foundMd = scan.found,
                        idLessMd = scan.idLess,
                        etagMode = scan.etagMode,
                        step = SetupStep.CONFIRM,
                        message = emptyList(),
                    )
                }
            } catch (e: CancellationException) {
                throw e // never swallow structured cancellation (M7)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = refusalWords(e, ep.host)) }
            }
        }
    }

    fun continueConfirm() {
        if (_state.value.busy) return
        _state.update { it.copy(step = SetupStep.DEVICE, message = emptyList()) }
    }

    // ---- step 6: device name -----------------------------------------------------------------------------

    fun continueDevice() {
        val s = _state.value
        if (s.busy) return
        val name = SetupLogic.deviceNameOrDefault(s.deviceName, Build.MODEL.orEmpty())
        _state.update { it.copy(deviceName = name, step = SetupStep.FIRST_SYNC, message = emptyList()) }
    }

    // ---- step 7: persist FIRST, then first sync ---------------------------------------------------------------

    fun startFirstSync() {
        val s = _state.value
        val ep = endpoint ?: return
        val path = s.pickedPath ?: return
        val passwordNow = secret ?: run {
            _state.update { it.copy(step = SetupStep.ACCOUNT, message = listOf("the password is gone — type it again")) }
            return
        }
        if (s.busy || s.done) return
        _state.update { it.copy(busy = true, syncLines = emptyList(), message = listOf("storing the configuration…")) }

        viewModelScope.launch {
            try {
                // Persist FIRST: a crash mid-first-sync must leave a configured,
                // resyncable install rather than a half-configured one (§12).
                val payload = SetupLogic.configPayload(ep, path, s.user, s.deviceName, s.etagMode)
                withContext(Dispatchers.IO) { persist(payload, passwordNow) }
                secret = null
                _state.update { it.copy(message = listOf("configuration stored. running the first sync…")) }

                val outcome = withContext(Dispatchers.IO) {
                    val store = VaultStore(appContext)
                    SyncEngine(
                        local = store,
                        remote = SetupLogic.buildClient(ep, path, s.user, passwordNow),
                        book = store,
                        deviceName = s.deviceName,
                    ).syncOnce()
                }
                when (outcome) {
                    is SyncEngine.SyncOutcome.Completed -> _state.update {
                        it.copy(
                            busy = false,
                            done = true,
                            message = emptyList(),
                            syncLines = listOf(
                                SetupLogic.completedSummary(
                                    pulled = outcome.pulled,
                                    pushed = outcome.pushed,
                                    merged = outcome.merged,
                                    forked = outcome.forked,
                                    trashed = outcome.trashed,
                                    resurrected = outcome.resurrected,
                                    nothing = outcome.nothing,
                                ),
                            ),
                        )
                    }
                    is SyncEngine.SyncOutcome.HaltedTrashSafety -> _state.update {
                        it.copy(
                            busy = false,
                            message = SetupLogic.haltedLines(outcome.wouldTrash, outcome.liveNotes),
                            step = SetupStep.FOLDER,
                        )
                    }
                    is SyncEngine.SyncOutcome.AuthFailed -> reopenAccountOnAuthFailure(outcome.status)
                }
            } catch (e: CancellationException) {
                throw e // never swallow structured cancellation (M7)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = refusalWords(e, ep.host)) }
            }
        }
    }

    /** §15 auth-failure row: verbatim status, account step pre-filled except the secret. */
    private fun reopenAccountOnAuthFailure(status: Int) {
        secret = null
        _state.update {
            it.copy(
                busy = false,
                step = SetupStep.ACCOUNT,
                password = "",
                message = SetupLogic.authReopenLines(status),
            )
        }
    }

    /** Seals via Keystore and writes the credential + setting rows. Blocking; call off main. */
    private suspend fun persist(payload: SetupLogic.ConfigPayload, password: String) {
        val sealed = CredentialVault(KeystoreKeyOps(SetupLogic.VAULT_KEY_ALIAS, strongBox = true))
            .seal(password.toByteArray(Charsets.UTF_8))
        val db = XxDatabase.builder(appContext).build()
        db.credentialDao().upsert(
            CredentialEntity(
                host = payload.credentialHost,
                basePath = payload.basePath,
                user = payload.user,
                sealedSecret = sealed,
                keyAlias = SetupLogic.VAULT_KEY_ALIAS,
            ),
        )
        for ((key, value) in payload.settings) {
            db.settingDao().put(SettingEntity(key = key, value = value))
        }
    }

    /** Verbatim-first words for any refused call (R10): status line, then plain words. */
    private fun refusalWords(e: Exception, host: String): List<String> = when (e) {
        is HttpError -> listOfNotNull(e.message, SetupLogic.describe(SetupLogic.Reach.Refused(e.status, null), host))
        is SSLException -> listOfNotNull(e.message, SetupLogic.describe(SetupLogic.Reach.TlsFailure(null), host))
        is IOException -> listOfNotNull(e.message, SetupLogic.describe(SetupLogic.Reach.Unreachable(null), host))
        else -> listOf("first sync failed: ${e.message ?: e.javaClass.simpleName}")
    }
}
