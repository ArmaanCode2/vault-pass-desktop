package com.vaultpass.desktop.domain.session

/**
 * Manages auto-lock inactivity timers, OS-level idle tracking, window minimization,
 * and system workstation lock events.
 */
interface SessionSecurityManager {
    /**
     * Records recent user interaction (e.g. keypress, mouse click, movement)
     * to reset the in-app inactivity timer.
     */
    fun notifyUserActivity()

    /**
     * Starts monitoring user activity, OS idle time, and session events.
     */
    fun startMonitoring()

    /**
     * Stops monitoring and cancels background polling jobs.
     */
    fun stopMonitoring()

    /**
     * Invoked when the main application window is minimized (iconified).
     */
    fun onWindowMinimized()

    /**
     * Invoked when the host workstation is locked (e.g. Win+L) or suspended.
     */
    fun onWorkstationLocked()

    /**
     * Returns true if active monitoring is currently running.
     */
    val isMonitoring: Boolean
}
