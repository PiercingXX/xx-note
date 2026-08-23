package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.NoteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MergeEngineTest {

    private companion object {
        const val ID = "01J9F2K3M4N5P6Q7R8S9T0V1W2"
    }

    private fun note(
        body: String,
        title: String? = null,
        type: String = "note",
        labels: List<String>? = null,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        updated: String? = null,
        created: String? = null,
        color: String? = null,
        extra: String = "",
    ): String = buildString {
        append("---\n")
        append("id: ").append(ID).append('\n')
        if (title != null) append("title: ").append(title).append('\n')
        if (created != null) append("created: ").append(created).append('\n')
        if (updated != null) append("updated: ").append(updated).append('\n')
        if (pinned != null) append("pinned: ").append(pinned).append('\n')
        if (archived != null) append("archived: ").append(archived).append('\n')
        if (color != null) append("color: ").append(color).append('\n')
        if (labels != null) append("labels: [").append(labels.joinToString(", ")).append("]\n")
        if (type.isNotEmpty()) append("type: ").append(type).append('\n')
        append(extra)
        append("---\n")
        append(body)
    }

    @Test
    fun `prose disjoint edits merge clean with both edits present`() {
        val base = note(body = "alpha\nbeta\ngamma\n")
        val local = note(body = "alpha local\nbeta\ngamma\n")
        val remote = note(body = "alpha\nbeta\ngamma remote\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(outcome)
        assertTrue(merged.wholeFileText.contains("alpha local"))
        assertTrue(merged.wholeFileText.contains("gamma remote"))
        val doc = Frontmatter.parse(merged.wholeFileText)
        assertTrue(doc.hasFrontmatter)
        assertEquals("alpha local\nbeta\ngamma remote\n", doc.bodyText)
    }

    @Test
    fun `same line edited on both sides forks as prose conflict`() {
        val base = note(body = "alpha\nbeta\ngamma\n")
        val local = note(body = "alpha\nbeta one\ngamma\n")
        val remote = note(body = "alpha\nbeta two\ngamma\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val fork = assertIs<MergeEngine.MergeOutcome.Fork>(outcome)
        assertEquals("prose conflict", fork.reason)
    }

    @Test
    fun `checklist on all three routes item-wise and survives concurrent ticks`() {
        val base = note(
            type = "checklist",
            body = "- [ ] a\n- [ ] b\n- [ ] c\n- [ ] d\n- [ ] e\n\nparagraph\n",
        )
        val local = note(
            type = "checklist",
            body = "- [ ] a\n- [x] b\n- [ ] c\n- [ ] d\n- [ ] e\n\nparagraph\n",
        )
        val remote = note(
            type = "checklist",
            body = "- [ ] a\n- [ ] b\n- [ ] c\n- [ ] d\n- [x] e\n\nparagraph edited\n",
        )

        val outcome = MergeEngine.merge(base, local, remote)

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(outcome)
        assertTrue(merged.wholeFileText.contains("- [x] b"))
        assertTrue(merged.wholeFileText.contains("- [x] e"))
        assertTrue(merged.wholeFileText.contains("- [ ] a"))
        assertTrue(merged.wholeFileText.contains("paragraph edited"))
    }

    @Test
    fun `checklist degraded to note on one side falls back to the prose path`() {
        val base = note(type = "checklist", body = "- [ ] a\n- [ ] b\n")
        val local = note(type = "note", body = "- [x] a\n- [ ] b\n")
        val remote = note(type = "checklist", body = "- [ ] a\n- [x] b\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val fork = assertIs<MergeEngine.MergeOutcome.Fork>(outcome)
        assertEquals("prose conflict", fork.reason)
    }

    @Test
    fun `labels union is case insensitive case preserving in base then local then remote order`() {
        val base = note(labels = listOf("home"), body = "hello\n")
        val local = note(labels = listOf("Home", "errands"), body = "hello\n")
        val remote = note(labels = listOf("HOME", "shopping"), body = "hello\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(outcome)
        assertEquals(
            listOf("home", "errands", "shopping"),
            Frontmatter.parse(merged.wholeFileText).labels,
        )
    }

    @Test
    fun `pinned or visible`() {
        val base = note(pinned = false, body = "hello\n")
        val local = note(pinned = true, body = "hello\n")
        val remote = note(pinned = false, body = "hello\n")

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(MergeEngine.merge(base, local, remote))
        assertEquals(true, Frontmatter.parse(merged.wholeFileText).pinned)

        val base2 = note(body = "hello\n")
        val local2 = note(body = "hello\n")
        val remote2 = note(pinned = true, body = "hello\n")

        val merged2 = assertIs<MergeEngine.MergeOutcome.Merged>(MergeEngine.merge(base2, local2, remote2))
        assertEquals(true, Frontmatter.parse(merged2.wholeFileText).pinned)
    }

    @Test
    fun `archived or visible`() {
        val base = note(archived = true, body = "hello\n")
        val local = note(archived = true, body = "hello\n")
        val remote = note(archived = false, body = "hello\n")

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(MergeEngine.merge(base, local, remote))

        assertEquals(false, Frontmatter.parse(merged.wholeFileText).archived)
    }

    @Test
    fun `updated takes the later instant`() {
        val base = note(updated = "2026-08-23T09:00:00Z", body = "hello\n")
        val local = note(updated = "2026-08-23T10:07:55Z", body = "hello\n")
        val remote = note(updated = "2026-08-23T11:02:03Z", body = "hello\n")

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(MergeEngine.merge(base, local, remote))
        assertEquals("2026-08-23T11:02:03Z", Frontmatter.parse(merged.wholeFileText).updated)

        val merged2 = assertIs<MergeEngine.MergeOutcome.Merged>(MergeEngine.merge(base, remote, local))
        assertEquals("2026-08-23T11:02:03Z", Frontmatter.parse(merged2.wholeFileText).updated)
    }

    @Test
    fun `color takes the local side`() {
        val base = note(color = "sand", body = "hello\n")
        val local = note(color = "moss", body = "hello\n")
        val remote = note(color = "ink", body = "hello\n")

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(MergeEngine.merge(base, local, remote))

        assertEquals("moss", Frontmatter.parse(merged.wholeFileText).color)
    }

    @Test
    fun `unknown key differing on both sides forks naming the key`() {
        val base = note(extra = "source: joplin\n", body = "hello\n")
        val local = note(extra = "source: joplin\n", body = "hello local\n")
        val remote = note(extra = "source: obsidian\n", body = "hello\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val fork = assertIs<MergeEngine.MergeOutcome.Fork>(outcome)
        assertEquals("frontmatter: source", fork.reason)
    }

    @Test
    fun `unknown key equal on both sides merges clean`() {
        val base = note(extra = "source: joplin\n", body = "hello\n")
        val local = note(extra = "source: joplin\n", body = "hello local\n")
        val remote = note(extra = "source: joplin\n", body = "hello\n")

        val outcome = MergeEngine.merge(base, local, remote)

        assertIs<MergeEngine.MergeOutcome.Merged>(outcome)
    }

    @Test
    fun `one sided unknown key is preserved verbatim from whichever side has it`() {
        val base = note(body = "alpha\nbeta\ngamma\n")
        val local = note(extra = "obsidianUIMode: preview\n", body = "alpha local\nbeta\ngamma\n")
        val remote = note(extra = "dataview-plugin:   enable-js  \n", body = "alpha\nbeta\ngamma\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(outcome)
        assertTrue(merged.wholeFileText.contains("obsidianUIMode: preview\n"))
        assertTrue(merged.wholeFileText.contains("dataview-plugin:   enable-js"))
    }

    @Test
    fun `merged output reparses cleanly and keeps an obsidian plugin key byte for byte`() {
        val base = note(extra = "cssclasses: [wide]\n", body = "alpha\nbeta\ngamma\n")
        val local = note(extra = "cssclasses: [wide]\n", body = "ALPHA\nbeta\ngamma\n")
        val remote = note(extra = "cssclasses: [wide]\n", body = "alpha\nbeta\nGAMMA\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(outcome)
        assertTrue(merged.wholeFileText.contains("\ncssclasses: [wide]\n"))
        val doc = Frontmatter.parse(merged.wholeFileText)
        assertTrue(doc.hasFrontmatter)
        assertTrue(!doc.isMalformed)
        assertEquals("ALPHA\nbeta\nGAMMA\n", doc.bodyText)
        assertEquals(ID, doc.id)
    }

    @Test
    fun `original id is untouched even when the far side claims another`() {
        val base = note(body = "hello\n")
        val local = note(body = "hello local\n")
        val remote = "---\nid: 01J9F2K8ZZ1A2B3C4D5E6F7G8H\ntype: note\n---\nhello\n"

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(MergeEngine.merge(base, local, remote))

        assertEquals(ID, Frontmatter.parse(merged.wholeFileText).id)
    }

    @Test
    fun remote_retitle_adopted_when_local_untouched() {
        val base = note(title = "Alpha", body = "hello\n")
        // Local edited the BODY only; the far side retitled.
        val local = note(title = "Alpha", body = "hello local\n")
        val remote = note(title = "Beta", body = "hello\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(outcome)
        val doc = Frontmatter.parse(merged.wholeFileText)
        assertEquals("Beta", doc.title) // the title edit survives
        assertEquals("hello local\n", doc.bodyText) // and so does the body edit
    }

    @Test
    fun retitle_both_sides_forks() {
        val base = note(title = "Alpha", body = "hello\n")
        val local = note(title = "Renamed here", body = "hello\n")
        val remote = note(title = "Renamed there", body = "hello\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val fork = assertIs<MergeEngine.MergeOutcome.Fork>(outcome)
        assertEquals("frontmatter: title", fork.reason)
    }

    @Test
    fun local_retitle_kept_when_remote_untouched() {
        val base = note(title = "Alpha", body = "hello\n")
        val local = note(title = "My alpha", body = "hello\n")
        val remote = note(title = "Alpha", body = "hello remote\n")

        val outcome = MergeEngine.merge(base, local, remote)

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(outcome)
        val doc = Frontmatter.parse(merged.wholeFileText)
        assertEquals("My alpha", doc.title)
        assertEquals("hello remote\n", doc.bodyText)
    }

    @Test
    fun type_and_created_follow_the_same_three_way_rule_as_title() {
        // Only remote moved type → adopted; only local moved created → kept.
        val base = note(type = "note", created = "2026-08-20T09:00:00Z", body = "hello\n")
        val local = note(
            type = "note",
            created = "2026-08-20T09:30:00Z",
            body = "hello local\n",
        )
        val remote = note(
            type = "checklist",
            created = "2026-08-20T09:00:00Z",
            body = "hello\n",
        )

        val merged = assertIs<MergeEngine.MergeOutcome.Merged>(MergeEngine.merge(base, local, remote))
        val doc = Frontmatter.parse(merged.wholeFileText)
        assertEquals(NoteType.CHECKLIST, doc.type)
        assertEquals("2026-08-20T09:30:00Z", doc.created)

        // Both moved apart on created → fork naming the key. (Type cannot
        // fork: with two values, one side always equals base.)
        val base2 = note(created = "2026-08-20T09:00:00Z", body = "hello\n")
        val local2 = note(created = "2026-08-20T10:00:00Z", body = "hello\n")
        val remote2 = note(created = "2026-08-20T11:00:00Z", body = "hello\n")
        val fork = MergeEngine.merge(base2, local2, remote2)
        assertEquals("frontmatter: created", assertIs<MergeEngine.MergeOutcome.Fork>(fork).reason)
    }
}
