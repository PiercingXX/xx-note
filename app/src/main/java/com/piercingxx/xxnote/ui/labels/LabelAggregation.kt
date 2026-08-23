package com.piercingxx.xxnote.ui.labels

import com.piercingxx.xxnote.core.Frontmatter

/** One label in use: its first-seen spelling and how many live notes carry it. */
data class LabelUsage(
    val name: String,
    val count: Int,
)

/**
 * PURE scan-and-count for the labels surface (§12 item 3): a list of
 * `(id, wholeFileText)` pairs → aggregated usages.
 *
 * Rules (all §8): matching is case-insensitive, so `[home, HOME]` on one note
 * counts that NOTE once; the displayed name is the first spelling seen while
 * scanning (notes in order, labels within a note in order); notes without a
 * well-formed frontmatter block contribute nothing (their labels are unread-
 * able body text, degrading does less); output is sorted case-insensitively
 * by name for a stable list. Unit-tested in LabelsAggregationTest.
 */
fun aggregateLabelUsage(notes: List<Pair<String, String>>): List<LabelUsage> {
    val counts = LinkedHashMap<String, Int>()
    val display = HashMap<String, String>()
    for ((_, wholeFileText) in notes) {
        val doc = Frontmatter.parse(wholeFileText)
        if (!doc.hasFrontmatter) continue
        val seenInNote = HashSet<String>()
        for (label in doc.labels) {
            if (label.isEmpty()) continue
            val key = label.lowercase()
            if (!seenInNote.add(key)) continue
            counts.merge(key, 1, Int::plus)
            display.putIfAbsent(key, label)
        }
    }
    return counts.map { (key, count) -> LabelUsage(display.getValue(key), count) }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.name }))
}
