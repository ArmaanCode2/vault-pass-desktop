package com.vaultpass.desktop.data

import com.vaultpass.desktop.data.crypto.InMemoryKeyManager
import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.closeAndWait
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.db.DatabaseConnectionManager
import com.vaultpass.desktop.domain.metadata.MetadataUnreadableException
import com.vaultpass.desktop.domain.migration.MigrationRegistry
import com.vaultpass.desktop.domain.migration.MigrationRunner
import com.vaultpass.desktop.domain.models.AppSettings
import com.vaultpass.desktop.domain.security.AuthenticationRepository
import com.vaultpass.desktop.domain.security.SecureByteArray
import com.vaultpass.desktop.domain.session.SessionKeyStorage
import com.vaultpass.desktop.domain.session.SessionManagerImpl
import com.vaultpass.desktop.domain.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager

class MetadataRepositoryImplTest {

    @TempDir
    lateinit var dataDir: File

    private val metadataFile get() = File(dataDir, "metadata.json")
    private val databaseFile get() = File(dataDir, "vaultpass.db")

    private fun repository() = MetadataRepositoryImpl(metadataFile, dataDir, databaseFile)

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun missingMetadataReadsAsNoVault() = runTest {
        assertNull(repository().getMetadata())
    }

    @Test
    fun corruptMetadataThrowsAndLeavesTheFileUntouched() = runTest {
        metadataFile.writeText("{ this is not json")
        val before = sha256(metadataFile)

        val error = assertThrows(MetadataUnreadableException::class.java) {
            kotlinx.coroutines.runBlocking { repository().getMetadata() }
        }
        assertEquals(metadataFile.absolutePath, error.path)
        assertEquals(before, sha256(metadataFile))
    }

    @Test
    fun corruptMetadataGivesFatalErrorNotSetup() = runTest {
        metadataFile.writeText("")
        val before = sha256(metadataFile)
        val sessionManager = sessionManager()

        sessionManager.initialize()

        val state = sessionManager.sessionState.value
        assertTrue(state is SessionState.FatalError, "was $state")
        assertEquals(
            "VaultPass couldn't read its vault file (${metadataFile.absolutePath}): " +
                "the file is damaged or isn't a VaultPass vault file. Your vault was not changed.",
            (state as SessionState.FatalError).reason
        )
        assertFalse(state.offerUpdateCheck)
        assertEquals(before, sha256(metadataFile))
    }

    @Test
    fun missingMetadataStillGoesToFirstLaunch() = runTest {
        val sessionManager = sessionManager()
        sessionManager.initialize()
        assertEquals(SessionState.FirstLaunch, sessionManager.sessionState.value)
    }

    @Test
    fun createIsRefusedOverExistingMetadata() = runTest {
        metadataFile.writeText("""{"initialized":false}""")
        val before = sha256(metadataFile)

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository().initializeMetadata() }
        }
        assertEquals(before, sha256(metadataFile))
    }

    @Test
    fun createIsRefusedOverADatabaseWithEntries() = runTest {
        val manager = SQLiteConnectionManager(databaseFile.absolutePath)
        manager.openConnection()
        manager.getConnection().createStatement().use {
            it.execute(
                "INSERT INTO vault_entry (id, type, encrypted_payload, created_at, updated_at) " +
                    "VALUES ('a', 'LOGIN', x'00', 1, 1)"
            )
        }
        manager.closeAndWait()

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { repository().initializeMetadata() }
        }
        assertFalse(metadataFile.exists())
        assertEquals(1, countEntries())
    }

    @Test
    fun createIsAllowedOverAnEmptyDatabase() = runTest {
        val manager = SQLiteConnectionManager(databaseFile.absolutePath)
        manager.openConnection()
        manager.closeAndWait()

        val metadata = repository().initializeMetadata()

        assertTrue(metadata.initialized)
        assertTrue(metadataFile.exists())
    }

    @Test
    fun createIsAllowedInAnEmptyFolder() = runTest {
        val metadata = repository().initializeMetadata()
        assertEquals(metadata, repository().getMetadata())
    }

    @Test
    fun metadataWriteIsAtomicAndLeavesNoTempFile() = runTest {
        val repository = repository()
        val first = repository.initializeMetadata()
        val updated = first.copy(lastOpenedAt = first.lastOpenedAt + 1000)

        repository.saveMetadata(updated)

        assertEquals(updated, repository.getMetadata())
        assertFalse(File(dataDir, "metadata.json.tmp").exists())
        assertEquals(listOf("metadata.json"), dataDir.list()!!.sorted())
    }

    private fun countEntries(): Int =
        DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM vault_entry").use { rs -> rs.next(); rs.getInt(1) }
            }
        }

    private fun sessionManager() = SessionManagerImpl(
        metadataRepository = repository(),
        authenticationRepository = object : AuthenticationRepository {
            override suspend fun saveMasterPassword(password: String) = true
            override suspend fun verifyMasterPassword(password: String) = true
        },
        migrationRunner = MigrationRunner(MigrationRegistry()),
        keyManager = InMemoryKeyManager(),
        databaseConnectionManager = object : DatabaseConnectionManager {
            private val state = MutableStateFlow(false)
            override val connectionState: StateFlow<Boolean> = state
            override fun openConnection() { state.value = true }
            override fun closeConnection() { state.value = false }
        },
        appSettingsRepository = object : AppSettingsRepository {
            override suspend fun getSettings() = AppSettings()
            override suspend fun updateSettings(settings: AppSettings) {}
        },
        sessionKeyStorage = object : SessionKeyStorage {
            override fun hasProtectedSession() = false
            override fun saveProtectedSession(dek: SecureByteArray) {}
            override fun loadProtectedSession(): SecureByteArray? = null
            override fun clearProtectedSession() {}
        },
        scope = TestScope()
    )
}
