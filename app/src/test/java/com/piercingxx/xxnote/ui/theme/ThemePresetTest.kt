package com.piercingxx.xxnote.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure preset model behind the launcher theme sync: the seven named
 * grounds, display-name resolution (the broadcast's matching key), and the
 * family-wide contrast rule that classifies a Custom background.
 */
class ThemePresetTest {

    // ---- the seven presets carry the family's exact grounds ----

    @Test
    fun `seven presets with the contract backgrounds`() {
        assertEquals(7, ThemePreset.entries.size)
        assertEquals(0xFF000000, ThemePreset.AMOLED_NIGHT.background)
        assertEquals(0xFF131316, ThemePreset.GRAPHITE.background)
        assertEquals(0xFF10261B, ThemePreset.FOREST_NIGHT.background)
        assertEquals(0xFF0F1C2E, ThemePreset.OCEAN_DRIFT.background)
        assertEquals(0xFF2A1018, ThemePreset.BURGUNDY.background)
        assertEquals(0xFFF3EEE2, ThemePreset.PAPER.background)
        assertEquals(0xFFE6EDF5, ThemePreset.MIST.background)
    }

    @Test
    fun `paper and mist are the light presets`() {
        val light = ThemePreset.entries.filterNot { it.isDark }
        assertEquals(listOf(ThemePreset.PAPER, ThemePreset.MIST), light)
    }

    @Test
    fun `default preset is amoled night`() {
        assertEquals(ThemePreset.AMOLED_NIGHT, ThemePreset.DEFAULT)
    }

    // ---- display-name resolution (what the broadcast carries) ----

    @Test
    fun `every display name resolves to its preset`() {
        for (preset in ThemePreset.entries) {
            assertEquals(preset, ThemePreset.fromDisplayName(preset.displayName))
        }
    }

    @Test
    fun `display name resolution is case-insensitive`() {
        assertEquals(ThemePreset.AMOLED_NIGHT, ThemePreset.fromDisplayName("amoled night"))
        assertEquals(ThemePreset.OCEAN_DRIFT, ThemePreset.fromDisplayName("OCEAN DRIFT"))
        assertEquals(ThemePreset.PAPER, ThemePreset.fromDisplayName("pApEr"))
    }

    @Test
    fun `unknown or null display name resolves to nothing`() {
        assertNull(ThemePreset.fromDisplayName("Not A Real Preset"))
        assertNull(ThemePreset.fromDisplayName(null))
        // "Custom" is deliberately NOT a preset — it rides the BACKGROUND extra.
        assertNull(ThemePreset.fromDisplayName("Custom"))
    }

    @Test
    fun `every key resolves and unknown keys do not`() {
        for (preset in ThemePreset.entries) {
            assertEquals(preset, ThemePreset.fromKey(preset.key))
        }
        assertNull(ThemePreset.fromKey("no-such-key"))
        assertNull(ThemePreset.fromKey(null))
    }

    // ---- the family-wide contrast rule (0.299r + 0.587g + 0.114b > 182) ----

    @Test
    fun `contrast rule threshold is exact at 182`() {
        // Pure gray 182: luminance exactly 182 — NOT above the threshold, white fg.
        assertFalse(ActiveGround.prefersDarkForeground(0xFFB6B6B6))
        // Pure gray 183: luminance 183 > 182 — dark fg.
        assertTrue(ActiveGround.prefersDarkForeground(0xFFB7B7B7))
    }

    @Test
    fun `contrast rule matches every preset's declared polarity`() {
        for (preset in ThemePreset.entries) {
            assertEquals(
                "polarity of ${preset.displayName}",
                preset.isDark,
                !ActiveGround.prefersDarkForeground(preset.background),
            )
        }
    }

    @Test
    fun `custom ground classifies by the contrast rule`() {
        // A near-white custom pick reads light (near-black foreground)...
        val light = ActiveGround.custom(0xFFF0F0F0.toInt())
        assertFalse(light.isDark)
        assertEquals(ActiveGround.FOREGROUND_DARK, light.foreground)
        assertEquals(0xFFF0F0F0, light.background)
        // ...and a deep custom pick reads dark (white foreground).
        val dark = ActiveGround.custom(0xFF203040.toInt())
        assertTrue(dark.isDark)
        assertEquals(ActiveGround.FOREGROUND_LIGHT, dark.foreground)
        assertEquals(0xFF203040, dark.background)
    }
}
