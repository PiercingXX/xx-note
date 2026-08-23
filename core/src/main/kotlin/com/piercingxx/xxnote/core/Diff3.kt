package com.piercingxx.xxnote.core

/**
 * Outcome of merging [Diff3.merge]'s three bodies (design §6 row 6). Refusal
 * is first-class (§5): [Conflicted] is a normal return value, never an
 * exception — a merge engine that never refuses is one that loses text, and
 * MergeEngine may not resolve by silently picking a side.
 */
sealed interface Diff3Result {
    /** Every hunk merged cleanly; [lines] is the merged body. */
    data class Merged(val lines: List<String>) : Diff3Result

    /** At least one hunk conflicts. Refusal is a first-class outcome, not an error (§5). */
    data class Conflicted(val hunks: List<Hunk>) : Diff3Result
}

/**
 * One conflicting region: what base said, what each side made of it, plus up
 * to [Diff3.CONTEXT_LINES] agreed lines on either side for display in the
 * Resolve sheet (§7).
 */
data class Hunk(
    val baseLines: List<String>,
    val localLines: List<String>,
    val remoteLines: List<String>,
    val contextBefore: List<String>,
    val contextAfter: List<String>,
)

/**
 * Line-based three-way merge — classic diff3, implemented from the published
 * algorithm description (design §1, "Git" row). Cleanroom: no diff library,
 * no `android.*`, no external dependency.
 *
 * **Alignment.** An O(n·m) longest-common-subsequence dynamic program
 * (note-sized inputs make this the simple, obviously-correct choice) aligns
 * base↔local and base↔remote. Ties break deterministically, so identical
 * inputs always produce identical results.
 *
 * **Stable-region policy (documented decision).** A base line aligned on
 * BOTH sides is a *sync point*, and every sync point is a split boundary:
 * classic diff3 splits on ANY common line, with no minimum-run requirement
 * (§16). Lines one side inserted at a gap, and runs of base lines either
 * side departed from, form candidate chunks between sync points.
 *
 * **Chunk resolution.** local == base → take remote; remote == base → take
 * local; local == remote → take once; otherwise the chunk conflicts.
 * Consequently: delete-vs-edit on the same region refuses (never a silent
 * pick); appends by both sides at the same anchor (notably the very end of
 * the body, and notes built from an empty base) share one empty-base chunk
 * and refuse unless identical; a whole-body rewrite with no surviving common
 * line becomes a single conflict spanning everything.
 */
object Diff3 {
    /**
     * Three-way merge. Returns [Diff3Result.Merged] when every chunk
     * resolves, otherwise [Diff3Result.Conflicted] carrying one [Hunk] per
     * conflicting chunk, in document order.
     */
    fun merge(base: List<String>, local: List<String>, remote: List<String>): Diff3Result {
        val (pieces, stable) = plan(base, local, remote)
        val out = mutableListOf<String>()
        val hunks = mutableListOf<Hunk>()
        for (piece in pieces) {
            when (piece) {
                is Piece.Stable -> out += piece.line
                is Piece.Region -> {
                    val resolved = resolve(piece)
                    if (resolved != null) {
                        out += resolved
                    } else {
                        hunks += Hunk(
                            baseLines = piece.baseLines,
                            localLines = piece.localLines,
                            remoteLines = piece.remoteLines,
                            contextBefore = context(stable, piece.stablePos, before = true),
                            contextAfter = context(stable, piece.stablePos, before = false),
                        )
                    }
                }
            }
        }
        return if (hunks.isEmpty()) Diff3Result.Merged(out) else Diff3Result.Conflicted(hunks)
    }

