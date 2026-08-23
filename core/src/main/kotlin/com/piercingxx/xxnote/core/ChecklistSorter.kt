package com.piercingxx.xxnote.core

/**
 * D18's save-time rewrite: within EACH contiguous GFM task-list block,
 * checked items sink to the bottom of their own list (design D18, §7.1, §8).
 *
 * The transformation is a **stable partition**, never a general sort: every
 * item keeps its authored relative order inside its partition — unchecked
 * items first, then checked ones. Each block is partitioned independently;
 * items sink to the bottom of *their own list block*, never to the bottom
 * of the file, and prose, blank separators, and non-task lines pass through
 * byte-for-byte (§7.1). The rewrite is lossless by construction: it rides
 * [TaskList.split]/[TaskList.render], whose round-trip law holds for every
 * body, and only ever permutes whole [TaskItem.rawLine]s inside a block.
 *
 * Deterministic: a pure function of its input — no clock, no randomness.
 *
 * Caller discipline: editor save path ONLY — never on pull, never during
 * sync (D19). Obsidian and the desktop do not know this sort rule, so a
 * pulled file renders exactly as it arrived until the user edits it here.
 */
object ChecklistSorter {

    /**
     * Whole-file convenience: sorts the body ONLY when the file's frontmatter
     * says `type: checklist` (D18 is behavioral, §8). A degraded or unknown
     * `type` value does LESS, not more — the text is returned untouched.
     * Malformed or absent frontmatter returns the text untouched, too.
     *
     * The frontmatter region itself (including any BOM) is never rewritten:
     * the result is the original head bytes followed by [sortBody] of the body.
     */
    fun sortCheckedToBottom(wholeFileText: String): String {
        val doc = Frontmatter.parse(wholeFileText)
        if (!doc.hasFrontmatter || doc.isMalformed || doc.type != NoteType.CHECKLIST) {
            return wholeFileText
        }
        val body = doc.bodyText
        val head = wholeFileText.substring(0, wholeFileText.length - body.length)
        return head + sortBody(body)
    }

    /**
     * Body-level form for callers already holding type truth (the editor save
     * path parses `type` itself and hands over only the body).
     *
     * Caller discipline: editor save path ONLY — never on pull, never during
     * sync (D19).
     */
    fun sortBody(body: String): String =
        TaskList.render(
            TaskList.split(body).map { segment ->
                when (segment) {
                    is Segment.Block -> Segment.Block(segment.block.checkedSunk())
                    is Segment.Text -> segment
                }
            },
        )

    /** Stable partition of one block: unchecked keep their order up front, checked sink in theirs. */
    private fun TaskBlock.checkedSunk(): TaskBlock {
        val kept = ArrayList<TaskItem>(items.size)
        val sunk = ArrayList<TaskItem>()
        for (item in items) (if (item.checked) sunk else kept).add(item)
        return TaskBlock(kept + sunk)
    }
}
