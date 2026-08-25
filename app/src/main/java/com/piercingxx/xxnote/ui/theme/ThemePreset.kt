package com.piercingxx.xxnote.ui.theme

import kotlin.math.roundToInt

/**
 * The seven named background presets of the xx family, mirroring the
 * xx-launcher's theme set (and TxxT's `ThemePreset`). Pure Kotlin — no
 * `android.*` or Compose imports — so the model and its ground derivation
 * are JVM-testable without a device.
 *
 * Names and background values are the brand's own, reused verbatim so theme
 * auto-sync with the launcher can match by display name.
 */
enum class ThemePreset(
    /** Stable identifier used in persisted settings. */
    val key: String,
    /** Display name carried in the launcher broadcast, e.g. "AMOLED Night". */
    val displayName: String,
    /** Background color as a 0xAARRGGBB long. */
    val background: Long,
    /** Whether the preset is a dark theme (white foreground ramp). */
    val isDark: Boolean,
) {
    AMOLED_NIGHT("amoled-night", "AMOLED Night", 0xFF000000, true),
    GRAPHITE("graphite", "Graphite", 0xFF131316, true),
    FOREST_NIGHT("forest-night", "Forest Night", 0xFF10261B, true),
    OCEAN_DRIFT("ocean-drift", "Ocean Drift", 0xFF0F1C2E, true),
    BURGUNDY("burgundy", "Burgundy", 0xFF2A1018, true),
    PAPER("paper", "Paper", 0xFFF3EEE2, false),
    MIST("mist", "Mist", 0xFFE6EDF5, false);

    companion object {
        /** The default preset (AMOLED Night — the brand's default ground). */
        val DEFAULT: ThemePreset = AMOLED_NIGHT

        /**
         * Resolve a preset by its stable [key]. Returns null for an unknown
         * key so callers can fall back to [DEFAULT] without throwing.
         */
        fun fromKey(key: String?): ThemePreset? =
            entries.firstOrNull { it.key == key }

        /** Resolve a preset by its display name (case-insensitive). */
        fun fromDisplayName(name: String?): ThemePreset? =
            entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) }
    }
}

/**
 * The active ground: a resolved background color plus its polarity. All of
 * [Tokens]' ground-and-foreground colors derive from one of these — either a
 * [ThemePreset]'s canonical ground ([of]) or an arbitrary launcher-picked
 * color ([custom]) classified by the family-wide contrast rule.
 */
data class ActiveGround(
    /** Background color as a 0xAARRGGBB long. */
    val background: Long,
    /** True → white foreground ramp; false → near-black ([FOREGROUND_DARK]) ramp. */
    val isDark: Boolean,
) {
    /** The foreground the ramp and the signal accent are built from. */
    val foreground: Long
        get() = if (isDark) FOREGROUND_LIGHT else FOREGROUND_DARK

    /**
     * A foreground opacity stop over this ground: [alpha] (0..255) applied to
     * the foreground's RGB — white-on-dark, near-black-on-light, identical
     * opacities either way.
     */
    fun rampStop(alpha: Int): Long =
        (alpha.toLong() shl 24) or (foreground and 0x00FFFFFF)

    /**
     * A raised surface: the ground nudged toward the foreground by [fraction]
     * (0..1) so cards still read on any ground.
     */
    fun raised(fraction: Float): Long = mix(background, foreground, fraction)

    companion object {
        /** Pure white — the foreground ramp base on dark grounds. */
        const val FOREGROUND_LIGHT: Long = 0xFFFFFFFF
        /** Near-black #FF1A1A1A — the foreground ramp base on light grounds. */
        const val FOREGROUND_DARK: Long = 0xFF1A1A1A

        /**
         * The family-wide contrast threshold: perceived luminance above this
         * takes the dark foreground.
         */
        const val LUMINANCE_THRESHOLD: Double = 182.0

        /** The default ground (AMOLED Night). */
        val DEFAULT: ActiveGround = of(ThemePreset.DEFAULT)

        /** The ground of a named [preset]. */
        fun of(preset: ThemePreset): ActiveGround =
            ActiveGround(preset.background, preset.isDark)

        /**
         * A Custom ground: the launcher-resolved [backgroundArgb] classified
         * by the contrast rule — luminance > 182 reads as light (dark
         * foreground), else dark (white foreground).
         */
        fun custom(backgroundArgb: Int): ActiveGround {
            val background = backgroundArgb.toLong() and 0xFFFFFFFFL
            return ActiveGround(background, isDark = !prefersDarkForeground(background))
        }

        /**
         * The family-wide contrast rule (identical across xx apps): perceived
         * luminance `0.299r + 0.587g + 0.114b` strictly above 182 → the
         * near-black foreground; otherwise white.
         */
        fun prefersDarkForeground(background: Long): Boolean {
            val r = (background ushr 16) and 0xFF
            val g = (background ushr 8) and 0xFF
            val b = background and 0xFF
            val luminance = 0.299 * r + 0.587 * g + 0.114 * b
            return luminance > LUMINANCE_THRESHOLD
        }

        /** Linearly interpolate [color] toward [target] by [fraction] (0..1). */
        private fun mix(color: Long, target: Long, fraction: Float): Long {
            val a = (color ushr 24) and 0xFF
            val r = ((color ushr 16) and 0xFF).toFloat()
            val g = ((color ushr 8) and 0xFF).toFloat()
            val b = (color and 0xFF).toFloat()
            val tr = ((target ushr 16) and 0xFF).toFloat()
            val tg = ((target ushr 8) and 0xFF).toFloat()
            val tb = (target and 0xFF).toFloat()
            val nr = (r + (tr - r) * fraction).roundToInt()
            val ng = (g + (tg - g) * fraction).roundToInt()
            val nb = (b + (tb - b) * fraction).roundToInt()
            return (a shl 24) or (nr.toLong() shl 16) or (ng.toLong() shl 8) or nb.toLong()
        }
    }
}
