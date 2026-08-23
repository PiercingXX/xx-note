package com.piercingxx.xxnote.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * §10 cache-budget law as a named scenario: eviction order follows VIEW
 * recency ([AttachmentStore.touch]), never insertion order and never file
 * mtimes — a photo inserted first but viewed last must outlive photos
 * viewed earlier. Ties fall back to hash-ascending for determinism.
 */
class TouchOrderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun row(
        store: AttachmentStore,
        seed: Int,
    ): AttachmentStore.InsertResult = store.insertProcessed(ByteArray(300) { seed.toByte() }, "jpg", 1, 1)

    @Test
    fun viewRecencyNotInsertionOrderDecidesSurvivors() = runBlocking {
        val vault = tmp.newFolder("touch-${System.nanoTime()}")
        val dao = FakeAttachmentDao()
        var now = 0L
        val s = AttachmentStore(vault, dao).also { it.clock = { now } }

        // Insertion order a, b, c — then the user views them backwards.
        now = 1; val a = row(s, 1)
        now = 2; val b = row(s, 2)
        now = 3; val c = row(s, 3)
        now = 4; s.touch(c.hash)
        now = 5; s.touch(b.hash)
        now = 6; s.touch(a.hash)

        // Budget fits two of three 300-byte rows. View order oldest→newest is
        // c, b, a: `c` (viewed earliest) goes; insertion-oldest `a`, viewed
        // last, survives.
        s.evictToBudget(budgetBytes = 600)

        assertNotNull(dao.rows[a.hash]!!.localPath)
        assertNotNull(dao.rows[b.hash]!!.localPath)
        assertNull(dao.rows[c.hash]!!.localPath)
        assertTrue(File(vault, c.relativePath).exists() == false)
        assertFalse(File(vault, c.relativePath).isFile)
        assertEquals(3, dao.rows.size) // rows survive eviction; files do not
    }

    @Test
    fun untouchedRowsAreEvictedBeforeTouchedOnes() = runBlocking {
        val vault = tmp.newFolder("untouched-${System.nanoTime()}")
        val dao = FakeAttachmentDao()
        var now = 0L
        val s = AttachmentStore(vault, dao).also { it.clock = { now } }

        now = 1; val old = row(s, 4)
        now = 2; val mid = row(s, 5)
        now = 3; val new = row(s, 6)
        now = 4; s.touch(new.hash)

        // Budget fits one row: the untouched middle row loses to the touched
        // newcomer even though the newcomer is not the oldest.
        s.evictToBudget(budgetBytes = 300)

        assertNull(dao.rows[old.hash]!!.localPath)
        assertNull(dao.rows[mid.hash]!!.localPath)
        assertNotNull(dao.rows[new.hash]!!.localPath)
        assertTrue(File(vault, new.relativePath).isFile)
    }
}
