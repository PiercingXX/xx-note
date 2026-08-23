package com.piercingxx.xxnote.ui.grid

import com.piercingxx.xxnote.sync.LocalNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1/L7 gate: the PURE home-grid projection and search fallback. Archived
 * notes must leave the home surface and search results entirely — the
 * Archive screen keeps showing them — while ordering (most-recently-updated
 * first, id descending tie-break) and the Pinned/Others split survive intact.
 */
class HomeProjectionTest {

    private fun card(
        id: String,
        updatedMillis: Long = 0L,
        pinned: Boolean = false,
        archived: Boolean = false,
    ): NoteCard = NoteCard(
        id = id,
        title = id,
        preview = "",
        pinned = pinned,
        type = com.piercingxx.xxnote.core.NoteType.NOTE,
        doneCount = 0,
        totalCount = 0,
        labels = emptyList(),
        tone = NoteTone.INK,
        archived = archived,
        updatedAtMillis = updatedMillis,
    )

    // ---- projectHome ------------------------------------------------------------

    @Test
    fun archivedCardsAreExcludedFromHome() {
        val cards = listOf(
            card("live-1", updatedMillis = 30),
            card("archived", updatedMillis = 99, archived = true), // freshest — still hidden
            card("live-2", updatedMillis = 20),
        )
        val (pinned, others) = projectHome(cards)
        assertTrue(pinned.none { it.id == "archived" })
        assertTrue(others.none { it.id == "archived" })
        assertEquals(listOf("live-1", "live-2"), others.map { it.id })
    }

    @Test
    fun orderingIsRecencyFirstWithIdDescendingTieBreak() {
        val cards = listOf(
            card("b", updatedMillis = 10),
            card("d", updatedMillis = 30),
            card("a", updatedMillis = 10),
            card("c", updatedMillis = 20),
        )
        val (_, others) = projectHome(cards)
        assertEquals(listOf("d", "c", "b", "a"), others.map { it.id })
    }

    @Test
    fun pinnedSplitKeepsPinnedAboveOthers() {
        val cards = listOf(
            card("plain", updatedMillis = 50),
            card("stale-pin", updatedMillis = 5, pinned = true),
            card("fresh-pin", updatedMillis = 40, pinned = true),
        )
        val (pinned, others) = projectHome(cards)
        assertEquals(listOf("fresh-pin", "stale-pin"), pinned.map { it.id })
        assertEquals(listOf("plain"), others.map { it.id })
    }

    @Test
    fun allArchivedYieldsEmptySections() {
        val (pinned, others) = projectHome(listOf(card("a", archived = true), card("b", archived = true)))
        assertTrue(pinned.isEmpty())
        assertTrue(others.isEmpty())
    }

    // ---- searchFallbackCards (L7) -------------------------------------------------

    @Test
    fun searchFallbackExcludesArchivedAndKeepsOrder() {
        val live = listOf(
            LocalNote("l1", "l1.md", "---\nid: l1\n---\nbody\n", trashed = false),
            LocalNote("arc", "arc.md", "---\nid: arc\narchived: true\n---\nbody\n", trashed = false),
            LocalNote("l2", "l2.md", "no frontmatter at all\n", trashed = false),
        )
        val fallback = searchFallbackCards(live)
        assertEquals(listOf("l1", "l2"), fallback.map { it.id })
    }

    @Test
    fun searchFailureWordsSayWhatHappenedAndWhatTheGridDidInstead() {
        assertEquals("search failed · showing all notes", GridViewModel.SEARCH_FAILED_WORDS)
    }
}
