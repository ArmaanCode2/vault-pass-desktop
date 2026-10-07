package com.vaultpass.desktop.domain.session

import com.vaultpass.desktop.domain.crypto.KeyManager
import com.vaultpass.desktop.domain.security.AuthenticationRepository
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.metadata.MetadataUnreadableException
import com.vaultpass.desktop.domain.metadata.VaultDatabaseFileState
import com.vaultpass.desktop.domain.migration.MigrationRunner
import com.vaultpass.desktop.domain.migration.MigrationType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.vaultpass.desktop.domain.db.DatabaseBackupException
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.db.DatabaseConnectionManager
import com.vaultpass.desktop.domain.db.NewerVaultException
import com.vaultpass.desktop.domain.AppSettingsRepository

/**
 * Concrete implementation of the SessionManager.
 */
class SessionManagerImpl(
    private val metadataRepository: MetadataRepository,
    private val authenticationRepository: AuthenticationRepository,
    private val migrationRunner: MigrationRunner,
    private val keyManager: KeyManager,
    private val databaseConnectionManager: DatabaseConnectionManager,
    private val appSettingsRepository: AppSettingsRepository,
    private val sessionKeyStorage: SessionKeyStorage,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : SessionManager {

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Unlocking)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    /**
     * Guards [lockEpoch], [lastUnlockEpoch] and the final step of an unlock, so an unlock and a
     * lock (or close) can't both think they won.
     */
    private val unlockMonitor = Any()

    /**
     * Moves on when a lock, a background lock or a close starts and again when it ends. An unlock
     * notes it when it starts and gives way when it moved: something locked or closed meanwhile.
     */
    private var lockEpoch = 0L

    /** The epoch the last successful unlock started in; see [locking]. */
    private var lastUnlockEpoch = -1L

    init {
        // A connection that had to be dropped (a rollback that failed) leaves an unlocked session
        // with no database: lock it, so the user sees the lock screen and unlocking opens a new one.
        databaseConnectionManager.setOnConnectionLost { onConnectionLost() }
    }

    /**
     * Decided under [unlockMonitor], like the last step of an unlock: an unlock that is finishing
     * either finished before this (then it is locked here) or sees the epoch moved and gives way,
     * so it never ends Unlocked without a connection.
     */
    private fun onConnectionLost() {
        val mustLock = synchronized(unlockMonitor) {
            val state = _sessionState.value
            (state == SessionState.Unlocked || state == SessionState.Unlocking).also { if (it) lockEpoch++ }
        }
        if (mustLock) {
            System.err.println("[WARN] SessionManager: the database connection was lost; locking the vault")
            lock()
        }
    }

    private fun currentEpoch(): Long = synchronized(unlockMonitor) { lockEpoch }

    /**
     * Runs a lock, background lock or close: [body] closes the database and wipes the key. An
     * unlock that overlaps it in any way never ends Unlocked; one that already finished while
     * this ran (it started after this began) is closed and wiped again. The state ends Locked
     * when [endLocked], when such an unlock happened, or when it was Unlocked or Unlocking (a
     * close then leaves no unlocked session with a closed database and a wiped key).
     */
    private fun locking(endLocked: Boolean, body: () -> Unit) {
        val started = synchronized(unlockMonitor) { ++lockEpoch }
        try {
            body()
        } catch (e: Exception) {
            System.err.println("[WARN] SessionManager: locking ran into ${e.javaClass.simpleName}")
        } finally {
            val unlockedMeanwhile = synchronized(unlockMonitor) {
                lockEpoch++
                val overlapped = lastUnlockEpoch >= started
                val state = _sessionState.value
                val wasOpen = state == SessionState.Unlocked || state == SessionState.Unlocking
                if (endLocked || overlapped || wasOpen) _sessionState.value = SessionState.Locked
                overlapped
            }
            if (unlockedMeanwhile) closeDatabaseAndWipeKeys()
        }
    }

    /**
     * Stores the vault key for the next start (the "don't ask for the master password" setting),
     * for the session that began in [epoch].
     *
     * - A lock or close noticed before the key is copied: nothing is saved, and nothing is
     *   cleared either. What happens to a key already stored is the lock's decision, made from
     *   the "require password" setting (with it off, a lock keeps the stored key).
     * - A lock or close that comes in after the copy was taken doesn't stop the save: the key
     *   copied before the lock may still be written right after it. That is the real key, and
     *   with the setting off a lock keeps a stored key anyway.
     * - What is written is always the real key: it leaves the key manager only as a copy taken
     *   atomically with respect to a wipe (never the live array, which wipeKeys() zero-fills in
     *   place), so it is never zero or half wiped. The copy is wiped after saving, and an all-zero
     *   copy is never stored.
     */
    private fun saveSessionKey(epoch: Long) {
        if (currentEpoch() != epoch) return
        val copy = try {
            keyManager.copyActiveDek()
        } catch (e: Exception) {
            return // No key loaded: locked meanwhile.
        }
        try {
            if (copy.data.isEmpty() || copy.data.all { it == 0.toByte() }) return
            sessionKeyStorage.saveProtectedSession(copy)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            copy.wipe()
        }
    }

    override suspend fun initialize() {
        // Whatever fails, startup ends on a screen that says why, never on the spinner.
        try {
            initializeState()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try { databaseConnectionManager.closeConnection() } catch (_: Exception) { }
            keyManager.wipeKeys()
            _sessionState.value = when (e) {
                is MetadataUnreadableException -> metadataUnreadableState(e)
                is NewerVaultException, is DatabaseBackupException -> openFailureState(e)
                else -> SessionState.FatalError("VaultPass couldn't start: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun initializeState() {
        val epoch = currentEpoch()
        // Milestone 2.5/Phase 3: Check metadata instead of raw database file
        val metadata = try {
            metadataRepository.getMetadata()
        } catch (e: MetadataUnreadableException) {
            // Never fall through to Setup: that would write over the vault.
            _sessionState.value = metadataUnreadableState(e)
            return
        }
        if (metadata != null && metadata.initialized) {
            // Check for unsupported version
            if (metadata.metadataVersion > 1) { // 1 is current
                _sessionState.value = SessionState.FatalError(NEWER_VAULT_TEXT, offerUpdateCheck = true)
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
            
            val settings = appSettingsRepository.getSettings()
            if (!settings.requireMasterPasswordOnStartup && sessionKeyStorage.hasProtectedSession()) {
                val loaded = sessionKeyStorage.loadProtectedSession()
                // Last line of defence: an all-zero key is a key that was wiped before it was stored.
                // Unlocking with it would show every entry as corrupted and write under a zero key.
                val dek = loaded?.takeIf { key -> key.data.isNotEmpty() && key.data.any { it != 0.toByte() } }
                if (loaded != null && dek == null) {
                    loaded.wipe()
                    sessionKeyStorage.clearProtectedSession()
                    _sessionState.value = SessionState.Locked
                } else if (dek != null && metadataRepository.databaseFileState() == VaultDatabaseFileState.MISSING) {
                    // Opening would create an empty database in its place. The stored key is kept for when it's restored.
                    dek.wipe()
                    keyManager.wipeKeys()
                    _sessionState.value = databaseMissingState()
                } else if (dek != null) {
                    try {
                        keyManager.loadActiveDek(dek)
                        databaseConnectionManager.openConnection()
                        // A lock or close meanwhile ends it Locked; the stored key stays.
                        finishUnlock(epoch)
                    } catch (e: DatabaseClosedException) {
                        // Locked while it opened: the stored key is fine, the vault just stays locked.
                        closeDatabaseAndWipeKeys()
                        _sessionState.value = SessionState.Locked
                    } catch (e: Exception) {
                        keyManager.wipeKeys()
                        // These two leave the database as it was on purpose; the stored key still
                        // fits it and lets the vault open by itself once the cause is fixed.
                        if (e !is NewerVaultException && e !is DatabaseBackupException) {
                            sessionKeyStorage.clearProtectedSession()
                        }
                        _sessionState.value = openFailureState(e)
                    }
                } else {
                    // Decryption failed (e.g. DPAPI error or session key corrupted)
                    sessionKeyStorage.clearProtectedSession()
                    _sessionState.value = SessionState.Locked
                }
            } else {
                _sessionState.value = SessionState.Locked
            }
        } else if (metadata == null && metadataRepository.databaseFileState() == VaultDatabaseFileState.HAS_ENTRIES) {
            // Setup would refuse to run over these entries anyway; say what is missing instead.
            val directory = metadataRepository.metadataFile.absoluteFile.parentFile?.absolutePath
                ?: metadataRepository.metadataFile.absolutePath
            _sessionState.value = SessionState.FatalError(
                "VaultPass found your vault entries in ${metadataRepository.databaseFile.absolutePath}, " +
                    "but the vault's key file (metadata.json) is missing from $directory. " +
                    "If you have a copy of this folder (for example from File History or another backup), " +
                    "put metadata.json back into it. Nothing was changed."
            )
        } else {
            _sessionState.value = SessionState.FirstLaunch
        }
    }

    override suspend fun unlock(password: String): Boolean {
        val epoch = currentEpoch()
        _sessionState.value = SessionState.Unlocking

        val success = try {
            authenticationRepository.verifyMasterPassword(password)
        } catch (e: Exception) {
            keyManager.wipeKeys()
            _sessionState.value = stateAfterMetadataFailure(e)
            throw e
        }
        if (success) {
            if (metadataRepository.databaseFileState() == VaultDatabaseFileState.MISSING) {
                // openConnection() would create an empty database in its place.
                keyManager.wipeKeys()
                _sessionState.value = databaseMissingState()
                return false
            }
            // Open the database first: a vault this version can't open is left without any write.
            try {
                databaseConnectionManager.openConnection()
            } catch (e: DatabaseClosedException) {
                // The vault was locked or closed while it opened: the lock wins.
                closeDatabaseAndWipeKeys()
                _sessionState.value = SessionState.Locked
                throw UnlockInterruptedException(UNLOCK_INTERRUPTED_TEXT)
            } catch (e: Exception) {
                keyManager.wipeKeys()
                _sessionState.value = openFailureState(e)
                return false
            }

            try {
                val metadata = metadataRepository.getMetadata()
                if (metadata != null) {
                    metadataRepository.saveMetadata(metadata.copy(lastOpenedAt = System.currentTimeMillis()))
                }
            } catch (e: Exception) {
                closeDatabaseAndWipeKeys()
                _sessionState.value = stateAfterMetadataFailure(e)
                throw e
            }

            try {
                val settings = appSettingsRepository.getSettings()
                if (!settings.requireMasterPasswordOnStartup) {
                    saveSessionKey(epoch)
                } else {
                    sessionKeyStorage.clearProtectedSession()
                }
            } catch (e: Exception) {
                closeDatabaseAndWipeKeys()
                _sessionState.value = openFailureState(e)
                return false
            }
            if (!finishUnlock(epoch)) throw UnlockInterruptedException(UNLOCK_INTERRUPTED_TEXT)
        } else {
            _sessionState.value = SessionState.Locked
        }
        return success
    }

    /**
     * Ends an unlock that started in [epoch] in Unlocked, unless a lock, background lock or close
     * came in meanwhile (the epoch moved) or the database is closed: then that wins, and this
     * closes, wipes and ends Locked, never Unlocked with a wiped key. Returns whether it unlocked.
     */
    private fun finishUnlock(epoch: Long): Boolean {
        val unlocked = synchronized(unlockMonitor) {
            val stillValid = lockEpoch == epoch && databaseConnectionManager.connectionState.value
            if (stillValid) {
                lastUnlockEpoch = epoch
                _sessionState.value = SessionState.Unlocked
            }
            stillValid
        }
        if (unlocked) return true
        closeDatabaseAndWipeKeys()
        _sessionState.value = SessionState.Locked
        return false
    }

    /**
     * Closes the database before the key is wiped: wipeKeys() zero-fills the key in place, and a
     * write still running is then rolled back instead of committing data encrypted with it.
     * Never throws: the key is wiped even when closing fails, and a failure is only logged (its
     * kind, nothing from the vault).
     */
    private fun closeDatabaseAndWipeKeys() {
        try {
            databaseConnectionManager.closeConnection()
        } catch (e: Exception) {
            System.err.println("[WARN] SessionManager: closing the database failed (${e.javaClass.simpleName})")
        } finally {
            try {
                keyManager.wipeKeys()
            } catch (e: Exception) {
                System.err.println("[WARN] SessionManager: wiping the key failed (${e.javaClass.simpleName})")
            }
        }
    }

    /** Never throws (it runs on the UI thread) and always ends in Locked. */
    override fun lock() = locking(endLocked = true) {
        closeDatabaseAndWipeKeys()
        scope.launch {
            try {
                val settings = appSettingsRepository.getSettings()
                if (settings.requireMasterPasswordOnStartup) {
                    sessionKeyStorage.clearProtectedSession()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override suspend fun onRequireMasterPasswordOnStartupChanged(required: Boolean) {
        if (!required) {
            val epoch = currentEpoch()
            if (keyManager.hasActiveDek()) {
                saveSessionKey(epoch)
            }
        } else {
            sessionKeyStorage.clearProtectedSession()
        }
    }

    /** Never throws and always ends in Locked, like [lock]. */
    override fun notifyBackground() = locking(endLocked = true) {
        closeDatabaseAndWipeKeys()
    }

    /**
     * Closes and wipes; an unlocked session, or an unlock or vault creation running meanwhile,
     * ends Locked. This is the exit path (not the UI thread): it then waits, for a bounded time,
     * until the database file really is closed.
     */
    override fun close() {
        locking(endLocked = false) {
            closeDatabaseAndWipeKeys()
        }
        try {
            runBlocking {
                withTimeoutOrNull(CLOSE_WAIT_MILLIS) { databaseConnectionManager.closeConnectionAndWait() }
            }
        } catch (e: Exception) {
            System.err.println("[WARN] SessionManager: waiting for the database to close failed (${e.javaClass.simpleName})")
        }
    }
    
    // Additional method for setup completion
    override suspend fun vaultCreated(password: String): Boolean = withContext(Dispatchers.IO) {
        val epoch = currentEpoch()
        var metadataWritten = false
        try {
            // 1. Initialize permanent metadata
            metadataRepository.initializeMetadata()
            metadataWritten = true

            // 2. Derive KEK via PBKDF2 and save master password hash
            val success = authenticationRepository.saveMasterPassword(password)
            if (!success) {
                discardCreatedVault()
                return@withContext false
            }
            // 3. Open database and create schema tables. From here the vault is complete: the
            // metadata holds the wrapped key, and openConnection() only reports a close that came
            // in meanwhile after it created the schema. A close or lock now is no reason to throw
            // the new vault away; it ends Locked and opens with the password just chosen.
            try {
                databaseConnectionManager.openConnection()
            } catch (e: DatabaseClosedException) {
                throw UnlockInterruptedException(VAULT_CREATED_BUT_LOCKED_TEXT)
            }

            // 4. Save session key if configured
            val settings = appSettingsRepository.getSettings()
            if (!settings.requireMasterPasswordOnStartup) {
                saveSessionKey(epoch)
            }

            // 5. Transition to Unlocked, unless a lock or close came in meanwhile.
            if (!finishUnlock(epoch)) throw UnlockInterruptedException(VAULT_CREATED_BUT_LOCKED_TEXT)
            true
        } catch (e: UnlockInterruptedException) {
            closeDatabaseAndWipeKeys()
            _sessionState.value = SessionState.Locked
            throw e
        } catch (e: Throwable) {
            e.printStackTrace()
            if (metadataWritten) discardCreatedVault()
            withContext(Dispatchers.Main) {
                _sessionState.value = when (e) {
                    is NewerVaultException, is DatabaseBackupException -> openFailureState(e as Exception)
                    else -> SessionState.FatalError("Vault creation failed: ${e.localizedMessage ?: e.message}")
                }
            }
            throw e // Re-throw to allow AuthViewModel to capture and display the localized message
        }
    }

    /**
     * Removes what a failed [vaultCreated] wrote, so Setup can run again instead of finding a
     * metadata file without a usable key. Anything that existed before is left alone.
     */
    private suspend fun discardCreatedVault() {
        try {
            // The database file is deleted next: wait until the connection really is closed.
            databaseConnectionManager.closeConnectionAndWait()
            metadataRepository.discardCreatedVault()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun metadataUnreadableState(e: MetadataUnreadableException) = SessionState.FatalError(
        "VaultPass couldn't read its vault file (${e.path}): ${e.reason}. Your vault was not changed."
    )

    /** After reading or writing metadata.json failed during unlock: never left on the spinner. */
    private fun stateAfterMetadataFailure(e: Exception): SessionState =
        if (e is MetadataUnreadableException) metadataUnreadableState(e) else SessionState.Locked

    private fun databaseMissingState() = SessionState.FatalError(
        "The vault database (${metadataRepository.databaseFile.absolutePath}) is missing. " +
            "VaultPass won't create an empty one in its place. Restore vaultpass.db from a backup."
    )

    private fun openFailureState(e: Exception): SessionState.FatalError = when (e) {
        is NewerVaultException -> SessionState.FatalError(NEWER_VAULT_TEXT, offerUpdateCheck = true)
        is DatabaseBackupException -> SessionState.FatalError(e.message ?: "Couldn't back up the vault before upgrading it.")
        else -> SessionState.FatalError("Couldn't open the vault database: ${e.message ?: e.javaClass.simpleName}")
    }

    // Trigger setup mode from FirstLaunch
    override fun beginSetup() {
        _sessionState.value = SessionState.SetupMasterPassword
    }

    private companion object {
        /**
         * How long [close] waits for the database file to be closed before it gives up waiting.
         * Clearly shorter than the updater's 5 s limit for its before-exit hook (which calls
         * [close]), so the hook finishes and reports in time.
         */
        const val CLOSE_WAIT_MILLIS = 3_000L
        const val UNLOCK_INTERRUPTED_TEXT =
            "VaultPass was locked while it was unlocking. Enter your master password again."
        const val VAULT_CREATED_BUT_LOCKED_TEXT =
            "Your vault was created, but VaultPass was locked before it opened. Unlock it with the master password you just chose."

        const val NEWER_VAULT_TEXT =
            "This vault was saved by a newer version of VaultPass. Install the latest version to open it."
    }
}
