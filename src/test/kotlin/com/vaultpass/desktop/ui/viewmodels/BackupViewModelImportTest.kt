package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.backup.BackupManager
import com.vaultpass.desktop.domain.backup.errors.BackupException
import com.vaultpass.desktop.domain.backup.models.BackupMetadata
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.errors.DatabaseError
import com.vaultpass.desktop.domain.exportimport.DetectedFormat
import com.vaultpass.desktop.domain.exportimport.ImportConflictStrategy
import com.vaultpass.desktop.domain.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupViewModelImportTest {

    @Test
    fun testExportCsv(@TempDir tempDir: Path) = runBlocking {
        val tempFile = tempDir.resolve("export.csv").toFile()
        val repo = FakeVaultRepository()
        val vm = BackupViewModel(
            backupManager = FakeBackupManager(),
            appSettingsRepository = FakeAppSettingsRepository(),
            vaultRepository = repo,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        val entries = listOf(
            VaultEntry(
                id = "1",
                title = "ProtonMail",
                username = "user@proton.me",
                secret = "SuperSecret123!",
                url = "https://mail.proton.me",
                notes = "Work email",
                category = "Email",
                tags = listOf("mail", "personal"),
                isFavorite = true,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        vm.exportCsv(tempFile, entries)

        assertTrue(tempFile.exists())
        val content = tempFile.readText()
        assertTrue(content.startsWith("Title,Username,Password,URL,Notes,Category,Tags,Favorite"))
        assertTrue(content.contains("ProtonMail"))
        assertTrue(content.contains("user@proton.me"))
        assertTrue(content.contains("SuperSecret123!"))
        assertNotNull(vm.uiState.value.successMessage)
        assertTrue(vm.uiState.value.successMessage!!.contains("Exported 1 entries"))
    }

    @Test
    fun testPrepareImportAutoDetectsBitwardenJson(@TempDir tempDir: Path) = runBlocking {
        val tempFile = tempDir.resolve("bitwarden.json").toFile()
        tempFile.writeText(
            """
            {
                "encrypted": false,
                "folders": [],
                "items": [
                    {
                        "name": "Bitwarden Account",
                        "type": 1,
                        "login": {
                            "username": "bw_user",
                            "password": "bw_password"
                        }
                    }
                ]
            }
            """.trimIndent()
        )
        val repo = FakeVaultRepository()
        val vm = BackupViewModel(
            backupManager = FakeBackupManager(),
            appSettingsRepository = FakeAppSettingsRepository(),
            vaultRepository = repo,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        vm.prepareImport(file = tempFile)

        val session = vm.uiState.value.pendingImportSession
        assertNotNull(session)
        assertEquals(DetectedFormat.BITWARDEN_JSON, session.format)
        assertEquals(1, session.parseResult.validEntries.size)
        assertEquals("Bitwarden Account", session.parseResult.validEntries[0].title)
    }

    @Test
    fun testConfirmUnifiedImportSkipDuplicates(@TempDir tempDir: Path) = runBlocking {
        val existingEntry = VaultEntry(
            id = "existing-1",
            title = "GitHub",
            username = "octocat",
            secret = "original_secret",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val repo = FakeVaultRepository(mutableListOf(existingEntry))
        val vm = BackupViewModel(
            backupManager = FakeBackupManager(),
            appSettingsRepository = FakeAppSettingsRepository(),
            vaultRepository = repo,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        val importFile = tempDir.resolve("import.csv").toFile()
        importFile.writeText(
            """
            Title,Username,Password
            GitHub,octocat,new_secret
            GitLab,gitlab_user,gitlab_pass
            """.trimIndent()
        )
        vm.prepareImport(file = importFile, existingEntries = repo.entries)
        vm.setImportConflictStrategy(ImportConflictStrategy.SKIP_DUPLICATES)
        vm.confirmUnifiedImport()

        assertEquals(2, repo.entries.size)
        val github = repo.entries.first { it.title == "GitHub" }
        assertEquals("original_secret", github.secret) // Skipped, not overwritten
        val gitlab = repo.entries.first { it.title == "GitLab" }
        assertEquals("gitlab_pass", gitlab.secret)

        val msg = vm.uiState.value.successMessage
        assertNotNull(msg)
        assertTrue(msg.contains("1 skipped") && msg.contains("0 updated"))
    }

    @Test
    fun testConfirmUnifiedImportOverwriteExisting(@TempDir tempDir: Path) = runBlocking {
        val existingEntry = VaultEntry(
            id = "existing-1",
            title = "GitHub",
            username = "octocat",
            secret = "original_secret",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val repo = FakeVaultRepository(mutableListOf(existingEntry))
        val vm = BackupViewModel(
            backupManager = FakeBackupManager(),
            appSettingsRepository = FakeAppSettingsRepository(),
            vaultRepository = repo,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        val importFile = tempDir.resolve("import.csv").toFile()
        importFile.writeText(
            """
            Title,Username,Password
            GitHub,octocat,overwritten_secret
            GitLab,gitlab_user,gitlab_pass
            """.trimIndent()
        )
        vm.prepareImport(file = importFile, existingEntries = repo.entries)
        vm.setImportConflictStrategy(ImportConflictStrategy.OVERWRITE_EXISTING)
        vm.confirmUnifiedImport()

        assertEquals(2, repo.entries.size)
        val github = repo.entries.first { it.title == "GitHub" }
        assertEquals("overwritten_secret", github.secret) // Overwritten
        assertEquals(1000L, github.createdAt) // Preserved createdAt
        assertTrue(github.updatedAt >= 1000L) // Updated updatedAt

        val msg = vm.uiState.value.successMessage
        assertNotNull(msg)
        assertTrue(msg.contains("1 updated"))
    }

    @Test
    fun testConfirmUnifiedImportKeepBoth(@TempDir tempDir: Path) = runBlocking {
        val existingEntry = VaultEntry(
            id = "existing-1",
            title = "GitHub",
            username = "octocat",
            secret = "original_secret",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val repo = FakeVaultRepository(mutableListOf(existingEntry))
        val vm = BackupViewModel(
            backupManager = FakeBackupManager(),
            appSettingsRepository = FakeAppSettingsRepository(),
            vaultRepository = repo,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        val importFile = tempDir.resolve("import.csv").toFile()
        importFile.writeText(
            """
            Title,Username,Password
            GitHub,octocat,second_secret
            GitLab,gitlab_user,gitlab_pass
            """.trimIndent()
        )
        vm.prepareImport(file = importFile, existingEntries = repo.entries)
        vm.setImportConflictStrategy(ImportConflictStrategy.KEEP_BOTH)
        vm.confirmUnifiedImport()

        assertEquals(3, repo.entries.size)
        val githubs = repo.entries.filter { it.title == "GitHub" }
        assertEquals(2, githubs.size)
        assertTrue(githubs.any { it.secret == "original_secret" })
        assertTrue(githubs.any { it.secret == "second_secret" })

        val msg = vm.uiState.value.successMessage
        assertNotNull(msg)
        assertTrue(msg.contains("Successfully imported 2 entries"))
    }

    @Test
    fun testConfirmUnifiedImportRollsBackOnTransactionFailure(@TempDir tempDir: Path) = runBlocking {
        val repo = FakeVaultRepository()
        repo.shouldFailOnEntryTitle = "FailSite"
        val vm = BackupViewModel(
            backupManager = FakeBackupManager(),
            appSettingsRepository = FakeAppSettingsRepository(),
            vaultRepository = repo,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            defaultDispatcher = Dispatchers.Unconfined
        )
        val importFile = tempDir.resolve("import.csv").toFile()
        importFile.writeText(
            """
            Title,Username,Password
            GoodSite,user1,pass1
            FailSite,user2,pass2
            """.trimIndent()
        )
        vm.prepareImport(file = importFile)
        vm.confirmUnifiedImport()

        // Transaction should have rolled back completely! GoodSite must not remain in repo
        assertEquals(0, repo.entries.size)
        assertNotNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.errorMessage!!.contains("rolled back"))
    }

    private class FakeVaultRepository(val entries: MutableList<VaultEntry> = mutableListOf()) : VaultRepository {
        var shouldFailOnEntryTitle: String? = null

        override fun observeAllEntries(isDeleted: Boolean): Flow<RepositoryResult<List<VaultEntry>>> {
            return flowOf(RepositoryResult.Success(entries.filter { it.isDeleted == isDeleted }))
        }

        override fun observeQuery(query: VaultQuery): Flow<RepositoryResult<PagedVaultResult>> {
            return flowOf(RepositoryResult.Success(PagedVaultResult(entries, entries.size, 1, 1)))
        }

        override suspend fun getEntryById(id: String): RepositoryResult<VaultEntry?> {
            return RepositoryResult.Success(entries.find { it.id == id })
        }

        override suspend fun getEntryHistory(id: String): RepositoryResult<List<VaultEntryHistory>> {
            return RepositoryResult.Success(emptyList())
        }

        override suspend fun createEntry(entry: VaultEntry): RepositoryResult<Unit> {
            if (entry.title == shouldFailOnEntryTitle) {
                return RepositoryResult.Error(DatabaseError.CorruptionDetected("Simulated DB failure for ${entry.title}"))
            }
            entries.add(entry)
            return RepositoryResult.Success(Unit)
        }

        override suspend fun updateEntry(entry: VaultEntry): RepositoryResult<Unit> {
            if (entry.title == shouldFailOnEntryTitle) {
                return RepositoryResult.Error(DatabaseError.CorruptionDetected("Simulated DB failure for ${entry.title}"))
            }
            val idx = entries.indexOfFirst { it.id == entry.id }
            if (idx >= 0) {
                entries[idx] = entry
            } else {
                entries.add(entry)
            }
            return RepositoryResult.Success(Unit)
        }

        override suspend fun softDeleteEntry(id: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun hardDeleteEntry(id: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun archiveEntry(id: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun restoreEntry(id: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun emptyRecycleBin(): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun purgeRecycleBinOlderThan(thresholdMillis: Long): RepositoryResult<Int> {
            val toRemove = entries.filter { it.isDeleted && (it.deletedAt ?: 0L) < thresholdMillis }
            entries.removeAll(toRemove)
            return RepositoryResult.Success(toRemove.size)
        }
        override suspend fun restoreEntries(ids: List<String>): RepositoryResult<Unit> {
            for (id in ids) {
                val idx = entries.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    entries[idx] = entries[idx].copy(isDeleted = false, deletedAt = null, updatedAt = System.currentTimeMillis())
                }
            }
            return RepositoryResult.Success(Unit)
        }
        override suspend fun hardDeleteEntries(ids: List<String>): RepositoryResult<Unit> {
            entries.removeAll { it.id in ids }
            return RepositoryResult.Success(Unit)
        }

        override suspend fun getTombstones(): RepositoryResult<List<com.vaultpass.desktop.domain.models.SyncTombstone>> =
            RepositoryResult.Success(emptyList())

        override suspend fun changeEntryId(oldId: String, newId: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

        override suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T> {
            val snapshot = ArrayList(entries)
            return try {
                val res = block()
                RepositoryResult.Success(res)
            } catch (e: Exception) {
                entries.clear()
                entries.addAll(snapshot)
                RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Transaction failed"))
            }
        }

        override fun clearCache() {}
    }

    private class FakeBackupManager : BackupManager {
        override suspend fun createBackup(destinationIdentifier: String): BackupResult<BackupMetadata> {
            return BackupResult.Failure(BackupException.StorageError("Not implemented"))
        }
        override suspend fun restoreBackup(sourceIdentifier: String, masterPassword: CharArray): BackupResult<Unit> {
            return BackupResult.Success(Unit)
        }
        override fun clearPendingBackup() {}
        override suspend fun validateBackup(sourceIdentifier: String): BackupResult<BackupMetadata> {
            return BackupResult.Failure(BackupException.StorageError("Not implemented"))
        }
    }

    private class FakeAppSettingsRepository : AppSettingsRepository {
        private var settings = AppSettings()
        override suspend fun getSettings(): AppSettings = settings
        override suspend fun updateSettings(settings: AppSettings) { this.settings = settings }
    }
}
