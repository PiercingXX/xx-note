package com.piercingxx.xxnote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlugTest {

    @Test
    fun `simple title becomes hyphenated lowercase`() {
        assertEquals("grocery-list", Slug.of("Grocery list"))
    }

    @Test
    fun `diacritics punctuation and runs collapse`() {
        assertEquals("cafe-the-dark-one", Slug.of("Café — the dark one!"))
    }

    @Test
    fun `whitespace-only title falls back`() {
        assertEquals("note", Slug.of("   "))
    }

    @Test
    fun `punctuation-only title falls back`() {
        assertEquals("note", Slug.of("!!!"))
        assertEquals("note", Slug.of(""))
        assertEquals("note", Slug.of("—— ★ ——"))
    }

    @Test
    fun `long title truncates cleanly at eighty`() {
        val title = "word ".repeat(40) // exactly 200 chars
        val slug = Slug.of(title)
        // Position 79 in the untruncated slug is a hyphen, so the cut must not leave it.
        assertEquals(79, slug.length)
        assertEquals(List(16) { "word" }.joinToString("-"), slug)
    }

    @Test
    fun `truncation respects maxLength without trailing hyphen`() {
        assertEquals("abcdefghij".repeat(8), Slug.of("abcdefghij".repeat(20)))
        assertEquals("grocery", Slug.of("Grocery list", 8))
        assertEquals("a", Slug.of("a b c", 1))
    }

    @Test
    fun `deterministic for repeated calls`() {
        for (title in listOf("Grocery list", "Café — the dark one!", "", "Σημείωση #12")) {
            assertEquals(Slug.of(title), Slug.of(title))
        }
    }

    @Test
    fun `non-ascii letters fall away or decompose`() {
        assertEquals("uber-cafe", Slug.of("Über-Café!"))
        // Non-Latin script carries no ASCII to keep; only its digits survive.
        assertEquals("12", Slug.of("Σημείωση #12"))
        assertEquals("2026", Slug.of("日本語 2026"))
    }

    @Test
    fun `digits and internal hyphens are preserved`() {
        assertEquals("plan-for-2026-v2", Slug.of("Plan for 2026 v2!!"))
        assertEquals("already-a-slug", Slug.of("already-a-slug"))
        assertEquals("mixed-runs-become-one", Slug.of("Mixed -- runs\tbecome ___ one"))
    }

    @Test
    fun `default max length is eighty`() {
        assertEquals(80, Slug.DEFAULT_MAX_LENGTH)
        assertEquals(Slug.of("Some title"), Slug.of("Some title", Slug.DEFAULT_MAX_LENGTH))
    }

    @Test
    fun `fallback ignores a smaller maxLength by contract`() {
        assertEquals("note", Slug.of("!!!", 2))
    }

    @Test
    fun `non-positive maxLength is rejected`() {
        assertFailsWith<IllegalArgumentException> { Slug.of("x", 0) }
        assertFailsWith<IllegalArgumentException> { Slug.of("x", -5) }
    }
}
