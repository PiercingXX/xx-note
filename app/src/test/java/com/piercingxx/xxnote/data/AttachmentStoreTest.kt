package com.piercingxx.xxnote.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * WS10 insert pipeline math — the [AttachmentStore.insertProcessed] seam:
 * SHA-256 addressing, dedup by content, row recording, path hygiene. Pure
 * JVM: fake DAO, no bitmaps, no EXIF rewriting (those live in the Robolectric
 * tests); HEIC/EXIF stages sit upstream of this seam. [ensureLocal]'s network
 * paths run against a local MockWebServer through the real [WebDavClient].
 */
class AttachmentStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newVault(): File = tmp.newFolder("vault-${System.nanoTime()}")

    private fun store(
        vault: File,
        dao: FakeAttachmentDao,
        now: Long = 1_000L,
        clientProvider: (() -> com.piercingxx.xxnote.net.WebDavClient)? = null,
    ): AttachmentStore = AttachmentStore(vault, dao, clientProvider).also { it.clock = { now } }

    // --- addressing -------------------------------------------------------

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            AttachmentStore.sha256Hex("abc".toByteArray()),
        )
        assertEquals(64, AttachmentStore.sha256Hex(byteArrayOf()).length)
    }

    @Test
    fun processedInsertUsesFirstSixteenHexCharsAndExtension() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val store = store(vault, dao)

        val result = store.insertProcessed("abc".toByteArray(), "jpg", 12, 34)

        assertEquals("attachments/ba7816bf8f01cfea.jpg", result.relativePath)
        assertEquals(12, result.width)
        assertEquals(34, result.height)
        assertFalse(result.transcoded)
        assertTrue(File(vault, result.relativePath).isFile)
        val row = dao.rows[result.hash]!!
        assertEquals(result.relativePath, row.localPath)
        assertEquals(3L, row.bytes)
        assertEquals(1_000L, row.lastViewedAt)
        assertFalse(row.remoteKnown)
    }

    @Test
    fun noTempResidueAfterInsert() = runBlocking {
        val vault = newVault()
        val dir = File(vault, "attachments")
        val store = store(vault, FakeAttachmentDao())
        store.insertProcessed("residue".toByteArray(), "png", 1, 1)
        assertTrue(dir.walkTopDown().filter { it.name.endsWith(".tmp") }.toList().isEmpty())
    }

    @Test
    fun unsafeExtensionsAreRejected() = runBlocking {
        val store = store(newVault(), FakeAttachmentDao())
        for (bad in listOf("../evil", "a/b", "a.b", "", " ")) {
            try {
                store.insertProcessed("x".toByteArray(), bad, 0, 0)
                fail("ext '$bad' should have been rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        Unit
    }

    // --- dedup ------------------------------------------------------------

    @Test
    fun sameBytesInsertedTwiceDedupToOneFileAndRow() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val dir = File(vault, "attachments")
        val store = store(vault, dao)
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        val first = store.insertProcessed(bytes, "png", 10, 20)
        val second = store.insertProcessed(bytes, "png", 10, 20)

        assertEquals(first.hash, second.hash)
        assertEquals(first.relativePath, second.relativePath)
        assertEquals(1, dir.listFiles()?.size)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun dedupHitReturnsExistingRowsDimensionsNotThisCalls() = runBlocking {
        val vault = newVault()
        val store = store(vault, FakeAttachmentDao())
        val bytes = "same content".toByteArray()

        val first = store.insertProcessed(bytes, "png", 100, 200)
        val second = store.insertProcessed(bytes, "png", 9, 9)

        assertEquals(100, second.width)
        assertEquals(200, second.height)
        assertEquals(first.relativePath, second.relativePath)
    }

    @Test
    fun sameBytesDifferentExtensionDedupsToOriginalRow() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val dir = File(vault, "attachments")
        val store = store(vault, dao)

        val first = store.insertProcessed("one blob".toByteArray(), "jpg", 1, 1)
        val second = store.insertProcessed("one blob".toByteArray(), "jpeg", 1, 1)

        assertEquals(first.hash, second.hash)
        assertEquals(first.relativePath, second.relativePath)
        assertEquals("jpg", dao.rows[first.hash]!!.ext)
        assertEquals(1, dir.listFiles()?.size)
    }

    @Test
    fun dedupHitReCachesEvictedLocalCopy() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        var now = 1_000L
        val s = AttachmentStore(vault, dao).also { it.clock = { now } }

        val first = s.insertProcessed("photo".toByteArray(), "jpg", 8, 8)
        s.evictToBudget(budgetBytes = 0)
        assertNull(dao.rows[first.hash]!!.localPath)
        assertFalse(File(vault, first.relativePath).isFile)

        now = 2_000L
        val again = s.insertProcessed("photo".toByteArray(), "jpg", 8, 8)

        assertEquals(first.hash, again.hash)
        assertTrue(File(vault, again.relativePath).isFile)
        assertEquals(again.relativePath, dao.rows[first.hash]!!.localPath)
    }

    @Test
    fun differentBytesLandInDifferentFiles() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val dir = File(vault, "attachments")
        val store = store(vault, dao)

        val a = store.insertProcessed("one".toByteArray(), "jpg", 1, 1)
        val b = store.insertProcessed("two".toByteArray(), "jpg", 1, 1)

        assertTrue(a.hash != b.hash)
        assertEquals(2, dir.listFiles()?.size)
        assertEquals(2, dao.rows.size)
    }

    // --- referencedHashes ---------------------------------------------------

    @Test
    fun imageLinksInsideNotesAreFound() {
        val store = store(newVault(), FakeAttachmentDao())
        val texts = listOf(
            "---\nid: 01J\n---\n# Note\n\n![](attachments/3f9a2c81b4e07d65.jpg)\n",
            "body text ![alt text](attachments/0123456789abcdef.png) more",
        )
        assertEquals(
            setOf("3f9a2c81b4e07d65", "0123456789abcdef"),
            store.referencedHashes(texts),
        )
    }

    @Test
    fun multipleReferencesToSameHashCollapseToOneEntry() {
        val store = store(newVault(), FakeAttachmentDao())
        val texts = listOf(
            "![](attachments/aaaaaaaaaaaaaaaa.jpg)",
            "![](attachments/aaaaaaaaaaaaaaaa.jpg) and <img src=\"attachments/AAAAAAAAAAAAAAAA.jpg\"/>",
        )
        assertEquals(setOf("aaaaaaaaaaaaaaaa"), store.referencedHashes(texts))
    }

    @Test
    fun nonImageLinksAndWrongShapesAreIgnored() {
        val store = store(newVault(), FakeAttachmentDao())
        val texts = listOf(
            "[regular note link](other-note.md)",
            "[folder link](sub/dir/note.md)",
            "![too short](attachments/deadbeef.jpg)",
            "![seventeen hex is not ours](attachments/aaaaaaaaaaaaaaaa0.jpg)",
            "<img src=\"/uploads/aaaaaaaaaaaaaaaa.jpg\">",
        )
        assertTrue(store.referencedHashes(texts).isEmpty())
    }

    @Test
    fun anyOccurrenceOfTheReferenceShapeCountsAsAReference() {
        // Deliberately conservative (§10: never sweep something possibly
        // referenced): the path pattern counts wherever it appears — Markdown,
        // HTML src, or a pasted absolute URL naming our own vault layout.
        val store = store(newVault(), FakeAttachmentDao())
        val texts = listOf(
            "see https://nas.example.com/Notes/attachments/aaaaaaaaaaaaaaaa.jpg for the photo",
        )
        assertEquals(setOf("aaaaaaaaaaaaaaaa"), store.referencedHashes(texts))
    }

    // --- orphanCount / sweepOrphans -----------------------------------------

    private suspend fun seed(
        store: AttachmentStore,
        contents: List<Pair<String, String>>,
    ): List<String> =
        contents.map { (content, ext) -> store.insertProcessed(content.toByteArray(), ext, 1, 1).relativePath }

    @Test
    fun orphanCountIsStoredMinusReferenced() = runBlocking {
        val vault = newVault()
        val store = store(vault, FakeAttachmentDao())
        val paths = seed(store, listOf("aaa" to "jpg", "bbb" to "jpg", "ccc" to "png"))
        val referencedPrefix = paths.first().substringAfterLast('/').substringBefore('.')
        val texts = listOf("![](attachments/$referencedPrefix.jpg)")

        assertEquals(2, store.orphanCount(texts))
        assertEquals(3, store.orphanCount(emptyList()))
    }

    @Test
    fun sweepRemovesUnreferencedLocalFilesAndRowsOnly() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val store = store(vault, dao)
        val paths = seed(store, listOf("keep-me" to "jpg", "sweep-me" to "jpg"))
        val keepFile = File(vault, paths[0])
        val sweepFile = File(vault, paths[1])
        assertTrue(keepFile.isFile && sweepFile.isFile)
        val referencedPrefix = paths[0].substringAfterLast('/').substringBefore('.')

        val swept = store.sweepOrphans(listOf("![](attachments/$referencedPrefix.jpg)"))

        assertEquals(1, swept)
        assertTrue(keepFile.isFile)
        assertFalse(sweepFile.exists())
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun sweepWithNoReferencesClearsEverythingLocally() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val dir = File(vault, "attachments")
        val store = store(vault, dao)
        seed(store, listOf("x" to "jpg", "y" to "png"))

        assertEquals(2, store.sweepOrphans(emptyList()))

        assertEquals(0, dao.rows.size)
        assertTrue(dir.listFiles()?.isEmpty() == true)
    }

    // --- touch ---------------------------------------------------------------

    @Test
    fun touchBumpsOnlyTheTouchedRow() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        var now = 1_000L
        val s = AttachmentStore(vault, dao).also { it.clock = { now } }
        val a = s.insertProcessed("a".toByteArray(), "jpg", 1, 1)
        val b = s.insertProcessed("b".toByteArray(), "jpg", 1, 1)

        now = 5_000L
        s.touch(a.hash)

        assertEquals(5_000L, dao.rows[a.hash]!!.lastViewedAt)
        assertEquals(1_000L, dao.rows[b.hash]!!.lastViewedAt)
    }

    @Test
    fun touchOfUnknownHashIsIgnored() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        store(vault, dao).touch("f".repeat(64))
        assertTrue(dao.rows.isEmpty())
    }

    // --- evictToBudget --------------------------------------------------------

    @Test
    fun evictionShedsLeastRecentlyViewedFirst() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        var now = 0L
        val s = AttachmentStore(vault, dao).also { it.clock = { now } }

        now = 1; val a = s.insertProcessed(ByteArray(300) { 1 }, "jpg", 1, 1)
        now = 2; val b = s.insertProcessed(ByteArray(300) { 2 }, "jpg", 1, 1)
        now = 3; val c = s.insertProcessed(ByteArray(300) { 3 }, "jpg", 1, 1)

        s.evictToBudget(budgetBytes = 600)

        assertNull(dao.rows[a.hash]!!.localPath)
        assertNotNull(dao.rows[b.hash]!!.localPath)
        assertNotNull(dao.rows[c.hash]!!.localPath)
        assertFalse(File(vault, a.relativePath).isFile)
        assertTrue(File(vault, b.relativePath).isFile)
        assertTrue(File(vault, c.relativePath).isFile)
        assertEquals(3, dao.rows.size)
    }

    @Test
    fun evictionBreaksRecencyTiesByHashAscending() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val s = store(vault, dao, now = 7)

        val one = s.insertProcessed(ByteArray(100) { 1 }, "jpg", 1, 1)
        val two = s.insertProcessed(ByteArray(100) { 2 }, "jpg", 1, 1)
        val (lowHash, highHash) = if (one.hash < two.hash) one to two else two to one

        s.evictToBudget(budgetBytes = 150)

        // Equal lastViewedAt → hash ASC is the eviction order, so the
        // lowest-hash row goes first and exactly one fits the budget.
        assertNull(dao.rows[lowHash.hash]!!.localPath)
        assertNotNull(dao.rows[highHash.hash]!!.localPath)
    }

    @Test
    fun underBudgetNothingIsEvicted() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val s = store(vault, dao)
        val r = s.insertProcessed(ByteArray(50), "jpg", 1, 1)

        s.evictToBudget()

        assertEquals(r.relativePath, dao.rows[r.hash]!!.localPath)
    }

    @Test
    fun alreadyMissingFilesLoseStalePathWithoutEvictingLiveRows() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        var now = 1L
        val s = AttachmentStore(vault, dao).also { it.clock = { now } }
        val ghost = s.insertProcessed("ghost".toByteArray(), "jpg", 1, 1)
        File(vault, ghost.relativePath).delete()

        now = 2
        val real = s.insertProcessed(ByteArray(10) { 9 }, "jpg", 1, 1)
        s.evictToBudget(budgetBytes = 20) // comfortably above the live total

        // The vanished file's stale pointer is cleared; the live row is untouched.
        assertNull(dao.rows[ghost.hash]!!.localPath)
        assertEquals(real.relativePath, dao.rows[real.hash]!!.localPath)
        assertTrue(File(vault, real.relativePath).isFile)
    }

    // --- ensureLocal ----------------------------------------------------------

    @Test
    fun presentFileReturnsImmediatelyWithoutNetwork() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        var asked = false
        val s = store(vault, dao) {
            asked = true
            error("no client expected")
        }
        val inserted = s.insertProcessed("already here".toByteArray(), "jpg", 4, 4)

        val file = s.ensureLocal(inserted.hash, "jpg")!!
        assertTrue(file.isFile)
        assertFalse(asked)
    }

    @Test
    fun absentFileDownloadsWritesAndUpdatesRow() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        var now = 1_000L
        val base = store(vault, dao).also { it.clock = { now } }
        val inserted = base.insertProcessed("from afar".toByteArray(), "jpg", 6, 6)
        base.evictToBudget(budgetBytes = 0)

        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(Buffer().write("from afar".toByteArray())),
            )
            val client = com.piercingxx.xxnote.net.WebDavClient(
                server.hostName, server.port, "/", "xxnote", "pw", scheme = "http",
            )
            now = 9_999L
            val s = store(vault, dao, now = 9_999L) { client }

            val file = s.ensureLocal(inserted.hash, "jpg")!!
            assertTrue(file.readBytes().contentEquals("from afar".toByteArray()))
            assertEquals("/${inserted.relativePath}", server.takeRequest().path)
            val row = dao.rows[inserted.hash]!!
            assertEquals(inserted.relativePath, row.localPath)
            assertTrue(row.remoteKnown)
            assertEquals(9_999L, row.lastViewedAt)
        }
    }

    @Test
    fun missingRemoteYieldsNullWithoutRowDamage() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val base = store(vault, dao)
        val inserted = base.insertProcessed("gone?".toByteArray(), "jpg", 1, 1)
        base.evictToBudget(budgetBytes = 0)

        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(404))
            val client = com.piercingxx.xxnote.net.WebDavClient(
                server.hostName, server.port, "/", "xxnote", "pw", scheme = "http",
            )
            val s = store(vault, dao) { client }

            assertNull(s.ensureLocal(inserted.hash, "jpg"))
            assertNull(dao.rows[inserted.hash]!!.localPath)
            assertFalse(dao.rows[inserted.hash]!!.remoteKnown)
        }
    }

    @Test
    fun corruptedDownloadIsRefusedByHashCheck() = runBlocking {
        val vault = newVault()
        val dao = FakeAttachmentDao()
        val base = store(vault, dao)
        val inserted = base.insertProcessed("the truth".toByteArray(), "jpg", 1, 1)
        base.evictToBudget(budgetBytes = 0)

        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(Buffer().write("tampered!!".toByteArray())),
            )
            val client = com.piercingxx.xxnote.net.WebDavClient(
                server.hostName, server.port, "/", "xxnote", "pw", scheme = "http",
            )
            val s = store(vault, dao) { client }

            assertNull(s.ensureLocal(inserted.hash, "jpg"))
            assertFalse(File(vault, inserted.relativePath).exists())
        }
    }

    @Test
    fun nullProviderYieldsNull() = runBlocking {
        val s = store(newVault(), FakeAttachmentDao(), clientProvider = null)
        assertNull(s.ensureLocal(AttachmentStore.sha256Hex("nobody".toByteArray()), "jpg"))
    }
}
