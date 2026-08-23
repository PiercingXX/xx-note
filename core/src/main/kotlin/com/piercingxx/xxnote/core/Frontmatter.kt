package com.piercingxx.xxnote.core

/**
 * The behavioral kind of a note (design §8).
 *
 * `checklist` enables the sort-to-bottom save-time rewrite (§7.1, D18), so this
 * is behavior, not cosmetics. An unknown or corrupted `type:` value degrades to
 * [NOTE], which does *less* to the file — the safe direction.
 */
enum class NoteType {

    /** A plain markdown note. The default for absent, empty, or corrupt `type:` values. */
    NOTE,

    /** A GFM task-list note; checked items sort to the bottom on editor save. */
    CHECKLIST,
}

/**
 * The §8 keys XX-Note owns, as mutable state handed to [FrontmatterDocument.rewritten].
 *
 * Constructed pre-populated with the document's parsed values; a lambda assigns
 * only what changes. Assigning `null` to a nullable key removes its line from an
 * existing block (and omits it from a fresh one). [labels] is mutated through its
 * ordinary [MutableList] operations; any structural change marks it dirty.
 * Keys never assigned survive the rewrite byte-for-byte.
 */
class OwnedKeys internal constructor(
    id: String?,
    title: String?,
    created: String?,
    updated: String?,
    pinned: Boolean?,
    archived: Boolean?,
    color: String?,
    labels: List<String>,
    type: NoteType,
    reminder: String?,
    conflictOf: String?,
    conflictAt: String?,
    trashedAt: String?,
) {
    internal val touched = LinkedHashSet<String>()
    private val tracker = TrackingLabels(labels)

    /** Immutable note identity (ULID). Absent means "assign on first read". Pass-through. */
    var id: String? = id
        set(value) { field = value; touched.add(K_ID) }

    /** Display title; may be empty. Pass-through. */
    var title: String? = title
        set(value) { field = value; touched.add(K_TITLE) }

    /** Creation instant, RFC 3339 UTC string. Pass-through, never reformatted. */
    var created: String? = created
        set(value) { field = value; touched.add(K_CREATED) }

    /** Content-modified instant, RFC 3339 UTC string. Pass-through, never reformatted. */
    var updated: String? = updated
        set(value) { field = value; touched.add(K_UPDATED) }

    /** Pinned flag; `null` = absent or unparseable in the source. */
    var pinned: Boolean? = pinned
        set(value) { field = value; touched.add(K_PINNED) }

    /** Archived flag; `null` = absent or unparseable in the source. */
    var archived: Boolean? = archived
        set(value) { field = value; touched.add(K_ARCHIVED) }

    /** Keep colour name rendered as a surface tone (D12). Pass-through. */
    var color: String? = color
        set(value) { field = value; touched.add(K_COLOR) }

    /** Flat label list; Obsidian reads these as tags. Empty when absent. */
    val labels: MutableList<String>
        get() = tracker

    /** Note kind; corrupt values degrade to [NoteType.NOTE]. */
    var type: NoteType = type
        set(value) { field = value; touched.add(K_TYPE) }

    /** Reserved for reminders (non-goal in v1, forward-compatible). Pass-through. */
    var reminder: String? = reminder
        set(value) { field = value; touched.add(K_REMINDER) }

    /** ULID of the original note when this document is a conflict fork (§7). Pass-through. */
    var conflictOf: String? = conflictOf
        set(value) { field = value; touched.add(K_CONFLICT_OF) }

    /** Fork instant, RFC 3339 UTC string (§7). Pass-through. */
    var conflictAt: String? = conflictAt
        set(value) { field = value; touched.add(K_CONFLICT_AT) }

    /** Trash stamp written on delete (§9); absent while the note lives. Pass-through. */
    var trashedAt: String? = trashedAt
        set(value) { field = value; touched.add(K_TRASHED_AT) }

    internal fun labelsTouched(): Boolean = tracker.dirty

    internal fun targetOf(key: String): Any? = when (key) {
        K_ID -> id
        K_TITLE -> title
        K_CREATED -> created
        K_UPDATED -> updated
        K_PINNED -> pinned
        K_ARCHIVED -> archived
        K_COLOR -> color
        K_LABELS -> tracker.snapshot()
        K_TYPE -> type
        K_REMINDER -> reminder
        K_CONFLICT_OF -> conflictOf
        K_CONFLICT_AT -> conflictAt
        K_TRASHED_AT -> trashedAt
        else -> null
    }

    internal companion object {
        internal val ORDER = listOf(
            K_ID, K_TITLE, K_CREATED, K_UPDATED, K_PINNED, K_ARCHIVED, K_COLOR,
            K_LABELS, K_TYPE, K_REMINDER, K_CONFLICT_OF, K_CONFLICT_AT, K_TRASHED_AT,
        )
    }
}

