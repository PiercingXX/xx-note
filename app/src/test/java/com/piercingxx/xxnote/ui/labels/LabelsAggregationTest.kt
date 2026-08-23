package com.piercingxx.xxnote.ui.labels

import com.piercingxx.xxnote.core.Frontmatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS8 gate: the PURE scan-and-count behind the labels surface — case-
 * insensitive merge with first-seen casing, one note counted once regardless
 * of duplicate variants, unreadable frontmatter skipped, stable ordering.
 */
class LabelsAggregationTest {

    private fun note(labels: String): String =
        "---\nid: x\nlabels: [$labels]\n---\nbody\n"

    @Test
    fun emptyInputYieldsNoUsages() {
        assertTrue(aggregateLabelUsage(emptyList()).isEmpty())
    }

    @Test
    fun countsMergedCaseInsensitivelyFirstSeenCasingWins() {
        val usages = aggregateLabelUsage(
            listOf(
                "n1" to note("home"),
                "n2" to note("HOME, errands"),
            ),
        )
        // Sorted case-insensitively by name (errands < home).
        assertEquals(listOf(LabelUsage("errands", 1), LabelUsage("home", 2)), usages)
    }

    @Test
    fun oneNoteCountsOnceDespiteDuplicateVariants() {
        val usages = aggregateLabelUsage(listOf("n1" to note("home, HOME, Home")))
        assertEquals(1, usages.size)
        assertEquals("home", usages[0].name) // first-seen spelling within the note
        assertEquals(1, usages[0].count)
    }

    @Test
    fun notesWithoutReadableFrontmatterContributeNothing() {
        val usages = aggregateLabelUsage(
            listOf(
                "n1" to "plain prose, no frontmatter\n",
                "n2" to "---\ngarbage line\n---\nbody\n",
                "n3" to Frontmatter.parse("---\nid: x\nlabels: [solo]\n---\nb").raw(),
            ),
        )
        assertEquals(listOf(LabelUsage("solo", 1)), usages)
    }

    @Test
    fun bareScalarLabelsCountAsOneItem() {
        val text = "---\nid: x\nlabels: solo\n---\nbody\n"
        assertEquals(listOf(LabelUsage("solo", 1)), aggregateLabelUsage(listOf("n1" to text)))
    }

    @Test
    fun outputSortedCaseInsensitivelyByDisplayName() {
        val usages = aggregateLabelUsage(
            listOf(
                "n1" to note("zeta, Alpha"),
                "n2" to note("beta"),
            ),
        )
        assertEquals(listOf("Alpha", "beta", "zeta"), usages.map { it.name })
    }

    @Test
    fun idsAreCarriedButDoNotAffectTheAggregate() {
        val sameText = note("home")
        val usages = aggregateLabelUsage(listOf("a" to sameText, "b" to sameText))
        assertEquals(listOf(LabelUsage("home", 2)), usages)
    }
}
