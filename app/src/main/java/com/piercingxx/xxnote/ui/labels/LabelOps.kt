package com.piercingxx.xxnote.ui.labels

import com.piercingxx.xxnote.core.Frontmatter

/**
 * PURE label algebra over whole-file text (WS8; design §8, §12 item 3).
 *
 * Every function parses with [Frontmatter.parse] and rewrites via
 * [FrontmatterDocument.rewritten], so unknown frontmatter keys survive
 * byte-for-byte (§4.3), line endings (LF/CRLF) are preserved per line, and an
 * empty mutation returns the input unchanged.
 *
 * §8 semantics implemented here:
 * - labels are flat, unordered;
 * - case-preserving storage, case-insensitive matching — the first-seen
 *   spelling in the file is never altered by a duplicate add;
 * - [addLabel] dedupes case-insensitively, [removeLabel] removes ALL case
 *   variants, [renameLabel] propagates the NEW name's casing everywhere.
 *
 * Conservative ruling: a document without a well-formed frontmatter block
 * (none at all, or a malformed one) is returned UNTOUCHED by every mutator.
 * Minting a fresh block for metadata the note never had would be doing MORE
 * to the file, not less — live notes always carry an `id:` block anyway, so
 * this path only guards foreign/degraded files.
 *
 * Removing the last label leaves an explicit empty `labels: []` line rather
 * than deleting the key: the owned-keys contract expresses label edits
 * exclusively through the [MutableList] surface, and an empty flow sequence
 * is a faithful rendering of "no labels" for every YAML reader.
 */
object LabelOps {

    /** Longest accepted label, measured after trimming. */
    const val MAX_LENGTH: Int = 64

    /**
     * Trimmed label, or [IllegalArgumentException] in plain words when blank,
 * longer than [MAX_LENGTH], or carrying a line break (which cannot fit a
     * single-line frontmatter entry).
     */
    fun normalize(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("label can't be empty")
        }
        if (trimmed.contains('\n') || trimmed.contains('\r')) {
            throw IllegalArgumentException("label can't contain line breaks")
        }
        if (trimmed.length > MAX_LENGTH) {
            throw IllegalArgumentException("label is too long — $MAX_LENGTH characters max")
        }
        return trimmed
    }

    /** Case-insensitive membership (§8: case-preserving store, CI match). */
    fun hasLabel(labels: List<String>, name: String): Boolean =
        labels.any { it.equals(name, ignoreCase = true) }

    /**
     * Appends [label] unless any case variant of it is already present (in
     * which case the input is returned byte-for-byte — first-seen casing
     * stays). Unknown keys, delimiters, BOM, body, and line endings survive.
     */
    fun addLabel(wholeFileText: String, label: String): String {
        val name = normalize(label)
        val doc = Frontmatter.parse(wholeFileText)
        if (!doc.hasFrontmatter) return wholeFileText
        if (hasLabel(doc.labels, name)) return wholeFileText
        return doc.rewritten { labels.add(name) }
    }

    /**
     * Removes EVERY case variant of [label]. A file not carrying the label is
     * returned byte-for-byte unchanged.
     */
    fun removeLabel(wholeFileText: String, label: String): String {
        val name = normalize(label)
        val doc = Frontmatter.parse(wholeFileText)
        if (!doc.hasFrontmatter) return wholeFileText
        if (!hasLabel(doc.labels, name)) return wholeFileText
        return doc.rewritten {
            val kept = labels.filterNot { it.equals(name, ignoreCase = true) }
            labels.clear()
            labels.addAll(kept)
        }
    }

    /**
     * Renames every case variant of [from] to [to], so the new name's casing
     * propagates across the note. Variants collapse into ONE entry (the first
     * occurrence wins its position; a pre-existing case variant of [to]
     * absorbs the rename instead of duplicating). Files without [from] are
     * returned byte-for-byte unchanged.
     */
    fun renameLabel(wholeFileText: String, from: String, to: String): String {
        val source = normalize(from)
        val target = normalize(to)
        val doc = Frontmatter.parse(wholeFileText)
        if (!doc.hasFrontmatter) return wholeFileText
        if (!hasLabel(doc.labels, source)) return wholeFileText
        return doc.rewritten {
            val renamed = ArrayList<String>(labels.size)
            for (existing in labels) {
                val candidate = if (existing.equals(source, ignoreCase = true)) target else existing
                if (candidate.equals(target, ignoreCase = true)) {
                    if (renamed.none { it.equals(target, ignoreCase = true) }) renamed.add(candidate)
                } else {
                    renamed.add(candidate)
                }
            }
            labels.clear()
            labels.addAll(renamed)
        }
    }
}

/**
 * One deferred label edit recorded by the editor (H1 pipeline): a checkbox
 * toggle or create-field add folds into pending state and is consumed by the
 * single debounced save, never written on its own coroutine.
 */
data class LabelIntent(val add: Boolean, val name: String)

/**
 * PURE: replays [intents] in recording order over [text] through
 * [LabelOps.addLabel]/[LabelOps.removeLabel]. Both operations are idempotent,
 * so replaying a failed save's intents over the same base converges to the
 * same bytes. Unit-tested in EditorLabelFoldTest.
 */
fun foldLabelIntents(text: String, intents: List<LabelIntent>): String =
    intents.fold(text) { acc, intent ->
        if (intent.add) LabelOps.addLabel(acc, intent.name) else LabelOps.removeLabel(acc, intent.name)
    }
