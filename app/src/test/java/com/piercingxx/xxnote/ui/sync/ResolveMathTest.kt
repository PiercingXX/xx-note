package com.piercingxx.xxnote.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact-byte proofs for the Resolve sheet's marker arithmetic (§7): what
 * "accept mine" / "accept theirs" / "edit merged" leave behind.
 */
class ResolveMathTest {

    private val ours = "<<<<<<< pixel-9"
    private val divider = "======="
    private val theirs = ">>>>>>> remote"

    @Test
    fun keepMine_leavesExactlyTheOursSections() {
        val marked = listOf(
            "line A",
            ours,
            "mine 1",
            "mine 2",
            divider,
            "theirs 1",
            theirs,
            "line B",
        ).joinToString("\n")

        val expected = listOf("line A", "mine 1", "mine 2", "line B").joinToString("\n")
        assertEquals(expected, ResolveMath.keepMine(marked))
    }

    @Test
    fun keepTheirs_leavesExactlyTheTheirsSections() {
        val marked = listOf(
            "line A",
            ours,
            "mine 1",
            divider,
            "theirs 1",
            "theirs 2",
            theirs,
            "line B",
        ).joinToString("\n")

        val expected = listOf("line A", "theirs 1", "theirs 2", "line B").joinToString("\n")
        assertEquals(expected, ResolveMath.keepTheirs(marked))
    }

    @Test
    fun multipleHunks_allStrip() {
        val marked = listOf(
            "head",
            ours, "m1", divider, "t1", theirs,
            "shared middle",
            ours, "m2", divider, "t2", theirs,
            "tail",
        ).joinToString("\n")

        assertEquals("head\nm1\nshared middle\nm2\ntail", ResolveMath.keepMine(marked))
        assertEquals("head\nt1\nshared middle\nt2\ntail", ResolveMath.keepTheirs(marked))
    }

    @Test
    fun emptyMineSection_theirsSurvivesBothWays() {
        val marked = listOf(ours, divider, "theirs line", theirs).joinToString("\n")
        assertEquals("", ResolveMath.keepMine(marked))
        assertEquals("theirs line", ResolveMath.keepTheirs(marked))
    }

    @Test
    fun emptyTheirsSection_mineSurvivesBothWays() {
        val marked = listOf(ours, "mine line", divider, theirs).joinToString("\n")
        assertEquals("mine line", ResolveMath.keepMine(marked))
        assertEquals("", ResolveMath.keepTheirs(marked))
    }

    @Test
    fun trailingNewline_isPreservedExactly() {
        val withFinalNewline = "$ours\nkept\n$divider\ndropped\n$theirs\n"
        assertEquals("kept\n", ResolveMath.keepMine(withFinalNewline))

        val noFinalNewline = "$ours\nkept\n$divider\ndropped\n$theirs"
        assertEquals("kept", ResolveMath.keepMine(noFinalNewline))
    }

    @Test
    fun crlfLineEndings_surviveOnKeptLines() {
        val marked = "a\r\n$ours\r\nmine\r\n$divider\r\ntheirs\r\n$theirs\r\nb"
        assertEquals("a\r\nmine\r\nb", ResolveMath.keepMine(marked))
    }

    @Test
    fun unclosedBlockAtEof_stillStripsItsLastOpenSection() {
        val marked = listOf(ours, "mine", divider, "theirs").joinToString("\n") // no >>>>>>>
        assertEquals("mine", ResolveMath.keepMine(marked))
        assertEquals("theirs", ResolveMath.keepTheirs(marked))
    }

    @Test
    fun editMerged_passthroughReturnsBytesUntouched() {
        val raw = listOf("Title", "", "prose stays", ours, "x", divider, "y", theirs).joinToString("\n") + "\n"
        assertEquals(raw, ResolveMath.resolve(raw, side = null))
    }

    @Test
    fun strayDividerOutsideABlock_isContent_notStructure() {
        // A setext H2 underline is legitimate prose; stripping must not eat it.
        val prose = "Heading\n-------\n\nBigger\n=======\nbody text"
        assertEquals(prose, ResolveMath.keepMine(prose))
        assertEquals(prose, ResolveMath.keepTheirs(prose))
    }

    @Test
    fun plainTextWithoutMarkers_roundTripsIdentically() {
        val plain = "one\ntwo\nthree\n"
        assertEquals(plain, ResolveMath.keepMine(plain))
        assertEquals(plain, ResolveMath.keepTheirs(plain))
        assertTrue(ResolveMath.resolve(plain, null) === plain)
    }

