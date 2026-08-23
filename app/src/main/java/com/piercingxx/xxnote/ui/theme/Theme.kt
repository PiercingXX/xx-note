package com.piercingxx.xxnote.ui.theme

import androidx.compose.material3.MaterialTheme
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

@Composable
fun XxNoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XxNoteColors,
        content = content,
    )
}
