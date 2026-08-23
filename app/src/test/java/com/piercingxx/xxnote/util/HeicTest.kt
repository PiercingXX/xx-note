package com.piercingxx.xxnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM half of [Heic]: routing decisions and passthrough behavior. */
class HeicTest {

    @Test
    fun heicExtensionsRouteToTranscode() {
        for (ext in listOf("heic", "heif", "f", "HEIC", "Heif", ".heic")) {
            assertTrue("ext '$ext'", Heic.needsTranscode(ext, null))
        }
    }

    @Test
    fun heicMimeTypesRouteToTranscode() {
        assertTrue(Heic.needsTranscode("", "image/heic"))
        assertTrue(Heic.needsTranscode("", "image/heif"))
        assertTrue(Heic.needsTranscode("", "image/heic-sequence"))
        assertTrue(Heic.needsTranscode("jpg", "IMAGE/HEIF")) // mime wins over ext
    }

    @Test
    fun ordinaryImagesPassThrough() {
        assertFalse(Heic.needsTranscode("jpg", "image/jpeg"))
        assertFalse(Heic.needsTranscode("png", "image/png"))
        assertFalse(Heic.needsTranscode("jpg", null))
        assertFalse(Heic.needsTranscode("webp", "image/webp"))
        assertFalse(Heic.needsTranscode("gif", "image/gifx")) // not a heic family
    }

    @Test
    fun passthroughKeepsBytesAndNormalizesExtension() {
        val bytes = byteArrayOf(1, 2, 3)
        val result = Heic.transcode(bytes, "PNG", "image/png")

        assertFalse(result.transcoded)
        assertEquals("png", result.ext)
        assertTrue(result.bytes.contentEquals(bytes))
    }

    @Test
    fun passthroughExtensionIsLowercasedWithoutDot() {
        assertEquals("jpg", Heic.transcode(byteArrayOf(9), ".JPG", null).ext)
        assertEquals("webp", Heic.normalizeExt(".WebP"))
    }
}
