package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.BaseSnapshot
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proof for the pass mutex (review S1a): WorkManager does not serialize across
 * unique names, so the periodic and expedited chains can launch two [SyncEngine]
 * passes into one process at once. The engine must queue the second behind the
 * first — whole-vault reconciliations may never interleave against the same
 * mirror, database, and NAS.
 *
 * The probe sits in [baseOf] (called during every pass's planning phase) and
 * tracks peak concurrency while each visit holds a window open long enough
 * that two unsynchronized passes WOULD overlap. Under the mutex the peak is
 * exactly 1 — deterministically, with no timing dependence on the assertion.
 */
class SyncEnginePassMutexTest {

    /** Delegates to an inner book while measuring peak concurrent planning. */
    private class ConcurrencyProbeBook(private val inner: InMemoryBook) : SyncBookkeeping {
        private val active = AtomicInteger(0)

        /** Highest number of passes observed inside [baseOf] at once. */
        var maxConcurrent = 0
            private set

        override fun baseOf(noteId: String): BaseSnapshot? {
            val now = active.incrementAndGet()
            // Racy max update is fine here: under serialization it can never
            // exceed 1, which is exactly what the assertion checks.
            if (now > maxConcurrent) maxConcurrent = now
            Thread.sleep(100) // widen the overlap window
            active.decrementAndGet()
            return inner.baseOf(noteId)
        }

        override fun recordBase(noteId: String, wholeFileText: String, etag: String?) =
            inner.recordBase(noteId, wholeFileText, etag)

        override fun forgetBase(noteId: String) = inner.forgetBase(noteId)

        override fun log(entry: SyncLogEntry) = inner.log(entry)

        override fun enqueueOp(noteId: String, op: String, payload: String) =
            inner.enqueueOp(noteId, op, payload)

        override fun pendingOps() = inner.pendingOps()

        override fun markOpDone(opId: Long) = inner.markOpDone(opId)

        override fun markOpFailed(opId: Long, error: String) = inner.markOpFailed(opId, error)
    }

    private companion object {
        val CLOCK: Instant = Instant.parse("2026-08-23T10:04:00Z")
        const val DEVICE = "test-device"
        const val ID_A = "01J9F2K3M4N5P6Q7R8S9T0V1W2"
    }

    @Test
    fun overlappingSyncOnceCallsQueueInsteadOfInterleaving() {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = ConcurrencyProbeBook(InMemoryBook())

        // One fully-agreed note so both passes do real planning work.
        val text = "---\nid: $ID_A\ntitle: Alpha\n---\nalpha\n"
        local.add(ID_A, "$ID_A-alpha.md", text)
        remote.seed("$ID_A-alpha.md", text, "\"r1\"")
        book.recordBase(ID_A, text, "\"r1\"")

        val engine = SyncEngine(local, remote, book, DEVICE, clock = { CLOCK })

        val startGate = CountDownLatch(1)
        val pool: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            val outcomes: List<Future<SyncEngine.SyncOutcome>> = (1..2).map {
                pool.submit<SyncEngine.SyncOutcome> {
                    startGate.await()
                    engine.syncOnce()
                }
            }
            startGate.countDown()
            for (outcome in outcomes) {
                assertEquals(
                    SyncEngine.SyncOutcome.Completed(
                        pulled = 0, pushed = 0, merged = 0,
                        forked = 0, trashed = 0, resurrected = 0,
                        nothing = 1,
                    ),
                    outcome.get(30, TimeUnit.SECONDS),
                )
            }
        } finally {
            pool.shutdownNow()
        }

        assertTrue(book.maxConcurrent == 1, "passes overlapped inside baseOf: ${book.maxConcurrent}")
    }
}
