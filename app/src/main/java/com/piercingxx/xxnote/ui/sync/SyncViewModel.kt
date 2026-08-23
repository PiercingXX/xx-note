package com.piercingxx.xxnote.ui.sync

import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.data.AttachmentStore
import com.piercingxx.xxnote.data.SyncLogDao
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.net.CredentialVault
import com.piercingxx.xxnote.net.HttpError
import com.piercingxx.xxnote.net.KeystoreKeyOps
import com.piercingxx.xxnote.net.WebDavClient
import com.piercingxx.xxnote.sync.SyncGraph
import com.piercingxx.xxnote.sync.SyncWorker
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WS9's state holder (design §12 item 4, R10). Reads the Room reason-store
 * and settings on Dispatchers.IO, maps everything through [Wording], and
 * owns the two real-code-path actions: Test connection (a live PROPFIND)
 * and Sync now (expedited worker + log-tail polling until evidence lands).
 *
 * **Headline evidence ruling.** The engine persists successes (log rows),
 * trash-safety halts (ok=false rows), and auth refusals (the
 * `credentialStale` setting) — but a plain transport failure retries in the
 * worker without logging, by design. The screen therefore treats fresh
 * first-hand evidence from THIS surface (Test connection outcome) as the
 * authority for the unreachable/connected split, layered over the persisted
 * marks; with no failure evidence anywhere, the last logged success stands
 * as the honest last-known-good headline.
 */
class SyncViewModel(application: Application) : AndroidViewModel(application) {

    data class OutboxRow(val noteId: String, val op: String, val reason: String)

    data class ConflictRow(val originalId: String, val title: String)

    data class LogLine(val text: String, val ok: Boolean)

    /** Everything the Resolve sheet renders, loaded once on open. */
    data class ConflictPair(
        val originalId: String,
        val forkId: String,
        val originalTitle: String,
        val originalBody: String,
        val forkMarkedBody: String,
    )

    enum class Tone { CALM, ATTENTION }

    data class UiState(
        val loading: Boolean = true,
        val headline: String = "",
        val tone: Tone = Tone.CALM,
        val outbox: List<OutboxRow> = emptyList(),
        /** WS10 §10: cached attachment bytes (localPath != null rows only). */
        val cacheUsageBytes: Long = 0L,
        /** WS10 §10: stored rows no live note references — report-only. */
        val orphanCount: Int = 0,
        /** True while a manual sweep/evict runs; both buttons idle together. */
        val maintainingAttachments: Boolean = false,
        val conflicts: List<ConflictRow> = emptyList(),
        val logLines: List<LogLine> = emptyList(),
        val tallyLine: String = "",
        val testing: Boolean = false,
        val testResult: String? = null,
        val syncing: Boolean = false,
        val sheet: ConflictPair? = null,
        /** Plain words for the last failed disk write; kept until the next attempt. */
        val notice: String? = null,
    )

    private val context get() = getApplication<Application>()

    private val db by lazy { XxDatabase.builder(context).build() }
    private val store by lazy { VaultStore(context) }

