package com.piercingxx.xxnote.ui.share

import com.piercingxx.xxnote.core.NoteType
import com.piercingxx.xxnote.ui.editor.buildSaveText
import com.piercingxx.xxnote.ui.grid.captureTemplate

/**
 * Pure title/body mapping for share-to-note (P2). No Android types: JVM tests
 * cover subject-or-first-line titles and "body from the extra".
 */
object ShareMapping {

    const val FALLBACK_TITLE = "Shared"

    data class Mapped(val title: String, val body: String)

    /**
     * Title from [subject] when non-blank, else the first non-blank line of
     * the extra, else an attachment stem, else [FALLBACK_TITLE]. Body is the
     * extra (joined when several texts were shared).
     */
    fun map(
        subject: String?,
        extraText: String?,
        extraTexts: List<String> = emptyList(),
        attachmentNames: List<String> = emptyList(),
    ): Mapped {
        val body = combinedBody(extraText, extraTexts)
        val title = subject?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstLine(body)
            ?: attachmentNames.firstOrNull()?.let { stemOf(it) }?.takeIf { it.isNotEmpty() }
            ?: FALLBACK_TITLE
        return Mapped(title, body)
    }

    fun combinedBody(extraText: String?, extraTexts: List<String>): String {
        val parts = ArrayList<String>(1 + extraTexts.size)
        extraText?.takeIf { it.isNotBlank() }?.let { parts.add(it.trimEnd()) }
        for (text in extraTexts) {
            val trimmed = text.trimEnd()
            if (trimmed.isNotEmpty() && trimmed != extraText?.trimEnd()) parts.add(trimmed)
        }
        return parts.joinToString("\n\n")
    }

    fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    fun isTextMime(mime: String?): Boolean {
        val m = mime?.lowercase()?.substringBefore(';')?.trim() ?: return false
        return m == "text/plain" || (m.startsWith("text/") && m.length > "text/".length)
    }

    fun extFor(mime: String?, filename: String): String {
        val fromName = filename.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .filter { it.isLetterOrDigit() }
        if (fromName.isNotEmpty() && fromName.length <= 8) return fromName
        val sub = mime?.substringAfter('/')?.substringBefore(';')?.lowercase()?.trim().orEmpty()
        return when (sub) {
            "", "*", "plain" -> "txt"
            "markdown", "x-markdown" -> "md"
            else -> sub.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "txt" }
        }
    }

    fun safeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "shared.txt" }
        val cleaned = buildString {
            for (c in base) {
                when {
                    c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' -> append(c)
                    c == ' ' -> append('_')
                }
            }
        }.trim('.', '_').ifBlank { "shared.txt" }
        return cleaned.take(80)
    }

    fun appendLinks(body: String, links: List<String>): String {
        if (links.isEmpty()) return body
        val joined = links.joinToString("\n")
        val prefix = body.trimEnd()
        return if (prefix.isEmpty()) "$joined\n" else "$prefix\n\n$joined\n"
    }

    fun noteText(id: String, title: String, body: String, nowIso: String): String {
        val skeleton = captureTemplate(id, title, NoteType.NOTE, nowIso)
        val bodyText = if (body.isEmpty() || body.endsWith("\n")) body else "$body\n"
        return buildSaveText(skeleton, title, bodyText, nowIso)
    }

    private fun stemOf(filename: String): String {
        val name = safeName(filename)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
