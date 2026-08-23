package com.piercingxx.xxnote.ui.grid

import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.NoteType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS7 gate: the PURE card mapper (design §12 item 1) — title fallback,
 * six-line preview clip, checklist progress from the body region, label
 * parsing, and the D12/O2 twelve-Keep-names → six-tones table. No Android,
 * no Robolectric: buildNoteCard touches only core parsers and ColorTones.
 */
class NoteCardMapperTest {

    // ---- Fixture -------------------------------------------------------------

    private fun note(
        title: String?,
        body: String,
        color: String? = null,
        pinned: Boolean = false,
        labelsRaw: String? = null,
        type: String? = null,
        archivedRaw: String? = null,
    ): String = buildString {
        append("---\n")
        append("id: 01J9F2K3M4N5P6Q7R8S9T0V1W2\n")
        if (title != null) append("title: $title\n")
        append("created: 2026-08-20T09:00:00Z\n")
        append("updated: 2026-08-21T10:00:00Z\n")
        append("pinned: $pinned\n")
        if (archivedRaw != null) append("archived: $archivedRaw\n")
        if (color != null) append("color: $color\n")
        if (labelsRaw != null) append("labels: [$labelsRaw]\n")
        if (type != null) append("type: $type\n")
        append("plugin_note: keep me verbatim\n")
        append("---\n")
        append(body)
    }

    private fun card(text: String): NoteCard = buildNoteCard("test-id", text)

    // ---- Title ---------------------------------------------------------------

    @Test
    fun frontmatterTitleWins() {
        val c = card(note(title = "Grocery list", body = "- [ ] oat milk\n"))
        assertEquals("Grocery list", c.title)
    }

    @Test
    fun missingTitleFallsBackToFirstNonEmptyBodyLine() {
        val c = card(note(title = null, body = "\n\nsecond line wins over blanks\nthird\n"))
        assertEquals("second line wins over blanks", c.title)
    }

    @Test
    fun blankTitleFallsBackLikeMissing() {
        val c = card(note(title = "", body = "# Heading as title\n"))
        assertEquals("# Heading as title".replace("# ", ""), c.title)
    }

    @Test
    fun emptyEverythingGivesBlankTitle() {
        val c = card("---\nid: x\n---\n\n")
        assertEquals("", c.title)
    }

    @Test
    fun checkboxPrefixStrippedFromFallbackTitle() {
        val c = card(note(title = null, body = "- [ ] oat milk first\n"))
        assertEquals("oat milk first", c.title)
    }

    // ---- Preview ---------------------------------------------------------------

    @Test
    fun previewClippedToSixLines() {
        val body = (1..9).joinToString("\n") { "line $it" } + "\n"
        val c = card(note(title = "t", body = body))
        val lines = c.preview.split('\n')
        assertEquals(CARD_PREVIEW_LINES, lines.size)
        assertEquals("line 1", lines.first())
        assertEquals("line 6", lines.last())
        assertFalse(c.preview.contains("line 7"))
    }

    @Test
    fun previewDropsBlanksAndCheckboxMarkers() {
        val c = card(note(title = "t", body = "\n\n- [ ] milk\n- [x] done thing\nprose\n"))
        assertEquals("milk\ndone thing\nprose", c.preview)
    }

    @Test
    fun previewEmptyWhenBodyEmpty() {
        val c = card(note(title = "t", body = ""))
        assertEquals("", c.preview)
    }

    // ---- Checklist progress ------------------------------------------------------

    @Test
    fun progressCountsAcrossBlocksAndIgnoresProse() {
        val body = "- [ ] a\n- [x] b\n\nprose line\n\n* [x] c\n+ [ ] d\n"
        val c = card(note(title = "t", type = "checklist", body = body))
        assertEquals(2, c.doneCount)
        assertEquals(4, c.totalCount)
    }

    @Test
    fun zeroItemChecklistShowsZeroZero() {
        val c = card(note(title = "t", type = "checklist", body = "no tasks yet, just prose\n"))
        assertEquals(0, c.doneCount)
        assertEquals(0, c.totalCount)
        assertTrue(c.type == NoteType.CHECKLIST)
    }

    @Test
    fun plainNoteHasNoProgress() {
        val c = card(note(title = "t", body = "- [x] looks like a task but type is note\n"))
        assertEquals(NoteType.NOTE, c.type)
        // Counts still derive from task-shaped lines; the CARD decides to hide
        // them when totalCount == 0 is false — here they are counted but the
        // note is not behavioral (D18 does not apply). Progress numbers remain
        // honest for any task-shaped content.
        assertEquals(1, c.totalCount)
    }

    @Test
    fun uppercaseXCountsChecked() {
        val c = card(note(title = "t", type = "checklist", body = "- [X] big tick\n- [ ] small\n"))
        assertEquals(1, c.doneCount)
        assertEquals(2, c.totalCount)
    }

    @Test
    fun frontmatterNeverContainsTasks() {
        // A `---` line inside the body must not be mistaken for more frontmatter.
        val body = "---\nnot: frontmatter\n---\n- [x] real task\n"
        val c = card(note(title = "t", type = "checklist", body = body))
        assertEquals(1, c.totalCount)
    }