    /**
     * The §10 attachment surface over the same mirror root the vault uses.
     * No client provider: the sync screen only reports and sheds LOCAL cache;
     * downloads belong to view time, uploads to the engine's push ordering.
     */
    private val attachments by lazy {
        AttachmentStore(
            vaultRoot = File(context.filesDir, VaultStore.MIRROR_DIR),
            dao = db.attachmentDao(),
        )
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Fresh real-code-path evidence from this session (Test connection).
     * Null when this visit has produced none; then the DB's own record
     * decides.
     */
    private var probeHint: Wording.ConnectionState? = null

    init {
        refresh()
    }

    /** Re-queries everything; cheap, and re-run on screen (re)entry. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            _state.value = load()
        }
    }

    private suspend fun load(): UiState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hint = probeHint
        val credential = db.credentialDao().get()
        val staleStatus = db.settingDao().get(SyncGraph.SETTING_CREDENTIAL_STALE)?.toIntOrNull()
        val lastSuccessAt = db.syncLogDao().latest(SyncLogDao.LOG_CAP).firstOrNull { it.ok }?.at

        // Headline layering (R10 ruling, pure fn): fresh successful probe >
        // persisted credentialStale mark > fresh probe failure evidence >
        // last logged success. A failed probe never degrades DB-derived state.
        val configured = credential != null &&
            credential.host.isNotBlank() && credential.basePath.isNotBlank()
        val connection = when {
            permissionRevoked() -> Wording.ConnectionState.PermissionOff
            else -> Wording.layeredConnection(
                probeHint = hint,
                staleStatus = staleStatus,
                storedHost = if (configured) credential?.host else null,
                lastSuccessAt = lastSuccessAt,
            )
        }

        val recent = db.syncLogDao().latest(LOG_DISPLAY_LIMIT)
        val ticks = db.syncLogDao().logsSince(now - TALLY_WINDOW_DAYS * DAY_MS)
            .map { Wording.LogTick(at = it.at, verdict = it.verdict) }

        // WS10 §10: cache usage = bytes of rows holding a local copy; orphans
        // are judged against the LIVE notes' texts (a reference in an unpulled
        // note must never look like an orphan — sweeping stays manual anyway).
        val attachmentRows = db.attachmentDao().all()
        val liveTexts = store.listLive().map { it.wholeFileText }
        val orphanCount = attachments.orphanCount(liveTexts)

        val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault())
        UiState(
            loading = false,
            headline = Wording.headline(connection, nowAt = now),
            tone = if (connection is Wording.ConnectionState.HttpRefused ||
                connection is Wording.ConnectionState.Unreachable ||
                connection is Wording.ConnectionState.TlsProblem ||
                connection is Wording.ConnectionState.PermissionOff
            ) Tone.ATTENTION else Tone.CALM,
            outbox = db.outboxDao().pending().map { op ->
                OutboxRow(
                    noteId = op.noteId,
                    op = op.op,
                    reason = Wording.outboxReason(op.op, op.attempts, op.lastError),
                )
            },
            cacheUsageBytes = attachmentRows.filter { it.localPath != null }.sumOf { it.bytes },
            orphanCount = orphanCount,
            conflicts = db.noteDao().conflictsList().map { row ->
                ConflictRow(
                    originalId = row.id,
                    title = row.title.ifBlank { "(untitled ${row.id.takeLast(TAIL_CHARS)})" },
                )
            },
            logLines = recent.map { entry ->
                LogLine(
                    text = "${stamp.format(Instant.ofEpochMilli(entry.at))}  " +
                        "${entry.verdict}  ${entry.reason}",
                    ok = entry.ok,
                )
            },
            tallyLine = Wording.tallyLine(Wording.weeklyTallies(ticks, nowAt = now)),
            testing = false,
            testResult = _state.value.testResult,
            syncing = false,
            sheet = _state.value.sheet?.let { current ->
                // Keep the open sheet only while both sides still exist.
                db.noteDao().byId(current.originalId)?.let { orig ->
                    db.noteDao().byId(current.forkId)?.takeIf { fork ->
                        fork.trashedAt == null && fork.conflictOf == current.originalId
                    }?.let { current }
                }
            },
            notice = _state.value.notice,
        )
    }

    private fun permissionRevoked(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.INTERNET) !=
            PackageManager.PERMISSION_GRANTED

    // ---- Resolve sheet ------------------------------------------------------

    fun openConflict(originalId: String) {
        viewModelScope.launch {
            val pair = withContext(Dispatchers.IO) {
                val original = db.noteDao().byId(originalId) ?: return@withContext null
                val fork = db.noteDao().listLive().firstOrNull { it.conflictOf == originalId }
                    ?: return@withContext null
                ConflictPair(
                    originalId = original.id,
                    forkId = fork.id,
                    originalTitle = original.title.ifBlank { "(untitled)" },
                    originalBody = Frontmatter.parse(original.body).bodyText,
                    forkMarkedBody = Frontmatter.parse(fork.body).bodyText,
                )
            } ?: return@launch
            _state.update { it.copy(sheet = pair) }
        }
    }

    fun closeSheet() {
        _state.update { it.copy(sheet = null) }
    }

    /**
     * Applies a resolution (§7): the surviving body goes back under the
     * ORIGINAL id — its frontmatter region is kept so identity never moves
     * (§7: the far side never sees the original change identity) and
     * `updated:` is stamped to now (a resolution is a content modification,
     * M12); the fork is trashed, never unlinked (D9); an expedited sync
     * carries the result up. `side == null` with non-null editedBody is the
     * edit-merged passthrough.
     *
     * A failed disk write surfaces in words and keeps the sheet open with
     * everything still in memory for retry (§15: every failure keeps text);
     * it never crashes the app.
     */
    fun applyResolution(pair: ConflictPair, side: ResolveMath.Side?, editedBody: String?) {
        viewModelScope.launch {
            _state.update { it.copy(notice = null) }
            val saved = withContext(Dispatchers.IO) {
                try {
                    val resolvedBody = when {
                        editedBody != null -> editedBody
                        side != null -> ResolveMath.resolve(pair.forkMarkedBody, side)
                        else -> return@withContext false
                    }
                    val original = db.noteDao().byId(pair.originalId)
                        ?: return@withContext false
                    val now = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
                    store.write(
                        pair.originalId,
                        ResolveMath.resolvedWholeFile(original.body, resolvedBody, now),
                    )
                    store.trash(pair.forkId)
                    SyncWorker.enqueueExpedited(context)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.update { it.copy(notice = RESOLVE_SAVE_FAILED_WORDS) }
                    false
                }
            }
            if (saved) {
                closeSheet()
            }
            // Success or failure: re-query. A failure keeps the sheet open
            // with everything still in memory; the notice rides along.
            refresh()
        }
    }

    // ---- Attachment maintenance (WS10, §10) --------------------------------------

    /**
     * The one-tap orphan sweep. NEVER automatic (§10): a reference may live in
     * a note this device has not pulled yet, so only this button — judged over
     * the LIVE notes' texts — removes local rows + files. Result speaks in
     * plain words: "swept N · M kept".
     */
    fun sweepOrphans() {
        if (_state.value.maintainingAttachments) return
        viewModelScope.launch {
            _state.update { it.copy(maintainingAttachments = true) }
            val notice = withContext(Dispatchers.IO) {
                try {
                    val texts = store.listLive().map { it.wholeFileText }
                    val swept = attachments.sweepOrphans(texts)
                    val kept = db.attachmentDao().all().size
                    Wording.sweepNotice(swept, kept)
                } catch (_: Exception) {
                    SWEEP_FAILED_WORDS
                }
            }
            _state.update { it.copy(maintainingAttachments = false, notice = notice) }
            refresh()
        }
    }

    /**
     * Sheds least-recently-viewed local copies down to the budget setting
     * (default 500 MB). Rows survive eviction — the remote is truth; usage
     * line updates on the refresh.
     */
    fun evictAttachmentCache() {
        if (_state.value.maintainingAttachments) return
        viewModelScope.launch {
            _state.update { it.copy(maintainingAttachments = true) }
            withContext(Dispatchers.IO) {
                try {
                    attachments.evictToBudget(cacheBudgetBytes())
                } catch (_: Exception) {
                    // Usage line simply stays as it was; nothing was lost.
                }
            }
            _state.update { it.copy(maintainingAttachments = false) }
            refresh()
        }
    }

    private suspend fun cacheBudgetBytes(): Long =
        db.settingDao().get(SETTING_CACHE_BUDGET_BYTES)?.toLongOrNull()
            ?: AttachmentStore.DEFAULT_CACHE_BUDGET_BYTES

    // ---- Test connection ------------------------------------------------------

    /**
     * Runs the REAL code path (R10): one live PROPFIND against the stored
     * credential through [WebDavClient]. The outcome prints verbatim —
     * including the HTTP status line — and doubles as headline evidence.
     */
    fun testConnection() {
        if (_state.value.testing) return
        viewModelScope.launch {
            _state.update { it.copy(testing = true, testResult = "PROPFIND …") }
            val result = withContext(Dispatchers.IO) { runProbe() }
            result.hint?.let { probeHint = it }
            _state.update { it.copy(testing = false, testResult = result.verdict) }
            refresh()
        }
    }

    private data class ProbeResult(val verdict: String, val hint: Wording.ConnectionState?)

    private suspend fun runProbe(): ProbeResult {
        val credential = db.credentialDao().get()
            ?: return ProbeResult(
                "no credential stored — finish Setup first; nothing was sent",
                hint = null,
            )
        val password = try {
            CredentialVault(KeystoreKeyOps(credential.keyAlias, strongBox = true))
                .unseal(credential.sealedSecret)
                .decodeToString()
        } catch (e: Exception) {
            return ProbeResult(
                "could not unseal the stored secret (${e.javaClass.simpleName}); " +
                    "nothing was sent anywhere",
                hint = null,
            )
        }
        val (host, port) = splitHostPort(credential.host)
        val client = WebDavClient(
            host = host,
            port = port,
            basePath = credential.basePath,
            username = credential.user,
            password = password,
        )
        return try {
            val entries = client.list("")
            // Seed the fresh-success hint with the DB's own last-success time
            // (M4): the headline must not degrade from `last sync 13:37` to
            // `last sync never` just because a probe succeeded.
            ProbeResult(
                "PROPFIND ${credential.basePath.ifEmpty { "/" }} → HTTP 207 Multi-Status · " +
                    "${entries.size} entr${if (entries.size == 1) "y" else "ies"}",
                hint = Wording.ConnectionState.Connected(
                    host = credential.host,
                    lastSyncAt = lastSuccessAt(),
                ),
            )
        } catch (e: HttpError) {
            // e.message carries "PROPFIND <redacted url> failed: HTTP <code>" verbatim (R10).
            ProbeResult(
                e.message ?: "PROPFIND failed: HTTP ${e.status}",
                hint = Wording.ConnectionState.HttpRefused(e.status),
            )
        } catch (e: SSLException) {
            // BEFORE IOException: SSLException IS-A IOException, and §15's TLS
            // row demands the certificate be named as the problem — hard fail,
            // no bypass, host named.
            ProbeResult(
                "certificate problem · $host refused TLS — ${e.message ?: e.javaClass.simpleName}",
                hint = Wording.ConnectionState.TlsProblem(credential.host),
            )
        } catch (e: Exception) {
            ProbeResult(
                "PROPFIND could not reach $host:$port — ${e.message ?: e.javaClass.simpleName}",
                hint = Wording.ConnectionState.Unreachable(lastSuccessAt = lastSuccessAt()),
            )
        }
    }

    private suspend fun lastSuccessAt(): Long? =
        db.syncLogDao().latest(SyncLogDao.LOG_CAP).firstOrNull { it.ok }?.at

    // ---- Sync now ---------------------------------------------------------------

    /**
     * Expedited foreground sync, then poll the log tail until new rows land
     * (or give up after [SYNC_POLL_BUDGET_MS] — offline is a normal state,
     * not an error) and reload.
     */
    fun syncNow() {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true) }
            val before = withContext(Dispatchers.IO) {
                db.syncLogDao().latest(1).firstOrNull()?.id ?: 0L
            }
            SyncWorker.enqueueExpedited(context)
            val deadline = System.currentTimeMillis() + SYNC_POLL_BUDGET_MS
            var landed = false
            while (System.currentTimeMillis() < deadline) {
                delay(SYNC_POLL_INTERVAL_MS)
                landed = withContext(Dispatchers.IO) {
                    (db.syncLogDao().latest(1).firstOrNull()?.id ?: 0L) > before
                }
                if (landed) break
            }
            _state.update { it.copy(syncing = false) }
            refresh()
        }
    }

    /**
     * Same convention as SyncGraph.engine: `host` or `host:port`; bare hosts
     * ride §4.2's HTTPS default. Duplicated here because Setup/SyncGraph own
     * their files and WS9 touches only ui/sync + read-only DAO queries.
     */
    private fun splitHostPort(raw: String): Pair<String, Int> {
        val idx = raw.lastIndexOf(':')
        if (idx > 0) {
            raw.substring(idx + 1).toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?.let { return raw.substring(0, idx) to it }
        }
        return raw to DEFAULT_HTTPS_PORT
    }

    companion object {
        private const val LOG_DISPLAY_LIMIT = 100
        private const val TALLY_WINDOW_DAYS = 7
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val TAIL_CHARS = 6
        private const val DEFAULT_HTTPS_PORT = 5006
        private const val SYNC_POLL_INTERVAL_MS = 400L
        private const val SYNC_POLL_BUDGET_MS = 12_000L

        /** Settings key holding the local attachment-cache budget in bytes (§10). */
        const val SETTING_CACHE_BUDGET_BYTES = "cache_budget_bytes"

        /** §15 disk-full row, sweep flavour: words, nothing removed. */
        internal const val SWEEP_FAILED_WORDS =
            "couldn't sweep — storage problem · nothing was removed — try again"

        /** §15 disk-full row, resolve flavour: words, sheet kept for retry. */
        internal const val RESOLVE_SAVE_FAILED_WORDS =
            "couldn't save the resolution — storage problem · nothing was written — try again"
    }
}
