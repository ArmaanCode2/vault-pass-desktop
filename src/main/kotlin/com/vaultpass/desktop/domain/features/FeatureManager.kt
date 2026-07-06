package com.vaultpass.desktop.domain.features

import kotlinx.coroutines.flow.Flow

/**
 * The orchestrator interface providing both synchronous and reactive access
 * to feature states across the application.
 */
interface FeatureManager {
    /**
     * Reactively observes a flag's state, perfect for Compose UI condition rendering.
     */
    fun observeFlag(flag: FeatureFlag): Flow<Boolean>

    /**
     * Synchronously checks if a flag is currently enabled.
     * Useful for background services deciding whether to initialize.
     */
    fun isEnabled(flag: FeatureFlag): Boolean
}
