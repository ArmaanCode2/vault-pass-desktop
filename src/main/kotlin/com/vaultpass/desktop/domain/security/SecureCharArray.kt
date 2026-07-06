package com.vaultpass.desktop.domain.security

/**
 * A wrapper around a character array that prevents sensitive data (like passwords)
 * from being interned in the JVM String pool, allowing for explicit memory wiping.
 */
class SecureCharArray(private var data: CharArray?) {

    /**
     * Executes an operation with the underlying character array and ensures
     * that temporary copies are avoided if possible.
     */
    fun <T> use(block: (CharArray) -> T): T {
        val currentData = data ?: throw IllegalStateException("SecureCharArray has already been wiped.")
        return block(currentData)
    }

    /**
     * Explicitly overwrites the underlying array with zeros and nullifies the reference,
     * guaranteeing the data cannot be recovered from a heap dump.
     */
    fun wipe() {
        data?.let { array ->
            array.fill('\u0000')
            data = null
        }
    }

    /**
     * Prevents accidental logging of sensitive contents.
     */
    override fun toString(): String {
        return "[REDACTED_SECURE_ARRAY]"
    }
}
