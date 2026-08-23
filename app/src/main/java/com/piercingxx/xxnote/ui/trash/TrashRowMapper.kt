package com.piercingxx.xxnote.ui.trash

import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.ui.grid.NoteTone
import com.piercingxx.xxnote.ui.grid.buildNoteCard
import java.time.Instant

/**
 * One trash row, fully derived from the note's whole-file text by the PURE
 * [buildTrashRow] — no Room row is trusted for content (D1: file wins).
 */
data class TrashRow(
    val id: String,
    /** Frontmatter title, or the first non-empty body line (§8 fallback). Never null. */
    val title: String,
    /** Plain-text body preview (grid mapper's six-line clip); "" when empty. */
    val preview: String,
    /** D12 surface tone from `color:`. */
    val tone: NoteTone,
    /** `trashedAt:` parsed to epoch millis; null when absent or unparseable — never guessed. */
    val trashedAtMillis: Long?,
    /** [TrashMath.daysRemaining] result; null renders "—", 0 renders "expires today". */
    val daysLeft: Int?,
)

/**
 * PURE row mapper (unit-tested in TrashRowMapperTest): whole-file text →
 * [TrashRow]. Reuses the tested grid card mapper for title fallback, preview,
 * and tone, then adds the trash-specific stamp math. No disk, no Room, no
 * Android; `now` is injected for determinism.
 */
fun buildTrashRow(id: String, wholeFileText: String, nowEpochMs: Long): TrashRow {
    val card = buildNoteCard(id, wholeFileText)
    val trashedAtMillis = trashedAtMillisOf(Frontmatter.parse(wholeFileText).trashedAt)
    return TrashRow(
        id = id,
        title = card.title,
        preview = card.preview,
        tone = card.tone,
        trashedAtMillis = trashedAtMillis,
        daysLeft = TrashMath.daysRemaining(trashedAtMillis, nowEpochMs),
    )
}

/** RFC3339 `trashedAt:` scalar → epoch millis; null/absent/unparseable → null. */
fun trashedAtMillisOf(iso: String?): Long? = try {
    iso?.let { Instant.parse(it).toEpochMilli() }
} catch (_: Exception) {
    null
}

// ---- Chip wording (§12 item 3) ----------------------------------------------

const val CHIP_UNKNOWN = "—"
const val CHIP_EXPIRES_TODAY = "expires today"
const val CHIP_DAYS_SUFFIX = "d left"

/**
 * PURE chip text for a [TrashMath.daysRemaining] result. Null (unknown stamp)
 * renders "—" rather than guessing; 0 means the final day or already past —
 * both speak as "expires today" because expiry, not the user, does the
 * deleting (D9); n ≥ 1 renders tabular "Nd left" (§12.1).
 */
fun daysChipText(daysLeft: Int?): String = when {
    daysLeft == null -> CHIP_UNKNOWN
    daysLeft <= 0 -> CHIP_EXPIRES_TODAY
    else -> "$daysLeft$CHIP_DAYS_SUFFIX"
}

/** Sanity bridge kept pure so the screen cannot re-derive these words ad hoc. */
fun TrashRow.chipText(): String = daysChipText(daysLeft)
