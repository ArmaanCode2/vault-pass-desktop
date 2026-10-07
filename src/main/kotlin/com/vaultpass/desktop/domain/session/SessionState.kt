package com.vaultpass.desktop.domain.session

/**
 * Defines the strict lifecycle states of the application.
 */
sealed class SessionState {
    data object FirstLaunch : SessionState()
    data object SetupMasterPassword : SessionState()
    data object Locked : SessionState()
    data object Unlocking : SessionState()
    data object Unlocked : SessionState()
    
    /**
     * Represents an unrecoverable mathematical or structural error (e.g., missing DEK).
     * The UI should drop into Recovery Mode when this state is active.
     * [offerUpdateCheck] adds a Check for updates button (vault saved by a newer version).
     */
    data class FatalError(val reason: String, val offerUpdateCheck: Boolean = false) : SessionState()
}
