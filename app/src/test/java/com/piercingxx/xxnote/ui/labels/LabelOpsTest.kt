package com.piercingxx.xxnote.ui.labels

import com.piercingxx.xxnote.core.Frontmatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS8 gate: the PURE label algebra over whole-file text — case-insensitive
 * dedupe/match, rename casing propagation, byte-for-byte preservation of
 * unknown frontmatter keys and CRLF endings, and the conservative refusal to
 * touch documents without a well-formed frontmatter block.
 */
class LabelOpsTest {

    // ---- Fixtures -------------------------------------------------------------

    private fun note(
        labelsLine: String?,
        vararg extraLines: String,
        eol: String = "\n",
    ): String {
        val lines = ArrayList<String>()
        lines.add("---")
        lines.add("id: 01J9F2K3M4N5P6Q7R8S9T0V1W2")
        if (labelsLine != null) lines.add(labelsLine)
        extraLines.forEach { lines.add(it) }
        lines.add("---")
        lines.add("body line")
        return lines.joinToString(eol) + eol
    }

    private fun labelsOf(text: String): List<String> = Frontmatter.parse(text).labels

    // ---- normalize --------------------------------------------------------------

    @Test
    fun normalizeTrimsOuterWhitespace() {
        assertEquals("home", LabelOps.normalize("  home\t"))
    }

    @Test
    fun normalizeRejectsBlank() {
        assertThrows(IllegalArgumentException::class.java) { LabelOps.normalize("") }
        assertThrows(IllegalArgumentException::class.java) { LabelOps.normalize("   ") }
    }

    @Test
    fun normalizeRejectsInteriorLineBreaksButTrimsEdgesFirst() {
        assertThrows(IllegalArgumentException::class.java) { LabelOps.normalize("\n") }
        assertThrows(IllegalArgumentException::class.java) { LabelOps.normalize("a\nb") }
        assertThrows(IllegalArgumentException::class.java) { LabelOps.normalize("a\rb") }
        // Trim happens before validation, so edge-adjacent breaks just vanish.
        assertEquals("ok", LabelOps.normalize("ok\n"))
    }

    @Test
    fun normalizeLengthBoundaryIsSixtyFourAfterTrim() {
        val sixtyFour = "a".repeat(64)
        assertEquals(sixtyFour, LabelOps.normalize(" $sixtyFour "))
        assertThrows(IllegalArgumentException::class.java) { LabelOps.normalize("a".repeat(65)) }
    }

    // ---- hasLabel ---------------------------------------------------------------

    @Test
    fun hasLabelMatchesCaseInsensitively() {
        assertTrue(LabelOps.hasLabel(listOf("Home", "errands"), "HOME"))
        assertFalse(LabelOps.hasLabel(listOf("Home", "errands"), "work"))
    }

    // ---- addLabel -----------------------------------------------------------------

    @Test
    fun addAppendsAndKeepsUnknownKeysByteForByte() {
        val input = note("labels: [a]", "plugin_note: keep me verbatim")
        val expected = note("labels: [a, b]", "plugin_note: keep me verbatim")
        assertEquals(expected, LabelOps.addLabel(input, "b"))
    }

    @Test
    fun addDedupesCaseInsensitivelyKeepingFirstSeenCasing() {
        val input = note("labels: [Home]")
        assertEquals(input, LabelOps.addLabel(input, "home"))
        assertEquals(input, LabelOps.addLabel(input, "HOME"))
        // Even pre-existing duplicate variants are left exactly as found.
        val dupes = note("labels: [Home, home]")
        assertEquals(dupes, LabelOps.addLabel(dupes, "HOME"))
    }

    @Test
    fun addCreatesLabelsLineAtBlockEndOnNotesWithoutTheKey() {
        val input = "---\nid: 01J9F2K3M4N5P6Q7R8S9T0V1W2\ntitle: T\n---\nbody"
        val expected = "---\nid: 01J9F2K3M4N5P6Q7R8S9T0V1W2\ntitle: T\nlabels: [errands]\n---\nbody"
        assertEquals(expected, LabelOps.addLabel(input, "errands"))
    }

    @Test
    fun addLeavesNoteWithoutFrontmatterUntouched() {
        val body = "just prose\nno frontmatter here\n"
        assertEquals(body, LabelOps.addLabel(body, "home"))
    }

    @Test
    fun addLeavesMalformedFrontmatterUntouched() {
        val interiorGarbage = "---\nthis line has no key shape\n---\nbody\n"
        assertEquals(interiorGarbage, LabelOps.addLabel(interiorGarbage, "home"))
        val unclosed = "---\nid: x\nnever closed\n"
        assertEquals(unclosed, LabelOps.addLabel(unclosed, "home"))
    }

