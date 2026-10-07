package com.vaultpass.desktop.domain.clipboard

import com.vaultpass.desktop.domain.security.SecureCharArray
import kotlinx.coroutines.flow.StateFlow

data class ClipboardCountdown(
    val isActive: Boolean = false,
    val remainingSeconds: Int = 0,
    val totalSeconds: Int = 0,
    val label: String = "" // e.g., "Password copied"
)

/**
 * Defines the contract for securely interacting with the operating system's clipboard.
 */
interface SecureClipboardManager {
    val countdownState: StateFlow<ClipboardCountdown>

    /**
     * Copies sensitive data to the system clipboard, excluding it from Windows history and cloud sync.
     * Starts an active countdown timer to clear the clipboard after ttlSeconds.
     */
    fun copySensitive(data: SecureCharArray, label: String = "Password", ttlSeconds: Int = 30)

    /**
     * Copies non-sensitive text (e.g. username, URL) to the clipboard.
     */
    fun copyPlain(text: String, label: String = "Copied", ttlSeconds: Int = 0)

    /**
     * Immediately purges the clipboard and resets the countdown.
     */
    fun clearClipboard()

    /**
     * Releases resources and cancels any running timers.
     */
    fun dispose()
}
