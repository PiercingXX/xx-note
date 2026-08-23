package com.piercingxx.xxnote.trash

import com.piercingxx.xxnote.ui.trash.CHIP_EXPIRES_TODAY
import com.piercingxx.xxnote.ui.trash.CHIP_UNKNOWN
import com.piercingxx.xxnote.ui.trash.TrashMath
import com.piercingxx.xxnote.ui.trash.buildTrashRow
import com.piercingxx.xxnote.ui.trash.chipText
import com.piercingxx.xxnote.ui.trash.daysChipText
import com.piercingxx.xxnote.ui.trash.trashedAtMillisOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WS8 gate: the PURE trash row mapper — whole-file text → [TrashRow] with its
 * days chip. Title fallback and preview reuse the tested grid mapper, so the
 * assertions here pin the TRASH-specific parts: the `trashedAt:` stamp parse
 * (never guessed), the days math wiring, and the exact chip words (§12 item 3).
 */
class TrashRowMapperTest {

    private val now = java.time.Instant.parse("2026-08-08T00:00:00Z").toEpochMilli()

    // ---- Fixture ---------------------------------------------------------------

    private fun trashedNote(
        title: String? = "Trashed note",
        body: String = "some body line\n",
        color: String? = null,
        trashedAtIso: String? = "2026-08-05T00:00:00Z", // 3d before [now] → exactly 4d left
    ): String = buildString {
        append("---\n")
        append("id: 01J9F2K3M4N5P6Q7R8S9T0V1W2\n")
        if (title != null) append("title: $title\n")
        append("created: 2026-08-01T09:00:00Z\n")
        append("updated: 2026-08-02T10:00:00Z\n")
        if (color != null) append("color: $color\n")
        if (trashedAtIso != null) append("trashedAt: $trashedAtIso\n")
        append("plugin_note: keep me verbatim\n")
        append("---\n")
        append(body)
    }

    private fun row(text: String): com.piercingxx.xxnote.ui.trash.TrashRow =
        buildTrashRow("row-id", text, now)

    // ---- Stamp parse -----------------------------------------------------------

    @Test
    fun rfc3339StampParsesToMillis() {
        val expected = java.time.Instant.parse("2026-08-05T00:00:00Z").toEpochMilli()
        assertEquals(expected, row(trashedNote()).trashedAtMillis)
    }

    @Test
    fun absentOrCorruptStampIsNullNeverGuessed() {
        assertNull(row(trashedNote(trashedAtIso = null)).trashedAtMillis)
        assertNull(row(trashedNote(trashedAtIso = "not-a-date")).trashedAtMillis)
        assertNull(trashedAtMillisOf(null))
    }

    @Test
    fun pureStampHelperMirrorsDocumentAccessor() {
        val iso = "2026-07-31T23:59:59Z"
        assertEquals(
            java.time.Instant.parse(iso).toEpochMilli(),
            trashedAtMillisOf(com.piercingxx.xxnote.core.Frontmatter.parse(trashedNote(trashedAtIso = iso)).trashedAt),
        )
    }

    // ---- Days wiring -------------------------------------------------------------

    @Test
    fun fourWholeDaysLeftRendersFourDaysLeft() {
        val r = row(trashedNote()) // trashed 3d ago → exactly 4d remaining
        assertEquals(4, r.daysLeft)
        assertEquals("4d left", daysChipText(r.daysLeft))
    }

    @Test
    fun finalDayCollapsesToExpiresToday() {
        val text = trashedNote(trashedAtIso = "2026-08-01T06:00:00Z") // 6d18h ago → <24h left
        val r = row(text)
        assertEquals(0, r.daysLeft)
        assertEquals(CHIP_EXPIRES_TODAY, daysChipText(r.daysLeft))
    }

    @Test
    fun unknownStampRendersTheEmDash() {
        val r = row(trashedNote(trashedAtIso = null))
        assertNull(r.daysLeft)
        assertEquals(CHIP_UNKNOWN, daysChipText(null))
        assertEquals(CHIP_UNKNOWN, r.chipText())
    }

    @Test
    fun clockSkewedStampCapsChipAtSeven() {
        val text = trashedNote(trashedAtIso = "2026-08-20T00:00:00Z") // stamped in the future
        val r = row(text)
        assertEquals(TrashMath.EXPIRY_DAYS.toInt(), r.daysLeft)
        assertEquals("7d left", daysChipText(r.daysLeft))
    }

    @Test
    fun pastExpiryStillSaysExpiresTodayNotNegative() {
        val text = trashedNote(trashedAtIso = "2026-07-01T00:00:00Z") // long expired
        assertEquals(0, row(text).daysLeft)
        assertEquals(CHIP_EXPIRES_TODAY, daysChipText(0))
    }

    // ---- Chip wording table --------------------------------------------------------

    @Test
    fun chipWordingIsExact() {
        assertEquals("—", daysChipText(null))
        assertEquals("expires today", daysChipText(0))
        assertEquals("1d left", daysChipText(1))
        assertEquals("4d left", daysChipText(4))
        assertEquals("7d left", daysChipText(7))
    }

    // ---- Card-derived fields ----------------------------------------------------------

    @Test
    fun missingTitleFallsBackToFirstBodyLine() {
        val r = row(trashedNote(title = null, body = "\n\nfirst real line\nsecond\n"))
        assertEquals("first real line", r.title)
    }

    @Test
    fun previewComesFromBodyOnly() {
        val r = row(trashedNote(body = "- [ ] milk\nprose line\n"))
        assertEquals("milk\nprose line", r.preview)
    }

    @Test
    fun toneFollowsColorName() {
        assertEquals(
            com.piercingxx.xxnote.ui.grid.NoteTone.SLATE,
            row(trashedNote(color = "basil")).tone,
        )
        assertEquals(
            com.piercingxx.xxnote.ui.grid.NoteTone.INK,
            row(trashedNote(color = null)).tone,
        )
    }
}