    /**
     * For the Resolve sheet (§7): merged text with conflict markers applied.
     * Clean chunks contribute their resolution; each conflicted chunk becomes:
     *
     * ```
     * <<<<<<< oursLabel
     * (local lines)
     * =======
     * (remote lines)
     * >>>>>>> theirsLabel
     * ```
     */
    fun mergeWithMarkers(
        base: List<String>,
        local: List<String>,
        remote: List<String>,
        oursLabel: String = "ours",
        theirsLabel: String = "theirs",
    ): List<String> {
        val (pieces, _) = plan(base, local, remote)
        val out = mutableListOf<String>()
        for (piece in pieces) {
            when (piece) {
                is Piece.Stable -> out += piece.line
                is Piece.Region -> {
                    val resolved = resolve(piece)
                    if (resolved != null) {
                        out += resolved
                    } else {
                        out += "<<<<<<< $oursLabel"
                        out += piece.localLines
                        out += "======="
                        out += piece.remoteLines
                        out += ">>>>>>> $theirsLabel"
                    }
                }
            }
        }
        return out
    }

    /** Agreed lines shown before/after a [Hunk]. Git-style default. */
    private const val CONTEXT_LINES = 3

    /**
     * Alignment of [base] against [other]: `result[i]` is the index in
     * [other] matched to base line `i`, or -1. Suffix-table LCS with a
     * deterministic greedy walk (prefer diagonal, then advancing base).
     */
    private fun align(base: List<String>, other: List<String>): IntArray {
        val n = base.size
        val m = other.size
        val suf = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                suf[i][j] = if (base[i] == other[j]) suf[i + 1][j + 1] + 1
                else maxOf(suf[i + 1][j], suf[i][j + 1])
            }
        }
        val into = IntArray(n) { -1 }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                base[i] == other[j] -> { into[i] = j; i++; j++ }
                suf[i + 1][j] >= suf[i][j + 1] -> i++
                else -> j++
            }
        }
        return into
    }

    private sealed interface Piece {
        /** A line all three bodies agree on; emitted once. */
        data class Stable(val line: String) : Piece

        /** A candidate chunk between sync points. [stablePos] indexes the agreed-line stream. */
        data class Region(
            val baseLines: List<String>,
            val localLines: List<String>,
            val remoteLines: List<String>,
            val stablePos: Int,
        ) : Piece
    }

    /**
     * Split the three bodies into stable lines and candidate chunks.
     *
     * Every base index aligned on BOTH sides is a sync point; the segments
     * strictly between consecutive sync points (and before the first / after
     * the last) are candidate chunks. Each side's chunk range runs from just
     * past its previous sync match to just before its next one, so a side's
     * insertions and replacements land in exactly one chunk, with no
     * double-counting at either end of the body.
     */
    private fun plan(
        base: List<String>,
        local: List<String>,
        remote: List<String>,
    ): Pair<List<Piece>, List<String>> {
        val lm = align(base, local)
        val rm = align(base, remote)

        val pieces = mutableListOf<Piece>()
        val stable = mutableListOf<String>()

        fun region(bFrom: Int, bTo: Int, lFrom: Int, lTo: Int, rFrom: Int, rTo: Int) {
            val b = base.subList(bFrom, bTo)
            val l = local.subList(lFrom, lTo)
            val r = remote.subList(rFrom, rTo)
            if (b.isEmpty() && l.isEmpty() && r.isEmpty()) return
            pieces += Piece.Region(b.toList(), l.toList(), r.toList(), stable.size)
        }

        var pb = -1
        var pl = -1
        var pr = -1
        for (i in base.indices) {
            val li = lm[i]
            val ri = rm[i]
            if (li >= 0 && ri >= 0) {
                region(pb + 1, i, pl + 1, li, pr + 1, ri)
                pieces += Piece.Stable(base[i])
                stable += base[i]
                pb = i
                pl = li
                pr = ri
            }
        }
        region(pb + 1, base.size, pl + 1, local.size, pr + 1, remote.size)
        return pieces to stable
    }

    /** The winning lines for a chunk, or null when it genuinely conflicts. */
    private fun resolve(region: Piece.Region): List<String>? = when {
        region.localLines == region.baseLines -> region.remoteLines
        region.remoteLines == region.baseLines -> region.localLines
        region.localLines == region.remoteLines -> region.localLines
        else -> null
    }

    private fun context(stable: List<String>, pos: Int, before: Boolean): List<String> =
        if (before) {
            stable.subList(maxOf(0, pos - CONTEXT_LINES), pos).toList()
        } else {
            stable.subList(pos, minOf(stable.size, pos + CONTEXT_LINES)).toList()
        }
}
