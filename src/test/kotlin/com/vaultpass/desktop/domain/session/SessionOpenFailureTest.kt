package com.vaultpass.desktop.domain.session

import com.vaultpass.desktop.data.MetadataRepositoryImpl
import com.vaultpass.desktop.data.crypto.InMemoryKeyManager
import com.vaultpass.desktop.data.database.DatabaseQueries
import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.db.DatabaseConnectionManager
import com.vaultpass.desktop.domain.db.NewerVaultException
import com.vaultpass.desktop.domain.migration.MigrationRegistry
import com.vaultpass.desktop.domain.migration.MigrationRunner
import com.vaultpass.desktop.domain.models.AppSettings
import com.vaultpass.desktop.domain.models.EncryptionConfig
import com.vaultpass.desktop.domain.models.VaultMetadata
import com.vaultpass.desktop.domain.security.AuthenticationRepository
import com.vaultpass.desktop.domain.security.SecureByteArray
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager

/** How SessionManagerImpl reports a database that can't be opened, and cleans up a failed create. */
class SessionOpenFailureTest {

    @TempDir
    lateinit var dataDir: File

    private val metadataFile get() = File(dataDir, "metadata.json")
    private val databaseFile get() = File(dataDir, "vaultpass.db")

    private val newerVaultText =
        "This vault was saved by a newer version of VaultPass. Install the latest version to open it."

    private class FakeSessionKeyStorage(var stored: ByteArray? = null) : SessionKeyStorage {
        var clearCount = 0
        override fun hasProtectedSession() = stored != null
        override fun saveProtectedSession(dek: SecureByteArray) { stored = dek.data.clone() }
        override fun loadProtectedSession(): SecureByteArray? = stored?.let { SecureByteArray(it.clone()) }
        override fun clearProtectedSession() { clearCount++; stored = null }
    }

