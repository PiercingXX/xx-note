package com.piercingxx.xxnote.core

import kotlin.math.max
import kotlin.math.min

/**
 * Outcome of the §7.1 item-wise checklist merge.
 */
sealed interface ChecklistMergeResult {

    /** A cleanly merged body; re-parses and re-renders stably. */
    data class Merged(val body: String) : ChecklistMergeResult

    /**
     * Ambiguity the heuristic must not guess through (§7.1): same-item text
     * edited differently on both sides, or an item pairing that ties within
     * [ChecklistMerge.AMBIGUITY_EPSILON]. Forks the note, never the item.
     */
    data object Fork : ChecklistMergeResult
}

/**
 * Item-wise three-way merge for `type: checklist` notes (design D18, §7.1;
 * todo rule #8). The caller checks `type: checklist` on all three snapshots;
 * this object owns the body merge.
 *
 * Each contiguous GFM task-list block ([TaskList]) merges by ITEM identity,
 * never by lines — two sides ticking different items both look like "a line
 * vanished, a line appeared" to diff3. Per item:
 *
 *  - Checked state is a three-way boolean: whichever side moved away from
 *    base wins; if both moved they necessarily moved the same way, so a
 *    checkbox can NEVER conflict (the whole reason D18 is safe).
 *  - Added on one side → kept; removed on one side while untouched on the
 *    other → dropped; removed on one side while edited on the other → the
 *    edit wins (D10 at item level).
 *  - Text edited differently on both sides → [ChecklistMergeResult.Fork]
 *    (the only content forking case).
 *
 * Item identity runs in three steps over normalized text (trim + collapse
 * whitespace, otherwise case-sensitive): (1) items group by exact normalized
 * text; (2) within each exact-text group the k-th occurrences pair
 * positionally base↔local and base↔remote — first "milk" with first "milk",
 * second with second — so duplicate item texts can never fork a concurrent
 * tick (D18), extras in a side's group are adds, and missing ones are
 * removes; (3) only leftovers that had NO exact-group partner pair by best
 * [similarity] at or above [SIMILARITY_THRESHOLD], with a Winkler-style
 * common-prefix bonus so append-style edits (`coffee` → `coffee, the dark
 * one`) still pair — pure length-normalized Levenshtein punishes additive
 * growth and would silently drop ticks (R5). Only this fuzzy pass consults
 * [AMBIGUITY_EPSILON]: an ambiguous pairing forks rather than guesses. No
 * hidden identifiers are written into the file to make this easier (§7.1) —
 * the heuristic is the price of a clean file.
 *
 * Ordering follows D19: the merged sequence preserves base-relative item
 * order with additions appended in the side-order they appear. No
 * sort-to-bottom happens here — partition-by-checked-state is an editor
 * save-time concern, and sync never reorders.
 *
 * Prose and non-block lines pass through untouched when the sides agree or
 * only one side touched them; multiple blocks merge independently.
 */
object ChecklistMerge {

    /**
     * Minimum [similarity] for two leftover items to pair as one identity.
     * Below it the items are genuinely different — an add plus a remove.
     * 0.6 pairs typos, retypes, and append-style edits while keeping
     * wholesale replacements ("buy milk" vs "call dentist") apart.
     */
    const val SIMILARITY_THRESHOLD = 0.6

    /**
     * Similarity slack for ambiguity: a candidate pairing is contested — and
     * forks instead of pairing — when another unused candidate touching
     * either of its endpoints scores within this much of it. 0.05 means a
     * near-tie refuses to guess which base item a leftover succeeded.
     */
    const val AMBIGUITY_EPSILON = 0.05

    private const val PREFIX_BONUS_WEIGHT = 0.1
    private const val PREFIX_BONUS_MAX_CHARS = 5

    /**
     * Merge three checklist bodies. Precondition: `type: checklist` holds on
     * all three snapshots (caller checks §7.1); this function sees bodies
     * only.
     */
    fun merge(base: String, local: String, remote: String): ChecklistMergeResult {
        val baseSegments = TaskList.split(base)
        val localSegments = TaskList.split(local)
        val remoteSegments = TaskList.split(remote)

        val merged = mutableListOf<Segment>()
        for (i in 0 until maxOf(baseSegments.size, localSegments.size, remoteSegments.size)) {
            // A trailing run of blank lines is a newline artifact, not
            // content: a body that merely ends with \n reads as absent there.
            val b = withoutTrailingArtifact(baseSegments, i)
            val l = withoutTrailingArtifact(localSegments, i)
            val r = withoutTrailingArtifact(remoteSegments, i)
            when (val piece = resolvePosition(b, l, r)) {
                is Piece.Keep -> merged += piece.segment
                Piece.Drop -> Unit
                Piece.Fork -> return ChecklistMergeResult.Fork
            }
        }
        if (merged.isNotEmpty() &&
            (base.endsWith("\n") || local.endsWith("\n") || remote.endsWith("\n"))
        ) {
            merged += Segment.Text(listOf(""))
        }
        return ChecklistMergeResult.Merged(TaskList.render(merged))
    }

