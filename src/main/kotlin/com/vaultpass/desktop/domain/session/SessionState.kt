package com.vaultpass.desktop.domain.session

/**
 * Defines the strict lifecycle states of the application.
 */
enum class SessionState {
    FirstLaunch,
    SetupMasterPassword,
    Locked,
    Unlocking,
    Unlocked
}
