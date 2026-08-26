package com.piercingxx.xxnote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EtagTest {

    @Test
    fun `only present strong validators can lock a write`() {
        assertTrue(Etag.isStrong("\"1a2b-3c4d\""))
        assertFalse(Etag.isStrong(null), "no validator at all can never lock a write")
        assertFalse(Etag.isStrong(""), "an empty header value is no validator")
        assertFalse(Etag.isWeak(null))
    }

    @Test
    fun `weak W-prefixed validators are named and refused`() {
        val weak = "W/\"1a2b-3c4d\""
        assertTrue(Etag.isWeak(weak))
        assertFalse(Etag.isStrong(weak))
        // The marker is exactly "W/" — quoting stays verbatim for the wire
        // (PropfindParser keeps it intact), so this lookalike is strong.
        assertFalse(Etag.isWeak("W\"not-weak\""))
        assertTrue(Etag.isStrong("W\"not-weak\""))
    }

    @Test
    fun `sha256 matches the published vectors`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Etag.sha256Hex("abc"),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Etag.sha256Hex(""),
        )
    }

    @Test
    fun `digest equality tracks byte equality of whole-file texts`() {
        val text = "---\ntitle: t\n---\nbody\n"
        assertEquals(Etag.sha256Hex(text), Etag.sha256Hex(text))
        assertTrue(Etag.sha256Hex(text) != Etag.sha256Hex(text + "\n"))
    }
}