    /**
     * Normalized Levenshtein similarity with a Winkler-style common-prefix
     * bonus: `1 - distance/max(len)` plus up to five shared leading
     * characters at 0.1 each, clamped to `[0, 1]`. The bonus exists because
     * note-item edits are overwhelmingly additive ("coffee" → "coffee, the
     * dark one"), which plain length normalization scores as a near-total
     * rewrite; see [SIMILARITY_THRESHOLD].
     *
     * Public because the threshold's meaning is defined in its terms and
     * §16 pins the metric with tests.
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) {
            return if (a.isEmpty() && b.isEmpty()) 1.0 else 0.0
        }
        var prefix = 0
        while (prefix < PREFIX_BONUS_MAX_CHARS &&
            prefix < a.length && prefix < b.length && a[prefix] == b[prefix]
        ) {
            prefix++
        }
        val base = 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
        return (base + prefix * PREFIX_BONUS_WEIGHT).coerceIn(0.0, 1.0)
    }

    /** Identity normalization: trim ends, collapse internal whitespace runs. Case-sensitive. */
    internal fun normalize(text: String): String = text.replace(WHITESPACE_RUN, " ").trim()

    private val WHITESPACE_RUN = Regex("\\s+")

    // ------------------------------------------------------------------
    // Document assembly
    //
    // Segments align positionally across the three snapshots. Whole-segment
    // disagreements resolve toward the changed side; a block-level
    // disagreement always descends into item-wise merging so per-item rules
    // (edit outranks remove, checkbox never conflicts) decide, even when one
    // side removed the entire block. Prose edited differently on both sides
    // is prose-conflict territory and forks rather than picks a winner.
    // ------------------------------------------------------------------

    private sealed interface Piece {
        data class Keep(val segment: Segment) : Piece
        data object Drop : Piece
        data object Fork : Piece
    }

    private fun withoutTrailingArtifact(segments: List<Segment>, index: Int): Segment? {
        if (index >= segments.size) return null
        val segment = segments[index]
        val isLast = index == segments.lastIndex
        return if (isLast && segment is Segment.Text && segment.lines.all { it.isBlank() }) {
            null
        } else {
            segment
        }
    }

    private fun resolvePosition(b: Segment?, l: Segment?, r: Segment?): Piece {
        if (l == null && r == null) return Piece.Drop
        if (l == null) return oneSideAbsent(b, r!!)
        if (r == null) return oneSideAbsent(b, l)

        if (sameSegment(l, r)) return Piece.Keep(l)
        if (b != null && sameSegment(l, b)) return Piece.Keep(r)
        if (b != null && sameSegment(r, b)) return Piece.Keep(l)

        val blockish = (b == null || b is Segment.Block) &&
            l is Segment.Block && r is Segment.Block
        if (blockish) {
            val outcome = mergeBlocks(
                (b as? Segment.Block)?.block,
                l.block,
                r.block,
            )
            return when (outcome) {
                is BlockOutcome.Ok ->
                    if (outcome.items.isEmpty()) Piece.Drop
                    else Piece.Keep(Segment.Block(TaskBlock(outcome.items)))
                BlockOutcome.Ambiguous -> Piece.Fork
            }
        }
        return Piece.Fork
    }

    private fun oneSideAbsent(b: Segment?, present: Segment): Piece {
        if (b != null && sameSegment(present, b)) return Piece.Drop
        if (present is Segment.Block) {
            val outcome = mergeBlocks((b as? Segment.Block)?.block, present.block, emptyBlock())
            return when (outcome) {
                is BlockOutcome.Ok ->
                    if (outcome.items.isEmpty()) Piece.Drop
                    else Piece.Keep(Segment.Block(TaskBlock(outcome.items)))
                BlockOutcome.Ambiguous -> Piece.Fork
            }
        }
        return Piece.Keep(present)
    }

    private fun emptyBlock(): TaskBlock = TaskBlock(emptyList())

    /** Structural equality, ignoring raw bytes: layout fields plus normalized text. */
    private fun sameSegment(a: Segment?, b: Segment?): Boolean {
        if (a == null || b == null) return false
        if (a is Segment.Text && b is Segment.Text) return a.lines == b.lines
        if (a is Segment.Block && b is Segment.Block) {
            val left = a.block.items
            val right = b.block.items
            return left.size == right.size &&
                left.zip(right).all { (x, y) ->
                    x.indent == y.indent &&
                        x.marker == y.marker &&
                        x.checked == y.checked &&
                        normalize(x.text) == normalize(y.text)
                }
        }
        return false
    }

