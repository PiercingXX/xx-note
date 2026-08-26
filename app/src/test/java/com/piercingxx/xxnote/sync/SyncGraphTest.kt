package com.piercingxx.xxnote.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.piercingxx.xxnote.data.CredentialEntity
import com.piercingxx.xxnote.data.SettingEntity
import com.piercingxx.xxnote.data.XxDatabase
import com.piercingxx.xxnote.net.AesGcmKeyOps
import com.piercingxx.xxnote.net.CredentialVault
import com.piercingxx.xxnote.net.KeyOps
import java.io.IOException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric proof of hardening #6 and #4c against the real [SyncGraph]
 * construction path (file-backed Room, real software AES-GCM through
 * [AesGcmKeyOps] standing in for the hardware keystore via [SyncGraph]'s test
 * seam): engine caching honours invalidate(), and an unsealable blob raises
 * the typed failure with the stale mark persisted — the state the worker maps
 * to Result.failure() so the sync screen asks for re-entry instead of looping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncGraphTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Software AES key standing in for the AndroidKeyStore key (R9). */
    private lateinit var jvmKey: SecretKey

    @Before
    fun setUp() {
        jvmKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        SyncGraph.invalidate()
        SyncGraph.vaultFactory = { CredentialVault(AesGcmKeyOps(jvmKey)) }
    }

    @After
    fun tearDown() {
        // The companion is process-wide across test classes — restore production wiring.
        SyncGraph.invalidate()
        SyncGraph.vaultFactory =
            { alias -> CredentialVault(com.piercingxx.xxnote.net.KeystoreKeyOps(alias, strongBox = true)) }
    }

    @Test
    fun engineIsCachedUntilInvalidatedForcesRebuild() {
        seedCredential(sealedFor("correct horse battery staple"))

        val first = SyncGraph.engine(context)!!
        assertSame(first, SyncGraph.engine(context))

        SyncGraph.invalidate()

        assertNotSame(first, SyncGraph.engine(context))
    }

    @Test
    fun storedEtagModeSettingDrivesTheConfiguredEngineMode() {
        seedCredential(sealedFor("correct horse battery staple"))

        writeSetting(SyncGraph.SETTING_ETAG_MODE, EtagMode.FALLBACK.stored)
        assertEquals(EtagMode.FALLBACK, SyncGraph.engine(context)!!.configuredEtagMode)

        // Setup re-runs overwrite the row; invalidate() makes the next build
        // read it — the stored promise becomes the enforced law.
        SyncGraph.invalidate()
        writeSetting(SyncGraph.SETTING_ETAG_MODE, EtagMode.ETAG.stored)
        assertEquals(EtagMode.ETAG, SyncGraph.engine(context)!!.configuredEtagMode)
    }

    @Test
    fun absentEtagModeSettingFallsToTheSafeFallback() {
        seedCredential(sealedFor("correct horse battery staple"))

        assertNull(readSetting(SyncGraph.SETTING_ETAG_MODE))
        assertEquals(EtagMode.FALLBACK, SyncGraph.engine(context)!!.configuredEtagMode)
    }

    private fun writeSetting(key: String, value: String) {
        val db = XxDatabase.builder(context).build()
        runBlocking { db.settingDao().put(SettingEntity(key = key, value = value)) }
        db.close()
    }

    @Test
    fun tamperedBlobFailsWithStaleMarkInsteadOfRetryingForever() {
        val sealed = sealedFor("correct horse battery staple")
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 0x55).toByte() // corrupt the GCM tag
        seedCredential(sealed)

        val thrown = assertThrows(SyncGraph.CredentialUnreadableException::class.java) {
            SyncGraph.engine(context)
        }

        // Genuine tamper detection, classified permanent → failure, never retry.
        assertTrue(thrown.cause is AEADBadTagException)
        assertTrue(SyncGraph.isPermanentUnsealFailure(thrown.cause!!))

        // The stale mark is persisted for the sync screen's existing path.
        assertEquals("401", readSetting(SyncGraph.SETTING_CREDENTIAL_STALE))
    }

    @Test
    fun transientTroubleAroundTheUnsealStaysRetryableAndUnmarked() {
        SyncGraph.vaultFactory = { _ ->
            CredentialVault(
                object : KeyOps {
                    override fun encrypt(plain: ByteArray): ByteArray = error("unused")
                    override fun decrypt(blob: ByteArray): ByteArray = throw IOException("disk hiccup")
                },
            )
        }
        seedCredential(sealedFor("correct horse battery staple"))

        val thrown = assertThrows(IOException::class.java) { SyncGraph.engine(context) }

        assertEquals("disk hiccup", thrown.message)
        assertFalse(SyncGraph.isPermanentUnsealFailure(thrown))
        assertNull(readSetting(SyncGraph.SETTING_CREDENTIAL_STALE))
    }

    @Test
    fun classificationSplitsPermanentCryptoDeathFromTransientTrouble() {
        assertTrue(SyncGraph.isPermanentUnsealFailure(AEADBadTagException("tag mismatch")))
        assertTrue(SyncGraph.isPermanentUnsealFailure(java.security.UnrecoverableEntryException()))
        assertTrue(SyncGraph.isPermanentUnsealFailure(java.security.UnrecoverableKeyException()))
        assertTrue(
            SyncGraph.isPermanentUnsealFailure(
                android.security.keystore.KeyPermanentlyInvalidatedException("user key dead"),
            ),
        )
        // AndroidKeyStore throws these TRANSIENTLY (TEE busy, IPC timeouts):
        // they must fall through to the worker's retry path.
        assertFalse(SyncGraph.isPermanentUnsealFailure(java.security.ProviderException("TEE busy")))
        assertFalse(SyncGraph.isPermanentUnsealFailure(java.security.ProviderException("keystore")))
        assertFalse(SyncGraph.isPermanentUnsealFailure(IOException("tailnet unreachable")))
    }

    @Test
    fun transientKeystoreProviderFailureStaysRetryableAndUnmarked() {
        SyncGraph.vaultFactory = { _ ->
            CredentialVault(
                object : KeyOps {
                    override fun encrypt(plain: ByteArray): ByteArray = error("unused")
                    override fun decrypt(blob: ByteArray): ByteArray =
                        throw java.security.ProviderException("TEE busy")
                },
            )
        }
        seedCredential(sealedFor("correct horse battery staple"))

        val thrown = assertThrows(java.security.ProviderException::class.java) {
            SyncGraph.engine(context)
        }

        // Retry path: the raw exception propagates (worker retries), no stale
        // mark is persisted — a busy TEE fixes itself on the next schedule.
        assertEquals("TEE busy", thrown.message)
        assertFalse(SyncGraph.isPermanentUnsealFailure(thrown))
        assertNull(readSetting(SyncGraph.SETTING_CREDENTIAL_STALE))
    }

    @Test
    fun invalidationLandingMidBuildPreventsAStaleEngineFromBeingCached() {
        seedCredential(sealedFor("correct horse battery staple"))

        // Deterministic stand-in for the invalidate()-mid-build race: decrypt
        // succeeds, then fires invalidate() before the builder reaches its
        // cache assignment — exactly the window where a late `.also { wired =
        // it }` used to re-cache a pre-invalidation engine. Single-threaded
        // via the vault seam; no timing involved.
        val inner = AesGcmKeyOps(jvmKey)
        var invalidatedOnce = false
        SyncGraph.vaultFactory = { _ ->
            CredentialVault(
                object : KeyOps {
                    override fun encrypt(plain: ByteArray): ByteArray = inner.encrypt(plain)
                    override fun decrypt(blob: ByteArray): ByteArray {
                        val plain = inner.decrypt(blob)
                        if (!invalidatedOnce) {
                            invalidatedOnce = true
                            SyncGraph.invalidate()
                        }
                        return plain
                    }
                },
            )
        }

        val builtBeforeInvalidation = SyncGraph.engine(context)!!

        // NOT cached: the next call rebuilds from the rows as they are now,
        // instead of returning the overtaken engine forever.
        assertNotSame(builtBeforeInvalidation, SyncGraph.engine(context))

        // And once nothing invalidates mid-build, the fresh rebuild sticks.
        val rebuilt = SyncGraph.engine(context)
        assertSame(rebuilt, SyncGraph.engine(context))
    }

    /** Seals like Setup does, but under a plain JVM key instead of the keystore. */
    private fun sealedFor(password: String): ByteArray =
        AesGcmKeyOps(jvmKey).encrypt(password.toByteArray(Charsets.UTF_8))

    /**
     * Writes through the SAME builder [SyncGraph.engine] reads from (the
     * file-backed app database), closed before the engine opens its own pass.
     */
    private fun seedCredential(sealedSecret: ByteArray) {
        val db = XxDatabase.builder(context).build()
        runBlocking {
            db.credentialDao().upsert(
                CredentialEntity(
                    host = "nas.tailnet.ts.net",
                    basePath = "Drive/Notes",
                    user = "operator",
                    sealedSecret = sealedSecret,
                    keyAlias = "hardening-test",
                ),
            )
        }
        db.close()
    }

    private fun readSetting(key: String): String? {
        val db = XxDatabase.builder(context).build()
        return runBlocking { db.settingDao().get(key) }.also { db.close() }
    }
}
