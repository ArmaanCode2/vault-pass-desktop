package com.vaultpass.desktop.domain.security

/**
 * A memory-safe wrapper around a byte array.
 * Prevents cryptographic keys (DEK, KEK) from lingering in the heap.
 */
class SecureByteArray(val data: ByteArray) : AutoCloseable {
    
    fun wipe() {
        data.fill(0)
    }

    override fun close() {
        wipe()
    }

    override fun toString(): String {
        return "[REDACTED]"
    }
}