    // ------------------------------------------------------------------
    // Item-wise block merge (§7.1)
    // ------------------------------------------------------------------

    private sealed interface BlockOutcome {
        data class Ok(val items: List<TaskItem>) : BlockOutcome
        data object Ambiguous : BlockOutcome
    }

    /** One item identity across the three snapshots; any slot may be empty. */
    private class Group(
        val base: TaskItem?,
        val local: TaskItem?,
        val remote: TaskItem?,
    )

    private class SideMatch(
        val byBaseIndex: MutableMap<Int, TaskItem> = mutableMapOf(),
        val unmatched: MutableList<Int> = mutableListOf(),
    )

    private fun mergeBlocks(base: TaskBlock?, local: TaskBlock?, remote: TaskBlock?): BlockOutcome {
        val baseItems = base?.items.orEmpty()
        val localItems = local?.items.orEmpty()
        val remoteItems = remote?.items.orEmpty()

        // Identity matching (§7.1), three steps: exact normalized text
        // groups pair k-th occurrences positionally between base and the
        // side; only leftovers with no exact-group partner go on to the
        // fuzzy [similarity] pass. Joining the two sides through the shared
        // base is what lets one identity carry an edit on one side and a
        // removal on the other — and positional pairing inside a duplicate-
        // text group is what keeps concurrent ticks on identical items from
        // forking (D18).
        val localMatch = matchSide(localItems, baseItems) ?: return BlockOutcome.Ambiguous
        val remoteMatch = matchSide(remoteItems, baseItems) ?: return BlockOutcome.Ambiguous

        val groups = baseItems.mapIndexed { index, item ->
            Group(item, localMatch.byBaseIndex[index], remoteMatch.byBaseIndex[index])
        }

        // Additions: identical normalized text added on both sides coalesces
        // into one identity (checked state ORs toward visible); everything
        // else stays its own addition. Local adds land before remote adds.
        val additions = mutableListOf<Group>()
        val freeRemote = remoteMatch.unmatched.toMutableList()
        for (li in localMatch.unmatched) {
            val rn = normalize(localItems[li].text)
            val ri = freeRemote.indexOfFirst {
                normalize(remoteItems[it].text) == rn
            }
            if (ri >= 0) {
                additions += Group(null, localItems[li], remoteItems[freeRemote.removeAt(ri)])
            } else {
                additions += Group(null, localItems[li], null)
            }
        }
        for (ri in freeRemote) {
            additions += Group(null, null, remoteItems[ri])
        }

        // Resolution, then D19 ordering: base-relative sequence first,
        // additions appended in side-order. No sorting anywhere.
        val resolved = mutableListOf<TaskItem>()
        for (group in groups) {
            when (val item = resolveGroup(group)) {
                is ItemResolution.Keep -> resolved += item.item
                ItemResolution.Drop -> Unit
                ItemResolution.Fork -> return BlockOutcome.Ambiguous
            }
        }
        for (group in additions) {
            when (val item = resolveGroup(group)) {
                is ItemResolution.Keep -> resolved += item.item
                ItemResolution.Drop -> Unit
                ItemResolution.Fork -> return BlockOutcome.Ambiguous
            }
        }
        return BlockOutcome.Ok(resolved)
    }

    private sealed interface ItemResolution {
        data class Keep(val item: TaskItem) : ItemResolution
        data object Drop : ItemResolution
        data object Fork : ItemResolution
    }

    private fun resolveGroup(group: Group): ItemResolution {
        val base = group.base
        val local = group.local
        val remote = group.remote

        if (base == null) {
            // Added after base. Identical double-adds coalesce earlier, so
            // at most one side's item survives here — but keep the OR rule
            // for defense in depth: a checkbox never loses a tick.
            val source = local ?: remote ?: return ItemResolution.Drop
            val checked = (local?.checked ?: false) || (remote?.checked ?: false)
            return ItemResolution.Keep(withChecked(source, checked))
        }

        val localPresent = local != null
        val remotePresent = remote != null
        if (!localPresent && !remotePresent) return ItemResolution.Drop

        if (localPresent != remotePresent) {
            // Removed on one side. Untouched on the survivor → removal wins;
            // touched (text or tick) → the edit wins (D10 at item level).
            val survivor = local ?: remote!!
            val touched =
                normalize(survivor.text) != normalize(base.text) ||
                    survivor.checked != base.checked
            return if (touched) ItemResolution.Keep(survivor) else ItemResolution.Drop
        }

        // Present on both sides.
        //
        // Checked: three-way boolean — whichever side moved away from base
        // wins; both moving means both moved the same way (D18). A checkbox
        // never conflicts.
        val checked = if (local!!.checked == base.checked) remote!!.checked else local.checked

        // Text: whichever side moved away from base wins; both moved, and to
        // different texts, is THE forking case (§7.1).
        val bn = normalize(base.text)
        val ln = normalize(local.text)
        val rn = normalize(remote!!.text)
        val winner = when {
            ln == bn && rn == bn -> base
            ln == bn -> remote
            rn == bn -> local
            ln == rn -> local
            else -> return ItemResolution.Fork
        }
        return ItemResolution.Keep(withChecked(winner, checked))
    }

