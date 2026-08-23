package com.piercingxx.xxnote.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * §10 / R11: HEIC from the Pixel camera is transcoded to JPEG on insert,
 * because "readable by anything" is the entire point — the attachment must
 * resolve in Obsidian, in a browser against the NAS share, in any other
 * Markdown reader ten years from now. HEIC is none of those; JPEG is all of
 * them. The transcode happens once, on insert, before hashing — so the vault
 * only ever holds the universal copy and content addressing stays simple.
 *
 * Pure framework code ([BitmapFactory.decodeByteArray] +
 * [Bitmap.compress]) — no native dependency (D17). An input labeled HEIC
 * that fails to decode is passed through untouched: never lose bytes beats
 * never ship an exotic container.
 */
object Heic {

    data class Transcoded(
        val bytes: ByteArray,
        val ext: String,
        val width: Int,
        val height: Int,
        /** True when this call actually re-encoded HEIC/HEIF → JPEG. */
        val transcoded: Boolean,
    )

    const val JPEG_QUALITY = 90
    const val JPEG_EXT = "jpg"

    /** Extensions that trigger transcoding ("f" is a HEIF container variant). */
    val TRANSCODE_EXTS = setOf("heic", "heif", "f")

    /**
     * True when [ext] or [mimeType] identifies HEIC/HEIF input. Extension
     * match is exact-after-lowercasing; MIME matches the `image/heic`/
     * `image/heif` families including their `-sequence` variants.
     */
    fun needsTranscode(ext: String, mimeType: String?): Boolean {
        val e = ext.lowercase().removePrefix(".")
        if (e in TRANSCODE_EXTS) return true
        val m = mimeType?.lowercase() ?: return false
        return m.startsWith("image/heic") || m.startsWith("image/heif")
    }

    /**
     * Transcodes HEIC/HEIF bytes to JPEG at [JPEG_QUALITY]; everything else
     * passes through byte-for-byte with its extension normalized to lowercase.
     * Dimensions come from the decode for transcoded input and from a cheap
     * bounds-only decode for passthrough (0 when unknowable without decoding).
     * Undecodable "HEIC" falls back to passthrough.
     */
    fun transcode(bytes: ByteArray, ext: String, mimeType: String?): Transcoded {
        if (!needsTranscode(ext, mimeType)) {
            val (w, h) = boundsOf(bytes)
            return Transcoded(bytes, normalizeExt(ext), w, h, transcoded = false)
        }
        val bitmap = decode(bytes)
            ?: return Transcoded(bytes, normalizeExt(ext), 0, 0, transcoded = false)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val result = Transcoded(out.toByteArray(), JPEG_EXT, bitmap.width, bitmap.height, true)
        bitmap.recycle()
        return result
    }

    fun normalizeExt(ext: String): String = ext.lowercase().removePrefix(".")

    private fun decode(bytes: ByteArray): Bitmap? =
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }

    /** Best-effort dimensions; (0, 0) means unknown. */
    fun boundsOf(bytes: ByteArray): Pair<Int, Int> =
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth to opts.outHeight
        } catch (_: Exception) {
            0 to 0
        }
}
