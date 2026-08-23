package com.piercingxx.xxnote.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrontmatterTest {

    private val ulid = "01J9F2K3M4N5P6Q7R8S9T0V1W2"

    // ------------------------------------------------------------------
    // §8 example note
    // ------------------------------------------------------------------

    private val section8 = """
        ---
        id: 01J9F2K3M4N5P6Q7R8S9T0V1W2
        title: Grocery list
        created: 2026-08-23T10:04:12Z
        updated: 2026-08-23T10:07:55Z
        pinned: true
        archived: false
        color: sand
        labels: [home, errands]
        type: checklist
        ---

        - [ ] oat milk
        - [ ] coffee, the dark one
        - [x] bin bags
    """.trimIndent() + "\n"

    @Test
    fun `section 8 example parses with every accessor correct`() {
        val d = Frontmatter.parse(section8)
        assertTrue(d.hasFrontmatter)
        assertFalse(d.isMalformed)
        assertEquals(ulid, d.id)
        assertEquals("Grocery list", d.title)
        assertEquals("2026-08-23T10:04:12Z", d.created)
        assertEquals("2026-08-23T10:07:55Z", d.updated)
        assertEquals(true, d.pinned)
        assertEquals(false, d.archived)
        assertEquals("sand", d.color)
        assertEquals(listOf("home", "errands"), d.labels)
        assertEquals(NoteType.CHECKLIST, d.type)
        assertNull(d.reminder)
        assertNull(d.conflictOf)
        assertNull(d.conflictAt)
        assertNull(d.trashedAt)
        assertEquals(
            "\n- [ ] oat milk\n- [ ] coffee, the dark one\n- [x] bin bags\n",
            d.bodyText,
        )
    }

    @Test
    fun `accessor reads never alter file bytes`() {
        val d = Frontmatter.parse(section8)
        repeat(3) {
            d.id; d.title; d.created; d.updated; d.pinned; d.archived; d.color
            d.labels; d.type; d.reminder; d.conflictOf; d.conflictAt; d.trashedAt
            d.bodyText; d.hasFrontmatter; d.isMalformed
        }
        assertEquals(section8, d.raw())
        assertEquals(Frontmatter.parse(section8).raw(), d.raw())
    }

    // ------------------------------------------------------------------
    // Round-trip identity on handcrafted fixtures
    // ------------------------------------------------------------------

    @Test
    fun `round trip identity holds for every fixture`() {
        for (fixture in fixtures()) {
            val d = Frontmatter.parse(fixture)
            assertEquals(fixture, d.raw(), "raw() must echo input")
            assertEquals(fixture, d.rewritten { }, "empty rewrite must be byte-exact no-op")
        }
    }

    private fun fixtures(): List<String> = listOf(
        section8,
        "---\n---\nbody after an empty block\n",
        "---\nid: $ulid\n...\nbody closed by yaml end\n",
        "# just a body\n---\nmore body that is not frontmatter\n",
        "\n---\nid: $ulid\n---\nleading blank line means no frontmatter\n",
        "---\nid: $ulid\ngarbage line without colon\n---\nsurvivor\n",
        "---\nid: $ulid\nnever closed before eof",
        "---",
        "---\n",
        "",
        "body only",
        "---\ntitle: \"quoted title\"\npinned: yes\n---\n",
        "---\r\nid: $ulid\r\npinned: true\r\n---\r\nbody\r\n",
        "\uFEFF---\nid: $ulid\n---\nbom'd body\n",
        "\uFEFF---\r\nid: $ulid\r\n---\r\nbom and crlf\r\n",
        "---\nid: $ulid\n---\nbody without trailing newline",
        "---\ndataview: stuff\n---\n---\nthis separator is body\n",
        "---\nTitle: capitalised key stays unknown\nid: lowercase owned\n---\nx\n",
        "---\nlabels: []\ntype: bogus\nreminder:\n---\n",
    )

    // ------------------------------------------------------------------
    // Unknown keys survive rewrites byte-for-byte
    // ------------------------------------------------------------------

    @Test
    fun `unknown plugin keys survive rewrites byte for byte in position`() {
        val input = """
            ---
            id: 01J9F2K3M4N5P6Q7R8S9T0V1W2
            dataview: stuff
            cssclasses: [a, b]
            weird "quoted": oh really?
              indented-child: nested-looking
            parent:
            tags: '#x'
            ---
            body
        """.trimIndent() + "\n"

        val out = Frontmatter.parse(input).rewritten { updated = "X" }
        assertEquals(
            """
                ---
                id: 01J9F2K3M4N5P6Q7R8S9T0V1W2
                dataview: stuff
                cssclasses: [a, b]
                weird "quoted": oh really?
                  indented-child: nested-looking
                parent:
                tags: '#x'
                updated: X
                ---
                body
            """.trimIndent() + "\n",
            out,
        )
        assertEquals("X", Frontmatter.parse(out).updated)
    }

    @Test
    fun `case variant keys are unknown and survive`() {
        val input = "---\nTitle: Capitalised\nID: upper\n---\nx\n"
        val d = Frontmatter.parse(input)
        assertTrue(d.hasFrontmatter)
        assertNull(d.title)
        assertNull(d.id)
        val out = d.rewritten { title = "t" }
        assertTrue(out.contains("Title: Capitalised"))
        assertTrue(out.contains("ID: upper"))
    }

    // ------------------------------------------------------------------
    // Unmutated owned keys keep their raw lines
    // ------------------------------------------------------------------

    @Test
    fun `unmutated owned keys keep their exact raw lines`() {
        val input = "---\ncreated: 2026-08-23T10:04:12Z\npinned: yes\nlabels: [home]\n---\nbody\n"
        val out = Frontmatter.parse(input).rewritten { title = "New" }
        assertEquals(
            "---\ncreated: 2026-08-23T10:04:12Z\npinned: yes\nlabels: [home]\ntitle: New\n---\nbody\n",
            out,
        )
    }

    @Test
    fun `assigning the same value back keeps the raw rendering`() {
        val input = "---\npinned: yes\ntitle: Same\n---\n"
        val out = Frontmatter.parse(input).rewritten {
            pinned = true
            title = "Same"
        }
        assertEquals(input, out)
    }

    // ------------------------------------------------------------------
    // Degradation matrix
    // ------------------------------------------------------------------

    @Test
    fun `unclosed block degrades to body text`() {
        val input = "---\nid: $ulid\nstill going at eof"
        val d = Frontmatter.parse(input)
        assertFalse(d.hasFrontmatter)
        assertTrue(d.isMalformed)
        assertEquals(input, d.bodyText)
        assertEquals(input, d.raw())
    }

    @Test
    fun `garbage line inside block degrades to body text`() {
        val input = "---\nid: $ulid\nnot a pair\n---\nrest"
        val d = Frontmatter.parse(input)
        assertFalse(d.hasFrontmatter)
        assertTrue(d.isMalformed)
        assertEquals(input, d.bodyText)
        assertEquals(input, d.raw())
    }

    @Test
    fun `lone delimiter is malformed not fatal`() {
        for (input in listOf("---", "---\n")) {
            val d = Frontmatter.parse(input)
            assertTrue(d.isMalformed, "input <$input>")
            assertEquals(input, d.bodyText)
        }
    }

    @Test
    fun `no frontmatter at all is absent not malformed`() {
        val input = "# heading\n---\na horizontal rule is body\n"
        val d = Frontmatter.parse(input)
        assertFalse(d.hasFrontmatter)
        assertFalse(d.isMalformed)
        assertEquals(input, d.bodyText)
    }

    @Test
    fun `leading blank line means no frontmatter`() {
        val input = "\n---\nid: $ulid\n---\nbody\n"
        val d = Frontmatter.parse(input)
        assertFalse(d.hasFrontmatter)
        assertFalse(d.isMalformed, "first line must be the delimiter; this is absence, not corruption")
        assertEquals(input, d.bodyText)
        assertEquals(input, d.raw())
    }

    // ------------------------------------------------------------------
    // Malformed + rewritten prepends a fresh block
    // ------------------------------------------------------------------

    @Test
    fun `rewriting a malformed note prepends a fresh block above intact body`() {
        val input = "---\ngarbage!!!\n---\nreal body\n"
        val out = Frontmatter.parse(input).rewritten {
            id = ulid
            title = "T"
        }
        assertEquals("---\nid: $ulid\ntitle: T\n---\n---\ngarbage!!!\n---\nreal body\n", out)

        val reparsed = Frontmatter.parse(out)
        assertTrue(reparsed.hasFrontmatter)
        assertEquals(ulid, reparsed.id)
        assertEquals("T", reparsed.title)
        assertEquals("---\ngarbage!!!\n---\nreal body\n", reparsed.bodyText)
    }

    @Test
    fun `empty mutation on malformed or absent docs is a strict no-op`() {
        for (input in listOf("---\noops\n---\nbody", "plain body")) {
            assertEquals(input, Frontmatter.parse(input).rewritten { })
        }
    }

    // ------------------------------------------------------------------
    // Id-assignment path (§8: absent id assigned on first read)
    // ------------------------------------------------------------------

    @Test
    fun `assigning id appends into an existing block`() {
        val input = "---\ntitle: Untitled\n---\nbody\n"
        val out = Frontmatter.parse(input).rewritten { id = ulid }
        assertEquals("---\ntitle: Untitled\nid: $ulid\n---\nbody\n", out)
        assertEquals(ulid, Frontmatter.parse(out).id)
        assertEquals("Untitled", Frontmatter.parse(out).title)
        assertEquals("body\n", Frontmatter.parse(out).bodyText)
    }

    @Test
    fun `assigning id onto a bare body prepends a fresh valid block`() {
        val input = "- [ ] first thing\n"
        val out = Frontmatter.parse(input).rewritten { id = ulid }
        assertEquals("---\nid: $ulid\n---\n- [ ] first thing\n", out)
        val d = Frontmatter.parse(out)
        assertTrue(d.hasFrontmatter)
        assertEquals("- [ ] first thing\n", d.bodyText)
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    @Test
    fun `label styles parse leniently`() {
        fun labelsOf(value: String): List<String> =
            Frontmatter.parse("---\nlabels: $value\n---\n").labels

        assertEquals(listOf("home", "errands"), labelsOf("[home, errands]"))
        assertEquals(listOf("a, b", "c"), labelsOf("[\"a, b\", c]"))
        assertEquals(listOf("'quoted'"), labelsOf("[\"'quoted'\"]"))
        assertEquals(listOf("home"), labelsOf("home"))
        assertEquals(emptyList(), labelsOf("[]"))
        assertEquals(emptyList(), labelsOf(""))
        assertEquals(emptyList(), Frontmatter.parse("---\n---\n").labels)
    }

    @Test
    fun `label mutation renders canonical flow style`() {
        val input = "---\nlabels: [home]\n---\n"
        val out = Frontmatter.parse(input).rewritten {
            labels.clear()
            labels.add("a, b")
            labels.add("c")
        }
        assertTrue(out.contains("labels: [\"a, b\", c]"), out)
        assertEquals(listOf("a, b", "c"), Frontmatter.parse(out).labels)
    }

    // ------------------------------------------------------------------
    // Type degradation
    // ------------------------------------------------------------------

    @Test
    fun `type values degrade toward note`() {
        fun typeOf(value: String?): NoteType =
            Frontmatter.parse(if (value == null) "---\n---\n" else "---\ntype: $value\n---\n").type

        assertEquals(NoteType.CHECKLIST, typeOf("checklist"))
        assertEquals(NoteType.CHECKLIST, typeOf("Checklist"))
        assertEquals(NoteType.NOTE, typeOf("note"))
        assertEquals(NoteType.NOTE, typeOf("bogus"))
        assertEquals(NoteType.NOTE, typeOf(""))
        assertEquals(NoteType.NOTE, typeOf(null))
    }

    // ------------------------------------------------------------------
    // Lenient scalars
    // ------------------------------------------------------------------

    @Test
    fun `booleans accept the lenient vocabulary`() {
        fun pinnedOf(value: String): Boolean? =
            Frontmatter.parse("---\npinned: $value\n---\n").pinned

        for (word in listOf("true", "yes", "on", "1", "TRUE", "Yes")) assertEquals(true, pinnedOf(word), word)
        for (word in listOf("false", "no", "off", "0", "FALSE", "Off")) assertEquals(false, pinnedOf(word), word)
        assertNull(pinnedOf("maybe"))
        assertNull(pinnedOf(""))
    }

    @Test
    fun `quotes strip exactly one layer`() {
        fun titleOf(value: String): String? =
            Frontmatter.parse("---\ntitle: $value\n---\n").title

        assertEquals("the dark one", titleOf("\"the dark one\""))
        assertEquals("the dark one", titleOf("'the dark one'"))
        assertEquals("\"nested\"", titleOf("'\"nested\"'"))
        assertEquals("plain", titleOf("plain"))
        assertEquals("", titleOf("\"\""))
    }

    @Test
    fun `values containing colons split once and round trip through mutation`() {
        val d = Frontmatter.parse("---\nurl: http://example.com/a:b\n---\n")
        assertTrue(d.hasFrontmatter)
        val out = d.rewritten { title = "a: b" }
        assertTrue(out.contains("title: \"a: b\""), out)
        assertEquals("a: b", Frontmatter.parse(out).title)
    }

    // ------------------------------------------------------------------
    // Removal via null assignment
    // ------------------------------------------------------------------

    @Test
    fun `null assignment removes the owned key line`() {
        val input = "---\nid: $ulid\npinned: true\ndataview: keep\n---\n"
        val out = Frontmatter.parse(input).rewritten { pinned = null }
        assertEquals("---\nid: $ulid\ndataview: keep\n---\n", out)
        assertNull(Frontmatter.parse(out).pinned)
        assertTrue(out.contains("dataview: keep"))
    }

    @Test
    fun `duplicate owned key collapses to the last position on mutation`() {
        val input = "---\ntitle: A\ndataview: d\ntitle: B\n---\n"
        val out = Frontmatter.parse(input).rewritten { title = "C" }
        assertEquals("---\ndataview: d\ntitle: C\n---\n", out)
        assertEquals("C", Frontmatter.parse(out).title)
    }

    // ------------------------------------------------------------------
    // Byte-level edge cases
    // ------------------------------------------------------------------

    @Test
    fun `crlf only file round trips and mutates in crlf`() {
        val input = "---\r\nid: $ulid\r\npinned: true\r\n---\r\nbody\r\n"
        val d = Frontmatter.parse(input)
        assertTrue(d.hasFrontmatter)
        assertEquals(input, d.raw())
        assertEquals(input, d.rewritten { })
        val out = d.rewritten { archived = true }
        assertEquals("---\r\nid: $ulid\r\npinned: true\r\narchived: true\r\n---\r\nbody\r\n", out)
    }

    @Test
    fun `missing trailing newline is preserved through mutation`() {
        val input = "---\nid: $ulid\n---\nbody"
        val out = Frontmatter.parse(input).rewritten { pinned = false }
        assertEquals("---\nid: $ulid\npinned: false\n---\nbody", out)
    }

    @Test
    fun `bom stays at head of file through mutation`() {
        val input = "\uFEFF---\nid: $ulid\n---\nbody\n"
        val out = Frontmatter.parse(input).rewritten { title = "T" }
        assertEquals("\uFEFF---\nid: $ulid\ntitle: T\n---\nbody\n", out)
        assertTrue(Frontmatter.parse(out).raw().startsWith("\uFEFF"))
    }

    @Test
    fun `yaml end closer is preserved as-is on rewrite`() {
        val input = "---\nid: $ulid\n...\nbody\n"
        val out = Frontmatter.parse(input).rewritten { title = "t" }
        assertEquals("---\nid: $ulid\ntitle: t\n...\nbody\n", out)
    }

    @Test
    fun `extra dashes in body stay body`() {
        val input = "---\nid: $ulid\n---\nintro\n---\nstill body\n---\n"
        val d = Frontmatter.parse(input)
        assertEquals("intro\n---\nstill body\n---\n", d.bodyText)
        assertEquals(input, d.rewritten { })
    }

    @Test
    fun `malformed crlf doc gets fresh crlf block with bom hoisted`() {
        val input = "\uFEFF---\r\noops\r\n---\r\nbody\r\n"
        val out = Frontmatter.parse(input).rewritten { id = ulid }
        assertEquals("\uFEFF---\r\nid: $ulid\r\n---\r\n---\r\noops\r\n---\r\nbody\r\n", out)
    }

    // ------------------------------------------------------------------
    // Seeded corpus: ~2000 generated documents
    // ------------------------------------------------------------------

    private class DocSpec(val text: String, val wellFormedBlock: Boolean, val mustSurvive: List<String>)

    @Test
    fun `seeded corpus of 2000 documents round trips byte exactly`() {
        val r = Random(0x58584E4FL)
        repeat(2000) { index ->
            val spec = generate(r)
            val d = Frontmatter.parse(spec.text)
            assertEquals(spec.text, d.raw(), "doc #$index raw()")
            assertEquals(spec.text, d.rewritten { }, "doc #$index empty rewrite")
            if (spec.wellFormedBlock) {
                assertTrue(d.hasFrontmatter, "doc #$index expected well-formed")
                assertFalse(d.isMalformed, "doc #$index expected well-formed")
            }
            if (index % 11 == 0 && spec.wellFormedBlock) mutateAndCheck(index, spec, d)
        }
    }

    private fun mutateAndCheck(index: Int, spec: DocSpec, d: FrontmatterDocument) {
        val originalLabels = d.labels
        val out = d.rewritten {
            updated = "2026-08-23T00:00:00Z"
            pinned = true
            title = "Mutated #$index"
            labels.add("zz-mutated")
        }
        val d2 = Frontmatter.parse(out)
        assertEquals(out, d2.raw(), "doc #$index fixpoint raw")
        assertEquals(out, d2.rewritten { }, "doc #$index fixpoint empty rewrite")
        assertTrue(out.endsWith(d.bodyText), "doc #$index body preserved")
        assertEquals("2026-08-23T00:00:00Z", d2.updated, "doc #$index mutated visible")
        assertEquals(true, d2.pinned, "doc #$index mutated visible")
        assertEquals("Mutated #$index", d2.title, "doc #$index mutated visible")
        assertEquals(originalLabels + "zz-mutated", d2.labels, "doc #$index labels visible")

        var pos = 0
        for (survivor in spec.mustSurvive) {
            val found = out.indexOf(survivor, pos)
            assertTrue(found >= 0, "doc #$index lost survivor <$survivor>")
            pos = found + survivor.length
        }
    }

    private val boolWords = listOf("true", "false", "yes", "no", "on", "off", "1", "0", "TRUE", "Maybe")
    private val colors = listOf("sand", "ink", "graphite", "\"slate\"")
    private val labelStyles = listOf("[home, errands]", "[\"a, b\", c]", "[]", "home", "'one label'", "[x]")
    private val typeWords = listOf("note", "checklist", "CHECKLIST", "bogus")
    private val stamps = listOf("2026-08-23T10:04:12Z", "\"2026-08-22T09:00:00Z\"", "'2026-08-21T08:00:00Z'")
    private val unknownNames = listOf("dataview", "cssclasses", "alias", "tags", "weird key", "\"qkey\"", "parent", "Title", "plugin")
    private val unknownValues = listOf("stuff", "http://example.com/x", "'#tag'", "[nested, flow]", "", "sub: deep", "\"multi word\"")
    private val bodyLines = listOf(
        "- [ ] oat milk",
        "- [x] done",
        "note: kept",
        "---",
        "text with, commas",
        "# heading",
        "Café ☕ unicode",
        "> quoted prose",
    )

    private fun generate(r: Random): DocSpec {
        val sb = StringBuilder()
        if (r.nextInt(12) == 0) sb.append('\uFEFF')
        fun eol(): String {
            val base = if (r.nextInt(4) == 0) "\r\n" else "\n"
            return if (r.nextInt(25) == 0) (if (base == "\r\n") "\n" else "\r\n") else base
        }
        val survivors = ArrayList<String>()
        val mode = r.nextInt(100)
        var wellFormed = false
        when {
            mode < 12 -> Unit // no frontmatter at all
            mode < 24 -> { // malformed: garbage interior, sometimes unclosed
                sb.append("---").append(eol())
                repeat(r.nextInt(1, 4)) {
                    val junk = when (r.nextInt(5)) {
                        0 -> "just some words"
                        1 -> "- a dash list item"
                        2 -> ": leading colon"
                        3 -> "http://not.a.pair"
                        else -> ownedLine(r)
                    }
                    sb.append(junk).append(eol())
                }
                if (r.nextBoolean()) sb.append("---").append(eol())
            }
            else -> { // well-formed block
                wellFormed = true
                sb.append("---").append(eol())
                survivors.add("---" )
                repeat(r.nextInt(0, 10)) {
                    when (r.nextInt(100)) {
                        in 0..5 -> sb.append(eol())
                        in 6..55 -> sb.append(ownedLine(r)).append(eol())
                        else -> {
                            val line = unknownLine(r)
                            survivors.add(line)
                            sb.append(line).append(eol())
                        }
                    }
                }
                val closer = if (r.nextInt(8) == 0) "..." else "---"
                sb.append(closer).append(eol())
            }
        }
        repeat(r.nextInt(0, 7)) {
            val line = bodyLines[r.nextInt(bodyLines.size)]
            sb.append(line).append(eol())
        }
        var text = sb.toString()
        if (r.nextInt(4) == 0 && text.endsWith("\n")) {
            text = if (text.endsWith("\r\n")) text.dropLast(2) else text.dropLast(1)
        }
        return DocSpec(text, wellFormed, survivors.map { it }.filter { it.isNotEmpty() })
    }

    private fun ownedLine(r: Random): String {
        val keys = OwnedKeys.ORDER
        val k = keys[r.nextInt(keys.size)]
        return when (k) {
            "id" -> "id: ${if (r.nextBoolean()) "01J9F2K3M4N5P6Q7R8S9T0V1W2" else "\"01J9F2K8ZZ1A2B3C4D5E6F7G8H\""}"
            "title" -> when (r.nextInt(4)) {
                0 -> "title: Grocery list"
                1 -> "title: \"the dark one\""
                2 -> "title:"
                else -> "title: 'quoted title'"
            }
            "created", "updated", "reminder", "conflictAt", "trashedAt" ->
                "$k: ${stamps[r.nextInt(stamps.size)]}"
            "conflictOf" -> "conflictOf: ${if (r.nextBoolean()) "01J9F2K8ZZ1A2B3C4D5E6F7G8H" else ""}"
            "pinned", "archived" -> "$k: ${boolWords[r.nextInt(boolWords.size)]}"
            "color" -> "color: ${colors[r.nextInt(colors.size)]}"
            "labels" -> "labels: ${labelStyles[r.nextInt(labelStyles.size)]}"
            else -> "type: ${typeWords[r.nextInt(typeWords.size)]}"
        }
    }

    private fun unknownLine(r: Random): String {
        val name = unknownNames[r.nextInt(unknownNames.size)]
        return if (r.nextInt(8) == 0) "$name:" else "$name: ${unknownValues[r.nextInt(unknownValues.size)]}"
    }
}
