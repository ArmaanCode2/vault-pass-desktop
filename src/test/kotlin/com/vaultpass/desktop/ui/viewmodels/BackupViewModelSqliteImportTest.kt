package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.SQLiteVaultDataSource
import com.vaultpass.desktop.data.database.closeAndWait
import com.vaultpass.desktop.data.repository.VaultRepositoryImpl
import com.vaultpass.desktop.data.sync.FakeCryptoManager
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.backup.BackupManager
import com.vaultpass.desktop.domain.backup.errors.BackupException
import com.vaultpass.desktop.domain.backup.models.BackupMetadata
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.exportimport.ImportConflictStrategy
import com.vaultpass.desktop.domain.models.AppSettings
import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * The import runs its reads and writes inside one transaction on the real SQLite connection:
 * they must reuse the transaction's connection instead of waiting for it (a deadlock).
 */
class BackupViewModelSqliteImportTest {

    private lateinit var dbFile: File
    private lateinit var manager: SQLiteConnectionManager
    private lateinit var crypto: FakeCryptoManager
    private lateinit var repository: VaultRepositoryImpl
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @BeforeEach
    fun setUp() {
        dbFile = File.createTempFile("vaultpass_import_tx_test_", ".db")
        manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        crypto = FakeCryptoManager()
        repository = VaultRepositoryImpl(SQLiteVaultDataSource(manager), crypto)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        manager.closeAndWait()
        for (suffix in listOf("", "-wal", "-shm")) File(dbFile.absolutePath + suffix).delete()
    }

    private fun viewModel() = BackupViewModel(
        backupManager = NoBackupManager(),
        appSettingsRepository = InMemorySettings(),
        vaultRepository = repository,
        coroutineScope = scope,
        ioDispatcher = Dispatchers.IO,
        defaultDispatcher = Dispatchers.Default
    )

    private suspend fun BackupViewModel.awaitState(done: (BackupUiState) -> Boolean): BackupUiState =
        withTimeout(10_000) { uiState.first(done) }

    private suspend fun entries(): List<VaultEntry> =
        (repository.observeAllEntries(false).first() as RepositoryResult.Success).data

    @Test
    fun unifiedImportInATransaction_finishesWithoutDeadlock(@TempDir tempDir: Path) = runBlocking {
        repository.createEntry(
            VaultEntry(id = "existing-1", title = "GitHub", username = "octocat", secret = "old", createdAt = 1000L, updatedAt = 1000L)
        )
        val importFile = tempDir.resolve("import.csv").toFile()
        importFile.writeText(
            """
            Title,Username,Password
            GitHub,octocat,new_secret
            GitLab,gitlab_user,gitlab_pass
            """.trimIndent()
        )
        val vm = viewModel()
        vm.prepareImport(file = importFile, existingEntries = entries())
        val prepared = vm.awaitState { it.pendingImportSession != null || it.errorMessage != null }
        assertNotNull(prepared.pendingImportSession, prepared.errorMessage)
        vm.setImportConflictStrategy(ImportConflictStrategy.OVERWRITE_EXISTING)

        vm.confirmUnifiedImport()
        // A deadlocked transaction never gets here: withTimeout fails the test.
        val state = vm.awaitState { it.successMessage != null || it.errorMessage != null }

        assertNull(state.errorMessage, state.errorMessage)
        assertTrue(state.successMessage!!.contains("1 updated"), state.successMessage)
        val byTitle = entries().associateBy { it.title }
        assertEquals("new_secret", byTitle.getValue("GitHub").secret)
        assertEquals("gitlab_pass", byTitle.getValue("GitLab").secret)
    }

    @Test
    fun anExportWhileTheVaultIsLocked_failsAndWritesNothing(@TempDir tempDir: Path) = runBlocking {
        val entry = VaultEntry(id = "e-1", title = "GitHub", username = "octocat", secret = "pw", createdAt = 1L, updatedAt = 1L)
        repository.createEntry(entry)
        // A lock, in the app's order: the database closes, then the key is wiped.
        manager.closeConnection()
        crypto.locked = true

        // Both "everything" and a given selection: neither is written while locked.
        for (entries in listOf(null, listOf(entry))) {
            val vm = viewModel()
            val target = tempDir.resolve("export-${entries?.size}.csv").toFile()
            vm.exportCsv(target, entries)
            val state = vm.awaitState { it.successMessage != null || it.errorMessage != null }

            assertNull(state.successMessage)
            assertTrue(state.errorMessage!!.contains("locked"), state.errorMessage)
            assertFalse(target.exists(), "No export file, and certainly not an empty one")
        }
    }

