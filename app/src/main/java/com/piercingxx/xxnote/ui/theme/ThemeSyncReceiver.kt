package com.piercingxx.xxnote.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * `BroadcastReceiver` for the xx-launcher's theme-change broadcast
 * (`xx.launcher.THEME_CHANGED`), completing the family-wide theme sync on
 * the receiver side: the launcher publishes its active theme (explicitly
 * targeted at this package — required since Android O for manifest
 * receivers); XX-Note subscribes.
 *
 * On a broadcast the receiver reads the carried display name and resolved
 * background and hands them to [ThemeSync.onBroadcast], which persists the
 * pick into the `xxnote_theme` store (so it survives process death and is
 * loaded by [ThemeSync.load] at the next app start) and flips the live
 * [Tokens.activeGround] so a foregrounded UI restyles immediately.
 *
 * The store factory is injectable (TxxT's seam pattern) so a JVM unit test
 * can drive [onReceive] with a real Intent over an in-memory store.
 */
class ThemeSyncReceiver(
    /**
     * Builds the [ThemeStore] a broadcast persists into. Defaults to the
     * production store over the `xxnote_theme` SharedPreferences (the same
     * store MainActivity loads at start); injectable so a JVM unit test can
     * supply a store over an in-memory seam.
     */
    private val storeFactory: (Context) -> ThemeStore = ThemeSync::storeFor,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ThemeSync.ACTION_THEME_CHANGED) return

        val name = intent.getStringExtra(ThemeSync.EXTRA_THEME_NAME) ?: return
        val background =
            if (intent.hasExtra(ThemeSync.EXTRA_BACKGROUND)) {
                intent.getIntExtra(ThemeSync.EXTRA_BACKGROUND, 0)
            } else {
                null
            }
        ThemeSync.onBroadcast(name, background, storeFactory(context))
    }
}
