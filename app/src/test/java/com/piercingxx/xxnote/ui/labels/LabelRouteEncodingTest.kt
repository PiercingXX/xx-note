package com.piercingxx.xxnote.ui.labels

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * H2 gate: label names ride the "label/{name}" route pattern raw, so every
 * hostile character must be neutralised by Uri.encode at the CALL SITE
 * (LabelsScreen) and reversed exactly once by Navigation's automatic nav-arg
 * decode. These tests pin the round trip for names that previously corrupted
 * or crashed navigation, and assert the encoded form is one safe path segment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelRouteEncodingTest {

    /** The finding's hostile set plus a few more route-breaking shapes. */
    private val hostile = listOf(
        "work/2026",
        "a b",
        "100%",
        "a#b",
        "q?param=1",
        "lead/trail both",
        "emoji \uD83D\uDE00 tag",
        "café",
    )

    @Test
    fun encodeThenNavDecodeRoundTripsHostileNames() {
        // Uri.encode here == what LabelsScreen does; Uri.decode is exactly the
        // single automatic decode Navigation performs on the nav argument.
        for (name in hostile) {
            assertEquals(name, Uri.decode(Uri.encode(name)))
        }
    }

    @Test
    fun encodedNameIsASingleSafePathSegment() {
        for (name in hostile) {
            val encoded = Uri.encode(name)
            assertFalse("slash survived encoding of '$name'", encoded.contains('/'))
            assertFalse("'?' survived encoding of '$name'", encoded.contains('?'))
            assertFalse("'#' survived encoding of '$name'", encoded.contains('#'))
            assertFalse("'%' survived unencoded in '$name'", Regex("%(?![0-9A-Fa-f]{2})").containsMatchIn(encoded))
            assertFalse("space survived encoding of '$name'", encoded.contains(' '))
            assertTrue(encoded.isNotEmpty())
        }
    }

    @Test
    fun benignNamesPassThroughMostlyUntouched() {
        assertEquals("work", Uri.decode(Uri.encode("work")))
        assertEquals("home-errands", Uri.decode(Uri.encode("home-errands")))
    }
}
