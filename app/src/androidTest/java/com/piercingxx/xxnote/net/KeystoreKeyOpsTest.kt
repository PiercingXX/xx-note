package com.piercingxx.xxnote.net

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith

/**
 * §16 / hardening #14: [KeystoreKeyOps] has no JVM stand-in — Robolectric's
 * keystore is software-only — so its laws are proven here, on real hardware.
 * Authored and compiled without a device attached; execution is pending
 * `connectedDebugAndroidTest` on the operator's hardware.
 *
 * Test names are camelCase rather than the JVM suite's backtick-with-spaces
 * style: androidTest methods must be representable in dex format.
 *
 * Each test owns [ALIAS] exclusively and deletes it before and after, because
 * generateOrGet silently reuses any existing key under the alias.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreKeyOpsTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var keyStore: KeyStore

    private val strongBoxHardware: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    @Before
    fun freshAlias() {
        keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(ALIAS)
    }

    @After
    fun dropAlias() {
        keyStore.deleteEntry(ALIAS)
    }

    private fun newKeyOps(strongBox: Boolean) = KeystoreKeyOps(ALIAS, strongBox)

    /** Which secure world actually holds the key under [ALIAS]. */
    private fun storedKeySecurityLevel(): Int {
        val entry = keyStore.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry
        val factory = KeyFactory.getInstance(entry.secretKey.algorithm, ANDROID_KEYSTORE)
        return factory.getKeySpec(entry.secretKey, KeyInfo::class.java).securityLevel
    }

    @Test
    fun sealThenUnsealRoundTripsThroughAKeystoreKey() {
        val ops = newKeyOps(strongBox = false)
        val secret = "correct horse battery staple".toByteArray(Charsets.UTF_8)

        val sealed = ops.encrypt(secret)

        assertFalse(sealed.contentEquals(secret)) // actually sealed
        assertTrue(secret.contentEquals(ops.decrypt(sealed)))
        // The key came from AndroidKeyStore's provider, not software KeyGenerator.
        assertNotEquals(KeyProperties.SECURITY_LEVEL_UNKNOWN, storedKeySecurityLevel())
    }

    @Test
    fun everySealUsesAFreshIv() {
        val ops = newKeyOps(strongBox = false)
        val plain = "same plaintext".toByteArray(Charsets.UTF_8)

        val first = ops.encrypt(plain)
        val second = ops.encrypt(plain)

        assertFalse(first.contentEquals(second)) // randomized encryption required
        assertTrue(plain.contentEquals(ops.decrypt(first)))
        assertTrue(plain.contentEquals(ops.decrypt(second)))
    }

    @Test
    fun strongBoxKeyRoundTripsWhenTheHardwareExists() {
        assumeTrue("no StrongBox on this device; covered by the fallback test", strongBoxHardware)

        val ops = newKeyOps(strongBox = true)
        val secret = "sealed in the strongest box".toByteArray(Charsets.UTF_8)

        val sealed = ops.encrypt(secret)

        assertTrue(secret.contentEquals(ops.decrypt(sealed)))
        assertEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, storedKeySecurityLevel())
    }

    @Test
    fun strongBoxRequestFallsBackToTeeWhereStrongBoxIsAbsent() {
        assumeFalse("StrongBox present; there is nothing to fall back from", strongBoxHardware)

        // §4.5: the constructor must swallow StrongBoxUnavailableException and
        // regenerate the same key shape TEE-backed instead of failing setup.
        val ops = newKeyOps(strongBox = true)
        val secret = "fallback sealed".toByteArray(Charsets.UTF_8)

        val sealed = ops.encrypt(secret)

        assertTrue(secret.contentEquals(ops.decrypt(sealed)))
        assertNotEquals(KeyProperties.SECURITY_LEVEL_STRONGBOX, storedKeySecurityLevel())
    }

    @Test
    fun tamperedBlobFailsAuthenticationLoudly() {
        val ops = newKeyOps(strongBox = false)
        val sealed = ops.encrypt("do not touch".toByteArray(Charsets.UTF_8))

        sealed[sealed.size - 1] = (sealed.last().toInt() xor 0x01).toByte()

        // GCM tag check: a flipped bit anywhere breaks authentication instead
        // of decrypting to plausible garbage.
        assertFailsWith<AEADBadTagException> { ops.decrypt(sealed) }
    }

    @Test
    fun truncatedBlobIsRejectedBeforeAnyCrypto() {
        val ops = newKeyOps(strongBox = false)
        assertFailsWith<IllegalArgumentException> { ops.decrypt(ByteArray(4)) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "androidTest.keystore-keyops"
    }
}