    @Test
    fun markerClassification_matchesGitShapedLinesOnly() {
        assertTrue(ResolveMath.isMarkerLine("<<<<<<< pixel-9"))
        assertTrue(ResolveMath.isMarkerLine("======="))
        assertTrue(ResolveMath.isMarkerLine(">>>>>>> remote"))
        assertFalse(ResolveMath.isMarkerLine("<<<<<<<".dropLast(1))) // 6 arrows: content
        assertFalse(ResolveMath.isMarkerLine("some ======= inside text"))
        // Divider detection must not swallow the theirs marker via prefix logic.
        assertFalse(ResolveMath.isDivider(">>>>>>> remote"))
        // Setext underlines of six '=' are content; exactly-seven-or-longer are structural.
        assertFalse(ResolveMath.isMarkerLine("======"))
        assertTrue(ResolveMath.isMarkerLine("========="))
    }

    // ---- resolvedWholeFile: the M12 updated-stamp assembly ----------------------

    private val original = buildString {
        append("---\n")
        append("id: 01J9F2K3M4N5P6Q7R8S9T0V1W2\n")
        append("title: Grocery list\n")
        append("created: 2026-08-20T09:00:00Z\n")
        append("updated: 2026-08-21T10:00:00Z\n")
        append("conflictOf: 01JZZZZZZZZZZZZZZZZZZZZZZZ\n")
        append("plugin_note: keep me verbatim\n")
        append("---\n")
    }

    @Test
    fun resolution_stampsUpdatedToNow_underTheOriginalIdentity() {
        val out = ResolveMath.resolvedWholeFile(
            originalWholeFileText = original + "kept prose\n",
            resolvedBody = "kept prose\n",
            updatedIso = "2026-08-23T15:04:05Z",
        )
        val doc = com.piercingxx.xxnote.core.Frontmatter.parse(out)
        assertEquals("2026-08-23T15:04:05Z", doc.updated)
        assertEquals("01J9F2K3M4N5P6Q7R8S9T0V1W2", doc.id) // identity never moves (§7)
        assertEquals("kept prose\n", doc.bodyText)
    }

    @Test
    fun resolution_keepsUnknownKeysAndUntouchedOwnedKeysByteForByte() {
        val out = ResolveMath.resolvedWholeFile(original + "x\n", "y\n", "2026-08-23T15:04:05Z")
        assertTrue(out.contains("plugin_note: keep me verbatim"))
        assertTrue(out.contains("conflictOf: 01JZZZZZZZZZZZZZZZZZZZZZZZ"))
        assertTrue(out.contains("created: 2026-08-20T09:00:00Z"))
        assertTrue(out.contains("title: Grocery list"))
        assertFalse(out.contains("updated: 2026-08-21T10:00:00Z"))
    }

    @Test
    fun resolution_bodyIsReplacedVerbatim_includingTrailingNewlineAbsence() {
        val outWithEnd = ResolveMath.resolvedWholeFile(original + "old\n", "new\n", "2026-08-23T15:04:05Z")
        assertEquals("new\n", com.piercingxx.xxnote.core.Frontmatter.parse(outWithEnd).bodyText)

        val noFinalNewline = ResolveMath.resolvedWholeFile(original + "old", "new", "2026-08-23T15:04:05Z")
        assertEquals("new", com.piercingxx.xxnote.core.Frontmatter.parse(noFinalNewline).bodyText)
    }

    @Test
    fun resolution_strippedMarkersNeverReachTheFile() {
        val markedBody = listOf(
            "<<<<<<< pixel-9", "mine", "=======", "theirs", ">>>>>>> remote",
        ).joinToString("\n")
        val out = ResolveMath.resolvedWholeFile(
            original + markedBody + "\n",
            ResolveMath.keepMine(markedBody) + "\n",
            "2026-08-23T15:04:05Z",
        )
        assertFalse(out.contains("<<<<<<<"))
        assertFalse(out.contains(">>>>>>>"))
        assertEquals("mine\n", com.piercingxx.xxnote.core.Frontmatter.parse(out).bodyText)
    }

    @Test
    fun resolution_noteWithoutFrontmatter_gainsAFreshStampedBlock() {
        val bare = "just prose, never had frontmatter\n"
        val out = ResolveMath.resolvedWholeFile(bare, bare, "2026-08-23T15:04:05Z")
        val doc = com.piercingxx.xxnote.core.Frontmatter.parse(out)
        assertTrue(doc.hasFrontmatter)
        assertEquals("2026-08-23T15:04:05Z", doc.updated)
        assertEquals(bare, doc.bodyText)
    }
}
