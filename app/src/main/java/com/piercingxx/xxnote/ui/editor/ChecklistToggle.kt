package com.piercingxx.xxnote.ui.editor

/**
 * PURE checkbox tap behavior (design §12 item 2): given the body text and the
 * text offset the user tapped, toggle the `- [ ]` / `- [x]` checkbox of THAT
 * line and nothing else.
 *
 * The replacement is always one character for one character (` ` ↔ `x`), so
 * every cursor/selection offset in the surrounding TextFieldValue stays valid
 * unchanged — "repositions nothing else" holds by construction, not by
 * compensation math. `[X]` degrades to `[ ]` on untick; a tick writes the
 * lowercase canonical `[x]`.
 *
 * Returns null when [offset] does not land on (or inside the prefix of) a GFM
 * task-item line — prose, plain bullets, blank lines, frontmatter offsets all
 * decline politely. Pure; unit-tested in EditorSaveTest.
 */
object ChecklistToggle {

    /** The toggled body plus the index of the character that flipped (`[line]`'s box char). */
    data class Result(val text: String, val toggledIndex: Int)

    /** Shared line shape: prefix ending in `[`, the box char, the `]`-rest. */
    internal val TASK_LINE = Regex("^([ \\t]*[-*+][ \\t]+\\[)([ xX])(\\].*)$")

    fun at(text: String, offset: Int): Result? {
        if (offset < 0 || offset > text.length) return null
        val lineStart = text.lastIndexOf('\n', startIndex = (offset - 1).coerceAtLeast(0)) + 1
        val lineEnd = text.indexOf('\n', startIndex = offset).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val match = TASK_LINE.matchEntire(line) ?: return null
        // Taps anywhere from the marker through "] " count as hitting the box;
        // beyond that, only an explicit tap inside the "[c]" glyphs does.
        val bracketIndexInLine = match.groupValues[1].length - 1 // '[' position
        val boxIndexInLine = bracketIndexInLine + 1
        val tappedInLine = offset - lineStart
        val hitsPrefix = tappedInLine <= boxIndexInLine + 2 // up to and including ']'
        val hitsBoxGlyphs = tappedInLine >= bracketIndexInLine && tappedInLine <= boxIndexInLine + 1
        if (!(hitsPrefix || hitsBoxGlyphs)) return null

        val wasChecked = match.groupValues[2] != " "
        val replacement = if (wasChecked) " " else "x"
        val absoluteBoxIndex = lineStart + boxIndexInLine
        val newText = text.substring(0, absoluteBoxIndex) + replacement +
            text.substring(absoluteBoxIndex + 1)
        return Result(newText, absoluteBoxIndex)
    }
}
