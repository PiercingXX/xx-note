package com.piercingxx.xxnote.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WS7 gate: the PURE editor save assembly and checkbox toggle (design §12
 * item 2, D18/D19/O3). Byte-level laws: only owned keys move, the sorter
 * applies iff `type: checklist`, a second save with the same clock stamp is
 * byte-stable, and a checkbox tap repositions nothing.
 */
class EditorSaveTest {

    // ---- Fixture ---------------------------------------------------------------

    private fun original(
        body: String,
        type: String? = null,
        title: String = "Old title",
        extraColor: Boolean = true,
    ): String = buildString {
        append("---\n")
        append("id: 01J9F2K3M4N5P6Q7R8S9T0V1W2\n")
        append("title: $title\n")
        append("created: 2026-08-20T09:00:00Z\n")
        append("updated: 2026-08-21T10:00:00Z\n")
        append("pinned: false\n")
        if (extraColor) append("color: basil\n")
        if (type != null) append("type: $type\n")
        append("plugin_note: keep me verbatim\n")
        append("---\n")
        append(body)
    }

    private fun save(
        text: String,
        title: String,
        body: String,
        now: String = "2026-08-23T12:00:00Z",
    ): String = buildSaveText(text, title, body, now)

    private fun bodyOf(text: String): String = com.piercingxx.xxnote.core.Frontmatter.parse(text).bodyText

    // ---- Save: frontmatter discipline ---------------------------------------------

    @Test
    fun titleRewrittenAndUpdatedStamped() {
        val out = save(original(body = "hello\n"), title = "New title", body = "hello\n")
        val doc = com.piercingxx.xxnote.core.Frontmatter.parse(out)
        assertEquals("New title", doc.title)
        assertEquals("2026-08-23T12:00:00Z", doc.updated)
        assertEquals("hello\n", bodyOf(out))
    }

    @Test
    fun unknownKeysAndUntouchedOwnedKeysSurviveByteForByte() {
        val src = original(body = "hello\n")
        val out = save(src, title = "Renamed", body = "hello\n")
        assertTrue(out.contains("plugin_note: keep me verbatim"))
        assertTrue(out.contains("color: basil"))
        assertTrue(out.contains("created: 2026-08-20T09:00:00Z"))
        assertTrue(out.contains("id: 01J9F2K3M4N5P6Q7R8S9T0V1W2"))
    }

    @Test
    fun blankTitleRemovesTitleLine() {
        val out = save(original(body = "b\n"), title = "", body = "b\n")
        val doc = com.piercingxx.xxnote.core.Frontmatter.parse(out)
        assertNull(doc.title)
        assertFalse(out.contains("title:"))
    }

    @Test
    fun noteWithoutFrontmatterGainsFreshBlock() {
        val src = "just some prose\nnothing else\n"
        val out = save(src, title = "Born whole", body = "just some prose\nnothing else\n")
        val doc = com.piercingxx.xxnote.core.Frontmatter.parse(out)
        assertTrue(doc.hasFrontmatter)
        assertEquals("Born whole", doc.title)
        assertEquals("2026-08-23T12:00:00Z", doc.updated)
        assertEquals("just some prose\nnothing else\n", bodyOf(out))
    }

    @Test
    fun crlfLineEndingsPreservedInFrontmatterRegion() {
        val src = "---\r\nid: 01J9F2K3M4N5P6Q7R8S9T0V1W2\r\ntitle: Old\r\n---\r\nbody line\r\n"
        val out = save(src, title = "New", body = "body line\r\n")
        assertTrue(out.contains("title: New\r\n"))
        assertTrue(out.endsWith("body line\r\n"))
    }

    // ---- Save: D18 sort-to-bottom ----------------------------------------------------

    @Test
    fun checklistSortsCheckedToBottomWithinEachBlock() {
        val body = "- [x] done one\nprose stays put\n- [ ] open one\n- [x] done two\n- [ ] open two\n"
        val out = save(original(body = body, type = "checklist"), title = "t", body = body)
        // Two blocks, partitioned independently (§7.1); prose passes through.
        assertEquals(
            "- [x] done one\nprose stays put\n- [ ] open one\n- [ ] open two\n- [x] done two\n",
            bodyOf(out),
        )
    }

