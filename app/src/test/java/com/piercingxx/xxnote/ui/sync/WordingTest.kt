package com.piercingxx.xxnote.ui.sync

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The §15 failure-wording table, proven string-for-string (R10: the words
 * ARE the feature). Fixed clock + UTC zone so expectations are exact.
 */
class WordingTest {

    private val zone = ZoneId.of("UTC")

    // 2026-08-23 is a Sunday; "now" sits at 14:02.
    private val now = Instant.parse("2026-08-23T14:02:00Z").toEpochMilli()
    private val todayEarlier = Instant.parse("2026-08-23T13:37:00Z").toEpochMilli()
    private val yesterday = Instant.parse("2026-08-22T09:14:00Z").toEpochMilli()

    @Test
    fun permissionOff_saysNothingIsLeavingTheDevice() {
        val text = Wording.headline(Wording.ConnectionState.PermissionOff, now, zone)
        assertEquals("Network permission off · nothing is leaving this device", text)
    }

    @Test
    fun notSetUp_isPlain() {
        val text = Wording.headline(Wording.ConnectionState.NotSetUp, now, zone)
        assertEquals("Not set up", text)
    }

    @Test
    fun httpError_surfacesStatusVerbatim() {
        val text = Wording.headline(Wording.ConnectionState.HttpRefused(502), now, zone)
        assertEquals("NAS answered HTTP 502 · server answered · the sync did not run", text)
    }

    @Test
    fun staleCredential_401_namesTheFix() {
        val text = Wording.headline(Wording.ConnectionState.HttpRefused(401), now, zone)
        assertEquals("NAS answered HTTP 401 · credentials refused — set up the account again", text)
    }

    @Test
    fun staleCredential_403_namesTheFix() {
        val text = Wording.headline(Wording.ConnectionState.HttpRefused(403), now, zone)
        assertEquals(
            "NAS answered HTTP 403 · credentials refused — set up the account again",
            text,
        )
    }

    @Test
    fun unreachable_showsLastSuccessTime_sameDayRendersClockOnly() {
        val text = Wording.headline(Wording.ConnectionState.Unreachable(todayEarlier), now, zone)
        assertEquals("Tailnet unreachable · last success 13:37", text)
    }

    @Test
    fun unreachable_neverSynced_rendersNever() {
        val text = Wording.headline(Wording.ConnectionState.Unreachable(null), now, zone)
        assertEquals("Tailnet unreachable · last success never", text)
    }

    @Test
    fun tlsProblem_namesTheHostAndTheCertificate_perS15TlsRow() {
        val text =
            Wording.headline(Wording.ConnectionState.TlsProblem("nas.tailnet.ts.net"), now, zone)
        assertEquals("Certificate problem · nas.tailnet.ts.net refused TLS", text)
    }

    @Test
    fun connected_showsHostAndClock() {
        val text =
            Wording.headline(Wording.ConnectionState.Connected("nas.tailnet.ts.net", todayEarlier), now, zone)
        assertEquals("Connected · nas.tailnet.ts.net · last sync 13:37", text)
    }

    @Test
    fun lastSuccessOnAnOlderDay_includesTheDate() {
        val text =
            Wording.headline(Wording.ConnectionState.Connected("nas.tailnet.ts.net", yesterday), now, zone)
        assertEquals("Connected · nas.tailnet.ts.net · last sync Aug 22, 09:14", text)
    }

    @Test
    fun neverSucceeded_connectedStateStillSaysNever() {
        val text = Wording.lastSync(at = null, nowAt = now, zone = zone)
        assertEquals("never", text)
    }

    // ---- headline evidence layering (M4) ---------------------------------------

    private fun connected(at: Long?) = Wording.ConnectionState.Connected("nas.tailnet.ts.net", at)

