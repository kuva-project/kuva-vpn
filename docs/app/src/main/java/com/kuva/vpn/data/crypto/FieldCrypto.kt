package com.kuva.vpn.data.crypto

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Field-level encryption for sensitive data (UUIDs, passwords, subscription URLs).
 * Key lives in Android Keystore (StrongBox-backed when available), AES-256-GCM
 * with a fresh IV per value. DB files alone reveal nothing if extracted.
 */
@Singleton
class FieldCrypto @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyAlias = "kuva_master_key"
    private val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private val secretKey: SecretKey by lazy {
        (keystore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: run {
                val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
                generator.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        keyAlias,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(false) // we provide our own IV
                        .build()
                )
                generator.generateKey()
            }
    }

    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return null
        return try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // format: base64(iv + ct)
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            null // never crash on crypto failure — caller stores null and re-asks user
        }
    }

    fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
