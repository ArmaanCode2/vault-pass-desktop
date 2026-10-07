package com.vaultpass.desktop.domain.session

import com.vaultpass.desktop.data.crypto.InMemoryKeyManager
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.db.DatabaseConnectionManager
import com.vaultpass.desktop.domain.metadata.MetadataRepository
import com.vaultpass.desktop.domain.migration.MigrationRunner
import com.vaultpass.desktop.domain.models.AppSettings
import com.vaultpass.desktop.domain.models.VaultMetadata
import com.vaultpass.desktop.domain.platform.FilePicker
import com.vaultpass.desktop.domain.security.AuthenticationRepository
import com.vaultpass.desktop.domain.security.SecureByteArray
import com.vaultpass.desktop.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SessionStartupPersistenceTest {

    private fun createTestMetadata() = VaultMetadata(
        initialized = true,
        vaultVersion = 1,
        metadataVersion = 1,
        kdfVersion = 1,
        encryptionVersion = 1,
        settingsVersion = 1,
        migrationVersion = 1,
        createdAt = System.currentTimeMillis(),
        lastOpenedAt = System.currentTimeMillis(),
        createdWithAppVersion = "1.0.0",
        lastOpenedAppVersion = "1.0.0"
    )

    private class FakeSessionKeyStorage : SessionKeyStorage {
        var storedDek: ByteArray? = null
        var clearCount = 0
        var saveCount = 0

        override fun hasProtectedSession(): Boolean = storedDek != null

        override fun saveProtectedSession(dek: SecureByteArray) {
            saveCount++
            storedDek = dek.data.clone()
        }

        override fun loadProtectedSession(): SecureByteArray? {
            return storedDek?.let { SecureByteArray(it.clone()) }
        }

        override fun clearProtectedSession() {
            clearCount++
            storedDek = null
        }
    }

    private class FakeAppSettingsRepository(initialSettings: AppSettings = AppSettings()) : AppSettingsRepository {
        var settings = initialSettings
        override suspend fun getSettings(): AppSettings = settings
        override suspend fun updateSettings(settings: AppSettings) {
            this.settings = settings
        }
    }

    private class FakeMetadataRepository(var metadata: VaultMetadata? = null) : MetadataRepository {
        override val metadataFile = File("metadata.json")
        override val databaseFile = File("vaultpass.db")
        override suspend fun databaseFileState() = com.vaultpass.desktop.domain.metadata.VaultDatabaseFileState.NO_ENTRIES
        override suspend fun getMetadata(): VaultMetadata? = metadata
        override suspend fun saveMetadata(metadata: VaultMetadata) {
            this.metadata = metadata
        }
        override suspend fun initializeMetadata(): VaultMetadata {
            val m = VaultMetadata(
                initialized = true,
                vaultVersion = 1,
                metadataVersion = 1,
                kdfVersion = 1,
                encryptionVersion = 1,
                settingsVersion = 1,
                migrationVersion = 1,
                createdAt = System.currentTimeMillis(),
                lastOpenedAt = System.currentTimeMillis(),
                createdWithAppVersion = "1.0.0",
                lastOpenedAppVersion = "1.0.0"
            )
            metadata = m
            return m
        }
        override suspend fun discardCreatedVault() {}
    }

    private class FakeAuthenticationRepository : AuthenticationRepository {
        override suspend fun verifyMasterPassword(password: String): Boolean = true
        override suspend fun saveMasterPassword(password: String): Boolean = true
    }

    private class FakeDatabaseConnectionManager : DatabaseConnectionManager {
        private val _connectionState = MutableStateFlow(false)
        override val connectionState: StateFlow<Boolean> = _connectionState

        val isOpen: Boolean get() = _connectionState.value

        override fun openConnection() { _connectionState.value = true }
        override fun closeConnection() { _connectionState.value = false }
    }

    private class FakeFilePicker : FilePicker {
        override suspend fun showFolderPickerDialog(title: String): File? = null
        override suspend fun showSaveFileDialog(title: String, defaultFileName: String, allowedExtensions: List<String>): File? = null
        override suspend fun showOpenFileDialog(title: String, allowedExtensions: List<String>): File? = null
    }

    @Test
    fun testTogglingRequirePasswordOnStartupPersistsActiveDek() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val keyManager = InMemoryKeyManager()
        val dummyDek = SecureByteArray(ByteArray(32) { 0x42.toByte() })
        keyManager.loadActiveDek(dummyDek)

        val sessionKeyStorage = FakeSessionKeyStorage()
        val appSettingsRepo = FakeAppSettingsRepository(AppSettings(requireMasterPasswordOnStartup = true))
        val sessionManager = SessionManagerImpl(
            metadataRepository = FakeMetadataRepository(createTestMetadata()),
            authenticationRepository = FakeAuthenticationRepository(),
            migrationRunner = MigrationRunner(com.vaultpass.desktop.domain.migration.MigrationRegistry()),
            keyManager = keyManager,
            databaseConnectionManager = FakeDatabaseConnectionManager(),
            appSettingsRepository = appSettingsRepo,
            sessionKeyStorage = sessionKeyStorage,
            scope = testScope
        )

        // Vault is unlocked and active DEK exists
        assertTrue(keyManager.hasActiveDek())
        assertFalse(sessionKeyStorage.hasProtectedSession())

        // User turns OFF 'require password on startup' (required = false)
        sessionManager.onRequireMasterPasswordOnStartupChanged(false)

        assertTrue(sessionKeyStorage.hasProtectedSession())
        assertEquals(1, sessionKeyStorage.saveCount)
        assertArrayEquals(ByteArray(32) { 0x42.toByte() }, sessionKeyStorage.storedDek)

        // User turns ON 'require password on startup' (required = true)
        sessionManager.onRequireMasterPasswordOnStartupChanged(true)

        assertFalse(sessionKeyStorage.hasProtectedSession())
        assertEquals(1, sessionKeyStorage.clearCount)
    }

    @Test
    fun testLockDoesNotClearProtectedSessionWhenStartupPasswordNotRequired() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val keyManager = InMemoryKeyManager()
        val dummyDek = SecureByteArray(ByteArray(32) { 0x77.toByte() })
        keyManager.loadActiveDek(dummyDek)

        val sessionKeyStorage = FakeSessionKeyStorage()
        sessionKeyStorage.saveProtectedSession(dummyDek)
        assertTrue(sessionKeyStorage.hasProtectedSession())

        val appSettingsRepo = FakeAppSettingsRepository(AppSettings(requireMasterPasswordOnStartup = false))
        val dbConn = FakeDatabaseConnectionManager()
        dbConn.openConnection()

        val sessionManager = SessionManagerImpl(
            metadataRepository = FakeMetadataRepository(createTestMetadata()),
            authenticationRepository = FakeAuthenticationRepository(),
            migrationRunner = MigrationRunner(com.vaultpass.desktop.domain.migration.MigrationRegistry()),
            keyManager = keyManager,
            databaseConnectionManager = dbConn,
            appSettingsRepository = appSettingsRepo,
            sessionKeyStorage = sessionKeyStorage,
            scope = testScope
        )

        // Calling lock
        sessionManager.lock()
        testScope.advanceUntilIdle()

        // In-memory key MUST be zeroized
        assertFalse(keyManager.hasActiveDek())
        assertFalse(dbConn.isOpen)
        assertEquals(SessionState.Locked, sessionManager.sessionState.value)

        // But protected session MUST persist on disk!
        assertTrue(sessionKeyStorage.hasProtectedSession())
        assertEquals(0, sessionKeyStorage.clearCount)
    }

    @Test
    fun testLockClearsProtectedSessionWhenStartupPasswordRequired() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val keyManager = InMemoryKeyManager()
        val dummyDek = SecureByteArray(ByteArray(32) { 0x99.toByte() })
        keyManager.loadActiveDek(dummyDek)

        val sessionKeyStorage = FakeSessionKeyStorage()
        sessionKeyStorage.saveProtectedSession(dummyDek)
        assertTrue(sessionKeyStorage.hasProtectedSession())

        val appSettingsRepo = FakeAppSettingsRepository(AppSettings(requireMasterPasswordOnStartup = true))
        val dbConn = FakeDatabaseConnectionManager()
        dbConn.openConnection()

        val sessionManager = SessionManagerImpl(
            metadataRepository = FakeMetadataRepository(createTestMetadata()),
            authenticationRepository = FakeAuthenticationRepository(),
            migrationRunner = MigrationRunner(com.vaultpass.desktop.domain.migration.MigrationRegistry()),
            keyManager = keyManager,
            databaseConnectionManager = dbConn,
            appSettingsRepository = appSettingsRepo,
            sessionKeyStorage = sessionKeyStorage,
            scope = testScope
        )

        // Calling lock
        sessionManager.lock()
        testScope.advanceUntilIdle()

        // Both in-memory key and on-disk session MUST be cleared
        assertFalse(keyManager.hasActiveDek())
        assertFalse(dbConn.isOpen)
        assertEquals(SessionState.Locked, sessionManager.sessionState.value)
        assertFalse(sessionKeyStorage.hasProtectedSession())
        assertEquals(1, sessionKeyStorage.clearCount)
    }

    @Test
    fun testSettingsViewModelUpdateRequireMasterPasswordOnStartup() = runTest {
        val appSettingsRepo = FakeAppSettingsRepository(AppSettings(requireMasterPasswordOnStartup = true))
        // Set from SettingsViewModel's own scope (the Swing main dispatcher), not the test's.
        val callback = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val fakeSessionManager = object : SessionManager {
            override val sessionState = kotlinx.coroutines.flow.MutableStateFlow(SessionState.Unlocked)
            override suspend fun initialize() {}
            override suspend fun unlock(password: String): Boolean = true
            override fun lock() {}
            override fun notifyBackground() {}
            override fun close() {}
            override suspend fun vaultCreated(password: String): Boolean = true
            override fun beginSetup() {}
            override suspend fun onRequireMasterPasswordOnStartupChanged(required: Boolean) {
                callback.set(required)
            }
        }

        val viewModel = SettingsViewModel(
            repository = appSettingsRepo,
            filePicker = FakeFilePicker(),
            sessionManager = fakeSessionManager
        )

        viewModel.updateRequireMasterPasswordOnStartup(false)
        testScheduler.advanceUntilIdle()
        // The view model calls the session from the Swing main dispatcher, which the test
        // scheduler doesn't drive: wait (in real time, bounded) for that call to arrive.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.withTimeout(5_000) { while (callback.get() == null) kotlinx.coroutines.delay(10) }
        }

        assertFalse(appSettingsRepo.settings.requireMasterPasswordOnStartup)
        assertEquals(false, callback.get())
    }
}
