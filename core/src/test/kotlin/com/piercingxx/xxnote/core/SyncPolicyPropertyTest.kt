package com.piercingxx.xxnote.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R5 — the §6 invariant as a property over generated inputs (todo rule #2,
 * design.md §6/§16): **no verdict reduces recoverable bytes**. Seeded
 * randomness only (core/README.md house rules); no wall clock anywhere.
 *
 * Model, stated precisely:
 *
 *  * Holders of a body *value* are the snapshots whose body equals it among
 *    {base, local, remote}. A value held by exactly one side AND not anchored
 *    to the shared ancestor (`base == null || value != base.body`) is
 *    one-sided work — exactly design §16's "what only one side held". Content
 *    identical to base is the common ancestor echoed back by a clean side, so
 *    neither side uniquely holds it; the base snapshot itself is engine state,
 *    not a side.
 *  * Recoverable-after is modeled conservatively per verdict — what a
 *    SyncEngine applying the verdict could still hand back to the user:
 *      - Nothing/Trash/Resurrect/Replan: nothing is destroyed. Trash MOVES the
 *        live copy into `.xxnote/trash/` retaining its bytes (D9); Resurrect
 *        restores without unlinking the other copy; Replan writes nothing.
 *        After-set = all copies that existed before (live + trashed).
 *      - Pull: the mirror is overwritten (rows 1/5 reach Pull only when local
 *        is absent or clean) → remote live body + retained trashed copies.
 *      - Push: the remote live file is overwritten (rows 2/4 reach Push only
 *        when remote is absent or clean) → local body + retained trashed
 *        copies.
 *      - Merge: row 6 modeled by actually invoking Diff3.merge(base, local,
 *        remote) — clean → the merged body ships (one-sided work survives AS
 *        LINES of it; asserted at line level in [no_verdict_reduces_recoverable_bytes]):
 *        every line uniquely held by one side — present on a side, absent
 *        from base — must appear in the merged output. Conflicted → the
 *        engine forks on refusal rather than dropping either side (§6 row 6)
 *        → {local.body, remote.body}.
 *      - Fork: two visible notes → {local.body, remote.body}.
 *      - Trashed copies retain their bodies through EVERY verdict (D9: trash
 *        never unlinks), including through Pull/Push on the other side.
 *
 * Assertions per triple: every one-sided work value before the verdict remains
 * in the recoverable set after. If a verdict fails this, the POLICY is wrong —
 * the property is law and is never relaxed.
 */
class SyncPolicyPropertyTest {

    private val rng = Random(0xFEEEFEEE)

    /** Small pool of distinct line-strings keeps body overlap controllable. */
    private val linePool = listOf(
        "# Morning pages",
        "The quick brown fox jumps over the lazy dog.",
        "- [ ] water the ferns",
        "- [x] wind the kitchen clock",
        "> A pulled quote worth keeping around.",
        "Second paragraph, edited independently on both sides.",
        "```kotlin\nval answer = 41 + 1\n```",
        "Trailing line that survives most merges.",
    )

    private fun randomBody(): String =
        (1..rng.nextInt(1, linePool.size + 1))
            .map { linePool[rng.nextInt(linePool.size)] }
            .distinct()
            .joinToString("\n")

    /** Invariant-respecting side generator: trashed ⇒ body != null. */
    private fun randomSide(absentP: Double, trashedP: Double): NoteState =
        if (rng.nextDouble() < absentP) NoteState(body = null, trashed = false)
        else NoteState(body = randomBody(), trashed = rng.nextDouble() < trashedP)

    private fun holdersOf(value: String, base: BaseSnapshot?, local: NoteState, remote: NoteState): Int =
        listOfNotNull(base?.body, local.body, remote.body).count { it == value }

    /** One-sided work: held by exactly one snapshot-side and off the ancestor. */
    private fun oneSidedWork(base: BaseSnapshot?, local: NoteState, remote: NoteState): Set<String> =
        setOfNotNull(local.body, remote.body)
            .filterTo(mutableSetOf()) { v ->
                holdersOf(v, base, local, remote) == 1 && (base == null || v != base.body)
            }

    /** D9: a trashed copy keeps its file — and therefore its bytes — forever. */
    private fun trashRetained(local: NoteState, remote: NoteState): Set<String> = buildSet {
        if (local.trashed) local.body?.let(::add)
        if (remote.trashed) remote.body?.let(::add)
    }

    private fun liveCopy(side: NoteState): Set<String> =
        if (side.present && !side.trashed) setOfNotNull(side.body) else emptySet()

    private fun allCopies(base: BaseSnapshot?, local: NoteState, remote: NoteState): Set<String> =
        liveCopy(local) + liveCopy(remote) + trashRetained(local, remote)

    private fun linesOf(body: String?): List<String> = body.orEmpty().split("\n")

