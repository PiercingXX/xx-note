package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.Frontmatter
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WS10 §10 upload-before-body ordering, over pure-JVM fakes:
 *
 * - an attachment referenced by an outgoing note lands on the far side
 *   BEFORE the note body (request order recorded by [InMemoryRemote]);
 * - a FAILED/PRECONDITION attachment upload defers the whole note push,
 *   queues an idempotent 'attach' op, and retries first on the next pass;
 * - dedup: two notes referencing one hash upload once;
 * - Merge and fork uploads obey the same gate;
 * - with no [Attachments] seam wired, ordering is disabled entirely.
 */
class AttachmentOrderingTest {

    private companion object {
        val CLOCK: Instant = Instant.parse("2026-08-23T10:04:00Z")
        const val DEVICE = "test-device"

        const val ID_A = "01J9F2K3M4N5P6Q7R8S9T0V1W2" // alpha
        const val ID_B = "01J9F2K8ZZ1A2B3C4D5E6F7G8H" // bravo

        /** 16-hex filename prefix as it appears inside note bodies. */
        const val PREFIX_A = "aaaaaaaaaaaaaaaa"
        val FULL_HASH_A = "a".repeat(64)
        val PHOTO_BYTES = ByteArray(64) { (it * 3).toByte() }

        fun note(id: String, title: String, body: String): String =
            "---\nid: $id\ntitle: $title\n---\n$body"
    }

    private fun newEngine(
        local: InMemoryLocal,
        remote: InMemoryRemote,
        book: InMemoryBook,
        attachments: Attachments?,
    ): SyncEngine = SyncEngine(
        local, remote, book, DEVICE,
        clock = { CLOCK },
        attachments = attachments,
    )

    private class World {
        val local = InMemoryLocal()
        val remote = InMemoryRemote()
        val book = InMemoryBook()
        val atts = InMemoryAttachments()

        init {
            atts.seed(FULL_HASH_A, "jpg", PHOTO_BYTES)
        }

        /** One local live note referencing PREFIX_A, nothing remote yet. */
        fun addLocalNoteWithImage(id: String, title: String): String {
            val path = "$id-${title.lowercase()}.md"
            local.add(
                id, path,
                note(id, title, "see photo ![](attachments/$PREFIX_A.jpg) here\n"),
            )
            return path
        }

        fun firstPutFileIndex(): Int =
            remote.requests.indexOfFirst { it.startsWith("putFile:") }

        fun firstBodyIndex(vararg paths: String): Int =
            remote.requests.indexOfFirst { req ->
                req.substringAfter(':').let { p -> paths.any { p == it } } &&
                    !req.startsWith("putFile:")
            }
    }

    @Test
    fun attachmentLandsOnServerBeforeTheNoteBodyReferencingIt() {
        val w = World()
        val pathA = w.addLocalNoteWithImage(ID_A, "Alpha")

        val outcome = w.firstEngine().syncOnce()

        assertEquals(
            SyncEngine.SyncOutcome.Completed(
                pulled = 0, pushed = 1, merged = 0,
                forked = 0, trashed = 0, resurrected = 0, nothing = 0,
            ),
            outcome,
        )
        // Request order: the attachment PUT was issued BEFORE any body PUT.
        val putFileIdx = w.firstPutFileIndex()
        val bodyIdx = w.firstBodyIndex(pathA)
        assertTrue(putFileIdx >= 0, "expected an attachment upload")
        assertTrue(bodyIdx > putFileIdx, "requests were ${w.remote.requests}")
        assertEquals(
            "${SyncEngine.ATTACHMENTS_DIR}$PREFIX_A.jpg",
            w.remote.putFiles.single().path,
        )
        assertTrue(w.remote.fileBytes("${SyncEngine.ATTACHMENTS_DIR}$PREFIX_A.jpg")
            .contentEquals(PHOTO_BYTES))
        // Row marked known; base advanced; body present remotely.
        assertTrue(w.atts.rows.getValue(FULL_HASH_A).remoteKnown)
        assertEquals(note(ID_A, "Alpha", "see photo ![](attachments/$PREFIX_A.jpg) here\n"),
            w.book.baseOf(ID_A)?.body)
        assertNotNull(w.remote.text(pathA))
    }

    @Test
    fun unknownHashPrefixAndKnownRowsPassThroughWithoutUpload() {
        val w = World()
        val pathA = "$ID_A-alpha.md"
        // A reference whose hash has NO store row, plus the seeded row marked
        // already-known: neither may trigger an upload.
        w.local.add(
            ID_A, pathA,
            note(ID_A, "Alpha", "![](attachments/0123456789abcdef.jpg) and " +
                "![](attachments/${PREFIX_A}.jpg)\n"),
        )
        w.atts.rows.getValue(FULL_HASH_A).remoteKnown = true

        val outcome = w.firstEngine().syncOnce()

        assertTrue(outcome is SyncEngine.SyncOutcome.Completed)
        assertEquals(1, (outcome as SyncEngine.SyncOutcome.Completed).pushed)
        assertTrue(w.remote.putFiles.isEmpty(), "no upload expected: ${w.remote.requests}")
    }

