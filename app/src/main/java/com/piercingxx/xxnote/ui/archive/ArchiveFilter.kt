package com.piercingxx.xxnote.ui.archive

import com.piercingxx.xxnote.core.Frontmatter

/**
 * PURE archive predicate (design §12 item 3): which live notes belong on the
 * archive surface. Reads only the frontmatter through [Frontmatter] — no disk,
 * no Room, no Android — and follows the does-less direction everywhere:
 *
 * - `archived: true` (any lenient spelling the parser accepts) → archived.
 * - Absent, `false`, blank, or unparseable value → NOT archived. An unknown
 *   state must never hide a note from its owner.
 * - A note with no frontmatter, or a malformed block, is body-only prose and
 *   therefore not archived.
 */
object ArchiveFilter {

    /** True iff this file's frontmatter carries an affirmative `archived:` value. */
    fun isArchived(wholeFileText: String): Boolean =
        Frontmatter.parse(wholeFileText).archived == true

    /**
     * Order-preserving filter over any note-like list; [wholeFileTextOf]
     * extracts each item's whole-file text so the caller's model stays opaque.
     */
    fun <T> filterArchived(items: List<T>, wholeFileTextOf: (T) -> String): List<T> =
        items.filter { isArchived(wholeFileTextOf(it)) }
}