/**
 * Byte-exact YAML-frontmatter view of one markdown note (design §4.3, §8; todo rule 6).
 *
 * Parsing is lenient by design. An unrecognized key is preserved verbatim — in
 * position, spelling, quoting, and indentation — through any rewrite, so vault
 * metadata written by Obsidian plugins survives. Duplicate owned keys: reads
 * take the FIRST occurrence of a key, while a rewrite collapses duplicates,
 * rendering the canonical line at the LAST occurrence's position and dropping
 * the earlier ones (documented, tested behavior). A malformed block (an interior
 * line with no `key:` shape, or a block never closed before end-of-input) is
 * treated as body text rather than discarded; nothing is ever dropped, silently
 * repaired, or normalized. Every accessor is a pure read and never alters the
 * bytes returned by [raw].
 *
 * Line endings: LF and CRLF are both tolerated and preserved per line. A UTF-8
 * BOM ahead of the opening `---` is tolerated and stays at the head of the file
 * on rewrite. Only the first line (exactly `---`) opens a block, and only the
 * first subsequent exact `---` or `...` closes it — further `---` lines belong
 * to the body.
 */
class FrontmatterDocument internal constructor(
    private val rawText: String,
    private val bom: String,
    private val block: BlockData?,
    private val sawOpeningDelimiter: Boolean,
) {
    /** True when a complete, well-formed frontmatter block was parsed. */
    val hasFrontmatter: Boolean
        get() = block != null

    /**
     * True when an opening `---` existed but the block could not be parsed as
     * frontmatter (unclosed, or an interior line with no `key:` shape). The
     * entire region degrades to [bodyText]; [hasFrontmatter] is false.
     */
    val isMalformed: Boolean
        get() = block == null && sawOpeningDelimiter

    /**
     * Everything after the frontmatter block, verbatim — including line endings,
     * a trailing newline iff the file had one, and any number of `---` lines.
     * When there is no frontmatter, or the block is malformed, this is the
     * entire raw text.
     */
    val bodyText: String
        get() = block?.body?.let { rawText.substring(it) } ?: rawText

    val id: String? get() = scalarOf(K_ID)
    val title: String? get() = scalarOf(K_TITLE)
    val created: String? get() = scalarOf(K_CREATED)
    val updated: String? get() = scalarOf(K_UPDATED)

    val pinned: Boolean?
        get() = scalarOf(K_PINNED)?.let(::lenientBool)

    val archived: Boolean?
        get() = scalarOf(K_ARCHIVED)?.let(::lenientBool)

    val color: String? get() = scalarOf(K_COLOR)

    /** Owned labels; empty list when the key is absent or holds an empty flow sequence. */
    val labels: List<String>
        get() = scalarOf(K_LABELS)?.let(::flowItems) ?: emptyList()

    val type: NoteType
        get() = scalarOf(K_TYPE).let(::lenientType)

    val reminder: String? get() = scalarOf(K_REMINDER)
    val conflictOf: String? get() = scalarOf(K_CONFLICT_OF)
    val conflictAt: String? get() = scalarOf(K_CONFLICT_AT)
    val trashedAt: String? get() = scalarOf(K_TRASHED_AT)

    /**
     * The original bytes this document was parsed from, unchanged — always
     * byte-exact, whatever the accessors or rewrites have been.
     */
    fun raw(): String = rawText

    /**
     * Re-renders the note with the mutations applied by [mutate] to the owned
     * §8 keys, leaving everything XX-Note does not own untouched.
     *
     * Guarantees: unknown keys survive verbatim, in position; unmutated owned
     * keys keep their raw lines (they are never re-rendered); mutated keys
     * render canonically (`key: value`, booleans as `true`/`false`, `type` in
     * lowercase, labels as `[a, b]` with per-item quoting when needed); new
     * keys append at the end of the block in §8 table order; the delimiters,
     * the BOM, the body, and every line ending are preserved. Assigning `null`
     * removes the key's lines. When the document has no frontmatter, or a
     * malformed one, a fresh well-formed block carrying exactly the assigned
     * keys is prepended above the intact body text (a malformed old block
     * remains part of the body — nothing is discarded).
     *
     * An empty mutation is a strict no-op: the result equals [raw] byte-for-byte.
     *
     * @throws IllegalArgumentException if a mutated string value contains a
     *   line break, which cannot fit a single-line frontmatter entry.
     */
    fun rewritten(mutate: OwnedKeys.() -> Unit): String {
        val current = OwnedKeys(
            id = id, title = title, created = created, updated = updated,
            pinned = pinned, archived = archived, color = color,
            labels = labels, type = type, reminder = reminder,
            conflictOf = conflictOf, conflictAt = conflictAt, trashedAt = trashedAt,
        )
        val baseline = OwnedKeys(
            id = id, title = title, created = created, updated = updated,
            pinned = pinned, archived = archived, color = color,
            labels = labels, type = type, reminder = reminder,
            conflictOf = conflictOf, conflictAt = conflictAt, trashedAt = trashedAt,
        )
        current.mutate()

        val data = block
            ?: return prependFresh(current, baseline, rawText, bom)

        val replacement = HashMap<String, String>()
        val removals = HashSet<String>()
        val additions = ArrayList<String>()
        for (key in OwnedKeys.ORDER) {
            if (!isTouched(current, key)) continue
            val target = current.targetOf(key)
            if (target == baseline.targetOf(key)) continue
            if (target == null) {
                if (data.lines.any { it.key == key }) removals.add(key)
                continue
            }
            val line = renderEntry(key, target)
            if (data.lines.any { it.key == key }) replacement[key] = line else additions.add(line)
        }
        if (replacement.isEmpty() && removals.isEmpty() && additions.isEmpty()) return rawText

        val lastIndexOf = HashMap<String, Int>()
        data.lines.forEachIndexed { i, l -> l.key?.let { lastIndexOf[it] = i } }

        val out = StringBuilder(bom)
        out.append(OPEN_DELIMITER).append(data.openTerminator)
        data.lines.forEachIndexed { i, line ->
            val key = line.key
            when {
                key != null && key in removals -> Unit
                key != null && key in replacement -> {
                    if (lastIndexOf[key] == i) out.append(replacement.getValue(key)).append(line.terminator)
                }
                else -> out.append(line.text).append(line.terminator)
            }
        }
        for (line in additions) out.append(line).append(data.appendTerminator)
        out.append(data.closingText).append(data.closingTerminator)
        out.append(rawText, data.body, rawText.length)
        return out.toString()
    }

    private fun isTouched(after: OwnedKeys, key: String): Boolean =
        if (key == K_LABELS) after.labelsTouched() else key in after.touched

    private fun scalarOf(key: String): String? =
        block?.entries?.get(key)?.scalar

    private fun prependFresh(after: OwnedKeys, baseline: OwnedKeys, rawText: String, bom: String): String {
        val lines = ArrayList<String>()
        for (key in OwnedKeys.ORDER) {
            if (!isTouched(after, key)) continue
            val target = after.targetOf(key)
            if (target == null) continue
            if (target == baseline.targetOf(key)) continue
            if (target is List<*> && target.isEmpty()) continue
            lines.add(renderEntry(key, target))
        }
        if (lines.isEmpty()) return rawText

        val eol = if (rawText.contains("\r\n")) "\r\n" else "\n"
        val rest = rawText.substring(bom.length)
        val out = StringBuilder(bom)
        out.append(OPEN_DELIMITER).append(eol)
        for (line in lines) out.append(line).append(eol)
        out.append(CLOSE_DELIMITER)
        if (rest.isNotEmpty()) out.append(eol)
        out.append(rest)
        return out.toString()
    }

    internal class BlockData(
        val lines: List<Line>,
        val entries: Map<String, Value>,
        val openTerminator: String,
        val closingText: String,
        val closingTerminator: String,
        val appendTerminator: String,
        val body: Int,
    )

    internal class Line(val text: String, val terminator: String, val key: String?)

    internal class Value(val scalar: String)
}

