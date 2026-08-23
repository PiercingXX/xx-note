package com.piercingxx.xxnote.ui.editor

import com.piercingxx.xxnote.core.Frontmatter
import com.piercingxx.xxnote.ui.labels.LabelIntent
import com.piercingxx.xxnote.ui.labels.foldLabelIntents
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WS8 gate: the editor's label intents fold into the ONE debounced save —
 * replayed in recording order over the assembled save text, idempotently, so
 * a retried save converges to the same bytes and never races the pipeline.
 */
class EditorLabelFoldTest {

    private val base = "---\nid: x\nlabels: [a]\nplugin_note: keep me\n---\nbody\n"

    @Test
    fun addThenRemoveOfTheSameNameNetsTheOriginalBytes() {
        val out = foldLabelIntents(
            base,
            listOf(LabelIntent(add = true, name = "b"), LabelIntent(add = false, name = "B")),
        )
        assertEquals(base, out)
    }

    @Test
    fun removeThenAddReAppliesWithNewCasing() {
        val out = foldLabelIntents(
            base,
            listOf(LabelIntent(add = false, name = "a"), LabelIntent(add = true, name = "A")),
        )
        assertEquals(listOf("A"), Frontmatter.parse(out).labels)
    }

    @Test
    fun unknownKeysSurviveTheFold() {
        val out = foldLabelIntents(base, listOf(LabelIntent(add = true, name = "z")))
        assertEquals("keep me", Frontmatter.parse(out).let { doc ->
            doc.raw().lineSequence().firstOrNull { it.startsWith("plugin_note") }?.substringAfter(": ") ?: ""
        })
        assertEquals(listOf("a", "z"), Frontmatter.parse(out).labels)
    }
}