    @Test
    fun addPreservesCrlfEndings() {
        val input = note("labels: [a]", eol = "\r\n")
        val expected = note("labels: [a, b]", eol = "\r\n")
        assertEquals(expected, LabelOps.addLabel(input, "b"))
    }

    @Test
    fun addQuotesLabelsThatNeedQuotingAndParsesBackFaithfully() {
        val result = LabelOps.addLabel(note("labels: [a]"), "milk, dark kind")
        assertTrue(result.contains("\"milk, dark kind\""))
        assertEquals(listOf("a", "milk, dark kind"), labelsOf(result))
    }

    // ---- removeLabel ----------------------------------------------------------------

    @Test
    fun removeStripsEveryCaseVariantInOnePass() {
        val result = LabelOps.removeLabel(note("labels: [home, HOME, errands]"), "HoMe")
        assertEquals(listOf("errands"), labelsOf(result))
    }

    @Test
    fun removingLastLabelLeavesAnExplicitEmptyFlow() {
        val result = LabelOps.removeLabel(note("labels: [home]"), "home")
        assertTrue(labelsOf(result).isEmpty())
        assertTrue(result.contains("labels: []"))
    }

    @Test
    fun removeAbsentLabelIsByteForByteNoOp() {
        val input = note("labels: [a]", "plugin_note: keep me verbatim")
        assertEquals(input, LabelOps.removeLabel(input, "zzz"))
    }

    @Test
    fun removePreservesUnknownKeysAndCrlf() {
        val input = note("labels: [a, B]", "plugin_note: keep me verbatim", eol = "\r\n")
        val expected = note("labels: [B]", "plugin_note: keep me verbatim", eol = "\r\n")
        assertEquals(expected, LabelOps.removeLabel(input, "a"))
    }

    @Test
    fun removeLeavesMalformedFrontmatterUntouched() {
        val malformed = "---\ngarbage\n---\nbody\n"
        assertEquals(malformed, LabelOps.removeLabel(malformed, "home"))
    }

    // ---- renameLabel ------------------------------------------------------------------

    @Test
    fun renameReplacesAllVariantsAndPropagatesNewCasing() {
        val result = LabelOps.renameLabel(note("labels: [home, HOME, errands]"), "home", "House")
        assertEquals(listOf("House", "errands"), labelsOf(result))
    }

    @Test
    fun renameCollapsesIntoPreExistingTargetKeepingFirstPosition() {
        assertEquals(
            listOf("House"),
            labelsOf(LabelOps.renameLabel(note("labels: [House, home]"), "home", "HOUSE")),
        )
        // And when `from` comes first, its slot carries the new casing.
        assertEquals(
            listOf("HOUSE"),
            labelsOf(LabelOps.renameLabel(note("labels: [home, House]"), "home", "HOUSE")),
        )
    }

    @Test
    fun casingOnlyRenameRewritesSpellingInPlace() {
        assertEquals(listOf("house"), labelsOf(LabelOps.renameLabel(note("labels: [Home]"), "Home", "house")))
    }

    @Test
    fun renameAbsentSourceIsByteForByteNoOp() {
        val input = note("labels: [a]", "plugin_note: keep me verbatim")
        assertEquals(input, LabelOps.renameLabel(input, "missing", "whatever"))
    }

    @Test
    fun renamePreservesUnknownKeysAndCrlf() {
        val input = note("labels: [a]", "plugin_note: keep me verbatim", eol = "\r\n")
        val expected = note("labels: [Z]", "plugin_note: keep me verbatim", eol = "\r\n")
        assertEquals(expected, LabelOps.renameLabel(input, "a", "Z"))
    }

    @Test
    fun renameLeavesMalformedFrontmatterUntouched() {
        val malformed = "---\ngarbage\n---\nbody\n"
        assertEquals(malformed, LabelOps.renameLabel(malformed, "a", "b"))
    }

    // ---- Round trips ---------------------------------------------------------------------

    @Test
    fun addThenRemoveRestoresOriginalBytes() {
        val original = note("labels: [a]", "plugin_note: keep me verbatim")
        assertEquals(original, LabelOps.removeLabel(LabelOps.addLabel(original, "b"), "b"))
    }

    @Test
    fun removeThenAddRestoresMembershipWithCallerCasing() {
        val original = note("labels: [Home, errands]")
        val cycled = LabelOps.addLabel(LabelOps.removeLabel(original, "ERRANDS"), "ERRANDS")
        assertEquals(note("labels: [Home, ERRANDS]"), cycled)
    }
}
