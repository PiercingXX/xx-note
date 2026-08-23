package com.piercingxx.xxnote.grid

import com.piercingxx.xxnote.ui.grid.SearchFieldEvent
import com.piercingxx.xxnote.ui.grid.SearchFieldState
import com.piercingxx.xxnote.ui.grid.reduceSearchField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS8: the search field's pure state machine. The load-bearing rule is the
 * exit: COLLAPSING resets the query so leaving search always restores the
 * normal Pinned/Others grid — no stale result set can survive behind a
 * collapsed field (and with it, R4's local-only query path is simply not
 * reachable while closed).
 */
class SearchStateTest {

    @Test
    fun initialFieldIsClosedAndBlank() {
        val field = SearchFieldState()
        assertFalse(field.open)
        assertFalse(field.isActive)
    }

    @Test
    fun expandOpensWithoutActivating_emptyQueryNeverSearches() {
        val field = reduceSearchField(SearchFieldState(), SearchFieldEvent.Expand)
        assertTrue(field.open)
        assertEquals("", field.raw)
        assertFalse(field.isActive)
    }

    @Test
    fun setQueryRecordsRawTextVerbatim_andActivates() {
        var field = reduceSearchField(SearchFieldState(), SearchFieldEvent.Expand)
        field = reduceSearchField(field, SearchFieldEvent.SetQuery("  oat milk "))
        assertEquals("  oat milk ", field.raw) // sanitizing happens at query time, not here
        assertTrue(field.isActive)
    }

    @Test
    fun blankQueryKeepsTheFieldOpenButInactive() {
        var field = reduceSearchField(SearchFieldState(), SearchFieldEvent.Expand)
        field = reduceSearchField(field, SearchFieldEvent.SetQuery("   "))
        assertTrue(field.open)
        assertFalse(field.isActive)
    }

    @Test
    fun collapseResetsQueryAndDeactivates() {
        var field = reduceSearchField(SearchFieldState(), SearchFieldEvent.Expand)
        field = reduceSearchField(field, SearchFieldEvent.SetQuery("oat"))
        assertTrue(field.isActive)

        field = reduceSearchField(field, SearchFieldEvent.Collapse)
        assertFalse(field.open)
        assertEquals("", field.raw)
        assertFalse(field.isActive)
    }

    @Test
    fun collapseFromClosedIsHarmless() {
        val field = reduceSearchField(SearchFieldState(), SearchFieldEvent.Collapse)
        assertEquals(SearchFieldState(), field)
    }
}
