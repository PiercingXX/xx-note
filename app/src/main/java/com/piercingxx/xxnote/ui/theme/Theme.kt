package com.piercingxx.xxnote.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Monochrome over the active ground (design §12): Ink surfaces, Signal
// primary, Error reserved for the one loud state — an unresolved fork.
// Built per-composition (not a frozen top-level val) so the launcher theme
// sync flipping Tokens.activeGround restyles the scheme too; on the default
// AMOLED Night ground this is bit-for-bit the historical darkColorScheme.
@Composable
private fun xxNoteColors(): ColorScheme = if (Tokens.activeGround.isDark) {
    darkColorScheme(
        background = Tokens.Ink,
        surface = Tokens.Ink,
        primary = Tokens.Signal,
        onPrimary = Tokens.Ink,
        onBackground = Tokens.White90,
        onSurface = Tokens.White90,
        secondary = Tokens.Slate,
        error = Tokens.Error,
    )
} else {
    lightColorScheme(
        background = Tokens.Ink,
        surface = Tokens.Ink,
        primary = Tokens.Signal,
        onPrimary = Tokens.Ink,
        onBackground = Tokens.White90,
        onSurface = Tokens.White90,
        secondary = Tokens.Slate,
        error = Tokens.Error,
    )
}

// Monospace IS the identity (BRAND-GUIDE.md §4): Space Mono for display roles,
// JetBrains Mono for everything else. Explicit per-call-site styles still win;
// this closes the gap where an unstyled Text fell back to Roboto.
private val M3Defaults = Typography()
private val XxNoteTypography = Typography(
    displayLarge = M3Defaults.displayLarge.copy(fontFamily = SpaceMono),
    displayMedium = M3Defaults.displayMedium.copy(fontFamily = SpaceMono),
    displaySmall = M3Defaults.displaySmall.copy(fontFamily = SpaceMono),
    headlineLarge = M3Defaults.headlineLarge.copy(fontFamily = SpaceMono),
    headlineMedium = M3Defaults.headlineMedium.copy(fontFamily = SpaceMono),
    headlineSmall = M3Defaults.headlineSmall.copy(fontFamily = SpaceMono),
    titleLarge = M3Defaults.titleLarge.copy(fontFamily = SpaceMono),
    titleMedium = M3Defaults.titleMedium.copy(fontFamily = JetBrainsMono),
    titleSmall = M3Defaults.titleSmall.copy(fontFamily = JetBrainsMono),
    bodyLarge = M3Defaults.bodyLarge.copy(fontFamily = JetBrainsMono),
    bodyMedium = M3Defaults.bodyMedium.copy(fontFamily = JetBrainsMono),
    bodySmall = M3Defaults.bodySmall.copy(fontFamily = JetBrainsMono),
    labelLarge = M3Defaults.labelLarge.copy(fontFamily = JetBrainsMono),
    labelMedium = M3Defaults.labelMedium.copy(fontFamily = JetBrainsMono),
    labelSmall = M3Defaults.labelSmall.copy(fontFamily = JetBrainsMono),
)

@Composable
fun XxNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = xxNoteColors(),
        typography = XxNoteTypography,
        content = content,
    )
}
