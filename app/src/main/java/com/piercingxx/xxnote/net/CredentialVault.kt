package com.piercingxx.xxnote.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * R9: credentials never touch plain storage. The DSM password is sealed with
 * an AES-GCM key before it is handed to anyone for persistence; Room's
 * credential table (WS3) stores only the sealed blob.
 *
 * This file deals in byte arrays and nothing else — converting the password
 * to bytes and persisting the ciphertext are the caller's jobs.
 */
interface KeyOps {
    fun encrypt(plain: ByteArray): ByteArray

    /** Throws a [javax.crypto.AEADBadTagException] subclass on tampered input. */
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * AES-GCM over any [SecretKey]: random 12-byte IV prepended to the
 * ciphertext, GCM tag providing tamper detection. A fresh cipher per call,
 * so every encryption gets a fresh IV.
 */
class AesGcmKeyOps(private val secretKey: SecretKey) : KeyOps {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        // doFinal FIRST: some providers (AndroidKeyStore among them) generate
        // the GCM IV during doFinal — reading it before can yield null or a
        // value that never matches the tag's IV.
        val ct = cipher.doFinal(plain)
        return checkNotNull(cipher.iv) + ct
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "sealed blob too short to contain an IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES),
        )
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/**
 * The hardware-backed variant (§4.5): an AndroidKeyStore AES-GCM key, 256
 * bits, StrongBox requested and gracefully dropped when the device answers
 * [StrongBoxUnavailableException]. Not unit-tested on the JVM — instrumented
 * tests against real hardware per §16.
 *
 * Keystore keys cannot export their key material and mandate a fresh cipher
 * per operation, which [AesGcmKeyOps] already provides.
 */
class KeystoreKeyOps(alias: String, strongBox: Boolean) : KeyOps {

    private val delegate = AesGcmKeyOps(generateOrGet(alias, strongBox))

    override fun encrypt(plain: ByteArray): ByteArray = delegate.encrypt(plain)

    override fun decrypt(blob: ByteArray): ByteArray = delegate.decrypt(blob)

    private companion object {
        private const val KEYSTORE = "AndroidKeyStore"

        /** Reuse the stored key under [alias], or generate it once. */
        private fun generateOrGet(alias: String, wantStrongBox: Boolean): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE,
            )
            try {
                generator.init(spec(alias, strongBox = wantStrongBox))
                return generator.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                // §4.5 fallback: same key shape, TEE-backed instead.
                generator.init(spec(alias, strongBox = false))
                return generator.generateKey()
            }
        }

        private fun spec(alias: String, strongBox: Boolean): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
    }
}

/**
 * Seals/unseals byte arrays (the DSM password among them) through [KeyOps].
 * Knows nothing about Room, files, or strings — WS3 persists what this
 * returns, and only this returns plaintext.
 */
class CredentialVault(private val keyOps: KeyOps) {

    fun seal(plain: ByteArray): ByteArray = keyOps.encrypt(plain)

    fun unseal(sealed: ByteArray): ByteArray = keyOps.decrypt(sealed)
}
