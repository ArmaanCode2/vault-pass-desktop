package com.vaultpass.desktop.domain.session

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.SQLiteVaultDataSource
import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.crypto.CryptoException
import com.vaultpass.desktop.domain.crypto.KeyManager
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.db.DatabaseConnectionManager
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.metadata.VaultDatabaseFileState
import com.vaultpass.desktop.domain.migration.MigrationRegistry
import com.vaultpass.desktop.domain.migration.MigrationRunner
import com.vaultpass.desktop.domain.models.AppSettings
import com.vaultpass.desktop.domain.models.EncryptionConfig
import com.vaultpass.desktop.domain.models.VaultMetadata
import com.vaultpass.desktop.domain.security.AuthenticationRepository
import com.vaultpass.desktop.domain.security.SecureByteArray
import com.vaultpass.desktop.ui.viewmodels.AuthViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Locking closes the database before it wipes the key: the wipe zero-fills the key in place,
 * and a write still running must be rolled back rather than commit data encrypted with it.
 * A lock, background lock or close that comes in while the vault unlocks (or is created) wins:
 * it never ends Unlocked, and the user isn't told the password was wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionLockOrderTest {

    private val events = mutableListOf<String>()

    private inner class RecordingKeyManager : KeyManager {
        private var dek: SecureByteArray? = SecureByteArray(ByteArray(32) { 7 })
        override fun loadActiveDek(dek: SecureByteArray) { this.dek = dek }
        override fun wipeKeys() {
            events += "wipeKeys"
            dek?.wipe()
            dek = null
        }
        override fun getActiveDek(): SecureByteArray = dek ?: throw CryptoException.KeyNotLoaded()
    }

    private inner class RecordingConnectionManager(
        private val failOnClose: Boolean = false,
        /** The open is overtaken by a lock, as SQLiteConnectionManager reports it. */
        private val lockedWhileOpening: Boolean = false,
        /** Runs as the open starts; it then completes, as when the close came in just before it. */
        private val onOpen: () -> Unit = {}
    ) : DatabaseConnectionManager {
        private val state = MutableStateFlow(false)
        override val connectionState: StateFlow<Boolean> = state
        /** What the session set to hear about a dropped connection. */
        var lostListener: (() -> Unit)? = null
        override fun setOnConnectionLost(listener: (() -> Unit)?) { lostListener = listener }
        override fun openConnection() {
            onOpen()
            if (lockedWhileOpening) throw DatabaseClosedException()
            state.value = true
        }
        override fun closeConnection() {
            events += "closeConnection"
            state.value = false
            if (failOnClose) throw IllegalStateException("close failed")
        }
        override suspend fun closeConnectionAndWait() {
            events += "waitUntilClosed"
            state.value = false
        }
    }

    private class Settings(
        private val settings: AppSettings = AppSettings(),
        private val onRead: () -> Unit = {}
    ) : AppSettingsRepository {
        override suspend fun getSettings(): AppSettings {
            onRead()
            return settings
        }
        override suspend fun updateSettings(settings: AppSettings) {}
    }

    private class SessionKey(var stored: ByteArray? = null) : SessionKeyStorage {
        var clearCount = 0
        override fun hasProtectedSession() = stored != null
        override fun saveProtectedSession(dek: SecureByteArray) { stored = dek.data.clone() }
        override fun loadProtectedSession(): SecureByteArray? = stored?.let { SecureByteArray(it.clone()) }
        override fun clearProtectedSession() {
            clearCount++
            stored = null
        }
    }

    private class Metadata(var metadata: VaultMetadata? = null) : MetadataRepository {
        var discarded = false
        override val metadataFile = File("metadata.json")
        override val databaseFile = File("vaultpass.db")
        override suspend fun databaseFileState() = VaultDatabaseFileState.NO_ENTRIES
        override suspend fun getMetadata(): VaultMetadata? = metadata
        override suspend fun saveMetadata(metadata: VaultMetadata) { this.metadata = metadata }
        override suspend fun initializeMetadata(): VaultMetadata = vaultMetadata().also { metadata = it }
        override suspend fun discardCreatedVault() {
            discarded = true
            metadata = null
        }
    }

    private class Auth(private val onVerify: () -> Unit = {}) : AuthenticationRepository {
        override suspend fun verifyMasterPassword(password: String): Boolean {
            onVerify()
            return true
        }
        override suspend fun saveMasterPassword(password: String) = true
    }

    private fun sessionManager(
        keyManager: KeyManager,
        connections: DatabaseConnectionManager,
        scope: TestScope,
        settings: AppSettingsRepository = Settings(),
        auth: AuthenticationRepository = Auth(),
        metadata: MetadataRepository = Metadata(),
        sessionKey: SessionKeyStorage = SessionKey()
    ) = SessionManagerImpl(
        metadataRepository = metadata,
        authenticationRepository = auth,
        migrationRunner = MigrationRunner(MigrationRegistry()),
        keyManager = keyManager,
        databaseConnectionManager = connections,
        appSettingsRepository = settings,
        sessionKeyStorage = sessionKey,
        scope = scope
    )

    @Test
    fun lock_closesTheDatabaseBeforeWipingTheKey() = runTest {
        val session = sessionManager(RecordingKeyManager(), RecordingConnectionManager(), TestScope(StandardTestDispatcher(testScheduler)))
        session.lock()
        assertEquals(listOf("closeConnection", "wipeKeys"), events)
        assertEquals(SessionState.Locked, session.sessionState.value)
    }

    @Test
    fun notifyBackgroundAndClose_closeTheDatabaseBeforeWipingTheKey() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        sessionManager(RecordingKeyManager(), RecordingConnectionManager(), scope).notifyBackground()
        assertEquals(listOf("closeConnection", "wipeKeys"), events)

        events.clear()
        sessionManager(RecordingKeyManager(), RecordingConnectionManager(), scope).close()
        // close() is the exit path: after closing and wiping, it waits until the file is closed.
        assertEquals(listOf("closeConnection", "wipeKeys", "waitUntilClosed"), events)
    }

    @Test
    fun lockAndNotifyBackground_neverThrow_wipeTheKey_andEndLocked_evenWhenClosingFails() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val keyManager = RecordingKeyManager()
        val session = sessionManager(keyManager, RecordingConnectionManager(failOnClose = true), scope)
        session.lock() // must not throw to the UI thread
        assertEquals(listOf("closeConnection", "wipeKeys"), events)
        assertFalse(keyManager.hasActiveDek())
        assertEquals(SessionState.Locked, session.sessionState.value)

        events.clear()
        val other = sessionManager(RecordingKeyManager(), RecordingConnectionManager(failOnClose = true), scope)
        other.notifyBackground()
        assertEquals(listOf("closeConnection", "wipeKeys"), events)
        assertEquals(SessionState.Locked, other.sessionState.value)
    }

    @Test
    fun unlock_overtakenByALockWhileTheDatabaseOpens_endsLocked() = runTest {
        val keyManager = RecordingKeyManager()
        val session = sessionManager(keyManager, RecordingConnectionManager(lockedWhileOpening = true), TestScope(StandardTestDispatcher(testScheduler)))
        assertThrows(UnlockInterruptedException::class.java) { runBlocking { session.unlock("password") } }
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(keyManager.hasActiveDek())
    }

    @Test
    fun unlock_overtakenByALockAfterTheDatabaseOpened_neverEndsUnlocked() = runTest {
        val keyManager = RecordingKeyManager()
        val connections = RecordingConnectionManager()
        lateinit var session: SessionManagerImpl
        // The unlock reads the settings after opening the database; the lock comes in right then.
        session = sessionManager(
            keyManager,
            connections,
            TestScope(StandardTestDispatcher(testScheduler)),
            settings = Settings(onRead = { if (session.sessionState.value == SessionState.Unlocking) session.lock() })
        )
        assertThrows(UnlockInterruptedException::class.java) { runBlocking { session.unlock("password") } }
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(keyManager.hasActiveDek())
        assertFalse(connections.connectionState.value)
    }

    @Test
    fun unlock_withACloseWhileThePasswordIsChecked_endsLocked_withTheDatabaseClosed() = runTest {
        val keyManager = RecordingKeyManager()
        val connections = RecordingConnectionManager()
        lateinit var session: SessionManagerImpl
        // As the updater does before its installer starts: close(), here while the unlock runs.
        session = sessionManager(keyManager, connections, TestScope(StandardTestDispatcher(testScheduler)), auth = Auth(onVerify = { session.close() }))

        assertThrows(UnlockInterruptedException::class.java) { runBlocking { session.unlock("password") } }
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(connections.connectionState.value, "The database the unlock opened afterwards is closed again")
        assertFalse(keyManager.hasActiveDek())
    }

    @Test
    fun unlock_withACloseJustAsTheDatabaseOpens_endsLocked() = runTest {
        val keyManager = RecordingKeyManager()
        lateinit var session: SessionManagerImpl
        val connections = RecordingConnectionManager(onOpen = { session.close() })
        session = sessionManager(keyManager, connections, TestScope(StandardTestDispatcher(testScheduler)))

        assertThrows(UnlockInterruptedException::class.java) { runBlocking { session.unlock("password") } }
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(connections.connectionState.value)
        assertFalse(keyManager.hasActiveDek())
    }

    @Test
    fun unlock_withACloseDuringTheRealOpen_endsLocked_withNoConnection(@TempDir dir: Path) = runTest {
        val manager = SQLiteConnectionManager(dir.resolve("vaultpass.db").toString())
        val keyManager = RecordingKeyManager()
        val session = sessionManager(keyManager, manager, TestScope(StandardTestDispatcher(testScheduler)))
        // The close lands after the open checked for one and before it installs its connection.
        manager.testHooks.beforeInstall = { session.close() }

        assertThrows(UnlockInterruptedException::class.java) { runBlocking { session.unlock("password") } }
        manager.testHooks.beforeInstall = null
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(manager.connectionState.value)
        assertFalse(keyManager.hasActiveDek())
    }

    @Test
    fun startupAutoUnlock_withACloseWhileItOpens_endsLocked_andKeepsTheStoredKey() = runTest {
        val keyManager = RecordingKeyManager()
        val sessionKey = SessionKey(stored = ByteArray(32) { 9 })
        lateinit var session: SessionManagerImpl
        val connections = RecordingConnectionManager(onOpen = { session.close() })
        session = sessionManager(
            keyManager,
            connections,
            TestScope(StandardTestDispatcher(testScheduler)),
            settings = Settings(AppSettings(requireMasterPasswordOnStartup = false)),
            metadata = Metadata(vaultMetadata()),
            sessionKey = sessionKey
        )

        session.initialize()
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(connections.connectionState.value)
        assertFalse(keyManager.hasActiveDek())
        assertEquals(0, sessionKey.clearCount, "Nothing was wrong with the stored key")
    }

    @Test
    fun vaultCreation_withACloseWhileTheDatabaseOpens_keepsTheVault_andEndsLocked() = runTest {
        val keyManager = RecordingKeyManager()
        val metadata = Metadata()
        lateinit var session: SessionManagerImpl
        val connections = RecordingConnectionManager(onOpen = { session.close() })
        session = sessionManager(keyManager, connections, TestScope(StandardTestDispatcher(testScheduler)), metadata = metadata)

        val error = runCatching { session.vaultCreated("password") }.exceptionOrNull()
        assertTrue(error is UnlockInterruptedException, "Got $error")
        assertEquals(SessionState.Locked, session.sessionState.value, "Not \"Vault creation failed\"")
        assertFalse(metadata.discarded, "The vault was complete: kept, to be unlocked with the new password")
        assertNotNull(metadata.metadata)
        assertFalse(connections.connectionState.value)
        assertFalse(keyManager.hasActiveDek())
    }

    @Test
    fun aLockThatWinsOverAnUnlock_isNeverReportedAsAWrongPassword() {
        val message = AuthViewModel.unlockFailureMessage(UnlockInterruptedException("VaultPass was locked while it was unlocking."))
        assertEquals("VaultPass was locked while it was unlocking.", message)
        assertFalse(message.contains("Incorrect", ignoreCase = true))
    }

    @Test
    fun aDroppedConnection_locksTheSession_andUnlockingAgainWorks(@TempDir dir: Path) = runTest {
        val manager = SQLiteConnectionManager(dir.resolve("vaultpass.db").toString())
        val dataSource = SQLiteVaultDataSource(manager)
        val session = sessionManager(RecordingKeyManager(), manager, TestScope(StandardTestDispatcher(testScheduler)))
        assertTrue(session.unlock("password"))
        assertEquals(SessionState.Unlocked, session.sessionState.value)

        // A rollback that fails (an I/O error) drops the open connection.
        manager.testHooks.rollback = { throw java.sql.SQLException("disk I/O error") }
        runCatching {
            dataSource.transaction {
                dataSource.insert(entity("half-done"))
                throw IllegalStateException("the work failed")
            }
        }
        manager.testHooks.rollback = null

        assertEquals(SessionState.Locked, session.sessionState.value, "The user sees the lock screen, not an empty vault")
        assertFalse(manager.connectionState.value)

        assertTrue(session.unlock("password"))
        assertEquals(SessionState.Unlocked, session.sessionState.value)
        dataSource.insert(entity("after-unlock"))
        assertEquals(listOf("after-unlock"), dataSource.getById("after-unlock")?.let { listOf(it.id) })
        assertNull(dataSource.getById("half-done"))
        session.close()
    }

    /** A stored session key that lets a close happen right before it reads the key it was given. */
    private class RacingSessionKey(var beforeRead: () -> Unit = {}) : SessionKeyStorage {
        var stored: ByteArray? = null
        var clearCount = 0
        override fun hasProtectedSession() = stored != null
        override fun saveProtectedSession(dek: SecureByteArray) {
            beforeRead()
            stored = dek.data.clone()
        }
        override fun loadProtectedSession(): SecureByteArray? = stored?.let { SecureByteArray(it.clone()) }
        override fun clearProtectedSession() {
            clearCount++
            stored = null
        }
    }

    private val noPasswordOnStartup = Settings(AppSettings(requireMasterPasswordOnStartup = false))

    @Test
    fun aCloseWhileTheSessionKeyIsStored_neverStoresAWipedKey_andTheNextStartUnlocksWithTheRealOne() = runTest {
        val key = ByteArray(32) { 0x33 }
        val keyManager = com.vaultpass.desktop.data.crypto.InMemoryKeyManager().apply { loadActiveDek(SecureByteArray(key.clone())) }
        val sessionKey = RacingSessionKey()
        val session = sessionManager(
            keyManager, RecordingConnectionManager(), TestScope(StandardTestDispatcher(testScheduler)),
            settings = noPasswordOnStartup, sessionKey = sessionKey
        )
        // The updater's close() lands between handing the key over and the storage reading it:
        // wipeKeys() zero-fills the key manager's array in place.
        sessionKey.beforeRead = { sessionKey.beforeRead = {}; session.close() }

        assertThrows(UnlockInterruptedException::class.java) { runBlocking { session.unlock("password") } }
        // The contract: a key copied before the lock may still be written right after it, and it
        // is then exactly the real key, never zeros or a half-wiped key.
        assertArrayEquals(key, sessionKey.stored, "The real key was stored")

        // The next start unlocks by itself, with the real key.
        val nextKeys = com.vaultpass.desktop.data.crypto.InMemoryKeyManager()
        val next = sessionManager(
            nextKeys, RecordingConnectionManager(), TestScope(StandardTestDispatcher(testScheduler)),
            settings = noPasswordOnStartup, metadata = Metadata(vaultMetadata()), sessionKey = sessionKey
        )
        next.initialize()
        assertEquals(SessionState.Unlocked, next.sessionState.value)
        assertArrayEquals(key, nextKeys.copyActiveDek().data, "Unlocked with the real key")
    }

    @Test
    fun aLockOrCloseDuringAPasswordUnlock_keepsTheStoredKey_andTheNextStartUnlocksByItself() = runTest {
        val key = ByteArray(32) { 0x55 }
        for (interruption in listOf("close", "lock")) {
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            // "Require the master password on startup" is off, and the key from an earlier session is stored.
            val sessionKey = SessionKey(stored = key.clone())
            val keyManager = com.vaultpass.desktop.data.crypto.InMemoryKeyManager().apply { loadActiveDek(SecureByteArray(key.clone())) }
            lateinit var session: SessionManagerImpl
            // The lock or close comes in while the password is checked, before the key is copied.
            session = sessionManager(
                keyManager, RecordingConnectionManager(), scope,
                settings = noPasswordOnStartup, sessionKey = sessionKey,
                auth = Auth(onVerify = { if (interruption == "lock") session.lock() else session.close() })
            )

            assertThrows(UnlockInterruptedException::class.java) { runBlocking { session.unlock("password") } }
            scope.advanceUntilIdle() // what lock() does afterwards with the setting
            assertArrayEquals(key, sessionKey.stored, "$interruption: the stored key is kept")
            assertEquals(0, sessionKey.clearCount, "$interruption: nothing cleared it")

            val nextKeys = com.vaultpass.desktop.data.crypto.InMemoryKeyManager()
            val next = sessionManager(
                nextKeys, RecordingConnectionManager(), TestScope(StandardTestDispatcher(testScheduler)),
                settings = noPasswordOnStartup, metadata = Metadata(vaultMetadata()), sessionKey = sessionKey
            )
            next.initialize()
            assertEquals(SessionState.Unlocked, next.sessionState.value, "$interruption: the next start unlocks by itself")
            assertArrayEquals(key, nextKeys.copyActiveDek().data)
        }
    }

    @Test
    fun aCloseWhileTurningOffThePasswordOnStartup_neverStoresAWipedKey() = runTest {
        val key = ByteArray(32) { 0x44 }
        val keyManager = com.vaultpass.desktop.data.crypto.InMemoryKeyManager().apply { loadActiveDek(SecureByteArray(key.clone())) }
        val sessionKey = RacingSessionKey()
        val session = sessionManager(keyManager, RecordingConnectionManager(), TestScope(StandardTestDispatcher(testScheduler)), sessionKey = sessionKey)
        sessionKey.beforeRead = { sessionKey.beforeRead = {}; session.close() }

        session.onRequireMasterPasswordOnStartupChanged(false)
        // Copied before the close: exactly the real key is stored, never zeros or garbage.
        assertArrayEquals(key, sessionKey.stored, "The real key was stored")
        assertFalse(keyManager.hasActiveDek(), "The close still wiped the key")
    }

    @Test
    fun anAllZeroStoredKey_isNeverUsedToUnlock() = runTest {
        val sessionKey = SessionKey(stored = ByteArray(32))
        val keyManager = com.vaultpass.desktop.data.crypto.InMemoryKeyManager()
        val connections = RecordingConnectionManager()
        val session = sessionManager(
            keyManager, connections, TestScope(StandardTestDispatcher(testScheduler)),
            settings = noPasswordOnStartup, metadata = Metadata(vaultMetadata()), sessionKey = sessionKey
        )
        session.initialize()
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(keyManager.hasActiveDek())
        assertFalse(connections.connectionState.value, "The database was never opened with it")
        assertNull(sessionKey.stored, "Treated as no stored session, and cleared")
    }

    @Test
    fun aConnectionLostWhileAnUnlockFinishes_neverEndsUnlocked() = runTest {
        val keyManager = RecordingKeyManager()
        val connections = RecordingConnectionManager()
        lateinit var session: SessionManagerImpl
        // The connection is dropped while the unlock is still Unlocking, after it opened the
        // database; the unlock's own check of the connection may already have passed.
        session = sessionManager(
            keyManager, connections, TestScope(StandardTestDispatcher(testScheduler)),
            settings = Settings(onRead = { if (session.sessionState.value == SessionState.Unlocking) connections.lostListener?.invoke() })
        )
        assertThrows(UnlockInterruptedException::class.java) { runBlocking { session.unlock("password") } }
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(keyManager.hasActiveDek())
    }

    @Test
    fun closeOnAnUnlockedSession_endsLocked() = runTest {
        val keyManager = RecordingKeyManager()
        val connections = RecordingConnectionManager()
        val session = sessionManager(keyManager, connections, TestScope(StandardTestDispatcher(testScheduler)))
        assertTrue(session.unlock("password"))
        assertEquals(SessionState.Unlocked, session.sessionState.value)

        // The updater's exit path calls close() whatever the state: no Unlocked session may remain
        // with a closed database and a wiped key.
        session.close()
        assertEquals(SessionState.Locked, session.sessionState.value)
        assertFalse(connections.connectionState.value)
        assertFalse(keyManager.hasActiveDek())
    }

    private fun entity(id: String) = VaultEntity(
        id = id, type = "PASSWORD", encryptedPayload = byteArrayOf(1), createdAt = 1L, updatedAt = 1L,
        isFavorite = false, isDeleted = false, deletedAt = null, syncVersion = 1
    )

    private companion object {
        fun vaultMetadata() = VaultMetadata(
            initialized = true,
            vaultVersion = 1,
            metadataVersion = 1,
            kdfVersion = 1,
            encryptionVersion = 1,
            settingsVersion = 1,
            migrationVersion = 1,
            createdAt = 1L,
            lastOpenedAt = 1L,
            createdWithAppVersion = "1.0.0",
            lastOpenedAppVersion = "1.0.0",
            encryptionConfig = EncryptionConfig(algorithm = "AES-GCM", wrappedDekBase64 = "d3JhcHBlZA==", ivBase64 = "aXY=")
        )
    }
}