    @Test
    fun anExportOfEverything_exportsTheVaultsEntries(@TempDir tempDir: Path) = runBlocking {
        repository.createEntry(VaultEntry(id = "e-1", title = "GitHub", username = "octocat", secret = "pw", createdAt = 1L, updatedAt = 1L))
        val vm = viewModel()
        val target = tempDir.resolve("export.csv").toFile()
        vm.exportCsv(target, null)
        val state = vm.awaitState { it.successMessage != null || it.errorMessage != null }

        assertTrue(state.successMessage!!.contains("Exported 1 entries"), state.successMessage)
        assertTrue(target.readText().contains("GitHub"))
    }

    @Test
    fun theSettingsScreenExportsTheWholeVault_throughTheLockSafeRead() {
        // A static check: there is no selection UI, and the screen's cached list may be stale or
        // emptied by a lock, so every export call there passes null (the export's own read).
        val source = File("src/main/kotlin/com/vaultpass/desktop/ui/screens/SettingsScreen.kt").readText()
        val calls = Regex("""backupViewModel\.export(PlainText|Json|Csv|Vpex)\(([^)]*)\)""").findAll(source).toList()
        assertEquals(4, calls.size, calls.joinToString { it.value })
        for (call in calls) {
            assertTrue(call.groupValues[2].trim().endsWith("null"), "Must pass null: ${call.value}")
        }
    }

    @Test
    fun entriesThatCantBeDecrypted_areSkipped_andTheUserIsTold(@TempDir tempDir: Path) = runBlocking {
        repository.createEntry(VaultEntry(id = "e-1", title = "GitHub", username = "octocat", secret = "pw", createdAt = 1L, updatedAt = 1L))
        SQLiteVaultDataSource(manager).insert(
            com.vaultpass.desktop.data.models.VaultEntity("broken-1", "PASSWORD", byteArrayOf(9, 9, 9), 1L, 1L, false, false, null, 1)
        )
        val vm = viewModel()
        val target = tempDir.resolve("export.csv").toFile()
        vm.exportCsv(target, null)
        val state = vm.awaitState { it.successMessage != null || it.errorMessage != null }

        assertEquals("Exported 1 entries to export.csv. 1 entry that can't be decrypted was skipped.", state.successMessage)
        assertTrue(target.readText().contains("GitHub"))
        assertFalse(target.readText().contains("Corrupted Entry"))
    }

    @Test
    fun anEmptySelection_exportsNothing_ratherThanTheWholeVault(@TempDir tempDir: Path) = runBlocking {
        repository.createEntry(VaultEntry(id = "e-1", title = "GitHub", username = "octocat", secret = "pw", createdAt = 1L, updatedAt = 1L))
        val vm = viewModel()
        val target = tempDir.resolve("export.json").toFile()
        vm.exportJson(target, emptyList())
        val state = vm.awaitState { it.successMessage != null || it.errorMessage != null }

        assertTrue(state.successMessage!!.contains("Exported 0 entries"), state.successMessage)
        assertFalse(target.readText().contains("GitHub"))
    }

    private class NoBackupManager : BackupManager {
        override suspend fun createBackup(destinationIdentifier: String): BackupResult<BackupMetadata> =
            BackupResult.Failure(BackupException.StorageError("Not used"))
        override suspend fun restoreBackup(sourceIdentifier: String, masterPassword: CharArray): BackupResult<Unit> =
            BackupResult.Failure(BackupException.StorageError("Not used"))
        override fun clearPendingBackup() {}
        override suspend fun validateBackup(sourceIdentifier: String): BackupResult<BackupMetadata> =
            BackupResult.Failure(BackupException.StorageError("Not used"))
    }

    private class InMemorySettings : AppSettingsRepository {
        private var settings = AppSettings()
        override suspend fun getSettings(): AppSettings = settings
        override suspend fun updateSettings(settings: AppSettings) { this.settings = settings }
    }
}
