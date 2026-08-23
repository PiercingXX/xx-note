package com.piercingxx.xxnote.ui.sync

import com.piercingxx.xxnote.core.Frontmatter

/**
 * Marker arithmetic behind the Resolve sheet (design §7): the fork body
 * carries `Diff3.mergeWithMarkers` output — `<<<<<<< oursLabel` / `=======`
 * / `>>>>>>> theirsLabel` — and a resolution reduces it to the surviving
 * bytes. Pure; proven by exact-byte tests.
 *
 * Semantics of [resolve]:
 * - Only an `<<<<<<<` line OPENS a conflict block (git-compatible: the
 *   marker may carry a label suffix).
 * - Inside a block, `=======` switches to the theirs section, a further
 *   `<<<<<<<` re-opens ours (degenerate hand-edited input), and `>>>>>>>`
 *   closes.
 * - Outside any block every line is content — including bare `=====` runs,
 *   which are legitimate setext underlines in prose. Stripping never eats
 *   text it did not have to.
 * - A block left unclosed at end-of-input still strips per its last open
 *   section; nothing is invented and nothing is dropped beyond markers.
 * - Line endings survive untouched: `\r` stays on kept lines, and the
 *   presence or absence of a final newline is preserved exactly.
 *
 * The edit-merged path is a deliberate passthrough ([resolve] with a null
 * side): whatever the operator typed becomes the note's new body verbatim —
 * including leaving markers in if they choose to — because editing IS the
 * resolution.
 */
object ResolveMath {

    /** Which side of each conflict block survives the strip. */
    enum class Side { MINE, THEIRS }

    const val OURS_PREFIX = "<<<<<<<"
    const val DIVIDER_PREFIX = "======="
    const val THEIRS_PREFIX = ">>>>>>>"

    fun isOursMarker(line: String): Boolean = line.startsWith(OURS_PREFIX)

    /** `=======`, never a `>>>>>>>` line misread through prefix sharing. */
    fun isDivider(line: String): Boolean =
        line.startsWith(DIVIDER_PREFIX) && !line.startsWith(THEIRS_PREFIX)

    fun isTheirsMarker(line: String): Boolean = line.startsWith(THEIRS_PREFIX)

    /** True when [line] is structural to a diff3 block — used for UI highlight. */
    fun isMarkerLine(line: String): Boolean =
        isOursMarker(line) || isDivider(line) || isTheirsMarker(line)

    /**
     * Reduces [text] to one side's bytes. `side == null` is the passthrough:
     * [text] returned untouched, byte-for-byte.
     */
    fun resolve(text: String, side: Side?): String {
        if (side == null) return text
        val keepOurs = side == Side.MINE
        // 0 = outside any block, 1 = inside ours section, 2 = inside theirs.
        var section = 0
        val out = ArrayList<String>()
        for (raw in text.split('\n')) {
            // A trailing \r belongs to a CRLF ending: structural probes see
            // the bare line, emission keeps the original bytes.
            val bare = if (raw.endsWith("\r")) raw.dropLast(1) else raw
            when {
                section == 0 && isOursMarker(bare) -> section = 1
                section == 1 && isTheirsMarker(bare) -> section = 0
                section == 1 && isDivider(bare) -> section = 2
                section == 2 && isTheirsMarker(bare) -> section = 0
                section == 2 && isOursMarker(bare) -> section = 1
                section == 0 || (section == 1 && keepOurs) || (section == 2 && !keepOurs) ->
                    out.add(raw)
            }
        }
        return out.joinToString("\n")
    }

    /** Keep this device's lines from a marked body. */
    fun keepMine(markedBody: String): String = resolve(markedBody, Side.MINE)

    /** Keep the far side's lines from a marked body. */
    fun keepTheirs(markedBody: String): String = resolve(markedBody, Side.THEIRS)

    /**
     * SaveText-style whole-file assembly for a resolution (§7): the surviving
     * [resolvedBody] goes back under the ORIGINAL identity's frontmatter —
     * the block's region is kept byte-for-byte except `updated:`, which is
     * stamped to [updatedIso] (a resolution is a content modification and
     * must bump the mtime the engine merges on). A note without a usable
     * frontmatter block gets a fresh one carrying the stamp; whatever was
     * body stays body. Pure; proven by exact-byte tests alongside [resolve].
     */
    fun resolvedWholeFile(originalWholeFileText: String, resolvedBody: String, updatedIso: String): String {
        val stamped = Frontmatter.parse(originalWholeFileText)
            .rewritten { updated = updatedIso }
        // Re-parse: rewritten() may have moved the head/frontmatter boundary.
        val restamped = Frontmatter.parse(stamped)
        val headLength = when {
            restamped.hasFrontmatter && !restamped.isMalformed ->
                stamped.length - restamped.bodyText.length
            else -> 0
        }
        return stamped.substring(0, headLength) + resolvedBody
    }
}
