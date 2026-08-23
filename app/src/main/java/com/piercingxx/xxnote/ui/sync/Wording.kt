package com.piercingxx.xxnote.ui.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * WS9's reason-string surface (design R10, §12 item 4, §15 failure-wording
 * rows): every sync state maps to a plain sentence, every queued op to a
 * plain reason. Pure — no Android, no I/O — because these strings ARE the
 * honesty contract and are proven byte-for-byte by unit tests.
 *
 * Wording rulings beyond the literal spec rows (documented because R10 is
 * the point):
 * - HTTP refusals split by status: 401/403 name the credentials (§15 auth
 *   row: Setup's account step is the fix); every other status states that
 *   the NAS answered and the sync did not run (§15 stopped-package row).
 * - `last success <time>` renders clock-time only when the success landed
 *   on the local calendar day, date-plus-time otherwise, and the literal
 *   word `never` when no successful pass has ever been logged.
 */
object Wording {

    /** Everything the headline needs, in the priority order §12.4 fixes. */
    sealed interface ConnectionState {
        /** INTERNET revoked (GrapheneOS): nothing is leaving this device. */
        data object PermissionOff : ConnectionState

        /** No usable credential row: Setup has not completed. */
        data object NotSetUp : ConnectionState

        /**
         * The far side answered with a refusal status. [status] surfaces
         * verbatim; 401/403 mean the sealed credential is stale (the worker
         * persists this mark — see SyncGraph.markCredentialStale).
         */
        data class HttpRefused(val status: Int) : ConnectionState

        /**
         * Transport-level failure evidence (real code path ran and threw an
         * IOException). [lastSuccessAt] is the newest logged successful
         * pass, null when none ever landed.
         */
        data class Unreachable(val lastSuccessAt: Long?) : ConnectionState

        /**
         * The TLS handshake itself was refused (§15 row: certificate invalid
         * or expired). Hard fail, no bypass; [host] is named because the fix
         * lives on the far side's certificate, not in any app setting.
         */
        data class TlsProblem(val host: String) : ConnectionState

        /** Last known good: host shown as stored, never user or secret. */
        data class Connected(val host: String, val lastSyncAt: Long?) : ConnectionState
    }

    /** The headline sentence for [state], formatted against [nowAt]/[zone]. */
    fun headline(
        state: ConnectionState,
        nowAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = when (state) {
        ConnectionState.PermissionOff -> PERMISSION_OFF
        ConnectionState.NotSetUp -> NOT_SET_UP
        is ConnectionState.HttpRefused -> "NAS answered HTTP ${state.status} · ${httpVerdict(state.status)}"
        is ConnectionState.Unreachable ->
            "Tailnet unreachable · last success ${lastSync(state.lastSuccessAt, nowAt, zone)}"
        is ConnectionState.TlsProblem ->
            "Certificate problem · ${state.host} refused TLS"
        is ConnectionState.Connected ->
            "Connected · ${state.host} · last sync ${lastSync(state.lastSyncAt, nowAt, zone)}"
    }

    /**
     * Headline evidence layering (the §12.4 priority, made total). Inputs:
     * - [probeHint] — fresh first-hand evidence from THIS session's
     *   Test-connection probe; null when this visit produced none.
     * - [staleStatus] — the persisted `credentialStale` mark (a worker pass
     *   was refused with this HTTP status).
     * - [storedHost] — the stored credential's host, or null when no usable
     *   credential row exists.
     * - [lastSuccessAt] — newest logged successful pass from the log tail.
     *
     * Layering law: a fresh successful probe outranks the persisted stale
     * mark (it IS the refutation); the persisted mark outranks fresh failure
     * evidence (it survived a full engine pass, not one probe); fresh failure
     * evidence outranks the log tail; with nothing anywhere, the last-known
     * good headline stands: Connected over the stored credential. A failed
     * probe therefore never degrades a DB-derived state, and never erases
     * its last-success time.
     */
    fun layeredConnection(
        probeHint: ConnectionState?,
        staleStatus: Int?,
        storedHost: String?,
        lastSuccessAt: Long?,
    ): ConnectionState = when {
        storedHost.isNullOrBlank() -> ConnectionState.NotSetUp
        probeHint is ConnectionState.Connected -> probeHint
        staleStatus != null -> ConnectionState.HttpRefused(staleStatus)
        probeHint != null -> probeHint
        else -> ConnectionState.Connected(storedHost, lastSuccessAt)
    }

    /**
     * §15's distinction: a refusal whose fix is re-authentication versus any
     * other server-side rejection. The status itself is carried by the
     * headline prefix, verbatim.
     */
    fun httpVerdict(status: Int): String =
        if (status == 401 || status == 403) {
            "credentials refused — set up the account again"
        } else {
            "server answered · the sync did not run"
        }

    /**
     * `last success` clause: `never`, clock time on the day of [nowAt],
     * date plus time otherwise.
     */
    fun lastSync(at: Long?, nowAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (at == null) return NEVER
        val zoned = Instant.ofEpochMilli(at).atZone(zone)
        val sameDay = zoned.toLocalDate() == Instant.ofEpochMilli(nowAt).atZone(zone).toLocalDate()
        val pattern = if (sameDay) "HH:mm" else "MMM d, HH:mm"
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).format(zoned)
    }

    /**
     * One outbox row's reason: what waits, how often it has tried, and the
     * last error verbatim. Never includes payloads — those carry note bytes.
     */
    fun outboxReason(op: String, attempts: Int, lastError: String?): String {
        val verb = when (op) {
            "put" -> "edit waiting to push"
            "move" -> "rename waiting to apply"
            "trash" -> "delete waiting to retire the remote copy"
            "delete" -> "permanent delete waiting"
            "attach" -> "attachment waiting to upload"
            else -> "$op waiting"
        }
        return buildString {
            append(verb)
            if (attempts > 1) append(" · attempt ").append(attempts)
            if (!lastError.isNullOrBlank()) append(" · last error: ").append(lastError)
        }
    }

    /** One log row reduced to what the weekly tally needs. */
    data class LogTick(val at: Long, val verdict: String)

    data class WeeklyTally(val synced: Int = 0, val merged: Int = 0, val forked: Int = 0)

    /**
     * Tallies over the trailing [windowDays] days (§12.4: synced / merged /
     * forked, this week). Verdict buckets follow the engine's own log verbs:
     * a pull, push, or resurrect moved text across sides (`synced`); a merge
     * is a clean three-way save (`merged`); a fork created a second visible
     * note (`forked`) — counted whether or not its upload landed, since the
     * fork exists locally either way. Window bounds are inclusive at the
     * start edge, exclusive past [nowAt].
     */
    fun weeklyTallies(ticks: List<LogTick>, nowAt: Long, windowDays: Int = 7): WeeklyTally {
        val start = nowAt - windowDays * DAY_MS
        var synced = 0
        var merged = 0
        var forked = 0
        for (tick in ticks) {
            if (tick.at < start || tick.at > nowAt) continue
            when (tick.verdict) {
                in SYNCED_VERDICTS -> synced++
                MERGED_VERDICT -> merged++
                FORKED_VERDICT -> forked++
            }
        }
        return WeeklyTally(synced, merged, forked)
    }

    /** The Space Mono numerals line under THIS WEEK. */
    fun tallyLine(tally: WeeklyTally): String =
        "synced ${tally.synced} · merged ${tally.merged} · forked ${tally.forked}"

    /**
     * Human byte count for the attachment-cache line (§12 item 4): whole
     * bytes under a KiB, then one decimal per unit, `.0` trimmed —
     * `3 B`, `999 B`, `1 KB`, `1.5 KB`, `500 MB`, `2 GB`. Binary divisors
     * under decimal labels (the platform's own file-size convention), so a
     * 500 MB budget reads as exactly `500 MB`.
     */
    fun bytes(count: Long): String {
        require(count >= 0) { "byte counts are never negative" }
        if (count < KB) return "$count B"
        val unit = when {
            count < MB -> "KB"
            count < GB -> "MB"
            else -> "GB"
        }
        val divisor = when (unit) {
            "KB" -> KB
            "MB" -> MB
            else -> GB
        }
        val text = String.format(Locale.ROOT, "%.1f", count.toDouble() / divisor)
        return text.removeSuffix(".0") + " " + unit
    }

    /** The one-line result of a manual orphan sweep — plain words, never automatic (§10). */
    fun sweepNotice(swept: Int, kept: Int): String = "swept $swept · $kept kept"

    private const val KB = 1024L
    private const val MB = 1024L * 1024
    private const val GB = 1024L * 1024 * 1024

    private val SYNCED_VERDICTS = setOf("Pull", "Push", "Resurrect")
    private const val MERGED_VERDICT = "Merge"
    private const val FORKED_VERDICT = "Fork"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    const val PERMISSION_OFF = "Network permission off · nothing is leaving this device"
    const val NOT_SET_UP = "Not set up"
    const val NEVER = "never"
}
