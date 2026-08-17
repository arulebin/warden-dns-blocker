package com.arulebin.warden

import java.security.MessageDigest
import java.security.SecureRandom

/** Tiny salted-hash helper for the disarm PIN. Not high-security, but enough
 *  that the PIN isn't stored in plaintext. */
object Lock {
    fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.toHex()
    }

    fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest((salt + pin).toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
