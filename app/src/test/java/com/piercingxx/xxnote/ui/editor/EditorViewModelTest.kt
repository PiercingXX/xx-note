package com.piercingxx.xxnote.ui.editor

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.data.VaultStore
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * H3/M6/L8 gate at the view-model level (Robolectric: real vault mirror +
 * real Room file db under filesDir, exactly the production wiring the lazy
 * [EditorViewModel] store resolves to). The editor's save scope is a private
 * Dispatchers.IO scope — no main-looper choreography needed; the helpers
 * simply poll with a deadline.
 *
 * - H3: a debounced save landing AFTER an external batch-trash degrades
 *   gracefully — plain words, pending intent dropped, trash bytes untouched,
 *   no live twin minted.
 * - M6: the archive toggle rides the same single-save pipeline.
 * - L8: a WorkManager enqueue refusal after a successful save stays silent
 *   (no crash, no false "not saved").
 * - Hardening #2: the ON_STOP/onCleared flush persists a dirty buffer
 *   immediately, cancels the debounce so nothing double-writes, and is a
 *   no-op on a clean editor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorViewModelTest {

    private lateinit var context: android.content.Context
    private lateinit var store: VaultStore
    private lateinit var vm: EditorViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Same mirror dir + same Room file the VM's own VaultStore(context) opens.
        File(context.filesDir, VaultStore.MIRROR_DIR).deleteRecursively()
        store = VaultStore(context)
        vm = EditorViewModel(ApplicationProvider.getApplicationContext<android.app.Application>())
    }

    /** Polls until [pred] holds or the deadline passes; returns the last state. */
    private fun await(deadlineMs: Long = 8_000, pred: (EditorViewModel.UiState) -> Boolean): EditorViewModel.UiState {
        val deadline = System.currentTimeMillis() + deadlineMs
        var state = vm.state.value
        while (System.currentTimeMillis() < deadline) {
            state = vm.state.value
            if (pred(state)) return state
            Thread.sleep(25)
        }
        return state
    }

    private fun noteText(id: String, title: String, body: String): String =
        "---\nid: $id\ntitle: $title\ncreated: 2026-08-20T09:00:00Z\n" +
            "updated: 2026-08-21T10:00:00Z\npinned: false\narchived: false\n---\n$body"

    /**
     * Hardening #2: invokes the protected ViewModel teardown hook directly.
     * [Method.invoke] dispatches virtually, so the EditorViewModel override
     * runs — proving the wiring itself, not a copy of it.
     */
    private fun EditorViewModel.clearNow() {
        androidx.lifecycle.ViewModel::class.java
            .getDeclaredMethod("onCleared")
            .apply { isAccessible = true }
            .invoke(this)
    }

    @Test
    fun writeAfterExternalTrashSurfacesWordsAndDropsThePendingIntentWithoutTouchingTrash() {
        val id = Ulid.generate()
        val original = noteText(id, "Old idea", "some captured thought\n")
        store.write(id, original)

        vm.load(id)
        await { it.ready }
        assertEquals("some captured thought\n", vm.state.value.initialBody)

        // The batch-trash wins the race against the open editor…
        store.trash(id)
        val trashedBytesBefore = File(
            context.filesDir,
            "${VaultStore.MIRROR_DIR}/.xxnote/trash",
        ).walkTopDown().filter { it.isFile }.single().readText()

        // …and the still-pending editor edit must NOT resurrect a twin.
        vm.onBodyChange("edited while trashed\n")

        val state = await { it.saveError?.contains("trash") == true }
        assertTrue(
            "expected the H3 words, got: ${state.saveError}",
            state.saveError!!.contains(EditorViewModel.TRASHED_REASON),
        )

        // Trash bytes untouched; nothing live anywhere; exactly one trashed copy.
        val trashFiles = File(context.filesDir, "${VaultStore.MIRROR_DIR}/.xxnote/trash")
            .walkTopDown().filter { it.isFile }.toList()
        assertEquals(1, trashFiles.size)
        assertEquals(trashedBytesBefore, trashFiles.single().readText())
        assertFalse(trashFiles.single().readText().contains("edited while trashed"))
        assertTrue(store.listLive().isEmpty())
        assertEquals(1, store.listTrashed().size)
    }

    @Test
    fun archiveToggleFoldsThroughTheSingleSavePipeline() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Deep work", "focus\n"))

        vm.load(id)
        await { it.ready }
        assertFalse(vm.state.value.archived)

        vm.setArchived(true)

        assertEquals(true, vm.state.value.archived) // optimistic mirror
        await { s ->
            store.read(id)?.wholeFileText?.contains("archived: true") == true
        }
        assertNull("a successful save must not speak (L8)", vm.state.value.saveError)
        // And back again through the same path.
        vm.setArchived(false)
        await { _ -> store.read(id)?.wholeFileText?.contains("archived: true") != true }
        assertNull(vm.state.value.saveError)
    }

    @Test
    fun deleteNoteMovesToTrashThenCloses() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Shred me", "gone soon\n"))

        vm.load(id)
        await { it.ready }

        var closed = false
        vm.deleteNote(onDeleted = { closed = true })

        await { _ -> closed }
        assertTrue(store.listLive().isEmpty())
        assertTrue(store.listTrashed().any { it.id == id })
        assertNull(vm.state.value.saveError)
    }

    // ---- WS10 gallery insert ---------------------------------------------------

    /**
     * A strippable JPEG via [Bitmap.compress], with the repo's AMBIGUOUS
     * escape where this Robolectric build cannot encode one.
     */
    private fun jpegBytes(): ByteArray? = try {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val scratch = File(context.cacheDir, "editor-fixture-${System.nanoTime()}.jpg")
        FileOutputStream(scratch).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val bytes = scratch.readBytes()
        scratch.delete()
        assumeTrue(bytes.isNotEmpty())
        bytes
    } catch (e: Exception) {
        assumeNoException(e)
        null
    }

    @Test
    fun galleryInsertFoldsIntoSavePipelineAtCursor() {
        val jpeg = jpegBytes() ?: return
        val id = Ulid.generate()
        store.write(id, noteText(id, "With image", "hello world\n"))

        vm.load(id)
        await { it.ready }

        // Cursor after "hello"; the link must splice exactly there.
        vm.insertImageBytes(jpeg, "jpg", "image/jpeg", cursorOffset = 5)

        val saved = await { s ->
            store.read(id)?.wholeFileText?.contains("![](attachments/") == true &&
                s.saveError == null
        }
        val whole = store.read(id)!!.wholeFileText
        val expectedBodyPrefix = "hello![](attachments/"
        assertTrue(
            "link not at cursor: ${Frontmatter.parse(whole).bodyText}",
            Frontmatter.parse(whole).bodyText.startsWith(expectedBodyPrefix),
        )
        // Link shape: ![](attachments/<16 hex>.<ext>)\n — nothing else moved.
        val link = Regex("!\\[\\]\\(attachments/[0-9a-f]{16}\\.jpg\\)\n").find(whole)
            ?: throw AssertionError("no attachment link in: $whole")
        assertEquals(" world\n", Frontmatter.parse(whole).bodyText.substringAfter(link.value))
        // The content-addressed file exists under the mirror's attachments/.
        val hex = link.value.substringAfter("attachments/").substringBefore('.')
        assertTrue(
            "content-addressed file missing for $hex",
            File(context.filesDir, "${VaultStore.MIRROR_DIR}/attachments/$hex.jpg").isFile,
        )
        // The one-shot event is published for the screen and consumable.
        assertNotNull(saved.insertion)
        vm.consumeInsertion()
        assertNull(vm.state.value.insertion)
    }

    @Test
    fun failedInsertSpeaksPlainWordsAndInsertsNothing() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "No image", "untouched\n"))
        vm.load(id)
        await { it.ready }

        vm.insertImageBytes(ByteArray(0), "jpg", "image/jpeg", cursorOffset = 0)

        val state = await { it.saveError != null }
        assertEquals(EditorViewModel.INSERT_FAILED_WORDS, state.saveError)
        assertFalse(
            "a failed insert must not touch the body",
            store.read(id)!!.wholeFileText.contains("![](attachments/"),
        )
    }

    // ---- Hardening #2: ON_STOP / onCleared flush ------------------------------

    /**
     * (a) A dirty buffer persists the moment [EditorViewModel.flushPendingSave]
     * runs — well inside the 800 ms window it would otherwise wait out.
     */
    @Test
    fun flushPersistsADirtyBufferBeforeTheDebounceElapses() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Draft", "typed just now\n"))
        vm.load(id)
        await { it.ready }

        vm.onBodyChange("typed just now, plus this line\n")
        vm.flushPendingSave()

        await { _ -> store.read(id)?.wholeFileText?.contains("plus this line") == true }
        assertNull("a flushed save must not speak", vm.state.value.saveError)
    }

    /**
     * (b) The flush CANCELS the debounce timer: after waiting past the
     * original window, no second persist lands. Observable because each
     * persistNow stamps a fresh millisecond `updated:` — a double write
     * would change the bytes.
     */
    @Test
    fun flushCancelsTheDebounceTimerSoNoSecondWriteLands() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Once", "one write only\n"))
        vm.load(id)
        await { it.ready }

        vm.onBodyChange("one write only, flushed\n")
        vm.flushPendingSave()
        await { _ -> store.read(id)?.wholeFileText?.contains("flushed") == true }
        val flushedBytes = store.read(id)!!.wholeFileText

        Thread.sleep(EditorViewModel.SAVE_DEBOUNCE_MS + 400)
        assertEquals(
            "the cancelled debounce must not fire a second write",
            flushedBytes,
            store.read(id)!!.wholeFileText,
        )
    }

    /**
     * (c) ViewModel teardown flushes too — the second line of defence when
     * back-navigation pops before composition could react to anything.
     */
    @Test
    fun onClearedFlushesTheDirtyBufferAsLastWritePath() {
        val id = Ulid.generate()
        store.write(id, noteText(id, "Teardown", "cleared mid-window\n"))
        vm.load(id)
        await { it.ready }

        vm.onBodyChange("cleared mid-window, then saved\n")
        vm.clearNow()

        await { _ -> store.read(id)?.wholeFileText?.contains("then saved") == true }
        assertNull(vm.state.value.saveError)
    }

    /** Backgrounding a clean editor is a no-op: no rewrite, no stamp churn. */
    @Test
    fun flushWithoutEditsLeavesTheFileUntouched() {
        val id = Ulid.generate()
        val original = noteText(id, "Idle", "nothing dirty here\n")
        store.write(id, original)
        vm.load(id)
        await { it.ready }

        vm.flushPendingSave()

        Thread.sleep(EditorViewModel.SAVE_DEBOUNCE_MS + 200)
        assertEquals(original, store.read(id)!!.wholeFileText)
        assertNull(vm.state.value.saveError)
    }
}
