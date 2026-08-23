package com.piercingxx.xxnote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Design §16, Diff3 bullet: clean merges (disjoint edits, additions at both
 * ends, interleaved edits in one paragraph) and refusals (same line edited
 * both sides, delete-vs-edit on one line, whole-body rewrite). A refusal is
 * an asserted outcome, not a failure. All tests deterministic.
 */
class Diff3Test {

    private fun l(vararg lines: String) = lines.toList()

    // ---- clean merges -----------------------------------------------------

    @Test
    fun `disjoint edits in different paragraphs merge cleanly`() {
        val base = l("alpha paragraph", "", "beta paragraph")
        val local = l("ALPHA paragraph", "", "beta paragraph")
        val remote = l("alpha paragraph", "", "BETA paragraph")

        val result = Diff3.merge(base, local, remote)

        val merged = assertIs<Diff3Result.Merged>(result)
        assertEquals(l("ALPHA paragraph", "", "BETA paragraph"), merged.lines)
    }

    @Test
    fun `addition at top by one side and bottom by other merges cleanly`() {
        val base = l("middle line")
        val local = l("added at top", "middle line")
        val remote = l("middle line", "added at bottom")

        val merged = assertIs<Diff3Result.Merged>(Diff3.merge(base, local, remote))

        assertEquals(l("added at top", "middle line", "added at bottom"), merged.lines)
    }

    @Test
    fun `both sides appending different lines at the very end conflicts`() {
        // Appends share the same base anchor — the empty region after the last
        // common line. Classic diff3 refuses rather than concatenating.
        val base = l("shopping", "")
        val local = base + l("oat milk")
        val remote = base + l("rye bread")

        val conflicted = assertIs<Diff3Result.Conflicted>(Diff3.merge(base, local, remote))

        assertEquals(1, conflicted.hunks.size)
        val hunk = conflicted.hunks[0]
        assertEquals(emptyList(), hunk.baseLines)
        assertEquals(l("oat milk"), hunk.localLines)
        assertEquals(l("rye bread"), hunk.remoteLines)
    }

    @Test
    fun `identical edit on both sides is merged once`() {
        val base = l("intro", "status: draft", "outro")
        val local = l("intro", "status: ready", "outro")
        val remote = l("intro", "status: ready", "outro")

        val merged = assertIs<Diff3Result.Merged>(Diff3.merge(base, local, remote))

        assertEquals(l("intro", "status: ready", "outro"), merged.lines)
    }

    @Test
    fun `deletion on one side with other untouched applies the deletion`() {
        val base = l("keep me", "delete me", "keep me too")
        val local = l("keep me", "keep me too")
        val remote = base

        val merged = assertIs<Diff3Result.Merged>(Diff3.merge(base, local, remote))

        assertEquals(l("keep me", "keep me too"), merged.lines)
    }

    @Test
    fun `interleaved edits within one paragraph separated by a common line merge cleanly`() {
        // Stable-region policy: split on ANY common line — no minimum run.
        val base = l("first thought", "shared anchor", "second thought")
        val local = l("FIRST thought", "shared anchor", "second thought")
        val remote = l("first thought", "shared anchor", "SECOND thought")

        val merged = assertIs<Diff3Result.Merged>(Diff3.merge(base, local, remote))

        assertEquals(l("FIRST thought", "shared anchor", "SECOND thought"), merged.lines)
    }

    @Test
    fun `one-sided addition from empty base region merges cleanly`() {
        val base = emptyList<String>()
        val local = l("a fresh note")
        val remote = emptyList<String>()

        val merged = assertIs<Diff3Result.Merged>(Diff3.merge(base, local, remote))

        assertEquals(l("a fresh note"), merged.lines)
    }

    // ---- refusals: asserted outcomes, not failures (§5) --------------------

    @Test
    fun `same line edited differently on both sides conflicts`() {
        val base = l("title", "status: draft")
        val local = l("title", "status: ready")
        val remote = l("title", "status: reviewed")

        val conflicted = assertIs<Diff3Result.Conflicted>(Diff3.merge(base, local, remote))

        assertEquals(1, conflicted.hunks.size)
        assertEquals(l("status: draft"), conflicted.hunks[0].baseLines)
        assertEquals(l("status: ready"), conflicted.hunks[0].localLines)
        assertEquals(l("status: reviewed"), conflicted.hunks[0].remoteLines)
    }

    @Test
    fun `delete versus edit on the same line conflicts - never a silent pick`() {
        val base = l("keep", "victim line", "tail")
        val local = l("keep", "tail")
        val remote = l("keep", "VICTIM line edited", "tail")

        val conflicted = assertIs<Diff3Result.Conflicted>(Diff3.merge(base, local, remote))

        assertEquals(1, conflicted.hunks.size)
        assertEquals(l("victim line"), conflicted.hunks[0].baseLines)
        assertTrue(conflicted.hunks[0].localLines.isEmpty())
        assertEquals(l("VICTIM line edited"), conflicted.hunks[0].remoteLines)
    }

