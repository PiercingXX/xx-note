package com.piercingxx.xxnote.ui.share

import com.piercingxx.xxnote.core.Frontmatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2: title from subject or first line, body from the extra. Pure — no
 * Android, no vault. Manifest share filters are pinned here so the
 * intent-filter cannot drift from the mapping.
 */
class ShareMappingTest {

    @Test
    fun titleComesFromSubjectWhenPresent() {
        val mapped = ShareMapping.map(
            subject = "A page title",
            extraText = "https://example.com/path",
        )
        assertEquals("A page title", mapped.title)
        assertEquals("https://example.com/path", mapped.body)
    }

    @Test
    fun titleFallsBackToFirstLineOfTheExtra() {
        val mapped = ShareMapping.map(
            subject = null,
            extraText = "Selected paragraph one.\n\nStill the extra.",
        )
        assertEquals("Selected paragraph one.", mapped.title)
        assertEquals("Selected paragraph one.\n\nStill the extra.", mapped.body)
    }

    @Test
    fun blankSubjectDoesNotWin() {
        val mapped = ShareMapping.map(subject = "   ", extraText = "Hello\nworld")
        assertEquals("Hello", mapped.title)
        assertEquals("Hello\nworld", mapped.body)
    }

    @Test
    fun emptyShareUsesFallbackTitle() {
        val mapped = ShareMapping.map(subject = null, extraText = null)
        assertEquals(ShareMapping.FALLBACK_TITLE, mapped.title)
        assertEquals("", mapped.body)
    }

    @Test
    fun sendMultipleJoinsExtraTexts() {
        val mapped = ShareMapping.map(
            subject = null,
            extraText = null,
            extraTexts = listOf("first clip", "second clip"),
        )
        assertEquals("first clip", mapped.title)
        assertEquals("first clip\n\nsecond clip", mapped.body)
    }

    @Test
    fun attachmentStemTitlesWhenThereIsNoText() {
        val mapped = ShareMapping.map(
            subject = null,
            extraText = null,
            attachmentNames = listOf("meeting-notes.txt"),
        )
        assertEquals("meeting-notes", mapped.title)
        assertEquals("", mapped.body)
    }

    @Test
    fun onlyTextStarMimesAreAccepted() {
        assertTrue(ShareMapping.isTextMime("text/plain"))
        assertTrue(ShareMapping.isTextMime("text/markdown; charset=utf-8"))
        assertTrue(ShareMapping.isTextMime("TEXT/HTML"))
        assertFalse(ShareMapping.isTextMime("image/jpeg"))
        assertFalse(ShareMapping.isTextMime("application/pdf"))
        assertFalse(ShareMapping.isTextMime(null))
        assertFalse(ShareMapping.isTextMime("text/"))
        assertFalse(ShareMapping.isTextMime("application/json"))
    }

    @Test
    fun noteTextIsADirtyNewMarkdownFile() {
        val whole = ShareMapping.noteText(
            id = "01JSHARETEST00000000000001",
            title = "A page title",
            body = "https://example.com/path",
            nowIso = "2026-09-05T12:00:00Z",
        )
        val doc = Frontmatter.parse(whole)
        assertEquals("01JSHARETEST00000000000001", doc.id)
        assertEquals("A page title", doc.title)
        assertEquals("https://example.com/path\n", doc.bodyText)
        assertTrue(whole.startsWith("---\n"))
    }

    @Test
    fun appendLinksAddsVaultRelativeMarkdown() {
        assertEquals(
            "hello\n\n[clip.txt](attachments/abc.txt)\n",
            ShareMapping.appendLinks("hello", listOf("[clip.txt](attachments/abc.txt)")),
        )
        assertEquals(
            "[clip.txt](attachments/abc.txt)\n",
            ShareMapping.appendLinks("", listOf("[clip.txt](attachments/abc.txt)")),
        )
    }

    @Test
    fun manifestDeclaresSendAndSendMultipleTextFilters() {
        val manifest = sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()
        assertTrue(manifest.contains("android.intent.action.SEND"))
        assertTrue(manifest.contains("android.intent.action.SEND_MULTIPLE"))
        assertTrue(manifest.contains("text/plain"))
        assertTrue(manifest.contains("text/*"))
        assertTrue(manifest.contains("android.intent.category.DEFAULT"))
    }
}
