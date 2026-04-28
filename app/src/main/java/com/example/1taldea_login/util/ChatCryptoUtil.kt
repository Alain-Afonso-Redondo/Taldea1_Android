package com.example.osislogin.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChatCryptoUtil {
    private const val PREFIX = "[AES]|"
    private const val SECRET = "1Taldea-Txat-AES-2026"
    private const val IV_SIZE = 16

    private val secureRandom = SecureRandom()

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_SIZE)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return PREFIX + Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    fun decryptIfNeeded(text: String): String {
        if (!text.startsWith(PREFIX)) return text

        return runCatching {
            val payload = Base64.decode(text.removePrefix(PREFIX), Base64.DEFAULT)
            require(payload.size > IV_SIZE)

            val iv = payload.copyOfRange(0, IV_SIZE)
            val encrypted = payload.copyOfRange(IV_SIZE, payload.size)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrElse { text }
    }

    private fun key(): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest(SECRET.toByteArray(Charsets.UTF_8))
}
