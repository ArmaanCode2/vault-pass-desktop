package com.vaultpass.desktop.domain.platform

import kotlinx.coroutines.flow.Flow

/**
 * A generic contract for interacting with the OS System Tray (Windows Taskbar, macOS Menu Bar).
 */
interface SystemTrayProvider {
    
    /**
     * Initializes the tray icon and context menu.
     */
    fun initialize()

    /**
     * Updates the tray icon to reflect the current state (e.g. Locked vs Unlocked icon).
     */
    fun updateIcon(isLocked: Boolean)

    /**
     * Observes actions triggered by the user clicking context menu items in the system tray.
     */
    fun observeTrayActions(): Flow<TrayAction>

    /**
     * Removes the icon from the system tray.
     */
    fun remove()
}

/**
 * Represents actions a user can trigger from the system tray menu.
 */
enum class TrayAction {
    LOCK_VAULT,
    SHOW_WINDOW,
    QUIT_APPLICATION
}
