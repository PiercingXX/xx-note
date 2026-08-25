package com.piercingxx.xxnote.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/*
 * Vendored from piercingxx-branding BRAND-GUIDE.md §3 via xx-phone's
 * android_colors.xml. Update by re-copying from the brand repo, never
 * retyping.
 *
 * Since the launcher theme sync, the ground-and-foreground tokens are
 * getters over one piece of Compose snapshot state ([Tokens.activeGround])
 * instead of frozen vals: every existing `Tokens.X` call site reads that
 * state and recomposes automatically when the launcher broadcasts a new
 * theme — no screen edits required. With the default AMOLED Night ground
 * every getter returns the historical constant bit-for-bit (the vendored
 * AMOLED ladder included), so nothing changes visually out of the box.
 */
object Tokens {
    /**
     * The active ground driving every derived token below. Snapshot state:
     * reads inside composition subscribe the reader to changes. Set via
     * [ThemeSync]; defaults to AMOLED Night (the historical constants).
     */
    var activeGround: ActiveGround by mutableStateOf(ActiveGround.DEFAULT)

    // Core palette. Reserved-white rule: pure white is Signal's alone;
    // body text uses White90, never the accent. On light grounds the
    // emphasis inverts: Signal is the near-black foreground.
    /** The ground itself ("Ink" by brand name, whatever the preset resolves to). */
    val Ink: Color get() = Color(activeGround.background)
    val Signal: Color get() = Color(activeGround.foreground)

    // Neutral ramp (the AMOLED ladder on the default ground; on any other
    // ground the same three rungs are the ground nudged 4%/7%/10% toward
    // the foreground so cards still read).
    val InkRaised: Color get() = raisedColor(RAISED_LOW, AMOLED_INK_RAISED)
    val Graphite: Color get() = raisedColor(RAISED_MID, AMOLED_GRAPHITE)
    val Slate: Color get() = raisedColor(RAISED_HIGH, AMOLED_SLATE)

    // Foreground opacity stops ("White" by brand name): white over dark
    // grounds, near-black #1A1A1A over light grounds, same opacities.
    val White10: Color get() = Color(activeGround.rampStop(0x1A))
    val White25: Color get() = Color(activeGround.rampStop(0x40))
    val White50: Color get() = Color(activeGround.rampStop(0x80))
    val White80: Color get() = Color(activeGround.rampStop(0xCC))
    val White90: Color get() = Color(activeGround.rampStop(0xE6))

    // Status: color only when something needs attention. Fixed across grounds.
    val Error = Color(0xFFFF6767)
    val Warn = Color(0xFFFDBA74)

    // The vendored AMOLED ladder (BRAND-GUIDE.md §3). A plain fg-into-bg
    // blend cannot reproduce these blue-tinged rungs, so on the brand's
    // default ground the vendored constants win verbatim.
    private const val AMOLED_INK_RAISED = 0xFF09090B
    private const val AMOLED_GRAPHITE = 0xFF131316
    private const val AMOLED_SLATE = 0xFF18181B

    // Raised-surface blend fractions for non-default grounds.
    private const val RAISED_LOW = 0.04f
    private const val RAISED_MID = 0.07f
    private const val RAISED_HIGH = 0.10f

    private val isAmoledDefault: Boolean
        get() = activeGround == ActiveGround.DEFAULT

    private fun raisedColor(fraction: Float, amoledLadder: Long): Color =
        Color(if (isAmoledDefault) amoledLadder else activeGround.raised(fraction))
}