/** Tracks whether any structural mutation happened through the [MutableList] surface. */
internal class TrackingLabels(initial: List<String>) : AbstractMutableList<String>() {

    internal var dirty = false
    private val items = ArrayList(initial)

    override val size: Int get() = items.size
    override fun get(index: Int): String = items[index]
    override fun set(index: Int, element: String): String {
        dirty = true
        return items.set(index, element)
    }

    override fun add(index: Int, element: String) {
        dirty = true
        items.add(index, element)
    }

    override fun removeAt(index: Int): String {
        dirty = true
        return items.removeAt(index)
    }

    internal fun snapshot(): List<String> = items.toList()
}

/**
 * Frontmatter entry point: parses [text] into a [FrontmatterDocument].
 *
 * Totality: every input yields a usable document. Absent or malformed blocks
 * degrade to body text (§4.3, R5) and [FrontmatterDocument.raw] always echoes
 * [text] byte-for-byte. Pure function; reading never mutates anything.
 */
object Frontmatter {

    /** Parses one note's full text into its byte-exact frontmatter document. */
    fun parse(text: String): FrontmatterDocument {
        val bom = if (text.startsWith(BOM)) BOM else ""
        val rest = text.substring(bom.length)

        val firstBreak = rest.indexOf('\n')
        val firstLine = (if (firstBreak >= 0) rest.substring(0, firstBreak) else rest).removeSuffix("\r")
        if (firstLine != OPEN_DELIMITER) {
            return FrontmatterDocument(text, bom, block = null, sawOpeningDelimiter = false)
        }

        val openEnd = if (firstBreak >= 0) firstBreak + 1 else rest.length
        val openTerminator = if (firstBreak >= 0) {
            if (firstBreak > 0 && rest[firstBreak - 1] == '\r') "\r\n" else "\n"
        } else ""

        val lines = ArrayList<FrontmatterDocument.Line>()
        var pos = openEnd
        while (true) {
            if (pos >= rest.length) {
                return FrontmatterDocument(text, bom, block = null, sawOpeningDelimiter = true)
            }
            val break_ = rest.indexOf('\n', pos)
            val end = if (break_ >= 0) break_ else rest.length
            val terminator = when {
                break_ < 0 -> ""
                end > pos && rest[end - 1] == '\r' -> "\r\n"
                else -> "\n"
            }
            val contentEnd = if (terminator == "\r\n") end - 1 else end
            val content = rest.substring(pos, contentEnd)
            if (content == OPEN_DELIMITER || content == CLOSE_YAML_END) {
                val entries = LinkedHashMap<String, FrontmatterDocument.Value>()
                for (line in lines) {
                    val key = line.key ?: continue
                    if (key.isEmpty()) continue
                    entries.putIfAbsent(key, FrontmatterDocument.Value(scalarAfter(contentOf(line))))
                }
                val appendTerminator = lines.lastOrNull()?.terminator
                    ?.takeIf { it.isNotEmpty() }
                    ?: terminator.ifEmpty { "\n" }
                val body = bom.length + end + if (break_ >= 0) 1 else 0
                return FrontmatterDocument(
                    text, bom,
                    FrontmatterDocument.BlockData(
                        lines, entries, openTerminator, content, terminator, appendTerminator, body,
                    ),
                    sawOpeningDelimiter = true,
                )
            }
            val key = keyShapeOf(content) ?: return FrontmatterDocument(text, bom, block = null, sawOpeningDelimiter = true)
            lines.add(FrontmatterDocument.Line(content, terminator, key))
            pos = end + 1
        }
    }

