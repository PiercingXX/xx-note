package com.piercingxx.xxnote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D18 sort-to-bottom rewrite, proven on the JVM (design D18, D19, §7.1, §8;
 * todo rule #8). Deterministic: fixed fixtures, no wall clock, no randomness.
 */
class ChecklistSorterTest {

    private fun bodyOf(vararg lines: String): String = lines.joinToString("\n", postfix = "\n")

    // ------------------------------------------------------------------
    // THE gate: a stable partition, never a comparator sort (D18)
    // ------------------------------------------------------------------

    @Test
    fun stable_partition_preserves_relative_order() {
        val input = bodyOf(
            "- [ ] alpha",
            "- [x] done-1",
            "- [ ] beta",
            "- [x] done-2",
        )
        val expected = bodyOf(
            "- [ ] alpha",
            "- [ ] beta",
            "- [x] done-1",
            "- [x] done-2",
        )
        assertEquals(expected, ChecklistSorter.sortBody(input))
    }

    // ------------------------------------------------------------------
    // §7.1: per own list block, never bottom-of-file
    // ------------------------------------------------------------------

    @Test
    fun per_block_independence() {
        val input = bodyOf(
            "- [x] b-done",
            "- [ ] a-open",
            "",
            "prose between the lists",
            "",
            "- [x] y-done",
            "- [ ] z-open",
            "- [x] w-done",
            "",
            "tail prose",
        )
        val expected = bodyOf(
            "- [ ] a-open",
            "- [x] b-done",
            "",
            "prose between the lists",
            "",
            "- [ ] z-open",
            "- [x] y-done",
            "- [x] w-done",
            "",
            "tail prose",
        )
        assertEquals(expected, ChecklistSorter.sortBody(input))
    }

    @Test
    fun non_task_lists_untouched() {
        val input = bodyOf(
            "- plain bullet",
            "* star bullet",
            "+ plus bullet, not a checkbox",
            "1. numbered one",
            "2. numbered two",
            "> quoted [x] still prose",
            "",
            "- [x] done",
            "- [ ] open",
        )
        val expected = bodyOf(
            "- plain bullet",
            "* star bullet",
            "+ plus bullet, not a checkbox",
            "1. numbered one",
            "2. numbered two",
            "> quoted [x] still prose",
            "",
            "- [ ] open",
            "- [x] done",
        )
        assertEquals(expected, ChecklistSorter.sortBody(input))
    }

    @Test
    fun prose_untouched_byte_for_byte() {
        val input = bodyOf(
            "intro line with : colon",
            "",
            "- [x] done",
            "- [ ]   spaced-open  ",
            "",
            "outro line, trailing",
        )
        val output = ChecklistSorter.sortBody(input)

        val proseIn = TaskList.split(input).filterIsInstance<Segment.Text>().flatMap { it.lines }
        val proseOut = TaskList.split(output).filterIsInstance<Segment.Text>().flatMap { it.lines }
        assertEquals(proseIn, proseOut)

        val expected = bodyOf(
            "intro line with : colon",
            "",
            "- [ ]   spaced-open  ",
            "- [x] done",
            "",
            "outro line, trailing",
        )
        assertEquals(expected, output)
    }

    // ------------------------------------------------------------------
    // §8: type is behavioral — degraded/unknown does LESS, never reorders
    // ------------------------------------------------------------------

    @Test
    fun type_note_whole_file_never_reorders() {
        val note = "---\ntype: note\n---\n\n# Shopping\n\n- [x] done\n- [ ] open\n"
        assertEquals(note, ChecklistSorter.sortCheckedToBottom(note))

        // Corrupt values degrade to note (§8) — same does-less direction.
        val corrupt = "---\ntype: bananatype\n---\n\n- [x] done\n- [ ] open\n"
        assertEquals(corrupt, ChecklistSorter.sortCheckedToBottom(corrupt))
    }

    @Test
    fun malformed_frontmatter_untouched() {
        val unclosed = "---\ntype: checklist\n\n- [x] done\n- [ ] open\n"
        assertEquals(unclosed, ChecklistSorter.sortCheckedToBottom(unclosed))

        val interiorJunk = "---\ntype: checklist\nthis line has no key shape\n---\n- [x] d\n- [ ] o\n"
        assertEquals(interiorJunk, ChecklistSorter.sortCheckedToBottom(interiorJunk))

        val noFrontmatter = "- [x] done\n- [ ] open\n"
        assertEquals(noFrontmatter, ChecklistSorter.sortCheckedToBottom(noFrontmatter))
    }

    @Test
    fun checklist_type_whole_file_sorts_body_only() {
        val input = "---\nid: 01ARZ3NDEKTSV4RRFFQ69G5FAV\ntype: checklist\n---\n\n- [x] done\n- [ ] open\n"
        val expected = "---\nid: 01ARZ3NDEKTSV4RRFFQ69G5FAV\ntype: checklist\n---\n\n- [ ] open\n- [x] done\n"
        assertEquals(expected, ChecklistSorter.sortCheckedToBottom(input))
    }

    // ------------------------------------------------------------------
    // Invariants: fixpoint, round-trip, CRLF bytes
    // ------------------------------------------------------------------

    @Test
    fun already_sorted_is_fixpoint() {
        val input = bodyOf(
            "# errands",
            "",
            "- [X] x-done",
            "    - [ ] nested-open",
            "* [ ] star-open",
            "+ [x] plus-done",
            "",
            "mid prose",
            "",
            "- [ ] last-open",
            "- [x] last-done",
            "- [ ] last-open-2",
        )

        val once = ChecklistSorter.sortBody(input)
        assertEquals(once, ChecklistSorter.sortBody(once))

        val whole = "---\ntype: checklist\n---\n" + input
        val wholeOnce = ChecklistSorter.sortCheckedToBottom(whole)
        assertEquals(wholeOnce, ChecklistSorter.sortCheckedToBottom(wholeOnce))
    }

    @Test
    fun round_trip_parseable_after_sort() {
        val input = bodyOf(
            "- [ ] a-open",
            "- [x] b-done",
            "",
            "prose",
            "",
            "- [x] y-done",
            "- [ ] z-open",
        )
        val sorted = ChecklistSorter.sortBody(input)

        // The sorted text is itself a parseable body and round-trips losslessly.
        assertEquals(sorted, TaskList.render(TaskList.split(sorted)))

        // Same block structure; each block holds the same items as before,
        // only partitioned (unchecked sequence then checked sequence).
        val blocksIn = TaskList.split(input).filterIsInstance<Segment.Block>().map { it.block }
        val blocksOut = TaskList.split(sorted).filterIsInstance<Segment.Block>().map { it.block }
        assertEquals(blocksIn.size, blocksOut.size)
        for ((before, after) in blocksIn.zip(blocksOut)) {
            assertEquals(before.items.filter { !it.checked }, after.items.filter { !it.checked })
            assertEquals(before.items.filter { it.checked }, after.items.filter { it.checked })
            assertEquals(before.items.size, after.items.size)
        }
        assertTrue(blocksOut.all { block ->
            block.items.dropWhile { !it.checked }.all { it.checked }
        })
    }

    @Test
    fun crlf_bodies_preserved() {
        // A CR-bearing line cannot match the GFM task-item grammar (the regex's
        // `.` stops at \r), so every line here is prose under TaskList — the
        // §8 does-less direction. Bytes must survive exactly.
        val crlf = "title\r\n\r\n- [x] done first\r\n- [ ] still open\r\n"
        assertEquals(crlf, ChecklistSorter.sortBody(crlf))
        assertEquals(crlf, TaskList.render(TaskList.split(crlf)))

        val crlfWhole = "---\r\ntype: checklist\r\n---\r\n\r\n- [x] done\r\n- [ ] open\r\n"
        assertEquals(crlfWhole, ChecklistSorter.sortCheckedToBottom(crlfWhole))
    }
}