    @Test
    fun failedAttachmentUploadDefersNotePushThenCompletesNextSync() {
        val w = World()
        val pathA = w.addLocalNoteWithImage(ID_A, "Alpha")
        w.remote.putFileMode = InMemoryRemote.PutMode.ALWAYS_FAIL

        val first = w.firstEngine().syncOnce()

        // Nothing landed: no body remotely, no base recorded…
        assertNull(w.remote.text(pathA))
        assertNull(w.book.baseOf(ID_A))
        assertEquals(0, (first as SyncEngine.SyncOutcome.Completed).pushed)
        // …an idempotent attach op waits in the outbox…
        val ops = w.book.pendingOps()
        assertEquals(1, ops.size)
        assertEquals(SyncEngine.OP_ATTACH, ops.single().op)
        assertEquals(FULL_HASH_A, ops.single().payload)
        // …and the deferral speaks.
        assertTrue(
            w.book.logs.any {
                it.reason.contains("$PREFIX_A not uploaded") &&
                    it.reason.contains("note push deferred")
            },
            "logs were ${w.book.logs.map { it.reason }}",
        )

        // A second failing pass must NOT multiply outbox ops.
        w.firstEngine().syncOnce()
        assertEquals(1, w.book.pendingOps().size)

        // Transport heals: next pass retries the attachment FIRST, then pushes.
        w.remote.putFileMode = InMemoryRemote.PutMode.NORMAL
        val third = w.firstEngine().syncOnce()

        assertEquals(1, (third as SyncEngine.SyncOutcome.Completed).pushed)
        assertTrue(w.remote.putFiles.last().result is PutResult.WRITTEN)
        val bodyIdx = w.firstBodyIndex(pathA)
        assertTrue(bodyIdx > w.remote.requests.indexOfLast { it.startsWith("putFile:") })
        assertNotNull(w.remote.text(pathA))
        assertNotNull(w.book.baseOf(ID_A))
        assertTrue(w.book.pendingOps().isEmpty(), "attach op drains once confirmed")
        assertTrue(
            w.book.logs.any { it.reason.contains("queued attachment upload confirmed") },
        )
    }

    @Test
    fun twoNotesReferencingOneHashUploadItOnce() {
        val w = World()
        val pathA = w.addLocalNoteWithImage(ID_A, "Alpha")
        val pathB = w.addLocalNoteWithImage(ID_B, "Bravo")

        val outcome = w.firstEngine().syncOnce()

        assertEquals(2, (outcome as SyncEngine.SyncOutcome.Completed).pushed)
        assertEquals(1, w.remote.putFiles.size, "content addressing dedups the upload")
        assertNotNull(w.remote.text(pathA))
        assertNotNull(w.remote.text(pathB))
    }

    @Test
    fun mergedTextIsGatedTooBeforeItsConditionalPush() {
        val w = World()
        val pathA = "$ID_A-alpha.md"
        val agreed = note(ID_A, "Alpha", "alpha\nbeta\ngamma\n")
        // Disjoint single-line edits merge cleanly; the LOCAL edit carries the
        // reference, so the merged text does too.
        w.local.add(
            ID_A, pathA,
            note(ID_A, "Alpha", "alpha local ![](attachments/$PREFIX_A.jpg)\nbeta\ngamma\n"),
        )
        w.remote.seed(pathA, note(ID_A, "Alpha", "alpha\nbeta\ngamma remote\n"), "\"r1\"")
        w.book.recordBase(ID_A, agreed, "\"r1\"")

        val outcome = w.firstEngine().syncOnce()

        assertEquals(1, (outcome as SyncEngine.SyncOutcome.Completed).merged)
        val putFileIdx = w.firstPutFileIndex()
        val putIdx = w.remote.requests.indexOfFirst { it == "put:$pathA" }
        assertTrue(putFileIdx >= 0 && putIdx > putFileIdx, "requests ${w.remote.requests}")
        val pushed = w.remote.text(pathA)!!
        assertTrue(pushed.contains("![](attachments/$PREFIX_A.jpg)"))
        assertEquals(ID_A, Frontmatter.parse(pushed).id)
    }

    @Test
    fun forkUploadKeepsForkLocalWhenItsAttachmentCannotLand() {
        val w = World()
        val pathA = "$ID_A-alpha.md"
        // Same-line edits on both sides force a prose conflict; the local side
        // also carries an image reference, so the marked fork text does too.
        w.local.add(
            ID_A, pathA,
            note(ID_A, "Alpha", "alpha one\nbeta\ngamma\n![](attachments/$PREFIX_A.jpg)\n"),
        )
        w.remote.seed(pathA, note(ID_A, "Alpha", "alpha two\nbeta\ngamma\n"), "\"r1\"")
        w.book.recordBase(ID_A, note(ID_A, "Alpha", "alpha\nbeta\ngamma\n"), "\"r1\"")
        w.remote.putFileMode = InMemoryRemote.PutMode.ALWAYS_FAIL

        val outcome = w.firstEngine().syncOnce()

        // The fork still EXISTS locally (text is never lost) and carries the
        // reference — but nothing of it reached the server while its
        // attachment cannot land.
        assertEquals(1, (outcome as SyncEngine.SyncOutcome.Completed).forked)
        val fork = w.local.listLive().first { it.id != ID_A }
        assertTrue(fork.wholeFileText.contains("![](attachments/$PREFIX_A.jpg)"))
        assertTrue(w.remote.snapshot().keys.containsAll(listOf(pathA)))
        assertEquals(setOf(pathA), w.remote.snapshot().keys, "fork body must not upload")
        assertTrue(w.book.pendingOps().any { it.op == SyncEngine.OP_ATTACH && it.payload == FULL_HASH_A })
    }

    @Test
    fun withoutAnAttachmentsSeamOrderingIsDisabledEntirely() {
        val w = World()
        val pathA = w.addLocalNoteWithImage(ID_A, "Alpha")

        val outcome = SyncEngine(
            w.local, w.remote, w.book, DEVICE, clock = { CLOCK },
            attachments = null,
        ).syncOnce()

        assertEquals(1, (outcome as SyncEngine.SyncOutcome.Completed).pushed)
        assertTrue(w.remote.requests.none { it.startsWith("putFile:") })
        assertNotNull(w.remote.text(pathA))
    }

    private fun World.firstEngine(): SyncEngine = newEngine(local, remote, book, atts)
}
