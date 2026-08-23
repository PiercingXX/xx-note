package com.piercingxx.xxnote.data

import android.graphics.Bitmap
import android.media.ExifInterface
import com.piercingxx.xxnote.util.ExifStripper
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The whole §10 insert pipeline over one synthetic JPEG: transcode routing →
 * temp file → [ExifStripper.strip] → hash over STRIPPED bytes → content-
 * addressed file → Room row. AMBIGUOUS-escape identical to
 * [ExifStripperRoboTest]: skipped consistently where this Robolectric build
 * cannot encode EXIF JPEGs.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentInsertRoboTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun gpsJpegBytes(): ByteArray? = try {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF993366.toInt())
        val scratch = File.createTempFile("insert-fixture", ".jpg", tmp.newFolder())
        FileOutputStream(scratch).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(scratch.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,30/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "122/1,25/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }
        val bytes = scratch.readBytes()
        assumeTrue(
            "fixture did not take GPS attrs",
            ExifInterface(scratch.absolutePath).hasAttribute(ExifInterface.TAG_GPS_LATITUDE),
        )
        bytes
    } catch (e: Exception) {
        assumeNoException(e) // AMBIGUOUS: this Robolectric can't write EXIF JPEGs
        null
    }

    @Test
    fun fullInsertStoresStrippedContentUnderItsHashWithARow() = runBlocking {
        val bytes = gpsJpegBytes() ?: return@runBlocking
        val vault = tmp.newFolder("vault")
        val dao = FakeAttachmentDao()
        val store = AttachmentStore(vault, dao)

        val result = store.insert(bytes, "jpg", "image/jpeg")

        assertEquals(64, result.hash.length)
        assertEquals(
            "attachments/${result.hash.take(16)}.jpg",
            result.relativePath,
        )
        assertFalse(result.transcoded)
        val stored = File(vault, result.relativePath)
        assertTrue(stored.isFile)

        // The STORED copy carries no location data.
        val after = ExifInterface(stored.absolutePath)
        assertNull(after.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertFalse(after.hasAttribute(ExifInterface.TAG_GPS_LONGITUDE))

        // Hash is over the stored (stripped) bytes exactly.
        assertEquals(result.hash, AttachmentStore.sha256Hex(stored.readBytes()))

        // Row recorded; no temp residue left behind.
        val row = dao.rows[result.hash]
        assertEquals(result.relativePath, row!!.localPath)
        assertEquals(stored.length(), row.bytes)
        assertTrue(
            File(vault, "attachments").walkTopDown()
                .filter { it.name.endsWith(".tmp") }.toList().isEmpty(),
        )
    }
}
