package com.example.demopartnerapp

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirrors the backend `AesEncryption.gcm_encrypt` (app/.../aes_encryption.rb):
 *
 *   key = Base64.decode64(encoded_key)
 *   iv  = Base64.decode64(encoded_iv)
 *   cipher = AES-256-GCM, encrypt
 *   ciphertext = cipher.update(msg) + cipher.final
 *   tag        = cipher.auth_tag            (16 bytes)
 *   => [ urlsafe_base64(ciphertext), urlsafe_base64(tag) ]
 *
 * Java/Kotlin GCM appends the tag to the ciphertext in doFinal(), so we split
 * the last 16 bytes back off to match Ruby's separate (message, auth_tag).
 */
object AesGcm {

    private const val TAG_BITS = 128            // 16-byte auth tag
    private const val TAG_BYTES = TAG_BITS / 8

    /** Returns Pair(message, authTag), both URL-safe base64 (padded, no wrap). */
    fun encrypt(plaintext: String, encodedKeyB64: String, encodedIvB64: String): Pair<String, String> {
        val key = Base64.decode(encodedKeyB64.trim(), Base64.DEFAULT)
        val iv = Base64.decode(encodedIvB64.trim(), Base64.DEFAULT)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, iv)
        )

        val combined = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) // ciphertext || tag
        val ciphertext = combined.copyOfRange(0, combined.size - TAG_BYTES)
        val tag = combined.copyOfRange(combined.size - TAG_BYTES, combined.size)

        val flags = Base64.URL_SAFE or Base64.NO_WRAP
        return Base64.encodeToString(ciphertext, flags) to Base64.encodeToString(tag, flags)
    }
}
