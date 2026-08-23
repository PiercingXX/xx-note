package com.piercingxx.xxnote.archive

import com.piercingxx.xxnote.ui.archive.ArchiveFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS8 gate: the PURE archive predicate (design §12 item 3). The archive
 * surface must show exactly and only notes whose frontmatter affirms
 * `archived: true`; every ambiguous state (absent, false, corrupt, no
 * frontmatter, malformed block) resolves to NOT archived — a note must never
 * vanish from its owner because of a value we could not read.
 */
class ArchiveFilterTest {

    // ---- Fixture ----------------------------------------------------------------

    private fun note(archivedLine: String?, extra: String = ""): String = buildString {
        append("---\n")
        append("id: 01J9F2K3M4N5P6Q7R8S9T0V1W2\n")
        append("title: t\n")
        if (archivedLine != null) append("archived: $archivedLine\n")
        append(extra)
        append("---\n")
        append("body line\n")
    }

    // ---- Affirmative cases --------------------------------------------------------

    @Test
    fun archivedTrueIsArchived() {
        assertTrue(ArchiveFilter.isArchived(note("true")))
    }

    @Test
    fun lenientTrueSpellingsAreArchived() {
        listOf("True", "TRUE", "yes", "on", "1").forEach { spelling ->
            assertTrue(spelling, ArchiveFilter.isArchived(note(spelling)))
        }
    }

    @Test
    fun quotedTrueIsStillArchived() {
        assertTrue(ArchiveFilter.isArchived(note("\"true\"")))
    }

    // ---- Non-archive cases ---------------------------------------------------------

    @Test
    fun archivedFalseIsNotArchived() {
        assertFalse(ArchiveFilter.isArchived(note("false")))
    }

    @Test
    fun absentKeyIsNotArchived() {
        assertFalse(ArchiveFilter.isArchived(note(null)))
    }

    @Test
    fun corruptValueIsNotArchived() {
        assertFalse(ArchiveFilter.isArchived(note("maybe")))
        assertFalse(ArchiveFilter.isArchived(note("")))
    }

    @Test
    fun noFrontmatterAtAllIsNotArchived() {
        assertFalse(ArchiveFilter.isArchived("just body text\nnothing else\n"))
    }

    @Test
    fun malformedBlockDegradesToBodyAndIsNotArchived() {
        assertFalse(ArchiveFilter.isArchived("---\narchived: true\nno closing delimiter\n"))
    }

    @Test
    fun emptyFileIsNotArchived() {
        assertFalse(ArchiveFilter.isArchived(""))
    }

    // ---- List filter ---------------------------------------------------------------

    @Test
    fun filterKeepsOnlyAffirmativeNotesInOrder() {
        val live = listOf(
            "a" to note("false"),
            "b" to note("true"),
            "c" to note(null),
            "d" to note("yes"),
            "e" to "---\nno: block here\n",
        )
        val kept = ArchiveFilter.filterArchived(live) { it.second }.map { it.first }
        assertEquals(listOf("b", "d"), kept)
    }

    @Test
    fun filterOfNothingIsNothing() {
        assertTrue(ArchiveFilter.filterArchived(emptyList<String>()) { it }.isEmpty())
    }
}
