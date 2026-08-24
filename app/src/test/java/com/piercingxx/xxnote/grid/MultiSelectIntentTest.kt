package com.piercingxx.xxnote.grid

import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.ui.grid.BatchAction
import com.piercingxx.xxnote.ui.grid.MultiSelectOps
import com.piercingxx.xxnote.ui.grid.batchNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS8 gate: the PURE multi-select intent folds (pin / archive / colour /
 * trash-stamp) over whole-file text, mirroring the LabelOps contract:
 * unknown frontmatter keys survive byte-for-byte, line endings are
 * preserved, effective no-ops return the input UNCHANGED, and documents
 * without a well-formed frontmatter block are skipped (`null`) so the batch
 * can report them instead of mangling foreign files.
 */
class MultiSelectIntentTest {

    // ---- Fixtures -------------------------------------------------------------

    /** A live note like VaultStore would find it: id block + body + extras. */
    private fun note(
        vararg ownedLines: String,
        extra: String? = "plugin_meta: keep me verbatim",
        eol: String = "\n",
    ): String {
        val lines = ArrayList<String>()
        lines.add("---")
        lines.add("id: 01J9F2K3M4N5P6Q7R8S9T0V1W2")
        lines.add("title: Oat inventory")
        ownedLines.forEach { lines.add(it) }
        if (extra != null) lines.add(extra)
        lines.add("---")
        lines.add("body stays")
        return lines.joinToString(eol) + eol
    }

    private fun pinnedOf(text: String): Boolean? = Frontmatter.parse(text).pinned
    private fun archivedOf(text: String): Boolean? = Frontmatter.parse(text).archived
    private fun colorOf(text: String): String? = Frontmatter.parse(text).color

    /**
     * Expected bytes when a fold ADDS a brand-new owned key: the core
     * rewrite contract appends it at the END of the block, after unknown
     * keys — this pins that placement byte-for-byte.
     */
    private fun noteWithAppended(line: String, eol: String = "\n"): String {
        val lines = listOf(
            "---",
            "id: 01J9F2K3M4N5P6Q7R8S9T0V1W2",
            "title: Oat inventory",
            "plugin_meta: keep me verbatim",
            line,
            "---",
            "body stays",
        )
        return lines.joinToString(eol) + eol
    }

