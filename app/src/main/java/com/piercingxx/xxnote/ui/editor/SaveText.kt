package com.piercingxx.xxnote.ui.editor

import com.piercingxx.xxnote.core.ChecklistSorter
import com.piercingxx.xxnote.core.Frontmatter

/**
 * PURE debounced-save assembly for WS7's editor: current title + body +
 * the file's own frontmatter → next whole-file text. Unit-tested byte-level
 * in EditorSaveTest; the ViewModel calls this once per debounced save, never
 * per keystroke (D18/O3).
 *
 * Guarantees:
 * - Only `title` and `updated` are touched in an existing block; unknown keys
 *   and untouched owned keys survive byte-for-byte (§4.3). A blank title
 *   REMOVES the `title:` line rather than writing `title: ""` — the grid's
 *   first-body-line fallback then applies, matching Keep.
 * - A note without frontmatter (or with a malformed one) gets a fresh
 *   well-formed block prepended carrying exactly the assigned keys; whatever
 *   was body stays body — nothing is ever discarded (R5).
 * - [ChecklistSorter.sortCheckedToBottom] runs iff the file's `type:` says
 *   `checklist` (D18 is behavioral, §8); it permutes whole task lines inside
 *   each block only, so a second identical call is byte-stable.
 * - Idempotence law: `buildSaveText(buildSaveText(x, t, b, now), t, b, now)`
 *   equals the first result — the same clock stamp must not perturb bytes.
 */
fun buildSaveText(originalWholeFileText: String, title: String, body: String, updatedIso: String): String {
    val doc = Frontmatter.parse(originalWholeFileText)
    val stamped = doc.rewritten {
        if (title.isBlank()) this.title = null else this.title = title
        this.updated = updatedIso
    }
    // Re-parse: rewritten() may have moved the head/frontmatter boundary.
    val stampedDoc = Frontmatter.parse(stamped)
    val headLength = when {
        stampedDoc.hasFrontmatter && !stampedDoc.isMalformed ->
            stamped.length - stampedDoc.bodyText.length
        else -> 0
    }
    val whole = stamped.substring(0, headLength) + body
    return ChecklistSorter.sortCheckedToBottom(whole)
}
