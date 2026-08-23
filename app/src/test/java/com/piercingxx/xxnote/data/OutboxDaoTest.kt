package com.piercingxx.xxnote.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxDaoTest {

    private lateinit var db: XxDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            XxDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    private suspend fun enqueue(noteId: String, op: String, payload: String, queuedAt: Long): Long =
        db.outboxDao().insert(
            OutboxEntity(
                noteId = noteId,
                op = op,
                payload = payload,
                lastError = null,
                queuedAt = queuedAt,
            ),
        )

    @Test
    fun pendingIsOldestFirstAndMarkDoneRemoves() = runBlocking {
        val c = enqueue("n-c", "put", "c", queuedAt = 30L)
        val a = enqueue("n-a", "trash", "a", queuedAt = 10L)
        enqueue("n-b", "move", "b", queuedAt = 20L)

        val order = db.outboxDao().pending()
        assertEquals(listOf("n-a", "n-b", "n-c"), order.map { it.noteId })

        db.outboxDao().markDone(a)
        assertEquals(listOf("n-b", "n-c"), db.outboxDao().pending().map { it.noteId })

        db.outboxDao().markDone(c)
        db.outboxDao().markDone(db.outboxDao().pending().single().id)
        assertEquals(0, db.outboxDao().pending().size)
    }

    @Test
    fun markOpFailedIncrementsAttemptsAndRecordsLastError() = runBlocking {
        val id = enqueue("n1", "put", "payload", queuedAt = 1L)

        db.outboxDao().markOpFailed(id, "timeout 1")
        db.outboxDao().markOpFailed(id, "timeout 2")

        val op = db.outboxDao().pending().single()
        assertEquals(2, op.attempts)
        assertEquals("timeout 2", op.lastError)

        // A later success still removes it; attempts never block the retry.
        db.outboxDao().markOpFailed(id, "timeout 3")
        assertEquals(3, db.outboxDao().pending().single().attempts)
        db.outboxDao().markDone(id)
        assertEquals(0, db.outboxDao().pending().size)
        assertNull(db.outboxDao().pending().firstOrNull())
    }
}