    private class FakeAuthenticationRepository : AuthenticationRepository {
        var saveResult: () -> Boolean = { true }
        override suspend fun saveMasterPassword(password: String) = saveResult()
        override suspend fun verifyMasterPassword(password: String) = true
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun createDatabase(userVersion: Int, entries: Int) {
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(DatabaseQueries.CREATE_VAULT_ENTRY_TABLE)
                stmt.execute(DatabaseQueries.CREATE_PAIRED_DEVICE_TABLE)
                repeat(entries) { i ->
                    stmt.execute(
                        "INSERT INTO vault_entry (id, type, encrypted_payload, created_at, updated_at) " +
                            "VALUES ('entry-$i', 'PASSWORD', x'0102', $i, $i)"
                    )
                }
                stmt.execute("PRAGMA user_version = $userVersion")
            }
        }
    }

    private fun writeExistingVaultMetadata() = runBlocking {
        MetadataRepositoryImpl(metadataFile, dataDir, databaseFile).saveMetadata(
            VaultMetadata(
                initialized = true,
                vaultVersion = 1,
                metadataVersion = 1,
                kdfVersion = 1,
                encryptionVersion = 1,
                settingsVersion = 1,
                migrationVersion = 1,
                createdAt = 1,
                lastOpenedAt = 1,
                createdWithAppVersion = "1.0.0",
                lastOpenedAppVersion = "1.0.0",
                encryptionConfig = EncryptionConfig("AES-GCM", "d3JhcHBlZA==", "aXY=")
            )
        )
    }

    private fun sessionManager(
        sessionKeyStorage: SessionKeyStorage = FakeSessionKeyStorage(),
        authenticationRepository: AuthenticationRepository = FakeAuthenticationRepository(),
        databaseConnectionManager: DatabaseConnectionManager = SQLiteConnectionManager(databaseFile.absolutePath),
        settings: AppSettings = AppSettings(),
        keyManager: InMemoryKeyManager = InMemoryKeyManager(),
        appSettingsRepository: AppSettingsRepository = object : AppSettingsRepository {
            override suspend fun getSettings() = settings
            override suspend fun updateSettings(settings: AppSettings) {}
        }
    ) = SessionManagerImpl(
        metadataRepository = MetadataRepositoryImpl(metadataFile, dataDir, databaseFile),
        authenticationRepository = authenticationRepository,
        migrationRunner = MigrationRunner(MigrationRegistry()),
        keyManager = keyManager,
        databaseConnectionManager = databaseConnectionManager,
        appSettingsRepository = appSettingsRepository,
        sessionKeyStorage = sessionKeyStorage,
        scope = TestScope()
    )

    private fun fatalError(manager: SessionManager): SessionState.FatalError {
        val state = manager.sessionState.value
        assertTrue(state is SessionState.FatalError, "was $state")
        return state as SessionState.FatalError
    }

    // Startup with a stored session key (no master password asked).

    @Test
    fun startup_newerVault_offersUpdateAndKeepsEverything() = runTest {
        writeExistingVaultMetadata()
        createDatabase(userVersion = 3, entries = 2)
        val dbHash = sha256(databaseFile)
        val metadataHash = sha256(metadataFile)
        val keys = FakeSessionKeyStorage(ByteArray(32) { 1 })

        val manager = sessionManager(keys, settings = AppSettings(requireMasterPasswordOnStartup = false))
        manager.initialize()

        val error = fatalError(manager)
        assertEquals(newerVaultText, error.reason)
        assertTrue(error.offerUpdateCheck)
        assertEquals(0, keys.clearCount)
        assertTrue(keys.hasProtectedSession())
        assertEquals(dbHash, sha256(databaseFile))
        assertEquals(metadataHash, sha256(metadataFile))
    }

    @Test
    fun startup_backupFailure_isReportedAndKeepsTheSessionKey() = runTest {
        writeExistingVaultMetadata()
        createDatabase(userVersion = 1, entries = 2)
        File(dataDir, "vaultpass.db.backup-tmp").apply { mkdirs() }.resolve("blocker.txt").writeText("x")
        val keys = FakeSessionKeyStorage(ByteArray(32) { 1 })

        val manager = sessionManager(keys, settings = AppSettings(requireMasterPasswordOnStartup = false))
        manager.initialize()

        val error = fatalError(manager)
        assertTrue(error.reason.startsWith("Couldn't back up the vault before upgrading it: "), error.reason)
        assertFalse(error.offerUpdateCheck)
        assertEquals(0, keys.clearCount)
    }

    @Test
    fun startup_otherOpenFailure_saysCouldntOpenAndClearsTheSessionKey() = runTest {
        writeExistingVaultMetadata()
        databaseFile.writeText("this is not a SQLite database".repeat(200))
        val keys = FakeSessionKeyStorage(ByteArray(32) { 1 })

        val manager = sessionManager(keys, settings = AppSettings(requireMasterPasswordOnStartup = false))
        manager.initialize()

        val error = fatalError(manager)
        assertTrue(error.reason.startsWith("Couldn't open the vault database: "), error.reason)
        assertFalse(error.reason.contains("corruption"))
        assertFalse(error.offerUpdateCheck)
        assertEquals(1, keys.clearCount)
    }

    @Test
    fun startup_unexpectedFailure_endsInFatalErrorNotTheSpinner() = runTest {
        writeExistingVaultMetadata()
        createDatabase(userVersion = DatabaseQueries.SCHEMA_VERSION, entries = 1)
        val failingSettings = object : AppSettingsRepository {
            override suspend fun getSettings(): AppSettings = throw java.io.IOException("settings.json is locked")
            override suspend fun updateSettings(settings: AppSettings) {}
        }

        val manager = sessionManager(appSettingsRepository = failingSettings)
        assertEquals(SessionState.Unlocking, manager.sessionState.value)
        manager.initialize()

        val error = fatalError(manager)
        assertEquals("VaultPass couldn't start: settings.json is locked", error.reason)
        assertFalse(error.offerUpdateCheck)
    }

    @Test
    fun startup_newerMetadataVersion_offersUpdate() = runTest {
        writeExistingVaultMetadata()
        val newer = metadataFile.readText().replace("\"metadataVersion\": 1", "\"metadataVersion\": 2")
        assertNotEquals(metadataFile.readText(), newer)
        metadataFile.writeText(newer)
        val metadataHash = sha256(metadataFile)

        val manager = sessionManager()
        manager.initialize()

        val error = fatalError(manager)
        assertEquals(newerVaultText, error.reason)
        assertTrue(error.offerUpdateCheck)
        assertEquals(metadataHash, sha256(metadataFile))
    }

    // Unlock with the master password.

    @Test
    fun unlock_newerVault_writesNothing() = runTest {
        writeExistingVaultMetadata()
        createDatabase(userVersion = 3, entries = 1)
        val dbHash = sha256(databaseFile)
        val metadataHash = sha256(metadataFile)
        val keys = FakeSessionKeyStorage()

        val manager = sessionManager(keys)
        assertFalse(manager.unlock("password"))

        val error = fatalError(manager)
        assertEquals(newerVaultText, error.reason)
        assertTrue(error.offerUpdateCheck)
        assertEquals(dbHash, sha256(databaseFile))
        assertEquals(metadataHash, sha256(metadataFile))
        assertEquals(0, keys.clearCount)
    }

    @Test
    fun unlock_v1Vault_isBackedUpAndOpens() = runTest {
        writeExistingVaultMetadata()
        createDatabase(userVersion = 1, entries = 2)

        val manager = sessionManager()
        assertTrue(manager.unlock("password"))
        assertEquals(SessionState.Unlocked, manager.sessionState.value)
        assertTrue(File(dataDir, "vaultpass.db.before-schema-v${DatabaseQueries.SCHEMA_VERSION}.bak").isFile)
        manager.close()
    }

    // Missing metadata.json or vaultpass.db.

    /** Counts calls to openConnection, which would create a missing database. */
    private inner class CountingConnectionManager : DatabaseConnectionManager {
        private val real = SQLiteConnectionManager(databaseFile.absolutePath)
        var opens = 0
        override val connectionState: StateFlow<Boolean> = real.connectionState
        override fun openConnection() { opens++; real.openConnection() }
        override fun closeConnection() = real.closeConnection()
        override suspend fun closeConnectionAndWait() = real.closeConnectionAndWait()
    }

    @Test
    fun startup_missingMetadataWithEntries_isAFatalErrorNotSetup() = runTest {
        createDatabase(userVersion = DatabaseQueries.SCHEMA_VERSION, entries = 2)
        val dbHash = sha256(databaseFile)
        val filesBefore = dataDir.list()!!.sorted()

        val manager = sessionManager()
        manager.initialize()

        assertEquals(
            "VaultPass found your vault entries in ${databaseFile.absolutePath}, but the vault's key file " +
                "(metadata.json) is missing from ${dataDir.absolutePath}. If you have a copy of this folder " +
                "(for example from File History or another backup), put metadata.json back into it. " +
                "Nothing was changed.",
            fatalError(manager).reason
        )
        assertEquals(dbHash, sha256(databaseFile))
        assertEquals(filesBefore, dataDir.list()!!.sorted())
    }

    @Test
    fun startup_missingMetadataWithAnEmptyOrNoDatabase_goesToSetup() = runTest {
        val manager = sessionManager()
        manager.initialize()
        assertEquals(SessionState.FirstLaunch, manager.sessionState.value)
        assertFalse(databaseFile.exists(), "The check doesn't create the database")

        createDatabase(userVersion = DatabaseQueries.SCHEMA_VERSION, entries = 0)
        val dbHash = sha256(databaseFile)
        val again = sessionManager()
        again.initialize()
        assertEquals(SessionState.FirstLaunch, again.sessionState.value)
        assertEquals(dbHash, sha256(databaseFile))
    }

    private val databaseMissingText get() =
        "The vault database (${databaseFile.absolutePath}) is missing. VaultPass won't create an empty one " +
            "in its place. Restore vaultpass.db from a backup."

    @Test
    fun startup_withSessionKeyAndNoDatabase_isAFatalErrorAndCreatesNothing() = runTest {
        writeExistingVaultMetadata()
        val keys = FakeSessionKeyStorage(ByteArray(32) { 1 })
        val keyManager = InMemoryKeyManager()
        val connections = CountingConnectionManager()

        val manager = sessionManager(keys, databaseConnectionManager = connections,
            settings = AppSettings(requireMasterPasswordOnStartup = false), keyManager = keyManager)
        manager.initialize()

        assertEquals(databaseMissingText, fatalError(manager).reason)
        assertEquals(0, connections.opens)
        assertFalse(databaseFile.exists())
        assertFalse(keyManager.hasActiveDek())
        assertEquals(0, keys.clearCount, "The stored key still fits once the database is restored")
    }

    @Test
    fun unlock_withNoDatabase_isAFatalErrorAndCreatesNothing() = runTest {
        writeExistingVaultMetadata()
        val keyManager = InMemoryKeyManager().apply { loadActiveDek(SecureByteArray(ByteArray(32) { 2 })) }
        val connections = CountingConnectionManager()

        val manager = sessionManager(databaseConnectionManager = connections, keyManager = keyManager)
        assertFalse(manager.unlock("password"))

        assertEquals(databaseMissingText, fatalError(manager).reason)
        assertEquals(0, connections.opens)
        assertFalse(databaseFile.exists())
        assertFalse(keyManager.hasActiveDek())
    }

    @Test
    fun unlock_unreadableMetadata_endsInFatalErrorNotTheSpinner() = runTest {
        createDatabase(userVersion = DatabaseQueries.SCHEMA_VERSION, entries = 1)
        metadataFile.writeText("{ damaged")

        val manager = sessionManager()
        assertThrows(com.vaultpass.desktop.domain.metadata.MetadataUnreadableException::class.java) {
            runBlocking { manager.unlock("password") }
        }

        assertEquals(
            "VaultPass couldn't read its vault file (${metadataFile.absolutePath}): " +
                "the file is damaged or isn't a VaultPass vault file. Your vault was not changed.",
            fatalError(manager).reason
        )
    }

    @Test
    fun unlock_metadataWriteFails_endsLocked() = runTest {
        writeExistingVaultMetadata()
        createDatabase(userVersion = DatabaseQueries.SCHEMA_VERSION, entries = 1)
        // A folder where the temporary metadata file goes makes the write fail.
        File(dataDir, "metadata.json.tmp").apply { mkdirs() }.resolve("blocker.txt").writeText("x")

        val manager = sessionManager()
        assertThrows(Exception::class.java) { runBlocking { manager.unlock("password") } }

        assertEquals(SessionState.Locked, manager.sessionState.value)
    }

    // Vault creation.

    @Test
    fun create_saveMasterPasswordFails_removesWhatItWroteSoSetupCanRetry() = runTest {
        val auth = FakeAuthenticationRepository().apply { saveResult = { false } }
        val manager = sessionManager(authenticationRepository = auth)

        assertFalse(manager.vaultCreated("password"))
        assertFalse(metadataFile.exists())
        assertFalse(databaseFile.exists())

        // A retry is no longer refused as "vault already exists".
        auth.saveResult = { true }
        assertTrue(manager.vaultCreated("password"))
        assertTrue(metadataFile.exists())
        assertTrue(databaseFile.exists())
        assertEquals(SessionState.Unlocked, manager.sessionState.value)
        manager.close()
    }

    @Test
    fun create_saveMasterPasswordThrows_removesWhatItWrote() = runTest {
        val auth = FakeAuthenticationRepository().apply { saveResult = { throw IllegalStateException("boom") } }
        val manager = sessionManager(authenticationRepository = auth)

        assertThrows(IllegalStateException::class.java) { runBlocking { manager.vaultCreated("password") } }
        assertFalse(metadataFile.exists())
        assertFalse(databaseFile.exists())
        assertEquals("Vault creation failed: boom", fatalError(manager).reason)
    }

    @Test
    fun create_databaseCreatedThenFailure_removesTheEmptyDatabase() = runTest {
        val real = SQLiteConnectionManager(databaseFile.absolutePath)
        val failing = object : DatabaseConnectionManager {
            override val connectionState: StateFlow<Boolean> = real.connectionState
            override fun openConnection() {
                real.openConnection()
                throw IllegalStateException("disk full")
            }
            override fun closeConnection() = real.closeConnection()
            override suspend fun closeConnectionAndWait() = real.closeConnectionAndWait()
        }
        val manager = sessionManager(databaseConnectionManager = failing)

        assertThrows(IllegalStateException::class.java) { runBlocking { manager.vaultCreated("password") } }
        assertFalse(metadataFile.exists())
        assertFalse(databaseFile.exists())
        assertFalse(File(dataDir, "vaultpass.db-wal").exists())
    }

    @Test
    fun create_overAnEmptyNewerDatabase_keepsThatDatabase() = runTest {
        createDatabase(userVersion = 3, entries = 0)
        val dbHash = sha256(databaseFile)
        val manager = sessionManager()

        assertThrows(NewerVaultException::class.java) { runBlocking { manager.vaultCreated("password") } }

        val error = fatalError(manager)
        assertEquals(newerVaultText, error.reason)
        assertTrue(error.offerUpdateCheck)
        assertFalse(metadataFile.exists())
        assertEquals(dbHash, sha256(databaseFile))
    }

    @Test
    fun create_overAnEmptyExistingDatabase_failureLeavesTheDatabase() = runTest {
        createDatabase(userVersion = DatabaseQueries.SCHEMA_VERSION, entries = 0)
        val dbHash = sha256(databaseFile)
        val auth = FakeAuthenticationRepository().apply { saveResult = { false } }
        val manager = sessionManager(authenticationRepository = auth)

        assertFalse(manager.vaultCreated("password"))
        assertFalse(metadataFile.exists())
        assertEquals(dbHash, sha256(databaseFile))
    }

    @Test
    fun create_refusedOverExistingMetadata_leavesItAlone() = runTest {
        writeExistingVaultMetadata()
        val metadataHash = sha256(metadataFile)
        val manager = sessionManager()

        assertThrows(IllegalStateException::class.java) { runBlocking { manager.vaultCreated("password") } }
        assertEquals(metadataHash, sha256(metadataFile))
    }
}
