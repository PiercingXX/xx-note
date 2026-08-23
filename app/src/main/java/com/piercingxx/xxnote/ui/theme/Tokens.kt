package com.piercingxx.xxnote.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Vendored from piercingxx-branding BRAND-GUIDE.md §3 via xx-phone's
 * android_colors.xml. Update by re-copying from the brand repo, never
 * retyping.
 */
object Tokens {
    // Core palette. Reserved-white rule: pure white is Signal's alone;
    // body text uses White90, never the accent.
    val Ink = Color(0xFF000000)
    val Signal = Color(0xFFFFFFFF)

    // Neutral ramp (AMOLED ladder).
    val InkRaised = Color(0xFF09090B)
    val Graphite = Color(0xFF131316)
    val Slate = Color(0xFF18181B)

    // White-on-ink opacity stops.
    val White10 = Color(0x1AFFFFFF)
    val White25 = Color(0x40FFFFFF)
    val White50 = Color(0x80FFFFFF)
    val White80 = Color(0xCCFFFFFF)
    val White90 = Color(0xE6FFFFFF)

    // Status: color only when something needs attention.
    val Error = Color(0xFFFF6767)
    val Warn = Color(0xFFFDBA74)
}
