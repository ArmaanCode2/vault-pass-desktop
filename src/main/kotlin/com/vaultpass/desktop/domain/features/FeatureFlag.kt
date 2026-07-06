package com.vaultpass.desktop.domain.features

/**
 * A centralized registry of all configurable application capabilities.
 */
enum class FeatureFlag(val defaultEnabled: Boolean) {
    /**
     * Toggles the local area network synchronization capability.
     */
    LAN_SYNC(false),
    
    /**
     * Toggles biometric authentication via Windows Hello / TouchID / etc.
     */
    BIOMETRICS(false),
    
    /**
     * Toggles the local WebSocket server for browser extension communication.
     */
    BROWSER_EXTENSION(false),
    
    /**
     * Toggles remote cloud synchronization.
     */
    CLOUD_SYNC(false),
    
    /**
     * Toggles experimental UI components for beta testing.
     */
    BETA_UI(false)
}
