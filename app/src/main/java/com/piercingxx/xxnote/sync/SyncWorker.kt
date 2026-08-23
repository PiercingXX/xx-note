package com.piercingxx.xxnote.sync

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.net.CredentialVault
import com.piercingxx.xxnote.net.KeystoreKeyOps
import com.piercingxx.xxnote.net.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager wiring for the sync engine (design §4.4). Thin by charter: every
 * decision lives in [SyncEngine]; this class only enqueues work and maps the
 * [SyncEngine.SyncOutcome] onto result data for the sync screen.
 *
 * - **Foreground sync** — [enqueueExpedited]: an expedited one-time request on
 *   app resume, debounced save, and pull-to-refresh. Quota-exceeded falls back
 *   to a normal work request rather than crashing. Enqueued with
 *   APPEND_OR_REPLACE so a save during an in-flight pass still queues its own
 *   follow-up sync instead of being dropped (M5).
 * - **Background sync** — [enqueuePeriodic]: 15-minute floor, network
 *   constraint, KEEP policy. Doze defers it; that is acceptable and stated.
 *
 * **Deliberate deviation from design §13's component list: there is no
 * BOOT_COMPLETED receiver.** WorkManager persists periodic work across reboots
 * natively, so a receiver would be dead weight — and adding
 * `RECEIVE_BOOT_COMPLETED` would break §13's four-permission claim
 * (`INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `POST_NOTIFICATIONS`, nothing
 * else), which is the short-list-is-the-claim rule (R8, todo standing rules).
 * This ruling supersedes §13's mention of the receiver.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val engine = try {
            SyncGraph.engine(applicationContext)
        } catch (_: Exception) {
            // Construction trouble (disk, keystore, migration) may be transient;
            // retry on the next schedule instead of failing the work outright.
            return@withContext Result.retry()
        } ?: return@withContext Result.success(
            workDataOf(KEY_OUTCOME to OUTCOME_NOT_CONFIGURED),
        )

        try {
            when (val outcome = engine.syncOnce()) {
                is SyncEngine.SyncOutcome.Completed -> Result.success(
                    workDataOf(
                        KEY_OUTCOME to OUTCOME_COMPLETED,
                        KEY_PULLED to outcome.pulled,
                        KEY_PUSHED to outcome.pushed,
                        KEY_MERGED to outcome.merged,
                        KEY_FORKED to outcome.forked,
                        KEY_TRASHED to outcome.trashed,
                        KEY_RESURRECTED to outcome.resurrected,
                        KEY_NOTHING to outcome.nothing,
                    ),
                )
                is SyncEngine.SyncOutcome.HaltedTrashSafety -> Result.success(
                    workDataOf(
                        KEY_OUTCOME to OUTCOME_HALTED_TRASH_SAFETY,
                        KEY_WOULD_TRASH to outcome.wouldTrash,
                        KEY_LIVE_NOTES to outcome.liveNotes,
                    ),
                )
                is SyncEngine.SyncOutcome.AuthFailed -> {
                    // Stale credentials: mark them so the UI can prompt, and
                    // report success — a retry would only loop on 401/403.
                    SyncGraph.markCredentialStale(applicationContext, outcome.status)
                    Result.success(
                        workDataOf(
                            KEY_OUTCOME to OUTCOME_AUTH_FAILED,
                            KEY_HTTP_STATUS to outcome.status,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            // Unreachable tailnet or a failed WebDAV call mid-pass: offline is a
            // normal state (§15) — retry on the next schedule, never crash.
            Result.retry()
        }
    }

    companion object {
        fun enqueueExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(NETWORK)
                .build()
            // M5: APPEND_OR_REPLACE, not KEEP. Under KEEP a save landing while
            // a sync pass was already in flight silently dropped its follow-up
            // intent — the new note/edit waited for the next 15-minute pass.
            // Appending queues the follow-up; REPLACE covers a cancelled/failed
            // predecessor so the intent can never wedge behind a corpse.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_ONESHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(NETWORK)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private val NETWORK = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private const val UNIQUE_ONESHOT = "xx-note-sync-once"
        private const val UNIQUE_PERIODIC = "xx-note-sync-periodic"
        private const val PERIOD_MINUTES = 15L // WorkManager's floor (§4.4)

        const val OUTCOME_COMPLETED = "completed"
        const val OUTCOME_HALTED_TRASH_SAFETY = "halted_trash_safety"
        const val OUTCOME_NOT_CONFIGURED = "not_configured"
        const val OUTCOME_AUTH_FAILED = "auth_failed"

        const val KEY_OUTCOME = "outcome"
        const val KEY_PULLED = "pulled"
        const val KEY_PUSHED = "pushed"
        const val KEY_MERGED = "merged"
        const val KEY_FORKED = "forked"
        const val KEY_TRASHED = "trashed"
        const val KEY_RESURRECTED = "resurrected"
        const val KEY_NOTHING = "nothing"
        const val KEY_WOULD_TRASH = "wouldTrash"
        const val KEY_LIVE_NOTES = "liveNotes"
        const val KEY_HTTP_STATUS = "httpStatus"
    }
}

/**
 * Singleton-ish engine construction from a [Context] (§5): [VaultStore] for the
 * mirror + bookkeeping, [WebDavClient] built from the sealed credential row.
 *
 * Placeholder-friendly by design: with no credential row — or one that cannot
 * be unsealed — this returns null and [SyncWorker] reports `not configured`
 * instead of crashing, so the app stays a fully functional local notes app
 * before Setup completes (R4).
 */
object SyncGraph {

    /** Settings key holding the operator-chosen device name (§7 naming). */
    const val SETTING_DEVICE_NAME = "device_name"

    /** Settings key holding the HTTP status of the last auth refusal (R10). */
    const val SETTING_CREDENTIAL_STALE = "credentialStale"

    private const val DEFAULT_HTTPS_PORT = 5006 // §4.2: WebDAV HTTPS default

    @Volatile
    private var wired: SyncEngine? = null

    /**
     * Records that the far side refused the sealed credential with [status]
     * (401/403). Read by the sync screen to prompt for re-authentication; a
     * fresh successful sync clears it. Best-effort: a failure to persist the
     * mark must never crash the worker.
     */
    fun markCredentialStale(context: Context, status: Int) {
        try {
            val app = context.applicationContext
            val db = XxDatabase.builder(app).build()
            runBlocking {
                db.settingDao().put(
                    com.piercingxx.xxnote.data.SettingEntity(SETTING_CREDENTIAL_STALE, status.toString()),
                )
            }
        } catch (_: Exception) {
            // The AuthFailed outcome itself already stopped the retry loop.
        }
    }

    fun engine(context: Context): SyncEngine? {
        wired?.let { return it }
        synchronized(this) {
            wired?.let { return it }

            val app = context.applicationContext
            val db = XxDatabase.builder(app).build()
            val credential = runBlocking { db.credentialDao().get() } ?: return null
            if (credential.host.isBlank() || credential.basePath.isBlank()) return null

            // R9: the plaintext exists only here, only while requests are signed.
            val password = CredentialVault(KeystoreKeyOps(credential.keyAlias, strongBox = true))
                .unseal(credential.sealedSecret)
                .decodeToString()

            val (host, port) = splitHostPort(credential.host)
            val remoteClient = WebDavClient(
                host = host,
                port = port,
                basePath = credential.basePath,
                username = credential.user,
                password = password,
            )

            // One Room instance, two personas: VaultStore owns mirror+outbox,
            // AttachmentStore owns the attachments/ cache under the same root.
            // The client provider hands the §10 upload/download path the exact
            // one-host client the engine itself uses — no second origin, ever.
            val mirrorRoot = java.io.File(app.filesDir, VaultStore.MIRROR_DIR)
            val store = VaultStore(mirrorRoot, db)
            val attachmentStore = com.piercingxx.xxnote.data.AttachmentStore(
                vaultRoot = mirrorRoot,
                dao = db.attachmentDao(),
                clientProvider = { remoteClient },
            )

            val deviceName = runBlocking { db.settingDao().get(SETTING_DEVICE_NAME) }
                ?.takeUnless { it.isBlank() }
                ?: Build.MODEL?.takeUnless { it.isBlank() }
                ?: "xx-device"

            return SyncEngine(
                local = store,
                remote = remoteClient,
                book = store,
                deviceName = deviceName,
                attachments = attachmentStore,
            ).also { wired = it }
        }
    }

    /**
     * Accepts either `nas.tailnet.ts.net` or `nas.tailnet.ts.net:5006`; the
     * credential table has no port column, so an explicit suffix is the
     * convention until Setup grows one. Bare hosts ride §4.2's HTTPS default.
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
}