    @Test
    fun layering_freshSuccessfulProbe_supersedesThePersistedStaleMark() {
        val state = Wording.layeredConnection(
            probeHint = connected(todayEarlier),
            staleStatus = 401,
            storedHost = "nas.tailnet.ts.net",
            lastSuccessAt = todayEarlier,
        )
        assertEquals(connected(todayEarlier), state)
    }

    @Test
    fun layering_failedProbe_leavesTheDbDerivedStaleMarkStanding() {
        val state = Wording.layeredConnection(
            probeHint = Wording.ConnectionState.Unreachable(todayEarlier),
            staleStatus = 403,
            storedHost = "nas.tailnet.ts.net",
            lastSuccessAt = todayEarlier,
        )
        assertEquals(Wording.ConnectionState.HttpRefused(403), state)
    }

    @Test
    fun layering_noStaleMark_failedProbeHintPassesThroughWithItsSeededLastSuccess() {
        // The probe seeds its Unreachable hint from the DB log tail at probe
        // time (SyncViewModel.runProbe); the layering fn selects it untouched,
        // so the last-success time never degrades to `never`.
        val hint = Wording.ConnectionState.Unreachable(todayEarlier)
        val state = Wording.layeredConnection(
            probeHint = hint,
            staleStatus = null,
            storedHost = "nas.tailnet.ts.net",
            lastSuccessAt = todayEarlier,
        )
        assertEquals(hint, state)
    }

    @Test
    fun layering_noPersistedMark_failedProbeEvidenceIsTheHeadline() {
        val hint = Wording.ConnectionState.TlsProblem("nas.tailnet.ts.net")
        val state = Wording.layeredConnection(
            probeHint = hint,
            staleStatus = null,
            storedHost = "nas.tailnet.ts.net",
            lastSuccessAt = todayEarlier,
        )
        assertEquals(hint, state)
    }

    @Test
    fun layering_nothingAnywhere_lastKnownGoodStandsFromStoreAndLogTail() {
        val state = Wording.layeredConnection(
            probeHint = null,
            staleStatus = null,
            storedHost = "nas.tailnet.ts.net",
            lastSuccessAt = yesterday,
        )
        assertEquals(connected(yesterday), state)
    }

    @Test
    fun layering_noUsableCredentialRow_isNotSetUp_regardlessOfEvidence() {
        assertEquals(
            Wording.ConnectionState.NotSetUp,
            Wording.layeredConnection(probeHint = connected(todayEarlier), staleStatus = null, storedHost = null, lastSuccessAt = todayEarlier),
        )
        assertEquals(
            Wording.ConnectionState.NotSetUp,
            Wording.layeredConnection(probeHint = connected(todayEarlier), staleStatus = 401, storedHost = "", lastSuccessAt = null),
        )
    }

    @Test
    fun layering_probeSuccessWithoutHistory_carriesTheNullLastSyncThrough() {
        val state = Wording.layeredConnection(
            probeHint = Wording.ConnectionState.Connected("nas.tailnet.ts.net", lastSyncAt = null),
            staleStatus = 401,
            storedHost = "nas.tailnet.ts.net",
            lastSuccessAt = null,
        )
        assertEquals(Wording.ConnectionState.Connected("nas.tailnet.ts.net", null), state)
    }

    // ---- outbox reasons ------------------------------------------------------

    @Test
    fun outboxReason_eachOpMapsToItsPhrase() {
        assertEquals("edit waiting to push", Wording.outboxReason("put", 0, null))
        assertEquals("rename waiting to apply", Wording.outboxReason("move", 1, null))
        assertEquals(
            "delete waiting to retire the remote copy",
            Wording.outboxReason("trash", 1, null),
        )
        assertEquals("permanent delete waiting", Wording.outboxReason("delete", 0, null))
        assertEquals("attachment waiting to upload", Wording.outboxReason("attach", 0, null))
        assertEquals("weird waiting", Wording.outboxReason("weird", 0, null))
    }

