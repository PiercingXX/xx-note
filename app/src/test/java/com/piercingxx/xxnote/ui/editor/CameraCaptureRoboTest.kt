package com.piercingxx.xxnote.ui.editor

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.Ulid
import com.piercingxx.xxnote.data.VaultStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P2.12 camera capture at the view-model level (Robolectric: real vault
 * mirror + real Room file db, exactly the production wiring the lazy
 * [EditorViewModel] store resolves to; the capture [Uri]'s bytes arrive
 * through the [EditorViewModel.readCaptureBytes] seam — the same
 * contentResolver-shaped surface the TakePicture callback feeds):
 *
 * - §13 permission-denied path: plain words once, editor fully usable, the
 *   gallery path still inserts and saves afterwards;
 * - success path: captured bytes ride [EditorViewModel.insertImageBytes] —
 *   SHA-256 address, EXIF strip, content-addressed file, link at cursor,
 *   debounced save — indistinguishable from a picker insert downstream.
 *
 * The manifest's FileProvider entry is exercised by every Robolectric boot
 * in this module (the merged manifest must parse); the authority itself is
 * only resolvable through the system's camera grant on hardware.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraCaptureRoboTest {

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

    /** 1×1 JPEG, hardcoded so this test never skips when Robolectric has no encoder. */
    private fun jpegBytes(): ByteArray = JPEG_1X1.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun capturedBytesRideTheSamePipelineAsThePickerInsert() {
        val jpeg = jpegBytes()
        val id = Ulid.generate()
        store.write(id, noteText(id, "From camera", "shot note\n"))
        vm.load(id)
        await { it.ready }

        val reads = mutableListOf<Uri>()
        vm.readCaptureBytes = { uri ->
            reads += uri
            jpeg
        }

        // What the screen hands over after TakePicture reports success.
        val captureUri = Uri.parse("content://${context.packageName}.fileprovider/camera/capture.jpg")
        vm.insertCapturedPhoto(captureUri, cursorOffset = 5)

        val saved = await { s ->
            store.read(id)?.wholeFileText?.contains("![](attachments/") == true &&
                s.saveError == null && s.insertion != null
        }
        assertEquals(listOf(captureUri), reads)

        // Identical outcome shape to the gallery insert: link spliced at the
        // cursor, content-addressed file under the mirror, save pipeline ran.
        val whole = store.read(id)!!.wholeFileText
        assertTrue(
            "link not at cursor: ${Frontmatter.parse(whole).bodyText}",
            Frontmatter.parse(whole).bodyText.startsWith("shot ![](attachments/"),
        )
        val link = Regex("!\\[\\]\\(attachments/[0-9a-f]{16}\\.jpg\\)\n").find(whole)
            ?: throw AssertionError("no attachment link in: $whole")
        val hex = link.value.substringAfter("attachments/").substringBefore('.')
        assertTrue(
            "content-addressed file missing for $hex",
            File(context.filesDir, "${VaultStore.MIRROR_DIR}/attachments/$hex.jpg").isFile,
        )
        assertNotNull(saved.insertion)
        assertNull("a clean capture must not speak", saved.cameraWords)
    }

    @Test
    fun unreadableCaptureSpeaksPlainWordsAndInsertsNothing() {
        val id = Ulid.generate()
        val original = noteText(id, "No shot", "untouched\n")
        store.write(id, original)
        vm.load(id)
        await { it.ready }

        vm.readCaptureBytes = { null }
        vm.insertCapturedPhoto(Uri.parse("content://test/capture.jpg"), cursorOffset = 0)

        val state = await { it.saveError != null }
        assertEquals(EditorViewModel.INSERT_FAILED_WORDS, state.saveError)
        assertFalse(
            "a failed capture must not touch the body",
            store.read(id)!!.wholeFileText.contains("![](attachments/"),
        )
    }

    @Test
    fun deniedCameraPromptKeepsTheEditorFullyUsable() {
        val jpeg = jpegBytes()
        val id = Ulid.generate()
        store.write(id, noteText(id, "Still works", "typing continues\n"))
        vm.load(id)
        await { it.ready }
        assertTrue(vm.state.value.ready)

        // §13 first-capture prompt refused: one line of words, nothing else.
        vm.onCameraPermissionDenied()
        assertEquals(EditorViewModel.CAMERA_DENIED_WORDS, vm.state.value.cameraWords)
        assertTrue(vm.state.value.ready)
        assertFalse(vm.state.value.missing)

        // A fresh attempt clears the words before the next prompt…
        vm.clearCameraWords()
        assertNull(vm.state.value.cameraWords)

        // …and the gallery path still inserts + saves end to end.
        vm.insertImageBytes(jpeg, "jpg", "image/jpeg", cursorOffset = 17)
        await { s ->
            store.read(id)?.wholeFileText?.contains("![](attachments/") == true &&
                s.saveError == null
        }
        assertTrue(vm.state.value.ready)
        assertNull(vm.state.value.cameraWords)
    }

    @Test
    fun captureUriIsDiscardedAfterTheBytesAreRead() {
        val jpeg = jpegBytes()
        val id = Ulid.generate()
        store.write(id, noteText(id, "From camera", "shot note\n"))
        vm.load(id)
        await { it.ready }

        val discarded = mutableListOf<Uri>()
        vm.discardCapture = { discarded += it }
        val captureUri = Uri.parse("content://${context.packageName}.fileprovider/camera/capture.jpg")
        vm.readCaptureBytes = { jpeg }
        vm.insertCapturedPhoto(captureUri, cursorOffset = 0)

        await { discarded.isNotEmpty() }
        assertEquals(listOf(captureUri), discarded)
    }
}

/** 1×1 JPEG (SOI + JFIF + DQT + SOF0 + DHT + SOS + EOI). */
private const val JPEG_1X1 =
    "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffc0000b080001000101011100ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2f3f4f5f6f7f8f9faffda000c03010002110311003f00fbffd9"