    private fun contentOf(line: FrontmatterDocument.Line): String {
        val colon = line.text.indexOf(':')
        return if (colon >= 0) line.text.substring(colon + 1) else ""
    }

    /** The mapping-entry key, `""` for a blank line, or `null` when the line cannot be one. */
    private fun keyShapeOf(content: String): String? {
        if (content.isBlank()) return ""
        val colon = content.indexOf(':')
        if (colon <= 0) return null
        val key = content.substring(0, colon).trim()
        if (key.isEmpty()) return null
        val after = content.getOrNull(colon + 1)
        if (after != null && after != ' ' && after != '\t') return null
        return key
    }
}

private const val OPEN_DELIMITER = "---"
private const val CLOSE_DELIMITER = "---"
private const val CLOSE_YAML_END = "..."
private const val BOM = "\uFEFF"

private const val K_ID = "id"
private const val K_TITLE = "title"
private const val K_CREATED = "created"
private const val K_UPDATED = "updated"
private const val K_PINNED = "pinned"
private const val K_ARCHIVED = "archived"
private const val K_COLOR = "color"
private const val K_LABELS = "labels"
private const val K_TYPE = "type"
private const val K_REMINDER = "reminder"
private const val K_CONFLICT_OF = "conflictOf"
private const val K_CONFLICT_AT = "conflictAt"
private const val K_TRASHED_AT = "trashedAt"

