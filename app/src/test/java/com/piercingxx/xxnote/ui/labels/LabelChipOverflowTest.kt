package com.piercingxx.xxnote.ui.labels

import com.piercingxx.xxnote.ui.grid.labelChipTexts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WS8 gate: the card-chip cap — up to three labels verbatim, then one "+N"
 * overflow chip (rendered by NoteCard.kt's LabelChipRow with the §12.1
 * treatment). Pure logic; lives here so it is tested beside the other label
 * rules it complements.
 */
class LabelChipOverflowTest {

    @Test
    fun capsAtThreeThenOverflow() {
        assertEquals(listOf("a", "b", "c", "+2"), labelChipTexts(listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun exactlyThreeShowsNoOverflow() {
        assertEquals(listOf("a", "b", "c"), labelChipTexts(listOf("a", "b", "c")))
    }

    @Test
    fun fewerThanMaxRendersVerbatim() {
        assertEquals(listOf("only"), labelChipTexts(listOf("only")))
    }

    @Test
    fun emptyLabelsRenderNothing() {
        assertEquals(emptyList<String>(), labelChipTexts(emptyList()))
    }

    @Test
    fun overflowCountsHiddenLabelsNotShownOnes() {
        assertEquals(listOf("a", "+4"), labelChipTexts(listOf("a", "b", "c", "d", "e"), maxVisible = 1))
    }

    @Test
    fun degenerateMaxRendersNothing() {
        assertEquals(emptyList<String>(), labelChipTexts(listOf("a", "b", "c"), maxVisible = 0))
    }
}
