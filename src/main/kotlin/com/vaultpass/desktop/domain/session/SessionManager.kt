package com.vaultpass.desktop.domain.session

import kotlinx.coroutines.flow.StateFlow

/**
 * The centralized source of truth for the application's lifecycle and security posture.
 * It dictates exactly what state the app is in, preventing UI elements from manually
 * transitioning states.
 */
interface SessionManager {
    /**
     * A reactive stream representing the current global session state.
     */
    val sessionState: StateFlow<SessionState>

    /**
     * Called when the application boots up. 
     * Determines whether we are NOT_INITIALIZED or LOCKED.
     */
    suspend fun initialize()

    /**
     * Transitions to UNLOCKING, delegates the actual verification logic,
     * and finally transitions to UNLOCKED if successful.
     * Throws [UnlockInterruptedException] when the vault was locked or closed meanwhile.
     */
    suspend fun unlock(password: String): Boolean

    /**
     * Transitions to LOCKING, actively wipes sensitive data, and ends in LOCKED.
     */
    fun lock()

    /**
     * Invoked when the OS minimizes or obscures the application.
     */
    fun notifyBackground()

    /**
     * Invoked when the OS is terminating the application.
     * Guarantees a final wipe of sensitive memory before exit.
     */
    fun close()

    /**
     * Completes setup and persists the initial vault parameters.
     */
    suspend fun vaultCreated(password: String): Boolean

    /**
     * Routes the application into setup mode.
     */
    fun beginSetup()

    /**
     * Called when the user toggles the 'Require Master Password on Startup' setting.
     * Persists or clears the protected DPAPI session based on the setting.
     */
    suspend fun onRequireMasterPasswordOnStartupChanged(required: Boolean)
}

/**
 * An unlock (or the opening that ends vault creation) gave way to a lock or close that came in
 * while it ran. The vault is Locked; nothing was wrong with the password. [message] says what to do.
 */
class UnlockInterruptedException(message: String) : Exception(message)

