package com.piercingxx.xxnote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// AMOLED-black monochrome (design §12): Ink surfaces, Signal primary,
// Error reserved for the one loud state — an unresolved fork.
private val XxNoteColors = darkColorScheme(
    background = Tokens.Ink,
    surface = Tokens.Ink,
    primary = Tokens.Signal,
    onPrimary = Tokens.Ink,
    onBackground = Tokens.White90,
    onSurface = Tokens.White90,
    secondary = Tokens.Slate,
    error = Tokens.Error,
)

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
        colorScheme = XxNoteColors,
        typography = XxNoteTypography,
        content = content,
    )
}
