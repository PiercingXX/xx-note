package com.piercingxx.xxnote.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dynamic Tokens derivation. Two locked guarantees:
 *
 * 1. AMOLED equivalence — with the default ground every token equals the
 *    historical vendored constant bit-for-bit (the pre-sync Tokens.kt vals),
 *    so nothing changes visually out of the box.
 * 2. Light-ground inversion — on Paper the ramp is #1A1A1A at the same
 *    opacities, Signal inverts to the near-black foreground, and the raised
 *    ladder is the ground nudged 4%/7%/10% toward the foreground.
 *
 * Tokens is a singleton over snapshot state, so every test restores the
 * default ground on the way out.
 */
class TokensDerivationTest {

    @After
    fun restoreDefaultGround() {
        Tokens.activeGround = ActiveGround.DEFAULT
    }

    // ---- guarantee 1: the amoled default IS the historical palette ----

    @Test
    fun `amoled default reproduces every historical constant exactly`() {
        Tokens.activeGround = ActiveGround.DEFAULT
        assertEquals(Color(0xFF000000), Tokens.Ink)
        assertEquals(Color(0xFFFFFFFF), Tokens.Signal)
        assertEquals(Color(0xFF09090B), Tokens.InkRaised)
        assertEquals(Color(0xFF131316), Tokens.Graphite)
        assertEquals(Color(0xFF18181B), Tokens.Slate)
        assertEquals(Color(0x1AFFFFFF), Tokens.White10)
        assertEquals(Color(0x40FFFFFF), Tokens.White25)
        assertEquals(Color(0x80FFFFFF), Tokens.White50)
        assertEquals(Color(0xCCFFFFFF), Tokens.White80)
        assertEquals(Color(0xE6FFFFFF), Tokens.White90)
        assertEquals(Color(0xFFFF6767), Tokens.Error)
        assertEquals(Color(0xFFFDBA74), Tokens.Warn)
    }

    @Test
    fun `amoled is the default without any assignment`() {
        // The receiver side never ran: the state's initial value must already
        // be the brand default, not merely reachable by assignment.
        assertEquals(ActiveGround.DEFAULT, Tokens.activeGround)
        assertEquals(Color(0xFF000000), Tokens.Ink)
        assertEquals(Color(0xFF09090B), Tokens.InkRaised)
    }

    // ---- guarantee 2: light grounds invert the ramp ----

    @Test
    fun `paper ground drives ink and inverts signal`() {
        Tokens.activeGround = ActiveGround.of(ThemePreset.PAPER)
        assertEquals(Color(0xFFF3EEE2), Tokens.Ink)
        // Emphasis inverts: the accent is the near-black foreground.
        assertEquals(Color(0xFF1A1A1A), Tokens.Signal)
    }

    @Test
    fun `paper ramp is near-black at the same opacities`() {
        Tokens.activeGround = ActiveGround.of(ThemePreset.PAPER)
        assertEquals(Color(0x1A1A1A1A), Tokens.White10)
        assertEquals(Color(0x401A1A1A), Tokens.White25)
        assertEquals(Color(0x801A1A1A), Tokens.White50)
        assertEquals(Color(0xCC1A1A1A), Tokens.White80)
        assertEquals(Color(0xE61A1A1A), Tokens.White90)
    }

    @Test
    fun `paper raised ladder nudges the ground toward the foreground`() {
        Tokens.activeGround = ActiveGround.of(ThemePreset.PAPER)
        // 4% / 7% / 10% of #1A1A1A blended into #F3EEE2 (rounded per channel).
        assertEquals(Color(0xFFEAE6DA), Tokens.InkRaised)
        assertEquals(Color(0xFFE4DFD4), Tokens.Graphite)
        assertEquals(Color(0xFFDDD9CE), Tokens.Slate)
    }

    @Test
    fun `paper status colors stay fixed`() {
        Tokens.activeGround = ActiveGround.of(ThemePreset.PAPER)
        assertEquals(Color(0xFFFF6767), Tokens.Error)
        assertEquals(Color(0xFFFDBA74), Tokens.Warn)
    }

    // ---- non-default dark grounds blend instead of using the amoled ladder ----

    @Test
    fun `graphite ground keeps the white ramp and blends its ladder`() {
        Tokens.activeGround = ActiveGround.of(ThemePreset.GRAPHITE)
        assertEquals(Color(0xFF131316), Tokens.Ink)
        assertEquals(Color(0xFFFFFFFF), Tokens.Signal)
        assertEquals(Color(0xE6FFFFFF), Tokens.White90)
        // 4% white into #131316 (rounded per channel) — not the vendored rung.
        assertEquals(Color(0xFF1C1C1F), Tokens.InkRaised)
    }

    @Test
    fun `custom dark ground behaves like a dark preset`() {
        Tokens.activeGround = ActiveGround.custom(0xFF10261B.toInt())
        assertEquals(Color(0xFF10261B), Tokens.Ink)
        assertEquals(Color(0xFFFFFFFF), Tokens.Signal)
        assertEquals(Color(0x80FFFFFF), Tokens.White50)
    }
}
