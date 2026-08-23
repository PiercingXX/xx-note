package com.piercingxx.xxnote.ui.grid

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.NoteType
import com.piercingxx.xxnote.core.Segment
import com.piercingxx.xxnote.core.TaskList
import com.piercingxx.xxnote.ui.theme.JetBrainsMono
import com.piercingxx.xxnote.ui.theme.Tokens

/**
 * One grid card, fully derived from a note's whole-file text by the PURE
 * mapper [buildNoteCard] — no Room row is trusted for content (D1: file wins).
 */
data class NoteCard(
    val id: String,
    /** Frontmatter title, or the first non-empty body line (§8 fallback). Never null. */
    val title: String,
    /** Plain-text body preview, clipped at [PREVIEW_MAX_LINES] lines; "" when empty. */
    val preview: String,
    val pinned: Boolean,
    val type: NoteType,
    /** Checked task count across all task-list blocks of the body region. */
    val doneCount: Int,
    /** Total task count; 0 for non-checklists and empty checklists. */
    val totalCount: Int,
    val labels: List<String>,
    val tone: NoteTone,
    /**
     * `archived:` parsed leniently from frontmatter — absent, blank, or
     * unparseable reads as false (H1: the same does-less direction as
     * ArchiveFilter). Archived cards are excluded from the home grid, search
     * results, and label grids; the Archive screen keeps showing them.
     */
    val archived: Boolean,
    /** `updated:` parsed to epoch millis (0 when absent/unparseable) — sort key. */
    val updatedAtMillis: Long,
)

/** Grid preview clip depth (design §12 item 1: "clipped at six lines"). */
const val CARD_PREVIEW_LINES = 6

/**
 * PURE card mapper (unit-tested in NoteCardMapperTest): whole-file text →
 * [NoteCard]. Reads only core parsers ([Frontmatter], [TaskList]) plus the
 * pure [toneForColor]; never touches disk, Room, or Android.
 *
 * Checklist progress counts items across EVERY contiguous GFM task block of
 * the BODY region only (frontmatter never contains tasks); prose and blank
 * separators contribute nothing.
 */
fun buildNoteCard(id: String, wholeFileText: String): NoteCard {
    val doc = Frontmatter.parse(wholeFileText)
    val body = doc.bodyText
    val fallback = firstBodyLine(body)
    return NoteCard(
        id = id,
        title = doc.title?.takeIf { it.isNotBlank() } ?: fallback,
        preview = previewOf(body),
        pinned = doc.pinned ?: false,
        type = doc.type,
        doneCount = taskCounts(body).first,
        totalCount = taskCounts(body).second,
        labels = doc.labels,
        tone = toneForColor(doc.color),
        archived = doc.archived ?: false,
        updatedAtMillis = updatedMillisOf(doc.updated),
    )
}

/**
 * Capture-bar note template (§8, R1): a fresh frontmatter block carrying
 * exactly what capture knows — id, optional title, created/updated stamps,
 * and `type` only when checklist (absent `type:` already means `note`, which
 * does less to the file). Pure; tested via EditorSaveTest conventions.
 */
fun captureTemplate(id: String, title: String, type: NoteType, nowIso: String): String =
    Frontmatter.parse("").rewritten {
        this.id = id
        if (title.isNotBlank()) this.title = title
        this.created = nowIso
        this.updated = nowIso
        if (type == NoteType.CHECKLIST) this.type = NoteType.CHECKLIST
    } + "\n"

// ---- Mapper internals (pure) -----------------------------------------------

private fun firstBodyLine(body: String): String =
    body.split('\n')
        .map { plainLine(it).trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()

/**
 * Preview: body lines rendered as plain text — GFM checkbox prefixes and
 * heading markers stripped (inline emphasis is left verbatim; §12 promises
 * inline rendering in the EDITOR, not in the card), blanks dropped, clipped
 * at [CARD_PREVIEW_LINES].
 */
private fun previewOf(body: String): String =
    body.split('\n')
        .map { plainLine(it).trimEnd() }
        .filter { it.isNotBlank() }
        .take(CARD_PREVIEW_LINES)
        .joinToString("\n")

/** Strips one task-item checkbox prefix (`- [x] `, any marker/indent/case) or leading heading hashes. */
private fun plainLine(line: String): String {
    val withoutTask = TASK_PREFIX.replace(line, "")
    return if (withoutTask != line) withoutTask else HEADING_PREFIX.replace(line, "")
}

// Mirrors TaskList.TASK_ITEM's shape up to and including the checkbox + one space.
private val TASK_PREFIX = Regex("""^[ \t]*[-*+][ \t]+\[[ xX]\][ \t]?""")
private val HEADING_PREFIX = Regex("""^#{1,6}[ \t]+""")

private fun taskCounts(body: String): Pair<Int, Int> {
    var total = 0
    var done = 0
    for (segment in TaskList.split(body)) {
        if (segment !is Segment.Block) continue
        for (item in segment.block.items) {
            total++
            if (item.checked) done++
        }
    }
    return done to total
}

private fun updatedMillisOf(updated: String?): Long {
    val value = updated ?: return 0L
    return try {
        java.time.Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        0L
    }
}

// ---- Label chips (WS8, §12 item 1) -------------------------------------------

/** How many of a card's labels render before the "+N" overflow chip. */
const val MAX_CARD_LABEL_CHIPS = 3

/**
 * PURE: chip texts for one card — the first [maxVisible] labels verbatim,
 * then a single "+N" overflow chip when more exist. Unit-tested in
 * LabelChipOverflowTest.
 */
fun labelChipTexts(labels: List<String>, maxVisible: Int = MAX_CARD_LABEL_CHIPS): List<String> {
    if (labels.isEmpty() || maxVisible <= 0) return emptyList()
    if (labels.size <= maxVisible) return labels.toList()
    return labels.take(maxVisible) + "+${labels.size - maxVisible}"
}

/**
 * A card's label row: up to [MAX_CARD_LABEL_CHIPS] chips plus "+N" overflow
 * (JetBrains Mono 11sp White50, hairline border, §12.1 tracking). Shared by
 * the home grid and the label-filtered grid so chips render identically.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelChipRow(labels: List<String>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labelChipTexts(labels).forEach { chip ->
            Text(
                chip,
                modifier = Modifier
                    .border(1.dp, Tokens.White10, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.em,
                    color = Tokens.White50,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
