package com.wristrelay.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure storage backed by Android Keystore + AES/GCM/NoPadding.
 * Each encryption uses a fresh random IV (prepended to the ciphertext).
 */
class SecureStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun put(key: String, value: String): Boolean {
        val ciphertext = encrypt(value) ?: return false
        prefs.edit().putString(key, Base64.encodeToString(ciphertext, Base64.NO_WRAP)).apply()
        return true
    }

    fun get(key: String): String? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            decrypt(Base64.decode(encoded, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed for $key: ${e.message}")
            null
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            // IV + ciphertext
            iv + ciphertext
        } catch (e: Exception) {
            Log.e(TAG, "encrypt failed: ${e.message}")
            null
        }
    }

    private fun decrypt(data: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = data.copyOfRange(0, IV_SIZE)
            val ciphertext = data.copyOfRange(IV_SIZE, data.size)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "decrypt failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SecureStorage"
        private const val PREFS_NAME = "wristrelay_secure"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "wristrelay_master_key"
        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
        private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val IV_SIZE = 12
    }
}