    @Test
    fun plainNoteIsNeverSorted() {
        val body = "- [x] first\n- [ ] second\n"
        val out = save(original(body = body), title = "t", body = body)
        assertEquals(body, bodyOf(out))
    }

    @Test
    fun corruptTypeDegradesToNoteAndDoesLess() {
        val body = "- [x] first\n- [ ] second\n"
        val out = save(original(body = body, type = "CHECKLIST-"), title = "t", body = body)
        assertEquals(body, bodyOf(out))
    }

    @Test
    fun sortingIsStablePartitionNotGeneralSort() {
        val body = "- [x] b1\n- [ ] o1\n- [x] b2\n- [ ] o2\n"
        val out = save(original(body = body, type = "checklist"), title = "t", body = body)
        assertEquals("- [ ] o1\n- [ ] o2\n- [x] b1\n- [x] b2\n", bodyOf(out))
    }

    // ---- Save: idempotence -------------------------------------------------------------

    @Test
    fun secondSaveWithSameClockStampIsByteStable() {
        val src = original(body = "- [x] d\n- [ ] o\nmore prose\n", type = "checklist")
        val once = save(src, title = "Same", body = "- [ ] o\n- [x] d\nmore prose\n")
        val twice = save(once, title = "Same", body = bodyOf(once))
        assertEquals(once, twice)
    }

    @Test
    fun freshStampChangesOnlyTheUpdatedLine() {
        val src = original(body = "steady\n", type = "checklist")
        val first = save(src, title = "T", body = "steady\n", now = "2026-08-23T12:00:00Z")
        val second = save(first, title = "T", body = "steady\n", now = "2026-08-23T12:30:00Z")
        assertNotEquals(first, second)
        assertTrue(second.contains("updated: 2026-08-23T12:30:00Z"))
        assertEquals(
            first.lines().filterNot { it.startsWith("updated:") },
            second.lines().filterNot { it.startsWith("updated:") },
        )
    }

    // ---- Checkbox toggle -----------------------------------------------------------------

    @Test
    fun tappingBoxCharChecksIt() {
        val body = "intro\n- [ ] oat milk\noutro\n"
        val offset = body.indexOf("[") + 1 // the ' ' inside the box
        val result = ChecklistToggle.at(body, offset)
        assertTrue(result != null)
        assertEquals(body.length, result!!.text.length) // same-length swap
        assertTrue(result.text.contains("- [x] oat milk"))
        assertEquals(offset, result.toggledIndex)
        // Nothing else moved:
        assertEquals(body.replace("- [ ] ", "- [x] "), result.text)
    }

    @Test
    fun tappingAnywhereOnTheDashPrefixAlsoToggles() {
        val body = "- [ ] milk\n"
        val dashOffset = 0
        val result = ChecklistToggle.at(body, dashOffset)
        assertTrue(result != null)
        assertTrue(result!!.text.contains("[x]"))
    }

    @Test
    fun untickingWritesSpaceNotLowercaseX() {
        val body = "- [x] milk\n"
        val result = ChecklistToggle.at(body, 3)
        assertTrue(result!!.text.startsWith("- [ ] "))
    }

    @Test
    fun uppercaseXTogglesDownToSpace() {
        val body = "* [X] shout\n"
        val result = ChecklistToggle.at(body, body.indexOf('X'))
        assertTrue(result!!.text.contains("[ ]"))
        assertFalse(result.text.contains("[X]"))
    }

    @Test
    fun proseAndPlainBulletsDeclinePolitely() {
        assertNull(ChecklistToggle.at("plain words here", 4))
        assertNull(ChecklistToggle.at("- plain bullet, no box", 0))
        assertNull(ChecklistToggle.at("", 0))
        assertNull(ChecklistToggle.at("- [ ] x\n", 40))
    }

    @Test
    fun nestedIndentedTaskToggles() {
        val body = "  - [ ] nested\n"
        val result = ChecklistToggle.at(body, body.indexOf('[') + 1)
        assertTrue(result!!.text.contains("  - [x] nested"))
    }
}
