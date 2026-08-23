package com.piercingxx.xxnote.ui.grid

import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.FrontmatterDocument

/**
 * PURE multi-select algebra over whole-file text (WS8; design §12 item 1:
 * long-press enters selection; pin, label, colour, archive, delete across a
 * selection). Mirrors [com.piercingxx.xxnote.ui.labels.LabelOps] exactly:
 * parse with [Frontmatter.parse], mutate through
 * `FrontmatterDocument.rewritten`, so unknown keys survive byte-for-byte
 * (§4.3), CRLF/LF is preserved per line, and an effective no-op returns the
 * input unchanged.
 *
 * Conservative ruling (same as LabelOps): a note without a WELL-FORMED
 * frontmatter block (none at all, or a malformed one) is SKIPPED — every
 * mutator here returns `null` instead of bytes. Live notes always carry an
 * `id:` block (§12 Import), so the null path only guards foreign or degraded
 * files; the caller counts skips and reports one plain-words notice.
 *
 * Batch edits never restamp `updated:` (ruling beyond spec): Keep's muscle
 * memory sorts by content edits, not metadata churn, and smaller byte deltas
 * make cleaner three-way merges (§6).
 */
object MultiSelectOps {

    /**
     * Sets `pinned: true`, or unpins. Unpinning removes the `pinned:` line a
     * `true` value had written and leaves both an absent key AND an explicit
     * `pinned: false` line untouched (byte-for-byte), so "unpin" does as
     * little to the file as the intent requires. Null when skipped.
     */
    fun applyPin(wholeFileText: String, pinned: Boolean): String? {
        val doc = Frontmatter.parse(wholeFileText)
        if (degraded(doc)) return null
        return doc.rewritten {
            this.pinned = if (pinned) true else if (doc.pinned == false) false else null
        }
    }

    /** Same contract as [applyPin] for the `archived:` key. Null when skipped. */
    fun applyArchive(wholeFileText: String, archived: Boolean): String? {
        val doc = Frontmatter.parse(wholeFileText)
        if (degraded(doc)) return null
        return doc.rewritten {
            this.archived = if (archived) true else if (doc.archived == false) false else null
        }
    }

    /**
     * Writes `color: [keepName]` — the picker passes canonical Keep names
     * ([canonicalColorFor]), so the value round-trips with other tools (D12).
     * A case-insensitively equal existing colour keeps its original spelling
     * (§8 case-preserving store). Null when skipped.
     */
    fun applyColor(wholeFileText: String, keepName: String): String? {
        val name = normalize(keepName, "colour")
        val doc = Frontmatter.parse(wholeFileText)
        if (degraded(doc)) return null
        if (doc.color?.trim()?.equals(name, ignoreCase = true) == true) return wholeFileText
        return doc.rewritten { color = name }
    }

    /**
     * Pure projection of the DELETE (→ trash) intent: stamps `trashedAt:` +
     * `updated:` with [stampIso] exactly as VaultStore.trash writes them, so
     * tests can pin the byte effect of a batch delete without a filesystem.
     * The AUTHORITATIVE path stays VaultStore.trash — only it moves the file
     * into `.xxnote/trash/` and rebuilds the row (D9); the grid never writes
     * a trashed stamp through plain write(). Re-stamping with the same iso is
     * idempotent; a different iso wins (last-stamp-wins, like the store).
     * Null when skipped.
     */
    fun applyTrash(wholeFileText: String, stampIso: String): String? {
        val iso = normalize(stampIso, "trash stamp")
        val doc = Frontmatter.parse(wholeFileText)
        if (degraded(doc)) return null
        return doc.rewritten {
            updated = iso
            trashedAt = iso
        }
    }

    private fun degraded(doc: FrontmatterDocument): Boolean =
        !doc.hasFrontmatter || doc.isMalformed

    private fun normalize(value: String, what: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "$what can't be empty" }
        require(!trimmed.contains('\n') && !trimmed.contains('\r')) {
            "$what can't contain line breaks"
        }
        return trimmed
    }
}

/**
 * Which whole-selection action the action bar asked for. One tap = one fold
 * per selected note, applied sequentially on Dispatchers.IO.
 */
sealed interface BatchAction {
    data class Pin(val pinned: Boolean) : BatchAction
    data class Archive(val archived: Boolean = true) : BatchAction

    /** Delete → trash via VaultStore.trash (file move authoritative, D9). */
    data object Trash : BatchAction
    data class Color(val keepName: String) : BatchAction
}

// ---- Selection state (pure reducer; SelectionReducerTest) -------------------

/** Long-press multi-select state over note ids. Empty [ids] ⇒ inactive. */
data class SelectionState(
    val active: Boolean = false,
    val ids: Set<String> = emptySet(),
)

sealed interface SelectionAction {
    /** Long-press: enter selection with this card, or add to an active one. */
    data class LongPress(val id: String) : SelectionAction

    /** Tap while selecting toggles membership; tapping never opens a note. */
    data class Tap(val id: String) : SelectionAction

    /** Explicit exit (✕ in the action bar); clears every selection bit. */
    data object Exit : SelectionAction
}

/**
 * Total reducer. Rulings: long-press on an ACTIVE selection adds the card
 * (Keep behaviour — no accidental reset); deselecting the last card exits
 * selection mode entirely rather than showing an empty action bar.
 */
fun reduceSelection(state: SelectionState, action: SelectionAction): SelectionState =
    when (action) {
        is SelectionAction.LongPress ->
            if (!state.active) SelectionState(active = true, ids = setOf(action.id))
            else state.copy(ids = state.ids + action.id)

        is SelectionAction.Tap ->
            if (!state.active) state
            else if (action.id in state.ids) {
                val rest = state.ids - action.id
                if (rest.isEmpty()) SelectionState() else state.copy(ids = rest)
            } else state.copy(ids = state.ids + action.id)

        SelectionAction.Exit -> SelectionState()
    }

// ---- Batch outcome wording ---------------------------------------------------

/**
 * The ONE plain-words notice for a finished batch that had failures (§15
 * voice: lowercase, middle dot, says what happened and what was kept).
 * Failures include vault write errors AND malformed-frontmatter skips — the
 * user needs one number, not two taxonomies.
 */
fun batchNotice(failed: Int, total: Int): String? {
    if (failed <= 0 || total <= 0) return null
    val changed = (total - failed).coerceAtLeast(0)
    return when {
        failed >= total && total == 1 ->
            "couldn't change that note — storage problem · nothing was changed"
        failed >= total ->
            "couldn't change any of the $total notes — storage problem · nothing was changed"
        else ->
            "changed $changed of $total notes · $failed failed — storage problem"
    }
}
