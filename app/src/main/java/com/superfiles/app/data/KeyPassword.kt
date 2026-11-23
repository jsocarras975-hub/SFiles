package com.superfiles.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The password protecting the APK signing keystore. It is random per device and never appears
 * in the source: it is encrypted with an AES key held in the Android Keystore and the ciphertext
 * lives in private SharedPreferences. If the Keystore is unavailable the password is kept only
 * in memory for this run.
 */
object KeyPassword {
    private const val KEYSTORE_ALIAS = "superfiles-key-password"
    private const val PREFS = "superfiles-signing"
    private const val PREF_ENC = "pass_enc"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private var memoryOnly: CharArray? = null

    fun get(ctx: Context): CharArray {
        memoryOnly?.let { return it }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PREF_ENC, null)?.let { enc ->
            try {
                val bytes = Base64.decode(enc, Base64.DEFAULT)
                return decrypt(bytes).toCharArray().also { memoryOnly = it }
            } catch (_: Exception) {
                // Corrupt entry: fall through and generate a fresh password below.
            }
        }
        val pass = randomPassword()
        try {
            val encrypted = encrypt(pass.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(PREF_ENC, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        } catch (_: Exception) {
            // Keystore unavailable: the password lives only in memory for this run.
        }
        return pass.toCharArray().also { memoryOnly = it }
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): String {
        require(data.size > 12) { "Bad encrypted password" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }
}
