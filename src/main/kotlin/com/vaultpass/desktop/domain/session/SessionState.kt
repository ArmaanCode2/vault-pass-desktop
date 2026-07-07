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
     */
    data class FatalError(val reason: String) : SessionState()
}
