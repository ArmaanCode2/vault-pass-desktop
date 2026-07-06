package com.vaultpass.desktop.domain.features

import kotlinx.coroutines.flow.Flow

/**
 * Defines how flag states are retrieved from the environment.
 * This abstracts away whether features are toggled via a local config file,
 * hardcoded defaults, or fetched remotely from a server.
 */
interface FeatureProvider {
    /**
     * Reactively observe the state of a specific flag.
     */
    fun observeFlag(flag: FeatureFlag): Flow<Boolean>

    /**
     * Synchronously checks the current state of a flag.
     */
    fun isEnabled(flag: FeatureFlag): Boolean
}
