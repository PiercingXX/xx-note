package com.piercingxx.xxnote.net

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith

/**
 * R9 seal/unseal, pure JVM (no Keystore — that is instrumented per §16).
 * [KeystoreKeyOps] compiles in this suite but is deliberately never
 * instantiated here.
 */
class CredentialVaultTest {

    private fun keyOps(): AesGcmKeyOps {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        return AesGcmKeyOps(generator.generateKey())
    }

    @Test
    fun `seal then unseal round-trips the password bytes`() {
        val vault = CredentialVault(keyOps())
        val secret = "correct horse battery staple".toByteArray(Charsets.UTF_8)

        val sealed = vault.seal(secret)

        assertNotEquals(secret.toList(), sealed.toList()) // actually sealed
        assertEquals(secret.toList(), vault.unseal(sealed).toList())
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val ops = keyOps()
        val sealed = ops.encrypt("do not touch".toByteArray(Charsets.UTF_8))

        sealed[sealed.size - 1] = (sealed.last().toInt() xor 0x01).toByte()

        // GCM tag check: a flipped bit anywhere breaks authentication loudly
        // instead of decrypting to plausible garbage.
        assertFailsWith<AEADBadTagException> { ops.decrypt(sealed) }
    }

    @Test
    fun `truncated blob is rejected before any crypto`() {
        val ops = keyOps()
        assertFailsWith<IllegalArgumentException> { ops.decrypt(ByteArray(4)) }
    }

    @Test
    fun `every seal uses a fresh IV`() {
        val ops = keyOps()
        val plain = "same plaintext".toByteArray(Charsets.UTF_8)

        val first = ops.encrypt(plain)
        val second = ops.encrypt(plain)

        assertNotEquals(first.toList(), second.toList()) // same input, different blob...
        val ivFirst = first.copyOf(AesGcmKeyOps.IV_BYTES)
        val ivSecond = second.copyOf(AesGcmKeyOps.IV_BYTES)
        assertNotEquals(ivFirst.toList(), ivSecond.toList()) // ...because of the IV...

        // ...and each still decrypts independently.
        assertEquals(plain.toList(), ops.decrypt(first).toList())
        assertEquals(plain.toList(), ops.decrypt(second).toList())
    }
}
