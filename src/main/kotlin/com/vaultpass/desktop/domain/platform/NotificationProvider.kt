package com.vaultpass.desktop.domain.platform

/**
 * A generic contract for triggering OS-level desktop notifications.
 * Decouples the domain logic from specific Windows/macOS notification APIs.
 */
interface NotificationProvider {
    /**
     * Displays a system notification to the user.
     *
     * @param title The title of the notification.
     * @param message The body text of the notification.
     * @param isError True if this is an error/alert notification, which may change the OS sound or styling.
     */
    fun showNotification(title: String, message: String, isError: Boolean = false)
}
