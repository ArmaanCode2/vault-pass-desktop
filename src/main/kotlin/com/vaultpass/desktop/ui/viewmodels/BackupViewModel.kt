package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.backup.BackupManager
import com.vaultpass.desktop.domain.backup.models.BackupMetadata
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.exportimport.ImportParseResult
import com.vaultpass.desktop.domain.exportimport.JsonExporter
import com.vaultpass.desktop.domain.exportimport.JsonImporter
import com.vaultpass.desktop.domain.exportimport.PlainTextExporter
import com.vaultpass.desktop.domain.exportimport.PlainTextImporter
import com.vaultpass.desktop.domain.exportimport.VpexCryptoManager
import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BackupUiState(
    val isLoading: Boolean = false,
    val pendingRestoreMetadata: BackupMetadata? = null,
    val pendingPlainTextImport: ImportParseResult? = null,
    val pendingJsonImport: ImportParseResult? = null,
    val pendingVpexImport: ImportParseResult? = null,
    val lastBackupMetadata: BackupMetadata? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class BackupViewModel(
    private val backupManager: BackupManager,
    private val appSettingsRepository: com.vaultpass.desktop.domain.AppSettingsRepository,
    private val vaultRepository: VaultRepository? = null
) {
    private val viewModelScope = CoroutineScope(Dispatchers.Main)
    
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    fun createBackup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            val result = withContext(Dispatchers.IO) {
                backupManager.createBackup("vaultpass_backup.vpb")
            }
            when (result) {
                is BackupResult.Success -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            successMessage = "Backup created successfully.",
                            lastBackupMetadata = result.data
                        ) 
                    }
                }
                is BackupResult.Failure -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.message
                        ) 
                    }
                }
            }
        }
    }

    fun initiateRestore() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            val result = withContext(Dispatchers.IO) {
                backupManager.validateBackup("")
            }
            when (result) {
                is BackupResult.Success -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            pendingRestoreMetadata = result.data
                        )
                    }
                }
                is BackupResult.Failure -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    fun confirmRestore(masterPassword: CharArray) {
        val metadata = _uiState.value.pendingRestoreMetadata ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null, pendingRestoreMetadata = null) }
            val result = withContext(Dispatchers.IO) {
                backupManager.restoreBackup("", masterPassword)
            }
            when (result) {
                is BackupResult.Success -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            successMessage = "Backup restored successfully.",
                            lastBackupMetadata = metadata
                        ) 
                    }
                }
                is BackupResult.Failure -> {
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            errorMessage = result.error.message
                        ) 
                    }
                }
            }
        }
    }

    fun cancelRestore() {
        backupManager.clearPendingBackup()
        _uiState.update { it.copy(pendingRestoreMetadata = null, errorMessage = null, successMessage = null) }
    }

    fun exportPlainText(targetFile: File, entries: List<VaultEntry>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val content = withContext(Dispatchers.Default) {
                    PlainTextExporter.format(entries)
                }
                withContext(Dispatchers.IO) {
                    targetFile.writeText(content, Charsets.UTF_8)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Exported ${entries.size} entries to ${targetFile.name}."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Export failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun parsePlainTextImport(file: File, existingEntries: List<VaultEntry> = emptyList()) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val text = withContext(Dispatchers.IO) {
                    file.readText(Charsets.UTF_8)
                }
                val result = withContext(Dispatchers.Default) {
                    PlainTextImporter.parse(text, existingEntries)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pendingPlainTextImport = result
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to parse import file: ${e.message ?: "Malformed file"}"
                    )
                }
            }
        }
    }

    fun confirmPlainTextImport() {
        val importResult = _uiState.value.pendingPlainTextImport ?: return
        val repository = vaultRepository ?: run {
            _uiState.update { it.copy(errorMessage = "Repository not available.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null, pendingPlainTextImport = null) }
            val result = withContext(Dispatchers.IO) {
                repository.transaction {
                    for (entry in importResult.validEntries) {
                        val createRes = createEntry(entry)
                        if (createRes is com.vaultpass.desktop.domain.RepositoryResult.Error) {
                            throw IllegalStateException("Failed to insert entry '${entry.title}'")
                        }
                    }
                }
            }

            when (result) {
                is com.vaultpass.desktop.domain.RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = "Successfully imported ${importResult.validEntries.size} entries."
                        )
                    }
                }
                is com.vaultpass.desktop.domain.RepositoryResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Import failed and was rolled back: ${result.error.message}"
                        )
                    }
                }
                else -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Import failed due to unexpected state."
                        )
                    }
                }
            }
        }
    }

    fun cancelPlainTextImport() {
        _uiState.update { it.copy(pendingPlainTextImport = null, errorMessage = null, successMessage = null) }
    }

    fun exportJson(targetFile: File, entries: List<VaultEntry>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val content = withContext(Dispatchers.Default) {
                    JsonExporter.format(entries)
                }
                withContext(Dispatchers.IO) {
                    targetFile.writeText(content, Charsets.UTF_8)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Exported ${entries.size} entries to ${targetFile.name}."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Export failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun parseJsonImport(file: File, existingEntries: List<VaultEntry> = emptyList()) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val text = withContext(Dispatchers.IO) {
                    file.readText(Charsets.UTF_8)
                }
                val result = withContext(Dispatchers.Default) {
                    JsonImporter.parse(text, existingEntries)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pendingJsonImport = result
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to parse import file: ${e.message ?: "Malformed JSON"}"
                    )
                }
            }
        }
    }

    fun confirmJsonImport() {
        val importResult = _uiState.value.pendingJsonImport ?: return
        val repository = vaultRepository ?: run {
            _uiState.update { it.copy(errorMessage = "Repository not available.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null, pendingJsonImport = null) }
            val result = withContext(Dispatchers.IO) {
                repository.transaction {
                    for (entry in importResult.validEntries) {
                        val createRes = createEntry(entry)
                        if (createRes is com.vaultpass.desktop.domain.RepositoryResult.Error) {
                            throw IllegalStateException("Failed to insert entry '${entry.title}'")
                        }
                    }
                }
            }

            when (result) {
                is com.vaultpass.desktop.domain.RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = "Successfully imported ${importResult.validEntries.size} entries."
                        )
                    }
                }
                is com.vaultpass.desktop.domain.RepositoryResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Import failed and was rolled back: ${result.error.message}"
                        )
                    }
                }
                else -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Import failed due to unexpected state."
                        )
                    }
                }
            }
        }
    }

    fun cancelJsonImport() {
        _uiState.update { it.copy(pendingJsonImport = null, errorMessage = null, successMessage = null) }
    }

    fun exportVpex(targetFile: File, exportPassword: String, entries: List<VaultEntry>) {
        if (exportPassword.length < 6) {
            _uiState.update { it.copy(errorMessage = "Export password must be at least 6 characters.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val jsonString = withContext(Dispatchers.Default) {
                    JsonExporter.format(entries)
                }
                val encryptedBase64 = withContext(Dispatchers.Default) {
                    VpexCryptoManager.encrypt(jsonString, exportPassword)
                }
                withContext(Dispatchers.IO) {
                    targetFile.writeText(encryptedBase64, Charsets.UTF_8)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = "Exported ${entries.size} entries to ${targetFile.name}."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Export failed: ${e.message ?: "Encryption error"}"
                    )
                }
            }
        }
    }

    fun parseVpexImport(file: File, exportPassword: String, existingEntries: List<VaultEntry> = emptyList()) {
        if (exportPassword.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Export password cannot be empty.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val fileContent = withContext(Dispatchers.IO) {
                    file.readText(Charsets.UTF_8)
                }
                val decryptedJson = withContext(Dispatchers.Default) {
                    VpexCryptoManager.decrypt(fileContent, exportPassword)
                }
                val result = withContext(Dispatchers.Default) {
                    JsonImporter.parse(decryptedJson, existingEntries)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pendingVpexImport = result
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Decryption failed: ${e.message ?: "Wrong password or corrupted .vpex file"}"
                    )
                }
            }
        }
    }

    fun confirmVpexImport() {
        val importResult = _uiState.value.pendingVpexImport ?: return
        val repository = vaultRepository ?: run {
            _uiState.update { it.copy(errorMessage = "Repository not available.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null, pendingVpexImport = null) }
            val result = withContext(Dispatchers.IO) {
                repository.transaction {
                    for (entry in importResult.validEntries) {
                        val createRes = createEntry(entry)
                        if (createRes is com.vaultpass.desktop.domain.RepositoryResult.Error) {
                            throw IllegalStateException("Failed to insert entry '${entry.title}'")
                        }
                    }
                }
            }

            when (result) {
                is com.vaultpass.desktop.domain.RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = "Successfully imported ${importResult.validEntries.size} entries."
                        )
                    }
                }
                is com.vaultpass.desktop.domain.RepositoryResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Import failed and was rolled back: ${result.error.message}"
                        )
                    }
                }
                else -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Import failed due to unexpected state."
                        )
                    }
                }
            }
        }
    }

    fun cancelVpexImport() {
        _uiState.update { it.copy(pendingVpexImport = null, errorMessage = null, successMessage = null) }
    }
    
    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