    @Test
    fun `whole-body rewrite on both sides is one conflicted hunk spanning everything`() {
        val base = l("old one", "old two", "old three")
        val local = l("new alpha", "new beta")
        val remote = l("alt gamma", "alt delta")

        val conflicted = assertIs<Diff3Result.Conflicted>(Diff3.merge(base, local, remote))

        assertEquals(1, conflicted.hunks.size)
        val hunk = conflicted.hunks[0]
        assertEquals(base, hunk.baseLines)
        assertEquals(local, hunk.localLines)
        assertEquals(remote, hunk.remoteLines)
        assertTrue(hunk.contextBefore.isEmpty())
        assertTrue(hunk.contextAfter.isEmpty())
    }

    @Test
    fun `both sides building different notes on an empty base conflicts`() {
        val conflicted = assertIs<Diff3Result.Conflicted>(
            Diff3.merge(emptyList(), l("# Note A", "body A"), l("# Note B", "body B")),
        )

        assertEquals(1, conflicted.hunks.size)
        assertEquals(emptyList(), conflicted.hunks[0].baseLines)
    }

    @Test
    fun `two independent conflicts yield two hunks in document order`() {
        val base = l("a1", "a2", "mid", "b1", "b2")
        val local = l("A1-local", "a2", "mid", "b1", "B2-local")
        val remote = l("A1-remote", "a2", "mid", "b1", "B2-remote")

        val conflicted = assertIs<Diff3Result.Conflicted>(Diff3.merge(base, local, remote))

        assertEquals(2, conflicted.hunks.size)
        assertEquals(l("a1"), conflicted.hunks[0].baseLines)
        assertEquals(l("b2"), conflicted.hunks[1].baseLines)
    }

    @Test
    fun `hunks carry up to three agreed context lines either side`() {
        val base = l("s1", "s2", "s3", "s4", "pivot", "t1", "t2", "t3", "t4")
        val local = l("s1", "s2", "s3", "s4", "LOCAL pivot", "t1", "t2", "t3", "t4")
        val remote = l("s1", "s2", "s3", "s4", "REMOTE pivot", "t1", "t2", "t3", "t4")

        val conflicted = assertIs<Diff3Result.Conflicted>(Diff3.merge(base, local, remote))

        val hunk = conflicted.hunks.single()
        assertEquals(l("s2", "s3", "s4"), hunk.contextBefore)
        assertEquals(l("t1", "t2", "t3"), hunk.contextAfter)
    }

    // ---- symmetry ----------------------------------------------------------

    @Test
    fun `conflict verdict is symmetric under swapping sides`() {
        data class Case(val base: List<String>, val a: List<String>, val b: List<String>)
        val cases = listOf(
            Case(l("x"), l("x-local"), l("x-remote")),
            Case(l("shopping", ""), l("shopping", "oat milk"), l("shopping", "rye bread")),
            Case(l("keep", "victim", "tail"), l("keep", "tail"), l("keep", "VICTIM", "tail")),
            Case(l("old"), l("brand new body"), l("completely different")),
            Case(emptyList(), l("note a"), l("note b")),
            Case(l("same", "lines"), l("same", "lines"), l("same", "lines")),
        )
        for (case in cases) {
            val ab = Diff3.merge(case.base, case.a, case.b)
            val ba = Diff3.merge(case.base, case.b, case.a)
            assertEquals(
                ab is Diff3Result.Conflicted,
                ba is Diff3Result.Conflicted,
                "symmetry broken for base=$case.base a vs b",
            )
        }
    }

    @Test
    fun `clean verdict stays clean under swapping sides`() {
        val base = l("middle line")
        val a = l("top", "middle line")
        val b = l("middle line", "bottom")

        assertFalse(Diff3.merge(base, a, b) is Diff3Result.Conflicted)
        assertFalse(Diff3.merge(base, b, a) is Diff3Result.Conflicted)
    }

    // ---- marker rendering ---------------------------------------------------

    @Test
    fun `markers follow the resolve-sheet template`() {
        val base = l("title", "status: draft")
        val local = l("title", "status: ready")
        val remote = l("title", "status: reviewed")

        val rendered = Diff3.mergeWithMarkers(base, local, remote)

        assertEquals(
            l(
                "title",
                "<<<<<<< ours",
                "status: ready",
                "=======",
                "status: reviewed",
                ">>>>>>> theirs",
            ),
            rendered,
        )
    }

    @Test
    fun `custom labels appear in markers`() {
        val rendered = Diff3.mergeWithMarkers(
            base = l("k"),
            local = l("phone"),
            remote = l("laptop"),
            oursLabel = "this device",
            theirsLabel = "nas",
        )

        assertEquals(
            l("<<<<<<< this device", "phone", "=======", "laptop", ">>>>>>> nas"),
            rendered,
        )
    }

    @Test
    fun `clean merge renders without any marker`() {
        val rendered = Diff3.mergeWithMarkers(
            base = l("middle line"),
            local = l("top", "middle line"),
            remote = l("middle line", "bottom"),
        )

        assertEquals(l("top", "middle line", "bottom"), rendered)
        assertFalse(rendered.any { it.startsWith("<<<<<") || it == "=======" || it.startsWith(">>>>>") })
    }
}