    /**
     * Match one side against the base pool in three steps. First, items
     * group by exact normalized text; within each group shared with base the
     * k-th occurrences pair positionally — deterministic by construction,
     * and immune to duplicate texts (two "milk"s pair first-to-first and
     * second-to-second on both sides). Second, extras in a side's shared
     * group stay unmatched: they are adds; base items left over in their own
     * group get no partner: they are removes. Third, only side items whose
     * exact-text group has no base counterpart at all go through greedy
     * best-score fuzzy matching against base items not yet consumed — ties
     * or contested pairings within [AMBIGUITY_EPSILON] fork rather than
     * guess (§7.1). Deterministic: candidates order by score descending,
     * then side index, then base index.
     */
    private fun matchSide(side: List<TaskItem>, base: List<TaskItem>): SideMatch? {
        class Candidate(val sideIndex: Int, val baseIndex: Int, val score: Double)

        // Steps 1–2: positional pairing inside each exact-text group.
        val baseGroups = LinkedHashMap<String, MutableList<Int>>()
        for ((bi, b) in base.withIndex()) {
            baseGroups.getOrPut(normalize(b.text)) { mutableListOf() }.add(bi)
        }
        val match = SideMatch()
        val matchedSide = mutableSetOf<Int>()
        val usedBase = mutableSetOf<Int>()
        val sideGroups = LinkedHashMap<String, MutableList<Int>>()
        for ((si, s) in side.withIndex()) {
            sideGroups.getOrPut(normalize(s.text)) { mutableListOf() }.add(si)
        }
        for ((text, sideGroup) in sideGroups) {
            val baseGroup = baseGroups[text] ?: continue
            repeat(minOf(sideGroup.size, baseGroup.size)) { k ->
                matchedSide += sideGroup[k]
                usedBase += baseGroup[k]
                match.byBaseIndex[baseGroup[k]] = side[sideGroup[k]]
            }
        }

        // Step 3: fuzzy pass over leftovers that had NO exact-group partner.
        val candidates = mutableListOf<Candidate>()
        for ((si, s) in side.withIndex()) {
            if (si in matchedSide) continue
            val sn = normalize(s.text)
            for ((bi, b) in base.withIndex()) {
                if (bi in usedBase) continue
                val score = similarity(sn, normalize(b.text))
                if (score >= SIMILARITY_THRESHOLD) candidates += Candidate(si, bi, score)
            }
        }
        candidates.sortWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.sideIndex }
                .thenBy { it.baseIndex },
        )

        for (candidate in candidates) {
            if (candidate.sideIndex in matchedSide || candidate.baseIndex in usedBase) continue
            val contested = candidates.any { other ->
                other !== candidate &&
                    other.sideIndex !in matchedSide &&
                    other.baseIndex !in usedBase &&
                    (other.sideIndex == candidate.sideIndex ||
                        other.baseIndex == candidate.baseIndex) &&
                    other.score >= candidate.score - AMBIGUITY_EPSILON
            }
            if (contested) return null
            matchedSide += candidate.sideIndex
            usedBase += candidate.baseIndex
            match.byBaseIndex[candidate.baseIndex] = side[candidate.sideIndex]
        }
        for (si in side.indices) {
            if (si !in matchedSide) match.unmatched += si
        }
        return match
    }

    /**
     * Re-emit [item] with [checked] state. Only the single character inside
     * the brackets changes; the rest of the line stays byte-for-byte.
     */
    private fun withChecked(item: TaskItem, checked: Boolean): TaskItem {
        if (item.checked == checked) return item
        val bracket = item.rawLine.indexOf('[', item.indent + item.marker.length)
        if (bracket < 0 || bracket + 1 >= item.rawLine.length) return item
        val rewritten = item.rawLine
            .substring(0, bracket + 1) +
            (if (checked) "x" else " ") +
            item.rawLine.substring(bracket + 2)
        return item.copy(rawLine = rewritten, checked = checked)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(previous[j] + 1, min(current[j - 1] + 1, substitution))
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
