package com.piercingxx.xxnote.ui.theme

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal key-value seam over SharedPreferences so theme persistence is
 * JVM-testable without Android (mirrors TxxT's `ThemeKeyValueStore`).
 */
interface ThemeKeyValueStore {
    fun getString(key: String): String?
    /** Returns null when [key] is absent. */
    fun getInt(key: String): Int?
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
}

/** The production [ThemeKeyValueStore] over the app's SharedPreferences. */
class SharedPreferencesThemeKeyValueStore(
    private val prefs: SharedPreferences,
) : ThemeKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getInt(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
}

/**
 * Persists the launcher-synced theme (preset key + resolved background) and
 * loads it back as an [ActiveGround]. Pure Kotlin over the seam above.
 */
class ThemeStore(private val kv: ThemeKeyValueStore) {

    /** Persist [ground] under [presetKey] ([KEY_CUSTOM] for a Custom pick). */
    fun save(presetKey: String, ground: ActiveGround) {
        kv.putString(KEY_PRESET, presetKey)
        kv.putInt(KEY_BACKGROUND, ground.background.toInt())
    }

    /**
     * The persisted ground, or null when nothing was ever synced. A known
     * preset key resolves to the preset's canonical ground (self-healing);
     * [KEY_CUSTOM] — or an unknown key from a newer launcher — falls back to
     * the persisted background classified by the contrast rule.
     */
    fun load(): ActiveGround? {
        val key = kv.getString(KEY_PRESET) ?: return null
        ThemePreset.fromKey(key)?.let { return ActiveGround.of(it) }
        val background = kv.getInt(KEY_BACKGROUND) ?: return null
        return ActiveGround.custom(background)
    }

    companion object {
        const val KEY_PRESET = "launcher_theme_preset"
        const val KEY_BACKGROUND = "launcher_theme_background"
        /** The preset-key sentinel persisted for a launcher Custom theme. */
        const val KEY_CUSTOM = "custom"
    }
}

/**
 * The launcher theme-sync contract and its pure application logic. The
 * broadcast side lives in [ThemeSyncReceiver]; everything here is plain
 * Kotlin so JVM tests drive it directly.
 */
object ThemeSync {
    /** The xx-launcher's theme-change broadcast action. */
    const val ACTION_THEME_CHANGED = "xx.launcher.THEME_CHANGED"
    /** String extra: the active preset's display name (or "Custom"). */
    const val EXTRA_THEME_NAME = "xx.launcher.extra.THEME_NAME"
    /** Int extra: the resolved background ARGB (present even for Custom). */
    const val EXTRA_BACKGROUND = "xx.launcher.extra.BACKGROUND"
    /** Display name the launcher sends for a user-picked custom background. */
    const val CUSTOM_NAME = "Custom"

    /** SharedPreferences file the synced theme persists into. */
    const val PREFS_NAME = "xxnote_theme"

    /** The production [ThemeStore] over [PREFS_NAME]. */
    fun storeFor(context: Context): ThemeStore = ThemeStore(
        SharedPreferencesThemeKeyValueStore(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    )

    /**
     * Resolve a broadcast payload to its ground, or null when the payload is
     * unusable (unknown name, or Custom without a background). "Custom" takes
     * the resolved [backgroundArgb] classified by the family contrast rule;
     * a named preset takes its canonical ground.
     */
    fun resolve(name: String?, backgroundArgb: Int?): ActiveGround? {
        if (CUSTOM_NAME.equals(name, ignoreCase = true)) {
            return backgroundArgb?.let { ActiveGround.custom(it) }
        }
        val preset = ThemePreset.fromDisplayName(name) ?: return null
        return ActiveGround.of(preset)
    }

    /** The persisted key for a payload: the preset's, or [ThemeStore.KEY_CUSTOM]. */
    fun presetKeyFor(name: String?): String =
        ThemePreset.fromDisplayName(name)?.key ?: ThemeStore.KEY_CUSTOM

    /**
     * Apply a broadcast payload: persist it into [store] and flip the live
     * [Tokens.activeGround] so every composed `Tokens.X` read recomposes.
     * Unresolvable payloads are ignored (nothing persisted, nothing changed).
     * Returns the applied ground, or null when ignored.
     */
    fun onBroadcast(name: String?, backgroundArgb: Int?, store: ThemeStore): ActiveGround? {
        val ground = resolve(name, backgroundArgb) ?: return null
        store.save(presetKeyFor(name), ground)
        Tokens.activeGround = ground
        return ground
    }

    /**
     * App-start hook: load the persisted ground (if any) into the live state
     * before the first frame. No persisted theme leaves the AMOLED default.
     */
    fun loadInto(store: ThemeStore) {
        store.load()?.let { Tokens.activeGround = it }
    }

    /** [loadInto] over the production store. */
    fun load(context: Context) = loadInto(storeFor(context))
}