private fun scalarAfter(raw: String): String {
    var v = raw
    if (v.startsWith(" ")) v = v.substring(1)
    v = v.trimEnd()
    return unquote(v)
}

/** Strips one layer of matching quotes, resolving `\"`/`\\` inside double quotes only. */
private fun unquote(v: String): String {
    if (v.length < 2) return v
    val first = v.first()
    val last = v.last()
    val matched = (first == '"' && last == '"') || (first == '\'' && last == '\'')
    if (!matched) return v
    val inner = v.substring(1, v.length - 1)
    if (first != '"') return inner
    return buildString(inner.length) {
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                val n = inner[i + 1]
                if (n == '\\' || n == '"') {
                    append(n)
                    i += 2
                    continue
                }
            }
            append(c)
            i++
        }
    }
}

private fun lenientBool(scalar: String): Boolean? = when (scalar.lowercase()) {
    "true", "yes", "on", "1" -> true
    "false", "no", "off", "0" -> false
    else -> null
}

private fun lenientType(scalar: String?): NoteType =
    if (scalar != null && scalar.equals("checklist", ignoreCase = true)) NoteType.CHECKLIST else NoteType.NOTE

/** Flow sequence `[a, b]` (quoted items tolerated) or a bare scalar as a single-item list. */
private fun flowItems(scalar: String): List<String> {
    val t = scalar.trim()
    if (!t.startsWith("[")) return if (t.isEmpty()) emptyList() else listOf(unquote(t))
    val inner = if (t.endsWith("]")) t.substring(1, t.length - 1) else t.substring(1)
    val out = ArrayList<String>()
    for (segment in splitTopLevel(inner)) {
        if (segment.isBlank()) continue
        out.add(unquote(segment.trim()))
    }
    return out
}

private fun splitTopLevel(inner: String): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var single = false
    var double = false
    var escaped = false
    for (c in inner) {
        when {
            escaped -> { current.append(c); escaped = false }
            double && c == '\\' -> { current.append(c); escaped = true }
            c == ',' && !single && !double -> { parts.add(current.toString()); current.setLength(0) }
            else -> {
                if (c == '\'' && !double) single = !single
                else if (c == '"' && !single) double = !double
                current.append(c)
            }
        }
    }
    parts.add(current.toString())
    return parts
}

private fun renderEntry(key: String, target: Any?): String = "$key: ${renderValue(target)}"

private fun renderValue(target: Any?): String = when (target) {
    is Boolean -> if (target) "true" else "false"
    is NoteType -> if (target == NoteType.CHECKLIST) "checklist" else "note"
    is List<*> -> renderFlow(target.filterIsInstance<String>())
    is String -> renderPlain(target)
    else -> throw IllegalArgumentException("unsupported frontmatter value: $target")
}

private fun renderPlain(value: String): String {
    require(!value.contains('\n') && !value.contains('\r')) {
        "frontmatter value for a single-line key must not contain a line break"
    }
    return if (needsQuoting(value)) quote(value) else value
}

private fun needsQuoting(v: String): Boolean {
    if (v.isEmpty() || v != v.trim()) return true
    if (v.contains(": ") || v.endsWith(":")) return true
    if (v.contains(" #")) return true
    if (v.contains(',') || v.contains('[') || v.contains(']')) return true
    val first = v.first()
    if (first == '[' || first == ']' || first == '{' || first == '}' ||
        first == '\'' || first == '"' || first == '#' || first == '&' ||
        first == '*' || first == '!' || first == '|' || first == '>' ||
        first == '%' || first == '@' || first == '`' || first == '-' ||
        first == '?' || first == ':'
    ) return true
    return false
}

private fun quote(v: String): String =
    "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun renderFlow(items: List<String>): String =
    items.joinToString(", ", "[", "]") { if (needsQuoting(it)) quote(it) else it }
