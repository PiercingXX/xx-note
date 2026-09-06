package com.piercingxx.xxnote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the uses-permission claim: [scripts/check-permissions.sh] expected
 * set must include family THEME_SYNC, and must exclude the unused
 * POST_NOTIFICATIONS / ACCESS_NETWORK_STATE grants GrapheneOS would prompt
 * for. The source manifest's granted uses-permission names are checked
 * alongside so the two cannot drift.
 */
class PermissionsAuditTest {

    private val scriptText: String =
        sequenceOf(
            File("../scripts/check-permissions.sh"),
            File("scripts/check-permissions.sh"),
        ).first { it.exists() }.readText()

    private val manifestText: String =
        sequenceOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()

    @Test
    fun checkPermissionsExpectedSetIncludesThemeSyncAndDropsUnused() {
        val expected = expectedUsesPermissions(scriptText)
        assertTrue(
            "check-permissions.sh expected set must include THEME_SYNC",
            "com.piercingxx.xxlauncher.permission.THEME_SYNC" in expected,
        )
        assertFalse(
            "POST_NOTIFICATIONS is unused — drop it from the expected set",
            "android.permission.POST_NOTIFICATIONS" in expected,
        )
        assertFalse(
            "ACCESS_NETWORK_STATE is unused — drop it from the expected set",
            "android.permission.ACCESS_NETWORK_STATE" in expected,
        )
        assertTrue("android.permission.INTERNET" in expected)
        assertTrue("android.permission.CAMERA" in expected)
        assertEquals("expected list must stay sorted", expected.sorted(), expected)
    }

    @Test
    fun sourceManifestGrantsThemeSyncAndNotTheDroppedUnusedOnes() {
        val grants = grantedUsesPermissions(manifestText)
        assertTrue(
            "source manifest must uses-permission THEME_SYNC",
            "com.piercingxx.xxlauncher.permission.THEME_SYNC" in grants,
        )
        assertFalse(
            "source manifest must not grant POST_NOTIFICATIONS",
            "android.permission.POST_NOTIFICATIONS" in grants,
        )
        assertFalse(
            "source manifest must not grant ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_NETWORK_STATE" in grants,
        )
        assertFalse(
            "only xx-launcher may declare THEME_SYNC",
            Regex(
                """<permission\b[^>]*android:name="com\.piercingxx\.xxlauncher\.permission\.THEME_SYNC"""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(manifestText),
        )
    }

    private fun expectedUsesPermissions(script: String): List<String> {
        val marker = "EXPECTED=\$(cat <<'EOF'"
        val start = script.indexOf(marker)
        require(start >= 0) { "EXPECTED heredoc missing from check-permissions.sh" }
        val after = script.indexOf('\n', start) + 1
        val end = script.indexOf("\nEOF", after)
        require(end > after) { "EXPECTED heredoc terminator missing" }
        return script.substring(after, end).lines().filter { it.isNotBlank() }
    }

    private fun grantedUsesPermissions(xml: String): Set<String> {
        val block = Regex("""<uses-permission\b([^>]*)/>""", RegexOption.DOT_MATCHES_ALL)
        return block.findAll(xml).mapNotNull { match ->
            val attrs = match.groupValues[1]
            if (attrs.contains("tools:node=\"remove\"")) null
            else Regex("""android:name="([^"]+)"""").find(attrs)?.groupValues?.get(1)
        }.toSet()
    }
}
