package com.piercingxx.xxnote.sync

import com.piercingxx.xxnote.core.ChecklistMerge
import com.piercingxx.xxnote.core.ChecklistMergeResult
import com.piercingxx.xxnote.core.Diff3
import com.piercingxx.xxnote.core.Diff3Result
import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.core.FrontmatterDocument
import com.piercingxx.xxnote.core.NoteType
import java.time.Instant
import java.time.OffsetDateTime

object MergeEngine {

    sealed interface MergeOutcome {
        data class Merged(val wholeFileText: String) : MergeOutcome
        data class Fork(val reason: String) : MergeOutcome
    }

    private const val OPEN_DELIMITER = "---"
    private const val CLOSE_YAML_END = "..."
    private const val BOM = "\uFEFF"

    private val OWNED_KEYS = setOf(
        "id", "title", "created", "updated", "pinned", "archived", "color",
        "labels", "type", "reminder", "conflictOf", "conflictAt", "trashedAt",
    )

    fun merge(baseWholeFile: String, localWholeFile: String, remoteWholeFile: String): MergeOutcome {
        val base = Frontmatter.parse(baseWholeFile)
        val local = Frontmatter.parse(localWholeFile)
        val remote = Frontmatter.parse(remoteWholeFile)

        unknownKeyVerdict(local, remote)?.let { return it }

        // Owned scalar keys (three-way, §8 authority): title / type / created.
        // Remote adopted when local == base; local kept when remote == base;
        // BOTH moved apart → fork naming the key. Without this a remote
        // retitle would merge clean against a local body edit and silently
        // annihilate the title change. (color stays local-authority per §8;
        // id is identity and never merges — see `original id is untouched`.)
        if (scalarConflict(base.title, local.title, remote.title)) {
            return MergeOutcome.Fork("frontmatter: title")
        }
        val title = scalarMerged(base.title, local.title, remote.title)
        if (scalarConflict(base.type.name, local.type.name, remote.type.name)) {
            return MergeOutcome.Fork("frontmatter: type")
        }
        // Never null in practice: parsed NoteType is always one of the enum names.
        val typeName = checkNotNull(scalarMerged(base.type.name, local.type.name, remote.type.name))
        val type = NoteType.valueOf(typeName)
        if (scalarConflict(base.created, local.created, remote.created)) {
            return MergeOutcome.Fork("frontmatter: created")
        }
        val created = scalarMerged(base.created, local.created, remote.created)

        val body = if (
            base.type == NoteType.CHECKLIST &&
            local.type == NoteType.CHECKLIST &&
            remote.type == NoteType.CHECKLIST
        ) {
            when (val result = ChecklistMerge.merge(base.bodyText, local.bodyText, remote.bodyText)) {
                is ChecklistMergeResult.Merged -> result.body
                ChecklistMergeResult.Fork -> return MergeOutcome.Fork("checklist conflict")
            }
        } else {
            when (val result = Diff3.merge(base.bodyText.lines(), local.bodyText.lines(), remote.bodyText.lines())) {
                is Diff3Result.Merged -> result.lines.joinToString("\n")
                is Diff3Result.Conflicted -> return MergeOutcome.Fork("prose conflict")
            }
        }

        val pinnedMerged = orTowardTrue(base.pinned, local.pinned, remote.pinned)
        val archivedMerged = orTowardFalse(base.archived, local.archived, remote.archived)
        val labelsMerged = labelsUnion(base.labels, local.labels, remote.labels)
        val updatedFromRemote = laterUpdated(local.updated, remote.updated)

        val mutated = local.rewritten {
            pinned = pinnedMerged
            archived = archivedMerged
            if (updatedFromRemote != null) updated = updatedFromRemote
            if (labelsMerged != labels) {
                labels.clear()
                labels.addAll(labelsMerged)
            }
            if (title != local.title) this.title = title
            if (type != local.type) this.type = type
            if (created != local.created) this.created = created
        }

        val assembled = injectRemoteOnlyUnknowns(mutated, local, remote)
        val reparsed = Frontmatter.parse(assembled)
        return MergeOutcome.Merged(assembled.removeSuffix(reparsed.bodyText) + body)
    }

    /**
     * True when local and remote both moved apart from base on one scalar —
     * an ambiguity no table row may guess at (fork instead). Null counts as a
     * value here: an absent key can move apart just like a written one.
     */
    private fun scalarConflict(baseValue: String?, localValue: String?, remoteValue: String?): Boolean =
        localValue != remoteValue && localValue != baseValue && remoteValue != baseValue

