package com.piercingxx.xxnote.core

/**
 * One GFM task-list item, parsed from a single physical line: a bullet
 * marker (`-`, `*`, or `+`), a `[ ]` / `[x]` / `[X]` checkbox, and the text
 * after it.
 *
 * [rawLine] keeps the line byte-for-byte so untouched blocks re-render
 * identically — the round-trip law holds by construction:
 * `TaskList.render(TaskList.split(body)) == body` for every body.
 *
 * [indent] counts leading whitespace characters (a tab counts as one).
 * [text] is everything after the checkbox and its delimiting whitespace,
 * verbatim; it is never trimmed here — identity normalization for merging
 * is [ChecklistMerge]'s business, not the parser's.
 */
data class TaskItem(
    val rawLine: String,
    val indent: Int,
    val marker: String,
    val checked: Boolean,
    val text: String,
)

/**
 * A contiguous run of GFM task-list lines (design §7.1). Blank lines,
 * prose, and non-task list items end a block and belong to no [TaskBlock].
 */
data class TaskBlock(
    val items: List<TaskItem>,
)

/**
 * One region of a note body: either a contiguous task-list block or raw
 * lines that are not part of any block (prose, blank separators, non-task
 * lists).
 */
sealed interface Segment {
    /** A contiguous GFM task-list block. */
    data class Block(val block: TaskBlock) : Segment

    /** Body lines outside any task block, kept verbatim. */
    data class Text(val lines: List<String>) : Segment
}

/**
 * GFM task-list block parsing for the §7.1 item-wise checklist merge.
 *
 * Pure syntax, no merge policy: [split] partitions a body into [Segment]s
 * without judging them, and [render] joins the partition back with no loss.
 *
 * Block membership: a line matching the task-item shape (`marker`,
 * whitespace, checkbox, optional text) belongs to the open block at any
 * indentation (nested items continue the list); a block may only START at
 * indent ≤ 3 (GFM's list-indent limit — deeper indentation is a code block,
 * not a list). Any other line — prose, a plain bullet, a blank separator —
 * ends the open block.
 */
object TaskList {

    private val TASK_ITEM =
        Regex("^([ \\t]*)([-*+])[ \\t]+\\[([ xX])\\](?:[ \\t]+(.*))?$")

    /**
     * Partition [body] into task-list blocks and raw text, preserving every
     * line byte-for-byte. The concatenation of all segments' lines, joined
     * with `\n`, is exactly [body].
     */
    fun split(body: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var current = mutableListOf<TaskItem>()

        fun flush() {
            if (current.isNotEmpty()) {
                segments += Segment.Block(TaskBlock(current.toList()))
                current = mutableListOf()
            }
        }

        for (line in body.split("\n")) {
            val item = parseLine(line, blockOpen = current.isNotEmpty())
            if (item != null) {
                current += item
            } else {
                flush()
                segments += Segment.Text(listOf(line))
            }
        }
        flush()
        return segments
    }

    /**
     * Join [segments] back into a body. Lossless inverse of [split]:
     * `render(split(body)) == body` for every body.
     */
    fun render(segments: List<Segment>): String =
        segments
            .flatMap { segment ->
                when (segment) {
                    is Segment.Block -> segment.block.items.map { it.rawLine }
                    is Segment.Text -> segment.lines
                }
            }
            .joinToString("\n")

    /**
     * Parse one line as a task item, or null if it is not one. Accepts any
     * indentation (an open block's nested items continue the list); use the
     * indent ≤ 3 start rule at the call site when opening a fresh block.
     */
    internal fun parseItem(line: String): TaskItem? = parseLine(line, blockOpen = true)

    private fun parseLine(line: String, blockOpen: Boolean): TaskItem? {
        val match = TASK_ITEM.matchEntire(line) ?: return null
        val indent = match.groupValues[1].length
        if (!blockOpen && indent > 3) return null
        return TaskItem(
            rawLine = line,
            indent = indent,
            marker = match.groupValues[2],
            checked = match.groupValues[3] != " ",
            text = match.groupValues[4],
        )
    }
}