    /** JUnit's assertNotNull is void; this flavour hands the value back. */
    // assertNotNull above guarantees non-null; the cast target is erased at runtime, so the warning is a false positive.
    @Suppress("UNCHECKED_CAST")
    private fun <T> notNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value as T
    }

    // ---- applyPin -----------------------------------------------------------------

    @Test
    fun pinWritesTrueAndPreservesUnknownKeysAndBody() {
        val input = note()
        val out = notNull(MultiSelectOps.applyPin(input, true))
        assertEquals(true, pinnedOf(out))
        assertEquals(noteWithAppended("pinned: true"), out) // exact expected bytes
    }

    @Test
    fun pinIsIdempotent_secondFoldReturnsIdenticalBytes() {
        val once = notNull(MultiSelectOps.applyPin(note(), true))
        val twice = notNull(MultiSelectOps.applyPin(once, true))
        assertEquals(once, twice)
    }

    @Test
    fun unpinRemovesTheTrueLine() {
        val input = note("pinned: true")
        val out = notNull(MultiSelectOps.applyPin(input, false))
        assertNull(pinnedOf(out))
        assertEquals(note(), out) // back to the pre-pin shape exactly
    }

    @Test
    fun unpinNeverTouchesAnExplicitFalseLine_orAnAbsentKey() {
        val explicitFalse = note("pinned: false")
        assertEquals(explicitFalse, MultiSelectOps.applyPin(explicitFalse, false))

        val absent = note()
        assertEquals(absent, MultiSelectOps.applyPin(absent, false))
    }

    @Test
    fun pinOverwritesAnExplicitFalseLine() {
        val input = note("pinned: false")
        val out = notNull(MultiSelectOps.applyPin(input, true))
        assertEquals(true, pinnedOf(out))
    }

    @Test
    fun crlfLineEndingsSurviveThePinFold() {
        val input = note(extra = null, eol = "\r\n")
        val out = notNull(MultiSelectOps.applyPin(input, true))
        assertTrue(out.contains("\r\n"))
        assertEquals(true, pinnedOf(out))
        assertEquals(note("pinned: true", extra = null), out.replace("\r\n", "\n"))
    }

    // ---- applyArchive ------------------------------------------------------------------

    @Test
    fun archiveWritesTrue() {
        val out = notNull(MultiSelectOps.applyArchive(note(), true))
        assertEquals(true, archivedOf(out))
    }

    @Test
    fun archiveIsIdempotent_andUnarchiveStripsTheLine() {
        val once = notNull(MultiSelectOps.applyArchive(note(), true))
        assertEquals(once, MultiSelectOps.applyArchive(once, true))

        val restored = notNull(MultiSelectOps.applyArchive(once, false))
        assertNull(archivedOf(restored))
        assertEquals(note(), restored)
    }

    @Test
    fun unarchiveLeavesExplicitFalseAndAbsentUntouched() {
        val explicitFalse = note("archived: false")
        assertEquals(explicitFalse, MultiSelectOps.applyArchive(explicitFalse, false))
        val absent = note()
        assertEquals(absent, MultiSelectOps.applyArchive(absent, false))
    }

    // ---- applyColor ----------------------------------------------------------------------

    @Test
    fun colorWritesCanonicalKeepName() {
        val out = notNull(MultiSelectOps.applyColor(note(), "banana"))
        assertEquals("banana", colorOf(out))
        assertEquals(noteWithAppended("color: banana"), out)
    }

    @Test
    fun colorIsIdempotent_sameNameReturnsInputUnchanged() {
        val input = note("color: banana")
        assertEquals(input, MultiSelectOps.applyColor(input, "banana"))
    }

    @Test
    fun colorMatchIsCaseInsensitive_firstSeenSpellingStays() {
        val input = note("color: Banana")
        assertEquals(input, MultiSelectOps.applyColor(input, "BANANA"))
    }

    @Test
    fun colorRejectsBlankAndLineBreaks() {
        assertThrows(IllegalArgumentException::class.java) {
            MultiSelectOps.applyColor(note(), "  ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiSelectOps.applyColor(note(), "ba\nnana")
        }
    }

    // ---- applyTrash (pure projection of VaultStore.trash's stamp) --------------------------

    @Test
    fun trashStampWritesTrashedAtAndUpdated() {
        val iso = "2026-08-23T10:00:00Z"
        val out = notNull(MultiSelectOps.applyTrash(note(), iso))
        val doc = Frontmatter.parse(out)
        assertEquals(iso, doc.trashedAt)
        assertEquals(iso, doc.updated)
    }

    @Test
    fun trashStampIsIdempotentForTheSameInstant() {
        val iso = "2026-08-23T10:00:00Z"
        val once = notNull(MultiSelectOps.applyTrash(note(), iso))
        assertEquals(once, MultiSelectOps.applyTrash(once, iso))
    }

    @Test
    fun trashStampLastWriteWins_onAFreshInstant() {
        val first = notNull(MultiSelectOps.applyTrash(note(), "2026-08-23T10:00:00Z"))
        val second = notNull(MultiSelectOps.applyTrash(first, "2026-08-23T11:30:00Z"))
        assertEquals("2026-08-23T11:30:00Z", Frontmatter.parse(second).trashedAt)
    }

    // ---- malformed / absent frontmatter: skip-and-report ------------------------------------

    @Test
    fun noFrontmatterBlock_isSkippedByEveryMutator() {
        val plain = "just body text\nno metadata at all\n"
        assertNull(MultiSelectOps.applyPin(plain, true))
        assertNull(MultiSelectOps.applyArchive(plain, true))
        assertNull(MultiSelectOps.applyColor(plain, "banana"))
        assertNull(MultiSelectOps.applyTrash(plain, "2026-08-23T10:00:00Z"))
    }

    @Test
    fun unclosedBlock_isSkippedByEveryMutator() {
        val unclosed = "---\nid: 01J9F2K3M4N5P6Q7R8S9T0V1W2\nbody continues\n"
        assertNull(MultiSelectOps.applyPin(unclosed, true))
        assertNull(MultiSelectOps.applyArchive(unclosed, true))
        assertNull(MultiSelectOps.applyColor(unclosed, "banana"))
        assertNull(MultiSelectOps.applyTrash(unclosed, "2026-08-23T10:00:00Z"))
    }

    @Test
    fun interiorGarbageLine_malformedBlock_isSkipped() {
        val malformed = "---\nid: 01J9F2K3M4N5P6Q7R8S9T0V1W2\ngarbage line no colon\n---\nbody\n"
        assertNull(MultiSelectOps.applyPin(malformed, true))
        assertNull(MultiSelectOps.applyArchive(malformed, true))
        assertNull(MultiSelectOps.applyColor(malformed, "banana"))
        assertNull(MultiSelectOps.applyTrash(malformed, "2026-08-23T10:00:00Z"))
    }

    // ---- batch notice wording ---------------------------------------------------------------

    @Test
    fun noticeIsNullWhenNothingFailed() {
        assertNull(batchNotice(failed = 0, total = 5))
    }

    @Test
    fun noticeNamesPartialFailureWithCounts() {
        assertEquals(
            "changed 3 of 5 notes · 2 failed — storage problem",
            batchNotice(failed = 2, total = 5),
        )
    }

    @Test
    fun noticeCoversTotalFailureIncludingSingleNote() {
        assertEquals(
            "couldn't change that note — storage problem · nothing was changed",
            batchNotice(failed = 1, total = 1),
        )
        assertEquals(
            "couldn't change any of the 4 notes — storage problem · nothing was changed",
            batchNotice(failed = 4, total = 4),
        )
    }
}