    /** The side the three-way merge keeps; [scalarConflict] must be false. */
    private fun scalarMerged(baseValue: String?, localValue: String?, remoteValue: String?): String? =
        if (localValue == baseValue) remoteValue else localValue

    private fun labelsUnion(vararg sides: List<String>): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (side in sides) {
            for (label in side) {
                if (seen.add(label.lowercase())) out.add(label)
            }
        }
        return out
    }

    private fun orTowardTrue(vararg values: Boolean?): Boolean? =
        if (values.any { it == true }) true else if (values.any { it == false }) false else null

    private fun orTowardFalse(vararg values: Boolean?): Boolean? =
        if (values.any { it == false }) false else if (values.any { it == true }) true else null

    private fun laterUpdated(localValue: String?, remoteValue: String?): String? {
        val l = instantOf(localValue)
        val r = instantOf(remoteValue)
        return if (l != null && r != null && r > l) remoteValue else null
    }

    private fun instantOf(value: String?): Instant? {
        if (value == null) return null
        return runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrNull()
    }

    private fun unknownKeyVerdict(local: FrontmatterDocument, remote: FrontmatterDocument): MergeOutcome.Fork? {
        val localScan = scan(local.raw())
        val remoteScan = scan(remote.raw())
        for ((key, entry) in localScan.entries) {
            if (key in OWNED_KEYS) continue
            val other = remoteScan.entries[key] ?: continue
            if (entry.scalar != other.scalar) return MergeOutcome.Fork("frontmatter: $key")
        }
        return null
    }

    private fun injectRemoteOnlyUnknowns(
        text: String,
        local: FrontmatterDocument,
        remote: FrontmatterDocument,
    ): String {
        val localKeys = scan(local.raw()).entries.keys
        val lines = scan(remote.raw()).entries
            .filterKeys { it !in OWNED_KEYS && it !in localKeys }
            .values
            .map { it.line }
        if (lines.isEmpty()) return text

        val block = lines.joinToString("\n") + "\n"
        val closeStart = scan(text).closeStart
        if (closeStart != null) {
            return text.substring(0, closeStart) + block + text.substring(closeStart)
        }
        val bom = if (text.startsWith(BOM)) BOM else ""
        val rest = text.substring(bom.length)
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        return bom + OPEN_DELIMITER + eol + block + OPEN_DELIMITER + eol + rest
    }

    private class RawEntry(val scalar: String, val line: String)

    private class Scan(val entries: Map<String, RawEntry>, val closeStart: Int?)

    private fun scan(raw: String): Scan {
        val entries = LinkedHashMap<String, RawEntry>()
        val bom = if (raw.startsWith(BOM)) BOM else ""
        val rest = raw.substring(bom.length)
        val firstBreak = rest.indexOf('\n')
        val firstLine = (if (firstBreak >= 0) rest.substring(0, firstBreak) else rest).removeSuffix("\r")
        if (firstLine != OPEN_DELIMITER) return Scan(entries, null)

        var pos = if (firstBreak >= 0) firstBreak + 1 else rest.length
        while (true) {
            if (pos >= rest.length) return Scan(emptyMap(), null)
            val break_ = rest.indexOf('\n', pos)
            val end = if (break_ >= 0) break_ else rest.length
            val content = rest.substring(pos, end).removeSuffix("\r")
            if (content == OPEN_DELIMITER || content == CLOSE_YAML_END) return Scan(entries, pos)
            if (break_ < 0) return Scan(emptyMap(), null)
            when (val key = keyShapeOf(content)) {
                null -> return Scan(emptyMap(), null)
                "" -> Unit
                else -> entries.putIfAbsent(key, RawEntry(comparableScalar(content), content))
            }
            pos = end + 1
        }
    }

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

    private fun comparableScalar(line: String): String {
        val colon = line.indexOf(':')
        var v = line.substring(colon + 1)
        if (v.startsWith(" ")) v = v.substring(1)
        v = v.trimEnd()
        if (v.length >= 2) {
            val first = v.first()
            val last = v.last()
            val matched = (first == '"' && last == '"') || (first == '\'' && last == '\'')
            if (matched) v = v.substring(1, v.length - 1)
        }
        return v
    }
}