    private fun recoverableAfter(
        verdict: Verdict,
        base: BaseSnapshot?,
        local: NoteState,
        remote: NoteState,
    ): Set<String> {
        val kept = trashRetained(local, remote)
        return when (verdict) {
            Verdict.Nothing,
            Verdict.Trash,
            Verdict.Resurrect,
            Verdict.Replan,
            -> allCopies(base, local, remote)

            Verdict.Pull -> liveCopy(remote) + kept
            Verdict.Push -> liveCopy(local) + kept

            // Row 6 modeled for real (M1): the engine runs Diff3.merge over
            // the three bodies. Clean → the merged body is what ships;
            // Conflicted → refusal forks and both sides survive untouched.
            Verdict.Merge -> when (val outcome =
                Diff3.merge(linesOf(base?.body), linesOf(local.body), linesOf(remote.body))
            ) {
                is Diff3Result.Merged -> setOf(outcome.lines.joinToString("\n")) + kept
                is Diff3Result.Conflicted -> liveCopy(local) + liveCopy(remote) + kept
            }

            Verdict.Fork -> liveCopy(local) + liveCopy(remote) + kept
        }
    }

    /**
     * Row 6 survival, asserted against the real merger (M1): run
     * [Diff3.merge] over the three bodies exactly as the engine will.
     *
     * Clean → one-sided work survives AS LINES: every line uniquely held by
     * one side (present on local/remote, absent from base) must appear in
     * the merged output — diff3 resolves such lines only out of their
     * holder's chunk, so an absence would mean the merge dropped work.
     * Conflicted → refusal forks, keeping {local.body, remote.body}, so the
     * whole-body values stay in the recoverable set.
     */
    private fun assertCleanMergeKeepsUniquelyHeldLines(
        base: BaseSnapshot?,
        local: NoteState,
        remote: NoteState,
        mustSurvive: Set<String>,
    ) {
        val outcome =
            Diff3.merge(linesOf(base?.body), linesOf(local.body), linesOf(remote.body))
        when (outcome) {
            is Diff3Result.Merged -> {
                val surviving = outcome.lines.toSet()
                val anchor = linesOf(base?.body).toSet()
                val uniquelyHeld = (linesOf(local.body) + linesOf(remote.body))
                    .filterTo(mutableSetOf()) { it !in anchor }
                val dropped = uniquelyHeld - surviving
                assertTrue(
                    dropped.isEmpty(),
                    "clean Merge dropped uniquely-held lines $dropped\n" +
                        "merged=${outcome.lines}\n" +
                        "base=$base\nlocal=$local\nremote=$remote",
                )
            }

            is Diff3Result.Conflicted -> {
                val lost = mustSurvive -
                    (liveCopy(local) + liveCopy(remote) + trashRetained(local, remote))
                assertTrue(
                    lost.isEmpty(),
                    "Conflicted Merge loses one-sided work $lost\n" +
                        "base=$base\nlocal=$local\nremote=$remote",
                )
            }
        }
    }

    @Test
    fun no_verdict_reduces_recoverable_bytes() {
        val seen = mutableMapOf<Verdict, Int>()
        repeat(5000) {
            val base = if (rng.nextDouble() < 0.35) null else BaseSnapshot(randomBody())
            val local = randomSide(absentP = 0.30, trashedP = 0.25)
            val remote = randomSide(absentP = 0.30, trashedP = 0.20)

            // Totality: decide() must return for every representable triple.
            val verdict = SyncPolicy.decide(base, local, remote)
            seen.merge(verdict, 1, Int::plus)

            val mustSurvive = oneSidedWork(base, local, remote)
            if (verdict == Verdict.Merge) {
                assertCleanMergeKeepsUniquelyHeldLines(base, local, remote, mustSurvive)
            } else {
                val after = recoverableAfter(verdict, base, local, remote)
                val lost = mustSurvive - after
                assertTrue(
                    lost.isEmpty(),
                    "Verdict $verdict loses one-sided work $lost\n" +
                        "base=$base\nlocal=$local\nremote=$remote",
                )
            }
        }
        // Every verdict reachable from decide() itself was exercised by the stream;
        // Replan is engine-level and covered by the grid below.
        val decideReachable = Verdict.entries.filterNot { it == Verdict.Replan }.toSet()
        assertTrue(
            seen.keys.containsAll(decideReachable),
            "random stream failed to exercise: ${decideReachable - seen.keys}",
        )
    }

    @Test
    fun dense_grid_covers_all_eight_verdicts_without_throwing() {
        val bases = listOf(null, BaseSnapshot("shared ancestor\n"), BaseSnapshot("another ancestor\n"))
        val sides = listOf(
            NoteState(body = null, trashed = false),
            NoteState(body = "state zero\n", trashed = false),
            NoteState(body = "state one\n", trashed = false),
            NoteState(body = "state two\n", trashed = false),
            NoteState(body = "trashed zero\n", trashed = true),
            NoteState(body = "trashed two\n", trashed = true),
        )
        val observed = mutableSetOf<Verdict>()
        for (b in bases) for (l in sides) for (r in sides) {
            observed += SyncPolicy.decide(b, l, r) // any throw fails the test
        }
        for (rounds in 0..3) observed += SyncPolicy.decidePushRejection(rounds)
        val all = Verdict.entries.toSet()
        assertTrue(
            observed == all,
            "grid missed: ${all - observed}",
        )
    }
}
