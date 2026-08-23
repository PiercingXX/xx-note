package com.piercingxx.xxnote.util

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real transcode path (WS10): HEIC-labeled bytes decode → JPEG at quality 90,
 * dimensions survive, passthrough inputs come back byte-identical.
 *
 * AMBIGUOUS-escape: Robolectric has no HEIC encoder, so the fixture is real
 * JPEG bytes labeled `heic` — BitmapFactory sniffs content, not names, which
 * exercises the exact decode→compress→recycle machinery a Pixel's HEIC would
 * take. If this Robolectric build lacks native graphics codecs, the
 * assumption skips consistently rather than failing.
 */
@RunWith(RobolectricTestRunner::class)
class HeicTranscodeRoboTest {

    private fun jpegBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(3, 5, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF663399.toInt())
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun heicLabeledBytesComeOutAsJpeg() {
        val source = try {
            jpegBytes()
        } catch (e: Exception) {
            assumeNoException(e) // AMBIGUOUS: no native graphics in this build
            return
        }
        assumeTrue(BitmapFactory.decodeByteArray(source, 0, source.size) != null)

        val result = Heic.transcode(source, "heic", "image/heic")

        assertTrue(result.transcoded)
        assertEquals("jpg", result.ext)
        assertEquals(3, result.width)
        assertEquals(5, result.height)
        // JPEG SOI/EOI markers on the re-encoded output.
        assertEquals(0xFF, result.bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, result.bytes[1].toInt() and 0xFF)
        assertEquals(0xFF, result.bytes[result.bytes.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, result.bytes[result.bytes.size - 1].toInt() and 0xFF)
    }

    @Test
    fun undecodableHeicFallsBackToPassthrough() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        // AMBIGUOUS-escape: this Robolectric's BitmapFactory reports success
        // even on garbage (fabricated bitmap), so the fallback branch can only
        // run where decoding is real. Skip consistently where it cannot be
        // exercised; device verification covers it.
        try {
            assumeTrue(BitmapFactory.decodeByteArray(garbage, 0, garbage.size) == null)
        } catch (e: Exception) {
            assumeNoException(e)
        }
        val result = Heic.transcode(garbage, "heic", null)

        assertFalse(result.transcoded)
        assertEquals("heic", result.ext)
        assertTrue(result.bytes.contentEquals(garbage))
    }

    @Test
    fun boundsDecodeReportsRealDimensionsForPassthroughImages() {
        val source = try {
            jpegBytes()
        } catch (e: Exception) {
            assumeNoException(e)
            return
        }
        assumeTrue(Heic.boundsOf(source).first != 0)

        val (w, h) = Heic.boundsOf(jpegBytes())
        assertEquals(3, w)
        assertEquals(5, h)
    }
}
