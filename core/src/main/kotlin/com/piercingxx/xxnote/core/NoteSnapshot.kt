package com.piercingxx.xxnote.core

/**
 * One side's view of a note. A trashed-but-present local note carries its
 * body so a resurrect can keep it (rows 8, 10). Pure data — imports nothing
 * from `android.*`.
 */
data class NoteState(
    /**
     * WHOLE-FILE text — YAML frontmatter block plus Markdown body, exactly
     * the bytes of the .md file. Metadata-only edits therefore dirty the
     * snapshot. Null means the note is absent on this side.
     */
    val body: String? = null,
    val trashed: Boolean = false,
) {
    /** False means the note does not exist in the live vault on this side. */
    val present: Boolean get() = body != null
}

/**
 * The last-agreed snapshot for a note (D7) — the entire reason three-way
 * merge is possible. [etag] is null when ETags proved unusable in WS0 and
 * the §4.2 fallback applies.
 */
data class BaseSnapshot(
    /**
     * WHOLE-FILE text — YAML frontmatter block plus Markdown body, exactly
     * the bytes of the .md file. Metadata-only edits therefore dirty the
     * snapshot.
     */
    val body: String,
    val etag: String? = null,
)
