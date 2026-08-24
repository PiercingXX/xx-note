package com.piercingxx.xxnote.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * WS3 gate coverage: the vault round-trips read -> edit -> re-read with no
 * network anywhere in the call graph — VaultStore touches only java.io/nio,
 * core.Frontmatter, and Room. Robolectric supplies Context for Room only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultStoreTest {

    private lateinit var root: File
    private lateinit var db: XxDatabase
    private lateinit var store: VaultStore

    @Before
    fun setUp() {
        val filesDir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
        root = File(filesDir, "vault-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            XxDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = VaultStore(root, db)
    }

    private fun newUlid(): String = Ulid.generate()

    /** Deterministic whole-file text: frontmatter block plus body, LF endings. */
    private fun noteText(
        id: String,
        title: String,
        body: String,
        color: String = "sand",
    ): String = "---\n" +
        "id: $id\n" +
        "title: $title\n" +
        "created: 2026-08-20T09:00:00Z\n" +
        "updated: 2026-08-21T10:00:00Z\n" +
        "pinned: false\n" +
        "archived: false\n" +
        "color: $color\n" +
        "labels: [home]\n" +
        "type: checklist\n" +
        "plugin_note: keep me verbatim\n" +
        "---\n" +
        "\n" +
        body

    private fun tmpFilesUnder(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(VaultStore.TMP_SUFFIX) }.toList()

    @Test
    fun roundTripWriteReadRereadIdenticalBytes() {
        val id = newUlid()
        val text = noteText(id, "Grocery list", "- [ ] oat milk\n")

        store.write(id, text)

        val once = assertNotNull(store.read(id))
        assertEquals(text, once.wholeFileText)
        assertFalse(once.trashed)
        assertEquals("$id-grocery-list.md", once.path)

        // Re-read: identical bytes again, straight from the mirror file.
        val twice = assertNotNull(store.read(id))
        assertEquals(text, twice.wholeFileText)
        assertEquals(text, File(root, once.path).readText())

        // Exactly one mirror file, no temp residue, no network anywhere above.
        assertEquals(
            listOf("$id-grocery-list.md"),
            root.walkTopDown().filter { it.isFile }.map { it.name }.toList(),
        )
        assertTrue(tmpFilesUnder(root).isEmpty())
    }

    @Test
    fun freshWriteLandsAtUliddSlugPathAndRowCarriesFrontmatterFields() = runBlocking {
        val id = newUlid()
        store.write(id, noteText(id, "Standup Notes", "done: nothing"))

        val row = assertNotNull(db.noteDao().byId(id))
        assertEquals("Standup Notes", row.title)
        assertEquals("checklist", row.type)
        assertEquals("sand", row.color)
        assertNull(row.trashedAt)
        assertEquals("plugin_note: keep me verbatim", row.extraFrontmatter)
        assertEquals(
            java.time.Instant.parse("2026-08-21T10:00:00Z").toEpochMilli(),
            row.updated,
        )
    }

    @Test
    fun failedWriteLeavesOriginalBytesAndNoTmpResidue() {
        val id = newUlid()
        val relPath = store.basePathFor(id, "Grocery list")
        val original = noteText(id, "Grocery list", "- [ ] oat milk\n")
        store.write(id, original)

        // Sabotage: occupy the exact temp path so FileOutputStream must fail.
        val planted = File(root, "$relPath${VaultStore.TMP_SUFFIX}")
        assertTrue(planted.mkdir())
        try {
            val edited = noteText(id, "Grocery list", "- [ ] oat milk\n- [ ] coffee\n")
            val thrown = runCatching { store.write(id, edited) }
            assertTrue(thrown.exceptionOrNull() is IOException)

            assertEquals(original, File(root, relPath).readText())
            assertTrue(tmpFilesUnder(root).isEmpty()) // regular .tmp files: none
            assertEquals(original, assertNotNull(store.read(id)).wholeFileText)
        } finally {
            planted.delete()
        }
    }

    @Test
    fun trashThenListTrashedThenRestorePreservesBody() {
        val id = newUlid()
        val text = noteText(id, "Old idea", "some captured thought\n")
        store.write(id, text)
        val bodyBefore = Frontmatter.parse(text).bodyText

        store.trash(id)

        assertFalse(File(root, "$id-old-idea.md").exists())
        val trashFile = File(root, "${VaultStore.TRASH_DIR}/$id-old-idea.md")
        assertTrue(trashFile.isFile)
        val trashedText = trashFile.readText()
        assertTrue(trashedText.contains("\ntrashedAt: "))
        assertTrue(Frontmatter.parse(trashedText).trashedAt != null)

        val trashed = assertNotNull(store.listTrashed().singleOrNull { it.id == id })
        assertTrue(trashed.trashed)
        assertEquals("${VaultStore.TRASH_DIR}/$id-old-idea.md", trashed.path)
        assertTrue(store.listLive().none { it.id == id })

        store.restore(id)

        val back = assertNotNull(store.listLive().singleOrNull { it.id == id })
        assertFalse(back.trashed)
        assertFalse(trashFile.exists())
        val restoredDoc = Frontmatter.parse(back.wholeFileText)
        assertNull(restoredDoc.trashedAt)
        assertFalse(back.wholeFileText.contains("trashedAt"))
        assertEquals(bodyBefore, restoredDoc.bodyText)
        assertEquals(id, restoredDoc.id)
        assertEquals("Old idea", restoredDoc.title)
        assertTrue(tmpFilesUnder(root).isEmpty())
    }

    @Test
    fun renamedFileIsSameNoteByIdentityAndRowFollowsTheMove() = runBlocking {
        val id = newUlid()
        val text = noteText(id, "Grocery list", "- [ ] oat milk\n")
        store.write(id, text)

        val oldFile = File(root, "$id-grocery-list.md")
        val newFile = File(root, "renamed-by-obsidian.md")
        assertTrue(oldFile.renameTo(newFile))

        val found = assertNotNull(store.listLive().singleOrNull { it.id == id })
        assertEquals("renamed-by-obsidian.md", found.path)
        assertEquals(text, found.wholeFileText)
        assertEquals("renamed-by-obsidian.md", assertNotNull(db.noteDao().byId(id)).path)
        assertEquals(text, assertNotNull(store.read(id)).wholeFileText)
    }

    @Test
    fun freeNameCollisionsIncrementUnderscoreSuffixes() {
        assertEquals("fork.md", store.freeName("fork.md"))
        assertTrue(File(root, "fork.md").createNewFile())
        assertEquals("fork_1.md", store.freeName("fork.md"))
        assertTrue(File(root, "fork_1.md").createNewFile())
        assertEquals("fork_2.md", store.freeName("fork.md"))

        val id = newUlid()
        assertEquals("$id-hello-world.md", store.basePathFor(id, "Hello, World!"))
    }

    @Test
    fun scanAssignsAnIdToFileWithoutOneAndWritesItBack() {
        val orphanText = "plain prose, no frontmatter at all\n"
        File(root, "orphan.md").writeText(orphanText)

        val imported = assertNotNull(store.listLive().singleOrNull())
        assertTrue(Ulid.isValid(imported.id))
        assertFalse(orphanText.contains("id:"))

        val onDisk = File(root, "orphan.md").readText()
        val doc = Frontmatter.parse(onDisk)
        assertEquals(imported.id, doc.id)
        assertEquals(orphanText, doc.bodyText) // body preserved byte-for-byte
    }

    @Test
    fun scanTreatsAnyNonBlankIdAsIdentityWithoutRewritingBytes() {
        val foreign = "---\nid: my-note\ntitle: Foreign tool\n---\nbody from another app\n"
        File(root, "foreign.md").writeText(foreign)

        val found = assertNotNull(store.listLive().singleOrNull { it.id == "my-note" })
        assertEquals("my-note", found.id)
        // Bytes survive the scan UNTOUCHED — no ULID reassigned, no rewrite.
        assertEquals(foreign, found.wholeFileText)
        assertEquals(foreign, File(root, "foreign.md").readText())
        assertFalse(Ulid.isValid(found.id))
    }

    @Test
    fun fileWinsOverRoomWhenEditedExternally() = runBlocking {
        val id = newUlid()
        store.write(id, noteText(id, "Stale title", "stale body\n"))

        val v2 = noteText(id, "Obsidian edit", "fresh body from the desktop\n")
        File(root, "$id-stale-title.md").writeText(v2)

        val note = assertNotNull(store.listLive().singleOrNull { it.id == id })
        assertEquals("Obsidian edit", Frontmatter.parse(note.wholeFileText).title)
        assertEquals(v2, note.wholeFileText)
        assertEquals("Obsidian edit", assertNotNull(db.noteDao().byId(id)).title)
    }

    @Test
    fun syncBookkeepingRoundTripThroughStore() = runBlocking {
        val id = newUlid()

        assertNull(store.baseOf(id))
        store.recordBase(id, "---\nid: $id\n---\n\nbase body\n", etag = "\"abc123\"")
        val base = assertNotNull(store.baseOf(id))
        assertEquals("\"abc123\"", base.etag)
        assertEquals("---\nid: $id\n---\n\nbase body\n", base.body)

        store.forgetBase(id)
        assertNull(store.baseOf(id))

        store.enqueueOp(id, "put", "{\"path\":\"x.md\"}")
        store.enqueueOp(id, "trash", "{}")
        val pending = store.pendingOps()
        assertEquals(listOf("put", "trash"), pending.map { it.op })
        assertEquals(0, pending[0].attempts)
        assertNull(pending[0].lastError)

        store.markOpFailed(pending[0].id, "507 insufficient storage")
        store.markOpFailed(pending[0].id, "still failing")
        val failed = store.pendingOps().single { it.op == "put" }
        assertEquals(2, failed.attempts)
        assertEquals("still failing", failed.lastError)

        store.markOpDone(failed.id)
        assertEquals(listOf("trash"), store.pendingOps().map { it.op })
    }

    @Test
    fun logPrunesToThousandRows() = runBlocking {
        val total = SyncLogDao.LOG_CAP + 1
        repeat(total) { i ->
            store.log(
                com.piercingxx.xxnote.sync.SyncLogEntry(
                    noteId = if (i == 0) null else "n-$i",
                    verdict = "Nothing",
                    reason = "entry $i",
                    ok = true,
                    detail = null,
                ),
            )
        }
        val rows = db.syncLogDao().latest(SyncLogDao.LOG_CAP + 10)
        assertEquals(SyncLogDao.LOG_CAP, rows.size)
        assertTrue(rows.none { it.reason == "entry 0" }) // the single oldest dropped
        assertEquals("entry ${total - 1}", rows.first().reason) // newest first
    }

    @Test
    fun outboxOpIsPurgedAndLoggedAsAbandonedAtTheAttemptCap() = runBlocking {
        val id = newUlid()
        store.enqueueOp(id, "put", "durable intent")
        val op = assertNotNull(store.pendingOps().singleOrNull())

        repeat(VaultStore.MAX_OP_ATTEMPTS - 1) { i ->
            store.markOpFailed(op.id, "failure ${i + 1}")
        }
        val stillQueued = assertNotNull(store.pendingOps().singleOrNull())
        assertEquals(VaultStore.MAX_OP_ATTEMPTS - 1, stillQueued.attempts)

        // The cap: this failure purges the zombie op and logs the abandonment.
        store.markOpFailed(op.id, "failure ${VaultStore.MAX_OP_ATTEMPTS}")
        assertTrue(store.pendingOps().isEmpty())
        val logRows = db.syncLogDao().latest(10)
        assertTrue(
            logRows.any {
                it.verdict == "Outbox" &&
                    it.reason.contains("abandoned after ${VaultStore.MAX_OP_ATTEMPTS} attempts") &&
                    !it.ok
            },
        )
    }

    @Test
    fun forkTrashedCopyRestampsInPlaceThenWriteRestoresOriginalLive() = runBlocking {
        val id = newUlid()
        val original = noteText(id, "Old idea", "stale trash body\n")
        store.write(id, original)
        store.trash(id)
        val originalBody = Frontmatter.parse(original).bodyText
        val trashDir = File(root, VaultStore.TRASH_DIR)

        val freshId = newUlid()
        val now = "2026-08-23T10:04:00Z"
        store.forkTrashedCopy(id, freshId) { text ->
            Frontmatter.parse(text).rewritten {
                this.id = freshId
                conflictOf = id
                conflictAt = now
            }
        }

        // Trash holds exactly one copy, re-stamped under its OWN identity.
        val forked = assertNotNull(store.listTrashed().singleOrNull { it.id == freshId })
        val forkDoc = Frontmatter.parse(forked.wholeFileText)
        assertEquals(id, forkDoc.conflictOf)
        assertEquals(now, forkDoc.conflictAt)
        assertEquals(originalBody, forkDoc.bodyText)
        assertNull(db.noteDao().byId(id)) // old identity retired with the re-stamp

        // The surviving side comes back live under the ORIGINAL id...
        val edited = noteText(id, "Old idea", "edited elsewhere while trashed\n")
        store.write(id, edited)
        val live = assertNotNull(store.listLive().singleOrNull { it.id == id })
        assertEquals(edited, live.wholeFileText)

        // ...and BOTH texts now exist on disk: live file + trashed fork.
        assertEquals(edited, File(root, live.path).readText())
        val trashFiles = trashDir.walkTopDown().filter { it.isFile && it.name.endsWith(".md") }.toList()
        assertEquals(listOf(forked.path.substringAfterLast('/')), trashFiles.map { it.name })
    }

    @Test
    fun writeRefusesATrashedNoteInsteadOfMintingALiveTwin() {
        val id = newUlid()
        val text = noteText(id, "Old idea", "some captured thought\n")
        store.write(id, text)
        store.trash(id)
        val trashPath = "${VaultStore.TRASH_DIR}/$id-old-idea.md"
        val trashedBytes = File(root, trashPath).readText()

        // H3: the pending editor save lands after the batch-trash. The write
        // must refuse — never create a live twin under the same id.
        val thrown = runCatching {
            store.write(id, noteText(id, "Old idea", "edited while trashed\n"))
        }
        assertTrue(thrown.exceptionOrNull() is TrashedNoteException)
        assertTrue(thrown.exceptionOrNull() is IllegalStateException)
        assertEquals(
            "note is trashed; restore before writing",
            thrown.exceptionOrNull()!!.message,
        )

        // The trash copy's bytes are untouched, and NO live file appeared.
        assertEquals(trashedBytes, File(root, trashPath).readText())
        assertTrue(store.listLive().isEmpty())
        assertEquals(1, store.listTrashed().size)
        assertTrue(tmpFilesUnder(root).isEmpty())
    }

    @Test
    fun restoreUnblocksWritingTheSameIdAgain() {
        val id = newUlid()
        val text = noteText(id, "Old idea", "some captured thought\n")
        store.write(id, text)
        store.trash(id)

        val refused = runCatching { store.write(id, text) }
        assertTrue(refused.exceptionOrNull() is TrashedNoteException)

        store.restore(id)
        store.write(id, noteText(id, "Old idea", "edited after restore\n"))
        val live = assertNotNull(store.listLive().singleOrNull { it.id == id })
        assertTrue(live.wholeFileText.contains("edited after restore"))
    }

    @Test
    fun purgeExpiredTrashRemovesOnlyOldStampedEntriesAndTheirRows() = runBlocking {
        val oldId = newUlid()
        val freshId = newUlid()
        val unstampedId = newUlid()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val now = java.time.Instant.parse("2026-08-23T10:04:00Z").toEpochMilli()

        // OLD: trashed long ago — craft the stamp directly in the mirror.
        store.write(oldId, noteText(oldId, "Ancient", "long gone\n"))
        store.trash(oldId)
        val oldFile = File(root, "${VaultStore.TRASH_DIR}/$oldId-ancient.md")
        oldFile.writeText(
            Frontmatter.parse(oldFile.readText()).rewritten {
                updated = "2026-01-01T00:00:00Z"
                trashedAt = "2026-01-01T00:00:00Z"
            },
        )
        // FRESH: trashed moments ago via the normal path.
        store.write(freshId, noteText(freshId, "Recent", "still resting\n"))
        store.trash(freshId)
        // UNSTAMPED: a foreign trash file with no trashedAt at all — NEVER expires.
        File(root, VaultStore.TRASH_DIR).mkdirs()
        File(root, "${VaultStore.TRASH_DIR}/$unstampedId-orphan.md").writeText(
            "---\nid: $unstampedId\ntitle: Orphan\n---\nno stamp on purpose\n",
        )
        // BOUNDARY: stamped EXACTLY at the retention age — not "older than" yet.
        File(root, "${VaultStore.TRASH_DIR}/boundary.md").writeText(
            "---\nid: boundary-id\ntitle: B\ntrashedAt: ${java.time.Instant.ofEpochMilli(now - sevenDaysMs)}\n---\nb\n",
        )
        // Re-scan so every row mirrors the disk state before the sweep.
        assertEquals(4, store.listTrashed().size)

        val purged = store.purgeExpiredTrash(sevenDaysMs, now)

        assertEquals(1, purged)
        assertFalse(oldFile.exists()) // file gone…
        assertNull(db.noteDao().byId(oldId)) // …and its row with it

        // Fresh, unstamped, and boundary-exact copies survive untouched, files AND rows.
        assertTrue(File(root, "${VaultStore.TRASH_DIR}/$freshId-recent.md").isFile)
        assertNotNull(db.noteDao().byId(freshId))
        assertTrue(File(root, "${VaultStore.TRASH_DIR}/$unstampedId-orphan.md").isFile)
        assertNotNull(db.noteDao().byId(unstampedId))
        assertTrue(File(root, "${VaultStore.TRASH_DIR}/boundary.md").isFile)
        org.junit.Assert.assertNotNull(db.noteDao().byId("boundary-id"))
    }

    // assertNotNull above guarantees non-null; the cast target is erased at runtime, so the warning is a false positive.
    @Suppress("UNCHECKED_CAST")
    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value as T
    }
}
