package com.piercingxx.xxnote.ui.theme

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory store seam (mirrors ThemeSyncTest's fake) for receiver-driven persistence. */
private class ReceiverInMemoryKv : ThemeKeyValueStore {
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
 * The broadcast half of the theme sync: a real [Intent] carrying the
 * launcher's extras drives [ThemeSyncReceiver.onReceive] over the injectable
 * store seam, and the manifest wiring the OS needs to dispatch the broadcast
 * at all is locked alongside (mirroring TxxT's ThemeSyncWiringTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeSyncReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun restoreDefaultGround() {
        Tokens.activeGround = ActiveGround.DEFAULT
    }

    private fun receiver(into: ThemeKeyValueStore): ThemeSyncReceiver =
        ThemeSyncReceiver(storeFactory = { ThemeStore(into) })

    private fun themeIntent(name: String, background: Int?): Intent =
        Intent(ThemeSync.ACTION_THEME_CHANGED).apply {
            setPackage("com.piercingxx.xxnote")
            putExtra(ThemeSync.EXTRA_THEME_NAME, name)
            if (background != null) putExtra(ThemeSync.EXTRA_BACKGROUND, background)
        }

    // ---- wiring: the manifest declares the receiver for the launcher broadcast ----

    private val manifestText: String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun `manifest declares the exported theme-sync receiver`() {
        assertTrue(
            "AndroidManifest.xml must declare the .ui.theme.ThemeSyncReceiver component",
            manifestText.contains(".ui.theme.ThemeSyncReceiver"),
        )
        assertTrue(
            "ThemeSyncReceiver must be exported for the launcher's targeted broadcast",
            Regex(
                """<receiver[^>]*ThemeSyncReceiver[^>]*android:exported="true"""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(manifestText) ||
                Regex(
                    """<receiver[^>]*android:exported="true"[^>]*ThemeSyncReceiver""",
                    RegexOption.DOT_MATCHES_ALL,
                ).containsMatchIn(manifestText),
        )
    }

    @Test
    fun `manifest registers the launcher theme-changed action`() {
        assertTrue(
            "AndroidManifest.xml must register ${ThemeSync.ACTION_THEME_CHANGED}",
            manifestText.contains(ThemeSync.ACTION_THEME_CHANGED),
        )
    }

    @Test
    fun `declared receiver name resolves to a class`() {
        Class.forName("com.piercingxx.xxnote.ui.theme.ThemeSyncReceiver")
    }

    // ---- dispatch: a real intent through onReceive persists and restyles ----

    @Test
    fun `a preset broadcast persists into the store and flips the live ground`() {
        val kv = ReceiverInMemoryKv()
        receiver(kv).onReceive(
            context,
            themeIntent("Forest Night", ThemePreset.FOREST_NIGHT.background.toInt()),
        )
        assertEquals(ThemePreset.FOREST_NIGHT.key, kv.getString(ThemeStore.KEY_PRESET))
        assertEquals(
            ThemePreset.FOREST_NIGHT.background.toInt(),
            kv.getInt(ThemeStore.KEY_BACKGROUND),
        )
        assertEquals(ActiveGround.of(ThemePreset.FOREST_NIGHT), Tokens.activeGround)
    }

    @Test
    fun `a custom broadcast honors the background extra and contrast rule`() {
        val kv = ReceiverInMemoryKv()
        receiver(kv).onReceive(context, themeIntent("Custom", 0xFFF3EEE2.toInt()))
        assertEquals(ThemeStore.KEY_CUSTOM, kv.getString(ThemeStore.KEY_PRESET))
        assertEquals(0xFFF3EEE2.toInt(), kv.getInt(ThemeStore.KEY_BACKGROUND))
        assertEquals(ActiveGround.custom(0xFFF3EEE2.toInt()), Tokens.activeGround)
        assertEquals(false, Tokens.activeGround.isDark)
    }

    @Test
    fun `a wrong action is ignored`() {
        val kv = ReceiverInMemoryKv()
        receiver(kv).onReceive(
            context,
            Intent("some.other.ACTION").putExtra(ThemeSync.EXTRA_THEME_NAME, "Paper"),
        )
        assertNull(kv.getString(ThemeStore.KEY_PRESET))
        assertEquals(ActiveGround.DEFAULT, Tokens.activeGround)
    }

    @Test
    fun `an unknown preset name is ignored`() {
        val kv = ReceiverInMemoryKv()
        receiver(kv).onReceive(context, themeIntent("Not A Real Preset", 0xFF123456.toInt()))
        assertNull(kv.getString(ThemeStore.KEY_PRESET))
        assertEquals(ActiveGround.DEFAULT, Tokens.activeGround)
    }

    // ---- durability: the production store path round-trips via SharedPreferences ----

    @Test
    fun `the default store persists into shared preferences a fresh load reads`() {
        // Default factory → real SharedPreferences (Robolectric-backed): the
        // broadcast must survive process death into ThemeSync.load at app start.
        ThemeSyncReceiver().onReceive(
            context,
            themeIntent("Burgundy", ThemePreset.BURGUNDY.background.toInt()),
        )
        Tokens.activeGround = ActiveGround.DEFAULT // simulate process death

        ThemeSync.load(context)
        assertEquals(ActiveGround.of(ThemePreset.BURGUNDY), Tokens.activeGround)
    }
}
