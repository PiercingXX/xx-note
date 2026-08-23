package com.piercingxx.xxnote.grid

import com.piercingxx.xxnote.ui.grid.SelectionAction
import com.piercingxx.xxnote.ui.grid.SelectionState
import com.piercingxx.xxnote.ui.grid.reduceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS8: the long-press multi-select reducer, proven as a pure table.
 * Load-bearing rulings: long-press on an ACTIVE selection ADDS the card
 * (Keep behaviour — a second long-press must never reset the batch), and
 * deselecting the LAST card exits selection mode entirely (no empty action
 * bar).
 */
class SelectionReducerTest {

    @Test
    fun initialSelectionIsInactiveAndEmpty() {
        assertEquals(SelectionState(), reduceSelection(SelectionState(), SelectionAction.Exit))
    }

    @Test
    fun longPressEntersSelectionWithThePressedCard() {
        val next = reduceSelection(SelectionState(), SelectionAction.LongPress("a"))
        assertTrue(next.active)
        assertEquals(setOf("a"), next.ids)
    }

    @Test
    fun longPressWhileActiveAddsRatherThanResets() {
        var state = reduceSelection(SelectionState(), SelectionAction.LongPress("a"))
        state = reduceSelection(state, SelectionAction.LongPress("b"))
        assertTrue(state.active)
        assertEquals(setOf("a", "b"), state.ids)
    }

    @Test
    fun tapTogglesMembershipWhileActive() {
        var state = reduceSelection(SelectionState(), SelectionAction.LongPress("a"))
        state = reduceSelection(state, SelectionAction.LongPress("b")) // adds, never resets
        state = reduceSelection(state, SelectionAction.Tap("a"))
        assertTrue(state.active)
        assertEquals(setOf("b"), state.ids)
    }

    @Test
    fun deselectingTheLastCardExitsSelectionMode() {
        var state = reduceSelection(SelectionState(), SelectionAction.LongPress("a"))
        state = reduceSelection(state, SelectionAction.Tap("a"))
        assertFalse(state.active)
        assertEquals(emptySet<String>(), state.ids)
    }

    @Test
    fun tapIsANoOpWhenNotSelecting_normalTapStillOpens() {
        val before = SelectionState()
        assertEquals(before, reduceSelection(before, SelectionAction.Tap("a")))
    }

    @Test
    fun exitClearsEverything() {
        var state = reduceSelection(SelectionState(), SelectionAction.LongPress("a"))
        state = reduceSelection(state, SelectionAction.LongPress("b"))
        state = reduceSelection(state, SelectionAction.Exit)
        assertEquals(SelectionState(), state)
    }

    @Test
    fun reEnteringAfterExitStartsFresh() {
        var state = reduceSelection(SelectionState(), SelectionAction.LongPress("a"))
        state = reduceSelection(state, SelectionAction.Exit)
        state = reduceSelection(state, SelectionAction.LongPress("z"))
        assertEquals(setOf("z"), state.ids)
    }
}
