package com.vaultpass.desktop.domain.session

import com.vaultpass.desktop.domain.crypto.KeyManager
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
import com.vaultpass.desktop.domain.db.DatabaseConnectionManager

/**
 * Concrete implementation of the SessionManager.
 */
class SessionManagerImpl(
    private val metadataRepository: MetadataRepository,
    private val authenticationRepository: AuthenticationRepository,
    private val migrationRunner: MigrationRunner,
    private val keyManager: KeyManager,
    private val databaseConnectionManager: DatabaseConnectionManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : SessionManager {

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Unlocking)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    override suspend fun initialize() {
        // Milestone 2.5/Phase 3: Check metadata instead of raw database file
        val metadata = metadataRepository.getMetadata()
        if (metadata != null && metadata.initialized) {
            // Check for unsupported version
            if (metadata.metadataVersion > 1) { // 1 is current
                _sessionState.value = SessionState.FatalError("Unsupported vault version. Please update VaultPass Desktop.")
                return
            }
            
            // Check for missing DEK
            if (metadata.encryptionConfig?.wrappedDekBase64.isNullOrEmpty()) {
                _sessionState.value = SessionState.FatalError("Missing cryptographic DEK. Database cannot be recovered.")
                return
            }
            
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
            // In a full implementation, we'd update lastOpenedAt here
            val metadata = metadataRepository.getMetadata()
            if (metadata != null) {
                metadataRepository.saveMetadata(metadata.copy(lastOpenedAt = System.currentTimeMillis()))
            }
            
            // Open database connection before broadcasting unlock
            try {
                databaseConnectionManager.openConnection()
                _sessionState.value = SessionState.Unlocked
            } catch (e: Exception) {
                _sessionState.value = SessionState.FatalError("Database corruption detected: ${e.message}")
                return false
            }
        } else {
            _sessionState.value = SessionState.Locked
        }
        return success
    }

    override fun lock() {
        keyManager.wipeKeys()
        databaseConnectionManager.closeConnection()
        _sessionState.value = SessionState.Locked
    }

    override fun notifyBackground() {
        keyManager.wipeKeys()
        databaseConnectionManager.closeConnection()
        _sessionState.value = SessionState.Locked
    }

    override fun close() {
        keyManager.wipeKeys()
        databaseConnectionManager.closeConnection()
    }
    
    // Additional method for setup completion
    override suspend fun vaultCreated(password: String): Boolean {
        // Phase 3: Initialize permanent metadata
        metadataRepository.initializeMetadata()
        
        val success = authenticationRepository.saveMasterPassword(password)
        if (success) {
            try {
                databaseConnectionManager.openConnection()
                _sessionState.value = SessionState.Unlocked
            } catch (e: Exception) {
                _sessionState.value = SessionState.FatalError("Database corruption detected: ${e.message}")
                return false
            }
        }
        return success
    }
    
    // Trigger setup mode from FirstLaunch
    override fun beginSetup() {
        _sessionState.value = SessionState.SetupMasterPassword
    }
}
