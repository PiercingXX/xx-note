package com.piercingxx.xxnote.sync

import android.content.Context
import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.piercingxx.xxnote.data.VaultStore
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.net.CredentialVault
import com.piercingxx.xxnote.net.KeystoreKeyOps
import com.piercingxx.xxnote.net.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import java.security.UnrecoverableEntryException

/**
 * WorkManager wiring for the sync engine (design §4.4). Thin by charter:
 * every decision lives in [SyncEngine]; this class only enqueues work and
 * maps the [SyncEngine.SyncOutcome] onto result data for the sync screen.
 *
 * - **Expedited sync** ([enqueueExpedited]) uses APPEND_OR_REPLACE so a save
 *   during an in-flight pass still queues its own follow-up (M5) — collapsed
 *   to AT MOST one queued follow-up via the completion-consumed flag
 *   (hardening #7). Quota-exceeded degrades to a normal work request rather
 *   than crashing.
 * - **Background sync** ([enqueuePeriodic]) delegates to
 *   [SyncScheduler.ensurePeriodic]: 15-minute floor, network constraint,
 *   KEEP policy. Doze defers it; acceptable and stated.
 * - **No BOOT_COMPLETED receiver** (supersedes design §13's mention):
 *   WorkManager persists periodic work across reboots natively, and the
 *   permission would break §13's four-permission claim (R8).
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        passStarted()
        try {
            val engine = try {
                SyncGraph.engine(applicationContext)
            } catch (_: SyncGraph.CredentialUnreadableException) {
                // Hardening #4c: the sealed blob can never be unsealed again
                // (tampered bytes, or the keystore key did not survive a
                // backup restore). engine() already persisted the stale mark
                // so the sync screen prompts for re-entry; retrying would
                // only loop forever.
                return@withContext Result.failure()
            } catch (_: Exception) {
                // Construction trouble (disk, migration) may be transient;
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
        } finally {
            // Hardening #7: at most ONE queued follow-up. Requests that landed
            // mid-pass collapsed into the flag; chain exactly one pass now.
            if (passFinished()) {
                try {
                    enqueueExpedited(applicationContext)
                } catch (_: Exception) {
                    // Swallowed silently like VaultStore.fsyncDir (this codebase
                    // logs nothing): an enqueue failure must never mask the
                    // Result computed above — the persisted periodic schedule
                    // stays the backstop either way.
                }
            }
        }
    }

    companion object {

        // ---- follow-up collapse state (hardening #7) ------------------------
        //
        // In-memory and JVM-testable by design: doWork marks each pass
        // started/finished itself, so enqueueExpedited never needs an async
        // WorkManager query. A process death loses at most an un-set flag —
        // WorkManager re-runs persisted work regardless.
        //
        // Single-process assumption: this gate coordinates only the passes of
        // THIS process' WorkManager instance (no android:process attribute is
        // declared anywhere in the app today); a second process would keep its
        // own counter and gate independently.

        /** Live [doWork] passes right now: 0 idle, ≥ 1 while passes execute. */
        internal val livePasses = AtomicInteger(0)

        /** Set when a request lands mid-pass; consumed exactly once at true zero. */
        internal val followUpRequested = AtomicBoolean(false)

        /**
         * The collapse gate: true when any pass is running and the caller's
         * intent was recorded as the running passes' single follow-up instead
         * of another queued request.
         */
        internal fun deferToRunningPass(): Boolean {
            if (livePasses.get() == 0) return false
            followUpRequested.set(true)
            return true
        }

        internal fun passStarted() {
            livePasses.incrementAndGet()
        }

        /**
         * Decrements the live-pass count after a pass; true only on the
         * transition to zero AND a pending follow-up — exactly one enqueue for
         * the last finisher, never one per overlapping pass. A request landing
         * after the zero transition sees an empty counter and enqueues itself,
         * so no intent can be lost between the two steps.
         */
        internal fun passFinished(): Boolean {
            val last = livePasses.decrementAndGet() == 0
            return last && followUpRequested.compareAndSet(true, false)
        }

        fun enqueueExpedited(context: Context) {
            if (deferToRunningPass()) return
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(NETWORK)
                .build()
            // M5: APPEND_OR_REPLACE, not KEEP. Under KEEP a save landing while
            // a sync pass was already queued silently dropped its follow-up
            // intent — the new note/edit waited for the next 15-minute pass.
            // Appending queues the follow-up; REPLACE covers a cancelled/failed
            // predecessor so the intent can never wedge behind a corpse. The
            // collapse gate above bounds how far such a queue can grow: one
            // running pass plus at most one chained follow-up.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_ONESHOT, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun enqueuePeriodic(context: Context) = SyncScheduler.ensurePeriodic(context)

        private val NETWORK = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private const val UNIQUE_ONESHOT = "xx-note-sync-once"

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
 * Placeholder-friendly by design: with no credential row this returns null and
 * [SyncWorker] reports `not configured` instead of crashing, so the app stays
 * a fully functional local notes app before Setup completes (R4). A row that
 * exists but cannot be unsealed is NOT null-and-quiet: it raises
 * [CredentialUnreadableException] after persisting the stale mark, because
 * retrying can never succeed (hardening #4c).
 */
object SyncGraph {

    /** Settings key holding the operator-chosen device name (§7 naming). */
    const val SETTING_DEVICE_NAME = "device_name"

    /**
     * Settings key holding the §4.2 ETag mode Setup detected (SetupLogic's
     * KEY_ETAG_MODE; kept equal by test). Read through [EtagMode.fromStored],
     * whose absent/unknown default is FALLBACK — installs configured before
     * modes existed get the mode that can never blind-write.
     */
    const val SETTING_ETAG_MODE = "etag_mode"

    /** Settings key holding the HTTP status of the last auth refusal (R10). */
    const val SETTING_CREDENTIAL_STALE = "credentialStale"

    private const val DEFAULT_HTTPS_PORT = 5006 // §4.2: WebDAV HTTPS default

    /**
     * Status persisted when a credential blob cannot be UNSEALED (hardening
     * #4c): no HTTP exchange ever happened, but the remedy is exactly the
     * 401 row's — re-enter credentials at Setup. The sync screen renders any
     * stale mark through Wording's HttpRefused state, whose 401 branch says
     * "credentials refused — set up the account again"; until a dedicated
     * wording surface exists for unseal failures, that fix-naming sentence
     * beats an invented sentinel status rendering nonsense words.
     */
    internal const val UNSEAL_FAILURE_STALE_STATUS = 401

    /**
     * The stored credential blob cannot be unsealed with its keystore key —
     * tampered bytes, or a backup restore brought back the blob without the
     * key (§4). Permanent until Setup re-runs; raised only AFTER the stale
     * mark has been persisted. [SyncWorker.doWork] maps this to failure,
     * never retry.
     */
    class CredentialUnreadableException(cause: Throwable) : Exception(cause)

    @Volatile
    private var wired: SyncEngine? = null

    /**
     * Bumped on every [invalidate]. A builder caches its engine only when the
     * generation it captured before building is still current (the assignment
     * happens under the monitor), so an invalidation landing mid-build can
     * never re-cache an engine wired from pre-invalidation rows. Guarded by
     * the monitor of `this`.
     */
    private var generation = 0L

    /**
     * Drops the cached engine so the next [engine] call rebuilds from the
     * current credential/settings rows (hardening #6). Without this,
     * re-running Setup to correct a wrong host or password — or marking
     * credentials stale — had no effect until the process died.
     */
    fun invalidate() {
        synchronized(this) {
            generation++
            wired = null
        }
    }

    /**
     * Records that the far side refused the sealed credential with [status]
     * (401/403). Read by the sync screen to prompt for re-authentication; a
     * fresh successful sync clears it. Best-effort: a failure to persist the
     * mark must never crash the worker. Also invalidates the cached engine —
     * whatever signs next must use the rows as they are NOW (hardening #6).
     */
    fun markCredentialStale(context: Context, status: Int) {
        invalidate()
        try {
            val app = context.applicationContext
            val db = XxDatabase.getInstance(app)
            runBlocking {
                db.settingDao().put(
                    com.piercingxx.xxnote.data.SettingEntity(SETTING_CREDENTIAL_STALE, status.toString()),
                )
            }
        } catch (_: Exception) {
            // The AuthFailed outcome itself already stopped the retry loop.
        }
    }

    /**
     * True for unseal deaths only Setup can fix (hardening #4c):
     * [AEADBadTagException] proves the stored bytes were tampered with or
     * re-encrypted under a lost key; [UnrecoverableEntryException] (and its
     * [java.security.UnrecoverableKeyException] subclass) means the keystore
     * entry itself did not survive a backup restore or was deleted;
     * [KeyPermanentlyInvalidatedException] means a user-auth-bound key can
     * never authenticate again. Everything else stays on the worker's normal
     * retry path — notably AndroidKeyStore's TRANSIENT
     * [java.security.ProviderException]s (TEE busy, IPC timeouts), which
     * surface as security exceptions yet clear on the next attempt.
     *
     * android.security.KeyStoreException is deliberately NOT matched: its
     * structured error codes cannot be pinned to permanent-vs-transient
     * without version fragility across API levels, and misclassifying a
     * transient code as permanent would brick a recoverable setup.
     */
    internal fun isPermanentUnsealFailure(e: Throwable): Boolean = when (e) {
        is AEADBadTagException -> true
        is UnrecoverableEntryException -> true
        is KeyPermanentlyInvalidatedException -> true
        else -> false
    }

    /**
     * Sealing strategy for the credential row. The indirection exists ONLY so
     * Robolectric tests can stand in a software AES key for the hardware
     * keystore, which Robolectric does not implement; production always gets
     * the KeystoreKeyOps path (R9).
     */
    @Volatile
    internal var vaultFactory: (alias: String) -> CredentialVault =
        { alias -> CredentialVault(KeystoreKeyOps(alias, strongBox = true)) }

    fun engine(context: Context): SyncEngine? {
        wired?.let { return it }
        synchronized(this) {
            wired?.let { return it }
            val buildGeneration = generation

            val app = context.applicationContext
            val db = XxDatabase.getInstance(app)
            val credential = runBlocking { db.credentialDao().get() } ?: return null
            if (credential.host.isBlank() || credential.basePath.isBlank()) return null

            // R9: the plaintext exists only here, only while requests are signed.
            val password = try {
                vaultFactory(credential.keyAlias)
                    .unseal(credential.sealedSecret)
                    .decodeToString()
            } catch (e: CancellationException) {
                throw e // never swallow structured cancellation (M7)
            } catch (e: Exception) {
                if (!isPermanentUnsealFailure(e)) throw e
                // Hardening #4c: the sealed secret can never come back. Persist
                // the stale mark first so the sync screen asks for re-entry,
                // then raise the typed failure the worker maps to Result.failure().
                markCredentialStale(app, UNSEAL_FAILURE_STALE_STATUS)
                throw CredentialUnreadableException(e)
            }

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

            // §4.2 mode plumbing: the promise Setup stored is the behavior the
            // engine enforces. Absent row (pre-mode installs) → FALLBACK, the
            // safe direction — see EtagMode.fromStored.
            val etagMode = EtagMode.fromStored(
                runBlocking { db.settingDao().get(SETTING_ETAG_MODE) },
            )

            val engine = SyncEngine(
                local = store,
                remote = remoteClient,
                book = store,
                deviceName = deviceName,
                attachments = attachmentStore,
                etagMode = etagMode,
            )
            synchronized(this) {
                // Generation guard: an invalidate() landing mid-build (stale
                // mark, Setup re-run) must never see its work undone by a
                // late assignment of an engine built from pre-invalidation
                // rows. A discarded engine is simply rebuilt on the next call.
                if (generation == buildGeneration) wired = engine
            }
            return engine
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
