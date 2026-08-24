package com.piercingxx.xxnote.sync

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM proof of hardening #7's collapse gate — the live-pass counter plus
 * follow-up flag pair on [SyncWorker]'s companion that bounds expedited sync
 * chains to the running passes plus AT MOST one queued follow-up. No
 * WorkManager involved: doWork marks each pass started/finished itself, so the
 * decision logic needs nothing Android to be proven.
 *
 * The counter exists because WorkManager does not serialize across unique
 * names: the periodic (`xx-note-sync-periodic`) and expedited
 * (`xx-note-sync-once`) chains can run CONCURRENTLY, so "a pass finished" is
 * not the same as "the last pass finished" — only the true-zero transition may
 * consume a follow-up.
 */
class SyncWorkerFollowUpGateTest {

    @Before
    fun resetGateBefore() {
        resetGate()
    }

    @After
    fun resetGateAfter() {
        resetGate()
    }

    /**
     * The companion outlives every method in the JVM, so tests start from a
     * known idle state (counter 0, no pending follow-up) instead of relying
     * on method order. Production never needs this: doWork pairs
     * passStarted/passFinished exactly.
     */
    private fun resetGate() {
        SyncWorker.livePasses.set(0)
        SyncWorker.followUpRequested.set(false)
    }

    @Test
    fun idleRunEnqueuesDirectlyInsteadOfDeferring() {
        assertFalse(SyncWorker.deferToRunningPass())
    }

    @Test
    fun savesDuringARunningPassCollapseIntoExactlyOneChainedPass() {
        SyncWorker.passStarted()

        // A ten-minute writing session's worth of debounced saves...
        repeat(25) { assertTrue(SyncWorker.deferToRunningPass()) }

        // ...all ride ONE follow-up, then the queue is empty again: a fresh
        // probe pass finds nothing left to chain behind it.
        assertTrue(SyncWorker.passFinished())
        SyncWorker.passStarted()
        assertFalse(SyncWorker.passFinished())
    }

    @Test
    fun passWithoutInterleavedSavesChainsNothing() {
        SyncWorker.passStarted()
        assertFalse(SyncWorker.passFinished())
    }

    @Test
    fun theChainedPassItselfCarriesAtMostOneFurtherFollowUp() {
        SyncWorker.passStarted()
        repeat(3) { assertTrue(SyncWorker.deferToRunningPass()) }
        assertTrue(SyncWorker.passFinished())

        // The successor pass can accumulate its own single follow-up, no more.
        SyncWorker.passStarted()
        repeat(2) { assertTrue(SyncWorker.deferToRunningPass()) }
        assertTrue(SyncWorker.passFinished())

        // And nothing chains behind the successor either.
        SyncWorker.passStarted()
        assertFalse(SyncWorker.passFinished())
    }

    @Test
    fun overlappingPassesConsumeTheFollowUpOnlyAtTrueZero() {
        // Periodic and expedited chains dispatched concurrently (distinct
        // unique names — WorkManager serializes neither against the other):
        // the live-pass counter climbs to 2.
        SyncWorker.passStarted()
        SyncWorker.passStarted()

        // A save landing mid-overlap still defers into the single flag.
        assertTrue(SyncWorker.deferToRunningPass())

        // First finisher: one pass is STILL live, so nothing may enqueue yet
        // — under the old single-boolean gate this finisher would have
        // cleared "running" and consumed the follow-up itself.
        assertFalse(SyncWorker.passFinished())
        // A later save during the survivor also rides the chain.
        assertTrue(SyncWorker.deferToRunningPass())

        // Last finisher hits true zero and consumes exactly one follow-up.
        assertTrue(SyncWorker.passFinished())

        // The queue is empty again: a fresh pass carries no chained work.
        SyncWorker.passStarted()
        assertFalse(SyncWorker.passFinished())
    }
}
