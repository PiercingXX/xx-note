package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.Slug
import com.piercingxx.xxnote.core.Ulid
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ConflictNamer(
    private val deviceName: String,
    private val clock: () -> Instant = { Instant.now() },
    private val nameExists: (String) -> Boolean = { false },
) {
    fun forkName(originalPath: String): String {
        val device = Slug.of(deviceName)
        val stamp = STAMP_FORMAT.format(clock())
        var n = 1
        while (true) {
            val tail = "${stamp}_EditConflict_${n}.md"
            val budget = MAX_NAME_BYTES -
                tail.toByteArray(Charsets.UTF_8).size -
                SEPARATOR_BYTES * 2 -
                device.toByteArray(Charsets.UTF_8).size
            val slug = Slug.of(titlePart(originalPath), maxOf(1, budget))
            val candidate = "${slug}_${device}_$tail"
            if (!nameExists(candidate)) return candidate
            n++
        }
    }

    private fun titlePart(originalPath: String): String {
        val fileName = originalPath.substringAfterLast('/', originalPath)
        val mdIndex = fileName.length - MD_SUFFIX.length
        val stem = if (
            mdIndex >= 0 &&
            fileName.regionMatches(mdIndex, MD_SUFFIX, 0, MD_SUFFIX.length, ignoreCase = true)
        ) {
            fileName.substring(0, mdIndex)
        } else {
            fileName
        }
        val dash = stem.indexOf('-')
        return if (dash == Ulid.LENGTH && Ulid.isValid(stem.substring(0, dash))) {
            stem.substring(dash + 1)
        } else {
            stem
        }
    }

    companion object {
        private const val MAX_NAME_BYTES = 255
        private const val SEPARATOR_BYTES = 1
        private const val MD_SUFFIX = ".md"

        private val STAMP_FORMAT =
            DateTimeFormatter.ofPattern("MMM-dd-HHmm-yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC)
    }
}
