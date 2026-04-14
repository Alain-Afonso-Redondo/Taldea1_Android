package com.example.osislogin.util

import java.security.MessageDigest
import java.util.Base64

class HashingUtil {
    fun hashPassword(password: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = messageDigest.digest(password.toByteArray())
        return Base64.getEncoder().encodeToString(hashedBytes)
    }

    fun verifyPassword(password: String, hashedPassword: String): Boolean {
        return hashPassword(password) == hashedPassword
    }
}
