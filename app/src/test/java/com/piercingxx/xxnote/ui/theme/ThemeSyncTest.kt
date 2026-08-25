package com.piercingxx.xxnote.ui.theme

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory [ThemeKeyValueStore] so persistence is JVM-testable without
 * Android (mirrors TxxT's theme-store fake).
 */
private class InMemoryThemeKv : ThemeKeyValueStore {
    private val strings = mutableMapOf<String, String>()
    private val ints = mutableMapOf<String, Int>()

    override fun getString(key: String): String? = strings[key]
    override fun getInt(key: String): Int? = ints[key]
    override fun putString(key: String, value: String) {
        strings[key] = value
    }
    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }
}

/**
 * The pure half of the receiver side: payload resolution ([ThemeSync.resolve]),
 * broadcast application (persist + live state), and app-start restore
 * ([ThemeSync.loadInto]) — all over the injectable store seam.
 */
class ThemeSyncTest {

    @After
    fun restoreDefaultGround() {
        Tokens.activeGround = ActiveGround.DEFAULT
    }

    // ---- resolution ----

    @Test
    fun `every preset display name resolves to its canonical ground`() {
        for (preset in ThemePreset.entries) {
            assertEquals(
                ActiveGround.of(preset),
                ThemeSync.resolve(preset.displayName, preset.background.toInt()),
            )
        }
    }

    @Test
    fun `resolution is case-insensitive`() {
        assertEquals(
            ActiveGround.of(ThemePreset.BURGUNDY),
            ThemeSync.resolve("burgundy", null),
        )
    }

    @Test
    fun `custom resolves through the background extra and contrast rule`() {
        val light = ThemeSync.resolve("Custom", 0xFFF7F7F7.toInt())
        assertEquals(0xFFF7F7F7, light!!.background)
        assertFalse(light.isDark)

        val dark = ThemeSync.resolve("custom", 0xFF102030.toInt())
        assertEquals(0xFF102030, dark!!.background)
        assertTrue(dark.isDark)
    }

    @Test
    fun `custom without a background is unusable`() {
        assertNull(ThemeSync.resolve("Custom", null))
    }

    @Test
    fun `unknown or missing names are unusable`() {
        assertNull(ThemeSync.resolve("Solarized", 0xFF002B36.toInt()))
        assertNull(ThemeSync.resolve(null, 0xFF000000.toInt()))
    }

    // ---- broadcast application: persist + flip the live state ----

    @Test
    fun `a preset broadcast persists and flips the live tokens`() {
        val kv = InMemoryThemeKv()
        val applied = ThemeSync.onBroadcast(
            "Ocean Drift",
            ThemePreset.OCEAN_DRIFT.background.toInt(),
            ThemeStore(kv),
        )
        assertEquals(ActiveGround.of(ThemePreset.OCEAN_DRIFT), applied)
        assertEquals(ActiveGround.of(ThemePreset.OCEAN_DRIFT), Tokens.activeGround)
        assertEquals(ThemePreset.OCEAN_DRIFT.key, kv.getString(ThemeStore.KEY_PRESET))
        assertEquals(0xFF0F1C2E.toInt(), kv.getInt(ThemeStore.KEY_BACKGROUND))
    }

    @Test
    fun `a custom broadcast persists the custom sentinel and background`() {
        val kv = InMemoryThemeKv()
        ThemeSync.onBroadcast("Custom", 0xFFEFEFEF.toInt(), ThemeStore(kv))
        assertEquals(ThemeStore.KEY_CUSTOM, kv.getString(ThemeStore.KEY_PRESET))
        assertEquals(0xFFEFEFEF.toInt(), kv.getInt(ThemeStore.KEY_BACKGROUND))
        assertEquals(ActiveGround.custom(0xFFEFEFEF.toInt()), Tokens.activeGround)
    }

    @Test
    fun `an unusable broadcast persists nothing and changes nothing`() {
        val kv = InMemoryThemeKv()
        assertNull(ThemeSync.onBroadcast("Not A Real Preset", null, ThemeStore(kv)))
        assertNull(kv.getString(ThemeStore.KEY_PRESET))
        assertNull(kv.getInt(ThemeStore.KEY_BACKGROUND))
        assertEquals(ActiveGround.DEFAULT, Tokens.activeGround)
    }

    // ---- app-start restore: what one controller persisted, the next reads ----

    @Test
    fun `a persisted preset survives into a fresh load`() {
        val kv = InMemoryThemeKv()
        ThemeSync.onBroadcast("Paper", ThemePreset.PAPER.background.toInt(), ThemeStore(kv))
        Tokens.activeGround = ActiveGround.DEFAULT // simulate process death

        ThemeSync.loadInto(ThemeStore(kv))
        assertEquals(ActiveGround.of(ThemePreset.PAPER), Tokens.activeGround)
    }

    @Test
    fun `a persisted custom pick survives into a fresh load`() {
        val kv = InMemoryThemeKv()
        ThemeSync.onBroadcast("Custom", 0xFF223344.toInt(), ThemeStore(kv))
        Tokens.activeGround = ActiveGround.DEFAULT

        ThemeSync.loadInto(ThemeStore(kv))
        assertEquals(ActiveGround.custom(0xFF223344.toInt()), Tokens.activeGround)
    }

    @Test
    fun `an empty store leaves the amoled default untouched`() {
        ThemeSync.loadInto(ThemeStore(InMemoryThemeKv()))
        assertEquals(ActiveGround.DEFAULT, Tokens.activeGround)
    }

    @Test
    fun `an unknown persisted key falls back to the stored background`() {
        // A newer launcher may persist a key this build doesn't know: the
        // stored background plus the contrast rule still yields a usable ground.
        val kv = InMemoryThemeKv()
        kv.putString(ThemeStore.KEY_PRESET, "some-future-preset")
        kv.putInt(ThemeStore.KEY_BACKGROUND, 0xFFFAFAFA.toInt())
        assertEquals(ActiveGround.custom(0xFFFAFAFA.toInt()), ThemeStore(kv).load())
    }
}
