package com.piercingxx.xxnote.core

import java.text.Normalizer

/**
 * Filesystem slugs for note filenames (design §8: `<ulid>-<slug>.md`).
 * ASCII-only output, deterministic, and stable across JVMs — a slug is
 * cosmetic, but it must never be the reason two tools disagree about a file.
 */
object Slug {

    /** Default filename budget for the slug portion of `<ulid>-<slug>.md`. */
    const val DEFAULT_MAX_LENGTH = 80

    /**
     * Derives a slug from [title]: NFKD-normalize so diacritics decompose,
     * strip combining marks (café → cafe), lowercase ASCII only, collapse
     * every run of non-`[a-z0-9]` characters into a single hyphen, trim
     * leading and trailing hyphens, then truncate to [maxLength] without
     * leaving a trailing hyphen. A degenerate result — empty title, no
     * alphanumerics at all, or everything truncated away — falls back to
     * `"note"`. The fallback ignores [maxLength] when it is smaller than 4.
     *
     * @throws IllegalArgumentException if [maxLength] is not positive.
     */
    fun of(title: String, maxLength: Int = DEFAULT_MAX_LENGTH): String {
        require(maxLength > 0) { "maxLength must be positive: $maxLength" }
        val decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD)
        val sb = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (isCombiningMark(Character.getType(c))) continue
            when (c) {
                in 'a'..'z', in '0'..'9' -> sb.append(c)
                in 'A'..'Z' -> sb.append(c + ('a' - 'A'))
                else -> if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
            }
        }
        var slug = sb.toString().trim('-').take(maxLength).trimEnd('-')
        if (slug.isEmpty()) slug = FALLBACK
        return slug
    }

    // Java's mark categories are byte constants; widen once, compare cleanly.
    private fun isCombiningMark(type: Int): Boolean =
        type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()

    private const val FALLBACK = "note"
}
