package com.vaultpass.desktop.domain.session

import com.vaultpass.desktop.domain.security.AuthenticationRepository
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.migration.MigrationRunner
import com.vaultpass.desktop.domain.migration.MigrationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Concrete implementation of the SessionManager.
 */
class SessionManagerImpl(
    private val metadataRepository: MetadataRepository,
    private val authenticationRepository: AuthenticationRepository,
    private val migrationRunner: MigrationRunner,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : SessionManager {

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Locked)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    override suspend fun initialize() {
        // Milestone 2.5/Phase 3: Check metadata instead of raw database file
        val metadata = metadataRepository.getMetadata()
        if (metadata != null && metadata.initialized) {
            val migratedMetadata = migrationRunner.run(
                type = MigrationType.METADATA,
                currentVersion = metadata.metadataVersion,
                targetVersion = 1, // Target is currently v1
                initialContext = metadata
            )
            
            if (migratedMetadata != metadata) {
                metadataRepository.saveMetadata(migratedMetadata)
            }
            _sessionState.value = SessionState.Locked
        } else {
            _sessionState.value = SessionState.FirstLaunch
        }
    }

    override suspend fun unlock(password: String): Boolean {
        _sessionState.value = SessionState.Unlocking
        
        val success = authenticationRepository.verifyMasterPassword(password)
        if (success) {
            _sessionState.value = SessionState.Unlocked
            
            // In a full implementation, we'd update lastOpenedAt here
            val metadata = metadataRepository.getMetadata()
            if (metadata != null) {
                metadataRepository.saveMetadata(metadata.copy(lastOpenedAt = System.currentTimeMillis()))
            }
        } else {
            _sessionState.value = SessionState.Locked
        }
        return success
    }

    override fun lock() {
        _sessionState.value = SessionState.Locked
    }

    override fun notifyBackground() {
        // Not required in Milestone 2.4
    }

    override fun close() {
        // Not required in Milestone 2.4
    }
    
    // Additional method for setup completion
    override suspend fun vaultCreated(password: String): Boolean {
        // Phase 3: Initialize permanent metadata
        metadataRepository.initializeMetadata()
        
        val success = authenticationRepository.saveMasterPassword(password)
        if (success) {
            _sessionState.value = SessionState.Unlocked
        }
        return success
    }
    
    // Trigger setup mode from FirstLaunch
    override fun beginSetup() {
        _sessionState.value = SessionState.SetupMasterPassword
    }
}
