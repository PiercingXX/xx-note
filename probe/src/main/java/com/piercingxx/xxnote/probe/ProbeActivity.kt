package com.piercingxx.xxnote.probe

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.widget.TextView
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "ws0-strongbox-probe"

class ProbeActivity : Activity() {

    private val report = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setPadding(48, 48, 48, 48)
        }
        setContentView(view)
        run()
        view.text = report
    }

    private fun run() {
        var strongBox = false
        try {
            strongBox = obtainKey()
        } catch (t: Throwable) {
            say("keygen: failed (${t.javaClass.simpleName})")
            say("VERDICT: INCONCLUSIVE — key generation failed; fix Keystore before WS4")
            return
        }
        say(if (strongBox) "StrongBox: available" else "StrongBox: unavailable(fallback)")

        val verdict: String = try {
            val sealed = seal(sample())
            say("seal: ok")
            val opened = unseal(sealed.first, sealed.second)
            val roundTrip = opened.contentEquals(sample())
            say(if (roundTrip) "unseal: ok" else "unseal: MISMATCH")
            if (roundTrip) {
                if (strongBox) "VERDICT: PASS — StrongBox AES-GCM seal/unseal works; §4.5 [VERIFY] resolved"
                else "VERDICT: PASS(fallback) — TEE key works; §4.5 [VERIFY] resolved via fallback"
            } else {
                "VERDICT: FAIL — unsealed bytes differ from input"
            }
        } catch (t: Throwable) {
            "VERDICT: FAIL — seal/unseal threw (${t.javaClass.simpleName})"
        }
        say(verdict)
    }

    private fun obtainKey(): Boolean =
        try {
            newKey(strongBox = true)
            true
        } catch (_: StrongBoxUnavailableException) {
            deleteStaleKey()
            newKey(strongBox = false)
            false
        }

    private fun newKey(strongBox: Boolean) {
        deleteStaleKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setIsStrongBoxBacked(true)
        generator.init(builder.build())
        generator.generateKey()
    }

    private fun deleteStaleKey() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    private fun seal(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Pair(cipher.iv, cipher.doFinal(plain))
    }

    private fun unseal(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun sample(): ByteArray = ByteArray(32) { it.toByte() }

    private fun say(line: String) = report.appendLine(line)
}