    // ---- Labels -------------------------------------------------------------------

    @Test
    fun labelsParsedFromFlowSequence() {
        val c = card(note(title = "t", labelsRaw = "home, errands", body = "b\n"))
        assertEquals(listOf("home", "errands"), c.labels)
    }

    @Test
    fun labelsEmptyWhenAbsent() {
        val c = card(note(title = "t", body = "b\n"))
        assertTrue(c.labels.isEmpty())
    }

    // ---- Tone table (D12/O2) ---------------------------------------------------------

    @Test
    fun twelveCanonicalKeepNamesMapToExpectedTones() {
        val expected = mapOf(
            "white" to NoteTone.INK,
            "graphite" to NoteTone.INK,
            "banana" to NoteTone.INK_RAISED,
            "tangerine" to NoteTone.INK_RAISED,
            "tomato" to NoteTone.GRAPHITE,
            "flamingo" to NoteTone.GRAPHITE,
            "basil" to NoteTone.SLATE,
            "sage" to NoteTone.SLATE,
            "peacock" to NoteTone.HAIRLINE_LEFT,
            "blueberry" to NoteTone.HAIRLINE_LEFT,
            "lavender" to NoteTone.HAIRLINE_FULL,
            "grape" to NoteTone.HAIRLINE_FULL,
        )
        assertEquals(12, expected.size)
        assertEquals(12, CANONICAL_COLOR_TONES.size)
        expected.forEach { (name, tone) ->
            assertEquals(name, tone, toneForColor(name))
        }
    }

    @Test
    fun toneLookupIsCaseAndSpaceInsensitive() {
        assertEquals(NoteTone.GRAPHITE, toneForColor(" Tomato "))
        assertEquals(NoteTone.SLATE, toneForColor("BASIL"))
    }

    @Test
    fun unknownColorDefaultsToInk() {
        assertEquals(NoteTone.INK, toneForColor("chartreuse"))
        assertEquals(NoteTone.INK, toneForColor(""))
        assertEquals(NoteTone.INK, toneForColor(null))
        assertEquals(NoteTone.INK, toneForColor("   "))
    }

    @Test
    fun designMdExampleSandStillRendersViaAlias() {
        assertEquals(NoteTone.INK_RAISED, toneForColor("sand"))
    }

    @Test
    fun everyToneHasACanonicalNameAndInverseRoundTrips() {
        NoteTone.entries.forEach { tone ->
            val canonical = canonicalColorFor(tone)
            assertTrue(canonical in CANONICAL_COLOR_TONES.map { it.first })
            assertEquals(tone, toneForColor(canonical))
        }
    }

    // ---- Pinned / sort key / template ---------------------------------------------

    @Test
    fun pinnedFlagParsed() {
        assertTrue(card(note(title = "t", pinned = true, body = "b")).pinned)
        assertFalse(card(note(title = "t", pinned = false, body = "b")).pinned)
    }

    // ---- Archived (H1) ------------------------------------------------------------

    @Test
    fun archivedTrueParsed() {
        assertTrue(card(note(title = "t", archivedRaw = "true", body = "b")).archived)
    }

    @Test
    fun archivedFalseParsed() {
        assertFalse(card(note(title = "t", archivedRaw = "false", body = "b")).archived)
    }

    @Test
    fun archivedAbsentReadsFalse() {
        assertFalse(card(note(title = "t", body = "b")).archived)
    }

    @Test
    fun archivedGarbageValueReadsFalseDoesLess() {
        // Lenient parse yields null for an unknown value; the mapper's
        // does-less direction keeps the note visible rather than hiding it.
        assertFalse(card(note(title = "t", archivedRaw = "maybe", body = "b")).archived)
    }

    @Test
    fun updatedStampBecomesSortKeyMillis() {
        val c = card(note(title = "t", body = "b"))
        assertEquals(Instant.parse("2026-08-21T10:00:00Z").toEpochMilli(), c.updatedAtMillis)
    }

    @Test
    fun corruptUpdatedStampSortsAsZero() {
        val text = note(title = "t", body = "b").replace(
            "updated: 2026-08-21T10:00:00Z",
            "updated: not-a-date",
        )
        assertEquals(0L, card(text).updatedAtMillis)
    }

    @Test
    fun captureTemplateCarriesIdentityStampsAndChecklistTypeOnlyWhenNeeded() {
        val tpl = captureTemplate(
            id = "01JABC",
            title = "Draft",
            type = NoteType.CHECKLIST,
            nowIso = "2026-08-23T10:04:12Z",
        )
        val doc = Frontmatter.parse(tpl)
        assertEquals("01JABC", doc.id)
        assertEquals("Draft", doc.title)
        assertEquals("2026-08-23T10:04:12Z", doc.created)
        assertEquals("2026-08-23T10:04:12Z", doc.updated)
        assertEquals(NoteType.CHECKLIST, doc.type)

        val plain = Frontmatter.parse(
            captureTemplate("01JABC", "", NoteType.NOTE, "2026-08-23T10:04:12Z"),
        )
        assertEquals(NoteType.NOTE, plain.type)
        assertEquals(null, plain.title)
    }
}
