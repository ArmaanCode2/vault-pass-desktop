package com.vaultpass.desktop.domain.clipboard

import com.vaultpass.desktop.domain.security.SecureCharArray

/**
 * Defines the contract for securely interacting with the operating system's clipboard.
 */
interface SecureClipboardManager {
    
    /**
     * Copies a sensitive string to the clipboard, and guarantees it will be purged
     * from the OS clipboard history after the specified TTL (Time-To-Live).
     *
     * @param data The sensitive data to copy.
     * @param ttlMillis The duration in milliseconds before the clipboard is automatically cleared. Defaults to 30 seconds.
     */
    fun copySensitive(data: SecureCharArray, ttlMillis: Long = 30_000L)

    /**
     * Immediately purges the OS clipboard, overwriting any sensitive data.
     */
    fun clearClipboard()
}
