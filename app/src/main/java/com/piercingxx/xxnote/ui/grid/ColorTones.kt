package com.piercingxx.xxnote.ui.grid

import androidx.compose.ui.graphics.Color
import com.piercingxx.xxnote.ui.theme.Tokens

/**
 * D12/O2 tone mapper: Keep's twelve background names become six surface
 * tones on the AMOLED ladder — no hue ever reaches the screen.
 *
 * Canonical twelve (Google Keep's classic palette, lowercase as stored in
 * `color:`), paired hue-adjacent onto the six tones:
 *
 * | Tone          | Keep names              | Rendered as                          |
 * |---------------|-------------------------|--------------------------------------|
 * | INK           | `white`, `graphite`     | Ink surface, no treatment (default)  |
 * | INK_RAISED    | `banana`, `tangerine`   | InkRaised surface                    |
 * | GRAPHITE      | `tomato`, `flamingo`    | Graphite surface                     |
 * | SLATE         | `basil`, `sage`         | Slate surface                        |
 * | HAIRLINE_LEFT | `peacock`, `blueberry`  | Ink surface + 2.dp left bar White10  |
 * | HAIRLINE_FULL | `lavender`, `grape`     | Ink surface + 1.dp outline White10   |
 *
 * Aliases tolerated for vaults written by other Keep eras/tools (design.md §8
 * itself uses `sand`): they map to their nearest canonical tone but are never
 * rewritten by this app — round-trip fidelity of an imported vault beats
 * canonicalization (§4.3: XX-Note touches only what it owns, and only when
 * the user edits).
 *
 * Unknown/absent/blank name → [NoteTone.INK], "does LESS" direction, same
 * spirit as a corrupt `type:` degrading to `note` (§8).
 */
enum class NoteTone {
    INK,
    INK_RAISED,
    GRAPHITE,
    SLATE,
    HAIRLINE_LEFT,
    HAIRLINE_FULL,
}

/** The twelve canonical Keep names → tones; the test-asserted source of truth. */
val CANONICAL_COLOR_TONES: List<Pair<String, NoteTone>> = listOf(
    "white" to NoteTone.INK,
    "graphite" to NoteTone.INK,
    "banana" to NoteTone.INK_RAISED,
    "tangerine" to NoteTone.INK_RAISED,
    "tomato" to NoteTone.GRAPHITE,
    "flamingo" to NoteTone.GRAPHITE,
    "basil" to NoteTone.SLATE,
    "sage" to NoteTone.SLATE,
    "peacock" to NoteTone.HAIRLINE_LEFT,
    "blueberry" to NoteTone.HAIRLINE_LEFT,
    "lavender" to NoteTone.HAIRLINE_FULL,
    "grape" to NoteTone.HAIRLINE_FULL,
)

/**
 * Names from other Keep generations that still render (never emitted by the
 * picker): mapped to their nearest canonical tone above.
 */
private val COLOR_ALIASES: Map<String, NoteTone> = mapOf(
    "sand" to NoteTone.INK_RAISED,
    "yellow" to NoteTone.INK_RAISED,
    "orange" to NoteTone.INK_RAISED,
    "gray" to NoteTone.INK,
    "brown" to NoteTone.GRAPHITE,
    "red" to NoteTone.GRAPHITE,
    "coral" to NoteTone.GRAPHITE,
    "pink" to NoteTone.HAIRLINE_FULL,
    "purple" to NoteTone.HAIRLINE_FULL,
    "mint" to NoteTone.SLATE,
    "green" to NoteTone.SLATE,
    "teal" to NoteTone.HAIRLINE_LEFT,
    "blue" to NoteTone.HAIRLINE_LEFT,
    "cerulean" to NoteTone.HAIRLINE_LEFT,
    "darkblue" to NoteTone.HAIRLINE_LEFT,
)

private val NAME_TO_TONE: Map<String, NoteTone> = buildMap {
    CANONICAL_COLOR_TONES.forEach { (name, tone) -> put(name, tone) }
    COLOR_ALIASES.forEach { (name, tone) -> putIfAbsent(name, tone) }
}

/**
 * Pure mapper: frontmatter `color:` value → [NoteTone]. Case-insensitive;
 * surrounding whitespace tolerated; null/blank/unknown → [NoteTone.INK].
 */
fun toneForColor(colorName: String?): NoteTone {
    val key = colorName?.trim()?.lowercase()
    if (key.isNullOrEmpty()) return NoteTone.INK
    return NAME_TO_TONE[key] ?: NoteTone.INK
}

/**
 * Inverse used by the editor colour picker: one canonical Keep name per tone,
 * so a picked tone round-trips through `color:` as a real Keep-compatible
 * name (D12). The first canonical name for the tone wins (documented order).
 */
fun canonicalColorFor(tone: NoteTone): String =
    CANONICAL_COLOR_TONES.firstOrNull { it.second == tone }?.first
        ?: CANONICAL_COLOR_TONES.first().first

// ---- Rendering (pure Compose Color math only; no Android runtime) ----------

/** Card surface elevation for a tone; hairline tones ride plain Ink. */
fun NoteTone.surfaceColor(): Color = when (this) {
    NoteTone.INK -> Tokens.Ink
    NoteTone.INK_RAISED -> Tokens.InkRaised
    NoteTone.GRAPHITE -> Tokens.Graphite
    NoteTone.SLATE -> Tokens.Slate
    NoteTone.HAIRLINE_LEFT -> Tokens.Ink
    NoteTone.HAIRLINE_FULL -> Tokens.Ink
}

/** Hairline stroke color (White10 per WS7 spec); null when the tone has none. */
fun NoteTone.hairlineColor(): Color? = when (this) {
    NoteTone.HAIRLINE_LEFT, NoteTone.HAIRLINE_FULL -> Tokens.White10
    else -> null
}

/** True for the full-outline treatment (1.dp border); false = 2.dp left bar only. */
fun NoteTone.isFullOutline(): Boolean = this == NoteTone.HAIRLINE_FULL
