package com.vaultpass.desktop.domain.security

/**
 * A memory-safe wrapper around a char array.
 * Prevents passwords from lingering in the heap as immutable String objects.
 */
class SecureCharArray(val data: CharArray) : AutoCloseable {
    
    fun wipe() {
        data.fill('\u0000')
    }

    override fun close() {
        wipe()
    }

    override fun toString(): String {
        return "[REDACTED]"
    }
}

/**
 * Converts a String to a SecureCharArray.
 * Note: The original String will still linger in memory, but this allows for safe handling moving forward.
 */
fun String.toSecureCharArray(): SecureCharArray {
    return SecureCharArray(this.toCharArray())
}
