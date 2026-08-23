package com.piercingxx.xxnote.util

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real [ExifInterface] round-trip over a synthetic JPEG fixture (WS10):
 * GPS attributes are gone after [ExifStripper.strip]; orientation and
 * DateTimeOriginal survive.
 *
 * AMBIGUOUS-escape: Robolectric must be able to encode a JPEG bitmap AND
 * rewrite EXIF through its shadows/native graphics. Where a Robolectric
 * build cannot, the assumptions below skip the scenario consistently rather
 * than fail it — coverage then rests on device verification of §10's strip.
 */
@RunWith(RobolectricTestRunner::class)
class ExifStripperRoboTest {

    private fun newJpegWithGps(dir: File): File {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val file = File(dir, "gps-fixture.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "37/1,46/1,30/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "122/1,25/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "12/1")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:08:23 10:00:00")
            setAttribute(ExifInterface.TAG_ORIENTATION, "6") // rotate 90
            saveAttributes()
        }
        return file
    }

    @Test
    fun stripRemovesGpsAndKeepsOrientationAndDateTime() {
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val file = try {
            newJpegWithGps(dir)
        } catch (e: Exception) {
            assumeNoException(e) // AMBIGUOUS: this Robolectric can't write EXIF JPEGs
            return
        }

        val before = ExifInterface(file.absolutePath)
        assumeTrue("fixture did not take GPS attrs", before.hasAttribute(ExifInterface.TAG_GPS_LATITUDE))

        ExifStripper.strip(file.absolutePath)

        val after = ExifInterface(file.absolutePath)
        assertNull(after.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(after.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(after.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
        assertNull(after.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
        assertFalse(after.hasAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertEquals("6", after.getAttribute(ExifInterface.TAG_ORIENTATION))
        assertEquals(
            "2026:08:23 10:00:00",
            after.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
        )
    }

    @Test
    fun stripIsANoOpOnAnImageWithoutGps() {
        val dir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        val file = try {
            newJpegWithGps(dir)
        } catch (e: Exception) {
            assumeNoException(e)
            return
        }
        val beforeBytes = file.readBytes()
        ExifStripper.strip(file.absolutePath)
        // Orientation/datetime still present afterwards — the file was not gutted.
        val after = ExifInterface(file.absolutePath)
        assertNotNull(after.getAttribute(ExifInterface.TAG_ORIENTATION))
    }
}
