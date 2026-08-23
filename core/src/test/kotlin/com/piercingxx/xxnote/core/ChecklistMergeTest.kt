package com.piercingxx.xxnote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §7.1 item-wise checklist merge, proven on the JVM (todo rule #8, design
 * §16). Deterministic: fixed fixtures, seeded nothing, no wall clock.
 */
class ChecklistMergeTest {

    // ------------------------------------------------------------------
    // THE gate: a checkbox can never conflict (D18, todo.md gate for WS2)
    // ------------------------------------------------------------------

    @Test
    fun checkbox_never_conflicts_across_all_state_combinations() {
        fun body(checked: Boolean) = "- [${if (checked) "x" else " "}] tea\n"

        for (baseChecked in listOf(false, true)) {
            for (localChecked in listOf(false, true)) {
                for (remoteChecked in listOf(false, true)) {
                    val result = ChecklistMerge.merge(
                        body(baseChecked),
                        body(localChecked),
                        body(remoteChecked),
                    )
                    // Whichever side moved away from base wins; if both moved,
                    // they necessarily moved the same way. Never a Fork.
                    val expected = if (localChecked == baseChecked) remoteChecked else localChecked
                    val merged = assertIs<ChecklistMergeResult.Merged>(result)
                    assertEquals(
                        body(expected),
                        merged.body,
                        "(base=$baseChecked, local=$localChecked, remote=$remoteChecked)",
                    )
                }
            }
        }

        // The 8-combination checkbox law, applied PER POSITION over a body of
        // TWO items with IDENTICAL text (H1): exact-text groups pair k-th
        // occurrences positionally, so a tick on either copy can never be
        // contested by its twin — every combination merges with both
        // positions resolved independently and in base order. Never a Fork.
        fun twoItemBody(first: Boolean, second: Boolean) =
            "- [${if (first) "x" else " "}] dup\n- [${if (second) "x" else " "}] dup\n"

        for (b1 in listOf(false, true)) {
            for (b2 in listOf(false, true)) {
                for (l1 in listOf(false, true)) {
                    for (l2 in listOf(false, true)) {
                        for (r1 in listOf(false, true)) {
                            for (r2 in listOf(false, true)) {
                                val result = ChecklistMerge.merge(
                                    twoItemBody(b1, b2),
                                    twoItemBody(l1, l2),
                                    twoItemBody(r1, r2),
                                )
                                val expected1 = if (l1 == b1) r1 else l1
                                val expected2 = if (l2 == b2) r2 else l2
                                val merged = assertIs<ChecklistMergeResult.Merged>(result)
                                assertEquals(
                                    twoItemBody(expected1, expected2),
                                    merged.body,
                                    "(base=($b1,$b2), local=($l1,$l2), remote=($r1,$r2))",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Duplicate item texts: positional pairing inside exact-text groups
    // (H1/D18 — a checkbox can never conflict, even on identical items)
    // ------------------------------------------------------------------

    @Test
    fun duplicate_texts_concurrent_ticks_merge() {
        // "milk" twice in base; local ticks the first copy, remote the second.
        // Under whole-pool fuzzy matching every candidate scored 1.0 sharing
        // endpoints and the note forked; positional pairing merges instead.
        val base = "- [ ] milk\n- [ ] milk\n- [ ] eggs\n"
        val local = "- [x] milk\n- [ ] milk\n- [ ] eggs\n"
        val remote = "- [ ] milk\n- [x] milk\n- [ ] eggs\n"

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("- [x] milk\n- [x] milk\n- [ ] eggs\n", merged.body)
    }

    @Test
    fun duplicate_add_on_one_side_merges() {
        // A second "milk" added (and ticked) locally while remote is untouched:
        // the copy pairs positionally with base's milk, the extra lands as an
        // add with its tick intact.
        val base = "- [ ] milk\n"
        val local = "- [ ] milk\n- [x] milk\n"
        val remote = base

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("- [ ] milk\n- [x] milk\n", merged.body)
    }

    // ------------------------------------------------------------------
    // Set merge: adds and removes
    // ------------------------------------------------------------------

    @Test
    fun add_and_remove_on_opposite_sides_both_apply() {
        val base = "- [ ] alpha\n- [ ] bravo\n"
        val local = "- [ ] alpha\n- [ ] bravo\n- [ ] delta\n"
        val remote = "- [ ] alpha\n"

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("- [ ] alpha\n- [ ] delta\n", merged.body)
    }

    @Test
    fun removed_item_dropped_when_other_side_untouched() {
        val base = "- [ ] alpha\n- [ ] bravo\n- [ ] charlie\n"
        val local = base
        val remote = "- [ ] alpha\n- [ ] charlie\n"

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("- [ ] alpha\n- [ ] charlie\n", merged.body)
    }

    @Test
    fun block_added_on_one_side_merges_against_empty() {
        val base = "shopping:\n"
        val local = "shopping:\n\n- [ ] new stuff\n"
        val remote = "shopping:\n"

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("shopping:\n\n- [ ] new stuff\n", merged.body)
    }

    // ------------------------------------------------------------------
    // Edit outranks remove (D10 at item level); identity survives editing
    // ------------------------------------------------------------------

    @Test
    fun edit_outranks_remove_at_item_level() {
        val base = "- [ ] coffee\n"
        val local = "- [ ] coffee, the dark one\n"
        val remote = ""

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("- [ ] coffee, the dark one\n", merged.body)
    }

    @Test
    fun identity_survives_text_edit_and_keeps_its_tick() {
        val base = "- [ ] coffee\n"
        val local = "- [x] coffee, the dark one\n"
        val remote = base

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("- [x] coffee, the dark one\n", merged.body)

        val item = TaskList.split(merged.body)
            .filterIsInstance<Segment.Block>()
            .single()
            .block.items
            .single()
        assertTrue(item.checked, "the tick must survive the rename (R5)")
    }

    @Test
    fun concurrent_tick_and_text_edit_on_different_items_both_survive() {
        val base = "- [ ] eggs\n- [ ] toast\n"
        val local = "- [x] eggs, a dozen\n- [ ] toast\n"
        val remote = "- [ ] eggs\n- [x] toast\n"

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("- [x] eggs, a dozen\n- [x] toast\n", merged.body)
    }

    // ------------------------------------------------------------------
    // The only forking cases
    // ------------------------------------------------------------------

    @Test
    fun same_item_text_edited_differently_forks() {
        val base = "- [ ] buy oat milk today\n"
        val local = "- [ ] buy oat milk tomorrow\n"
        val remote = "- [ ] buy oat milk tonight\n"

        assertEquals(ChecklistMergeResult.Fork, ChecklistMerge.merge(base, local, remote))
    }

    @Test
    fun ambiguous_similarity_pairing_forks() {
        // "az" ties against both leftovers "ax" and "ay" (similarity 0.6
        // each, well inside AMBIGUITY_EPSILON): pairing would be a guess,
        // and a wrong guess costs a tick silently (§15).
        val base = "- [ ] ax\n- [ ] ay\n"
        val local = "- [ ] az\n"
        val remote = ""

        assertEquals(ChecklistMergeResult.Fork, ChecklistMerge.merge(base, local, remote))
    }

    @Test
    fun prose_edited_differently_on_both_sides_forks() {
        val base = "hello world\n"
        val local = "hello brave world\n"
        val remote = "hello cruel world\n"

        assertEquals(ChecklistMergeResult.Fork, ChecklistMerge.merge(base, local, remote))
    }

    // ------------------------------------------------------------------
    // Below threshold: genuinely different items
    // ------------------------------------------------------------------

    @Test
    fun below_threshold_swap_is_add_plus_remove_without_phantom_tick() {
        // Base item was CHECKED; the replacement must arrive unchecked —
        // similarity below the threshold transfers no state.
        val base = "- [x] buy milk\n"
        val local = "- [ ] call dentist\n"
        val remote = ""

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        assertEquals("- [ ] call dentist\n", merged.body)
    }

    // ------------------------------------------------------------------
    // Structure: independent blocks, prose passthrough, ordering law
    // ------------------------------------------------------------------

    @Test
    fun two_blocks_merge_independently_and_prose_is_byte_exact() {
        val base = "Before prose line one.\n\n- [ ] alpha\n- [ ] bravo\n\nMiddle prose.\n\n- [x] gamma\n\nAfter prose.\n"
        val local = "Before prose line one.\n\n- [x] alpha\n- [ ] bravo\n\nMiddle prose.\n\n- [x] gamma\n\nAfter prose.\n"
        val remote = "Before prose line one.\n\n- [ ] alpha\n\nMiddle prose.\n\n- [x] gamma\n\nAfter prose.\n"

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))
        // Byte-for-byte: prose lines and separators survive exactly once, in place.
        assertEquals(
            "Before prose line one.\n\n- [x] alpha\n\nMiddle prose.\n\n- [x] gamma\n\nAfter prose.\n",
            merged.body,
        )
    }

    @Test
    fun merged_order_preserves_base_sequence_and_appends_in_side_order() {
        val base = "- [ ] alpha\n- [ ] bravo\n- [x] charlie\n"
        val local = "- [ ] alpha\n- [ ] delta\n- [ ] bravo\n- [x] charlie\n"
        val remote = "- [ ] alpha\n- [ ] bravo\n- [x] charlie\n- [ ] echo\n"

        val merged = assertIs<ChecklistMergeResult.Merged>(ChecklistMerge.merge(base, local, remote))

        val items = TaskList.split(merged.body)
            .filterIsInstance<Segment.Block>()
            .single()
            .block.items
        assertEquals(
            listOf("alpha", "bravo", "charlie", "delta", "echo"),
            items.map { it.text.trim() },
        )
        // No sort-to-bottom on sync (D19): charlie is checked and still sits
        // third, ahead of the additions.
        assertTrue(items[2].checked)
        assertTrue(items.drop(3).none { it.checked })
    }

    @Test
    fun merged_output_reparses_stably() {
        val cases = listOf(
            Triple("- [ ] coffee\n", "- [x] coffee, the dark one\n", "- [ ] coffee\n"),
            Triple("- [x] buy milk\n", "- [ ] call dentist\n", ""),
            Triple(
                "intro\n\n- [ ] alpha\n- [ ] bravo\n\noutro\n",
                "intro\n\n- [x] alpha\n- [ ] bravo\n- [ ] delta\n\noutro\n",
                "intro\n\n- [ ] alpha\n\noutro\n",
            ),
        )
        for ((base, local, remote) in cases) {
            val result = ChecklistMerge.merge(base, local, remote)
            val merged = assertIs<ChecklistMergeResult.Merged>(result)
            assertEquals(merged.body, TaskList.render(TaskList.split(merged.body)))
        }
    }

    // ------------------------------------------------------------------
    // The pinned similarity metric
    // ------------------------------------------------------------------

    @Test
    fun similarity_metric_bounds_are_pinned() {
        // Append-style growth must pair — this is R5's guard for the tick.
        assertTrue(
            ChecklistMerge.similarity("coffee", "coffee, the dark one") >=
                ChecklistMerge.SIMILARITY_THRESHOLD,
        )
        // Wholesale replacement must not.
        assertTrue(
            ChecklistMerge.similarity("buy milk", "call dentist") <
                ChecklistMerge.SIMILARITY_THRESHOLD,
        )
        assertEquals(1.0, ChecklistMerge.similarity("same", "same"))
        assertEquals(1.0, ChecklistMerge.similarity("", ""))
        assertEquals(0.0, ChecklistMerge.similarity("a", ""))
        val a = "oat milk and bin bags"
        val b = "bin bags and oat milk"
        assertEquals(
            ChecklistMerge.similarity(a, b),
            ChecklistMerge.similarity(b, a),
            "similarity must be symmetric",
        )
    }
}

// ----------------------------------------------------------------------
// TaskList parsing and the round-trip law
// ----------------------------------------------------------------------

class TaskListTest {

    @Test
    fun roundtrip_is_lossless_over_fixtures() {
        val fixtures = listOf(
            "- [ ] oat milk\n- [x] bin bags\n",
            "- [ ] no trailing newline",
            "",
            "\n",
            "just prose, no lists\n",
            "- [ ] crlf line\r\n- [x] second\r\n",
            "- [ ] alpha\n  - [x] nested\n* [X] star\n+ [ ] plus\n",
            "- [ ] checkbox only line\n- [ ]\n",
            "- [ ] trailing spaces   \n",
            "para one\n\n- [ ] first block\n\nmiddle prose\n\n* [x] second block\n\npara two\n",
            "    - [ ] four spaces is code, not a list\n",
        )
        for (fixture in fixtures) {
            assertEquals(fixture, TaskList.render(TaskList.split(fixture)), "round-trip broke")
        }
    }

    @Test
    fun block_membership_follows_gfm_shapes() {
        val body = "- [ ] top\n  - [x] nested\n\t- [ ] tabbed\n\nplain para\n- plain bullet\n* [X] star\n    - [ ] deep start\n+ [ ] plus\n"
        val segments = TaskList.split(body)

        val block0 = assertIs<Segment.Block>(segments[0]).block
        assertEquals(listOf("top", "nested", "tabbed"), block0.items.map { it.text })
        assertEquals(listOf(false, true, false), block0.items.map { it.checked })

        // One Text segment per non-item line; consecutive runs stay adjacent.
        assertEquals("", assertIs<Segment.Text>(segments[1]).lines.single())
        assertEquals("plain para", assertIs<Segment.Text>(segments[2]).lines.single())
        assertEquals("- plain bullet", assertIs<Segment.Text>(segments[3]).lines.single())

        // An open block continues through any indentation (nested items);
        // markers may change line to line.
        val block1 = assertIs<Segment.Block>(segments[4]).block
        assertEquals(listOf("star", "deep start", "plus"), block1.items.map { it.text })
        assertEquals(listOf("*", "-", "+"), block1.items.map { it.marker })
        assertTrue(block1.items[0].checked, "[X] is checked")
        assertTrue(block1.items.drop(1).none { it.checked })

        assertEquals("", assertIs<Segment.Text>(segments[5]).lines.single())
    }
}