    @Test
    fun outboxReason_attemptsAndLastErrorAppend() {
        assertEquals(
            "edit waiting to push · attempt 3 · last error: superseded by a fresh plan",
            Wording.outboxReason("put", 3, "superseded by a fresh plan"),
        )
        // First attempt (attempts == 1) reads as untried: no attempt clause.
        assertEquals("edit waiting to push", Wording.outboxReason("put", 1, null))
        // A blank error string adds nothing, but repeated attempts still show.
        assertEquals("edit waiting to push · attempt 4", Wording.outboxReason("put", 4, ""))
    }

    // ---- weekly tallies ------------------------------------------------------

    private fun tick(hoursAgo: Long, verdict: String): Wording.LogTick =
        Wording.LogTick(at = now - hoursAgo * 60L * 60L * 1000L, verdict = verdict)

    @Test
    fun weeklyTallies_bucketsFollowEngineVerdicts() {
        val tally = Wording.weeklyTallies(
            listOf(
                tick(1, "Pull"),
                tick(2, "Push"),
                tick(3, "Resurrect"),
                tick(4, "Merge"),
                tick(5, "Merge"),
                tick(6, "Fork"),
            ),
            nowAt = now,
        )
        assertEquals(Wording.WeeklyTally(synced = 3, merged = 2, forked = 1), tally)
    }

    @Test
    fun weeklyTallies_ignoresNonTallyVerdicts() {
        val tally = Wording.weeklyTallies(
            listOf(tick(1, "Nothing"), tick(2, "Trash"), tick(3, "HaltedTrashSafety"), tick(4, "Fetch")),
            nowAt = now,
        )
        assertEquals(Wording.WeeklyTally(), tally)
    }

    @Test
    fun weeklyTallies_windowStartIsInclusive_andAnythingOlderDrops() {
        val exactlySevenDays = Wording.LogTick(now - 7L * 24 * 60 * 60 * 1000, "Push")
        val sevenDaysMinusOneMs = Wording.LogTick(now - 7L * 24 * 60 * 60 * 1000 - 1, "Push")
        assertEquals(
            Wording.WeeklyTally(synced = 1),
            Wording.weeklyTallies(listOf(exactlySevenDays), nowAt = now),
        )
        assertEquals(
            Wording.WeeklyTally(),
            Wording.weeklyTallies(listOf(sevenDaysMinusOneMs), nowAt = now),
        )
    }

    @Test
    fun weeklyTallies_futureEntriesAreExcluded() {
        val future = Wording.LogTick(now + 60_000, "Fork")
        assertEquals(Wording.WeeklyTally(), Wording.weeklyTallies(listOf(future), nowAt = now))
    }

    @Test
    fun tallyLine_formatsTabularSentence() {
        val line = Wording.tallyLine(Wording.WeeklyTally(synced = 12, merged = 2, forked = 1))
        assertEquals("synced 12 · merged 2 · forked 1", line)
    }

    // ---- WS10: attachment-cache bytes + sweep notice --------------------------

    @Test
    fun bytes_wholeBytesUnderAKib() {
        assertEquals("0 B", Wording.bytes(0))
        assertEquals("3 B", Wording.bytes(3))
        assertEquals("1023 B", Wording.bytes(1023))
    }

    @Test
    fun bytes_kibThroughGib_withTrimmedDecimals() {
        assertEquals("1 KB", Wording.bytes(1024))
        assertEquals("1.5 KB", Wording.bytes(1536))
        assertEquals("999.9 KB", Wording.bytes(999 * 1024 + 900))
        assertEquals("1 MB", Wording.bytes(1024 * 1024))
        assertEquals("500 MB", Wording.bytes(500L * 1024 * 1024))
        assertEquals("1.5 GB", Wording.bytes(1536L * 1024 * 1024))
    }

    @Test
    fun sweepNotice_isPlainWordsWithBothCounts() {
        assertEquals("swept 2 · 5 kept", Wording.sweepNotice(swept = 2, kept = 5))
        assertEquals("swept 0 · 0 kept", Wording.sweepNotice(swept = 0, kept = 0))
    }
}
