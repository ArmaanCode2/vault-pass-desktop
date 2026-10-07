package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.backup.BackupManager
import com.vaultpass.desktop.domain.backup.models.BackupMetadata
import com.vaultpass.desktop.domain.backup.models.BackupResult
import com.vaultpass.desktop.domain.exportimport.*
import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PendingImportSession(
    val format: DetectedFormat,
    val parseResult: ImportParseResult,
    val selectedStrategy: ImportConflictStrategy = ImportConflictStrategy.SKIP_DUPLICATES,
    val sourceFileName: String
)

data class BackupUiState(
    val isLoading: Boolean = false,
    val pendingRestoreMetadata: BackupMetadata? = null,
    val pendingPlainTextImport: ImportParseResult? = null,
    val pendingJsonImport: ImportParseResult? = null,
    val pendingVpexImport: ImportParseResult? = null,
    val pendingImportSession: PendingImportSession? = null,
    val lastBackupMetadata: BackupMetadata? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class BackupViewModel(
    private val backupManager: BackupManager,
    private val appSettingsRepository: com.vaultpass.desktop.domain.AppSettingsRepository,
    private val vaultRepository: VaultRepository? = null,
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val viewModelScope = coroutineScope ?: CoroutineScope(Dispatchers.Main)
    
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

    /**
     * Builds an export's file content with [build] inside one vault transaction, so a vault that
     * is locked, or locks before the content is done, fails the export (thrown, with a message for
     * the user) instead of writing anything; the caller writes the file only after this returned.
     * A lock that comes in after the content is done doesn't stop that write: content built just
     * before a lock may still be written right after it.
     * [entries] is what to export; null means every entry of the vault. A given list is only
     * checked against a lock (an empty transaction), not read again. Entries that can't be
     * decrypted are never exported; how many were skipped is returned, so the user is told.
     * For VPEX, [build] includes the export password's key derivation, which then runs inside the
     * transaction and holds the database for its duration (a known trade-off).
     */
    private suspend fun <T> exportContent(entries: List<VaultEntry>?, build: (List<VaultEntry>) -> T): ExportedContent<T> {
        fun contentOf(all: List<VaultEntry>): ExportedContent<T> {
            val exported = all.filter { !it.isCorrupted }
            return ExportedContent(exported, build(exported), skipped = all.size - exported.size)
        }
        val repository = vaultRepository
            ?: return contentOf(entries ?: throw IllegalStateException("Repository not available."))
        val result = repository.transaction {
            val all = entries ?: when (val read = getAllEntries(false)) {
                is com.vaultpass.desktop.domain.RepositoryResult.Success -> read.data
                is com.vaultpass.desktop.domain.RepositoryResult.Error -> throw IllegalStateException(read.error.message)
                else -> throw IllegalStateException("The vault isn't ready.")
            }
            contentOf(all)
        }
        return when (result) {
            is com.vaultpass.desktop.domain.RepositoryResult.Success -> result.data
            is com.vaultpass.desktop.domain.RepositoryResult.Error ->
                if (result.error is com.vaultpass.desktop.domain.errors.AuthError.VaultLocked) {
                    throw IllegalStateException("The vault is locked. Unlock it, then export again.")
                } else {
                    throw IllegalStateException("The vault couldn't be read: ${result.error.message}")
                }
            else -> throw IllegalStateException("The vault isn't ready.")
        }
    }

    /** An export's entries and file content, and how many entries were left out because they can't be decrypted. */
    private data class ExportedContent<T>(val entries: List<VaultEntry>, val content: T, val skipped: Int)

    /** The success message; it says when entries that can't be decrypted were skipped. */
    private fun exportedMessage(exported: ExportedContent<*>, targetFile: File): String {
        val base = "Exported ${exported.entries.size} entries to ${targetFile.name}."
        return if (exported.skipped == 0) base
        else if (exported.skipped == 1) "$base 1 entry that can't be decrypted was skipped."
        else "$base ${exported.skipped} entries that can't be decrypted were skipped."
    }

    /** [entries] null exports every entry of the vault that can be decrypted. */
    fun exportPlainText(targetFile: File, entries: List<VaultEntry>?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val exported = withContext(Dispatchers.Default) {
                    exportContent(entries) { PlainTextExporter.format(it) }
                }
                withContext(Dispatchers.IO) {
                    targetFile.writeText(exported.content, Charsets.UTF_8)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = exportedMessage(exported, targetFile)
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

    /** [entries] null exports every entry of the vault that can be decrypted. */
    fun exportJson(targetFile: File, entries: List<VaultEntry>?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val exported = withContext(Dispatchers.Default) {
                    exportContent(entries) { JsonExporter.format(it) }
                }
                withContext(Dispatchers.IO) {
                    targetFile.writeText(exported.content, Charsets.UTF_8)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = exportedMessage(exported, targetFile)
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

    /**
     * [entries] null exports every entry of the vault that can be decrypted. The file is encrypted
     * with [exportPassword] (not the vault key) before the vault transaction ends, so a lock before
     * then writes nothing; a file finished just before a lock may still be written after it.
     */
    fun exportVpex(targetFile: File, exportPassword: String, entries: List<VaultEntry>?) {
        if (exportPassword.length < 6) {
            _uiState.update { it.copy(errorMessage = "Export password must be at least 6 characters.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val exported = withContext(Dispatchers.Default) {
                    exportContent(entries) { VpexCryptoManager.encrypt(JsonExporter.format(it), exportPassword) }
                }
                withContext(Dispatchers.IO) {
                    targetFile.writeText(exported.content, Charsets.UTF_8)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = exportedMessage(exported, targetFile)
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

    /** [entries] null exports every entry of the vault that can be decrypted. */
    fun exportCsv(targetFile: File, entries: List<VaultEntry>?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val exported = withContext(defaultDispatcher) {
                    exportContent(entries) { CsvExporter.format(it) }
                }
                withContext(ioDispatcher) {
                    targetFile.writeText(exported.content, Charsets.UTF_8)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        successMessage = exportedMessage(exported, targetFile)
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

    fun prepareImport(
        file: File,
        explicitFormat: DetectedFormat? = null,
        vpexPassword: String? = null,
        existingEntries: List<VaultEntry> = emptyList(),
        onPasswordRequired: (() -> Unit)? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            try {
                val content = withContext(ioDispatcher) {
                    file.readText(Charsets.UTF_8)
                }
                val format = if (explicitFormat == null || explicitFormat == DetectedFormat.UNKNOWN) {
                    ImportFormatDetector.detect(content, file.name)
                } else {
                    explicitFormat
                }

                if (format == DetectedFormat.VAULTPASS_VPEX && vpexPassword == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    onPasswordRequired?.invoke()
                    return@launch
                }

                val parseResult: ImportParseResult = withContext(defaultDispatcher) {
                    when (format) {
                        DetectedFormat.VAULTPASS_VPEX -> {
                            val decrypted = VpexCryptoManager.decrypt(content, vpexPassword ?: "")
                            JsonImporter.parse(decrypted, existingEntries)
                        }
                        DetectedFormat.VAULTPASS_JSON -> JsonImporter.parse(content, existingEntries)
                        DetectedFormat.VAULTPASS_TXT -> PlainTextImporter.parse(content, existingEntries)
                        DetectedFormat.GENERIC_CSV -> CsvImporter.parse(content, existingEntries)
                        DetectedFormat.BITWARDEN_JSON -> BitwardenImporter.parseJson(content, existingEntries)
                        DetectedFormat.BITWARDEN_CSV -> BitwardenImporter.parseCsv(content, existingEntries)
                        DetectedFormat.KEEPASS_XML -> KeePassImporter.parseXml(content, existingEntries)
                        DetectedFormat.KEEPASS_CSV -> KeePassImporter.parseCsv(content, existingEntries)
                        DetectedFormat.UNKNOWN -> {
                            val csvAttempt = CsvImporter.parse(content, existingEntries)
                            if (csvAttempt.validEntries.isNotEmpty()) csvAttempt
                            else throw IllegalArgumentException("Unrecognized or unsupported vault file format.")
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pendingImportSession = PendingImportSession(
                            format = format,
                            parseResult = parseResult,
                            selectedStrategy = ImportConflictStrategy.SKIP_DUPLICATES,
                            sourceFileName = file.name
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Import preparation failed: ${e.message ?: "Could not parse file."}"
                    )
                }
            }
        }
    }

    fun setImportConflictStrategy(strategy: ImportConflictStrategy) {
        _uiState.update { state ->
            state.copy(
                pendingImportSession = state.pendingImportSession?.copy(selectedStrategy = strategy)
            )
        }
    }

    fun confirmUnifiedImport() {
        val session = _uiState.value.pendingImportSession ?: return
        val repository = vaultRepository ?: run {
            _uiState.update { it.copy(errorMessage = "Repository not available.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null, pendingImportSession = null) }
            val result = withContext(ioDispatcher) {
                repository.transaction {
                    val existingList = when (val res = observeAllEntries(false).first()) {
                        is com.vaultpass.desktop.domain.RepositoryResult.Success -> res.data
                        else -> emptyList()
                    }
                    val currentMap = existingList.associateBy {
                        "${it.title.trim().lowercase()}|${it.username.trim().lowercase()}"
                    }.toMutableMap()

                    var importedCount = 0
                    var skippedCount = 0
                    var updatedCount = 0

                    val now = System.currentTimeMillis()

                    for (entry in session.parseResult.validEntries) {
                        val key = "${entry.title.trim().lowercase()}|${entry.username.trim().lowercase()}"
                        val existing = currentMap[key]

                        if (existing == null) {
                            val createRes = createEntry(entry)
                            if (createRes is com.vaultpass.desktop.domain.RepositoryResult.Error) {
                                throw IllegalStateException("Failed to insert entry '${entry.title}': ${createRes.error.message}")
                            }
                            currentMap[key] = entry
                            importedCount++
                        } else {
                            when (session.selectedStrategy) {
                                ImportConflictStrategy.SKIP_DUPLICATES -> {
                                    skippedCount++
                                }
                                ImportConflictStrategy.OVERWRITE_EXISTING -> {
                                    val updatedEntry = existing.copy(
                                        secret = entry.secret,
                                        url = if (entry.url.isNotBlank()) entry.url else existing.url,
                                        notes = if (entry.notes.isNotBlank()) entry.notes else existing.notes,
                                        category = entry.category ?: existing.category,
                                        tags = if (entry.tags.isNotEmpty()) entry.tags else existing.tags,
                                        isFavorite = entry.isFavorite,
                                        updatedAt = now
                                    )
                                    val updateRes = updateEntry(updatedEntry)
                                    if (updateRes is com.vaultpass.desktop.domain.RepositoryResult.Error) {
                                        throw IllegalStateException("Failed to update entry '${entry.title}': ${updateRes.error.message}")
                                    }
                                    currentMap[key] = updatedEntry
                                    updatedCount++
                                }
                                ImportConflictStrategy.KEEP_BOTH -> {
                                    val newEntry = entry.copy(id = UUID.randomUUID().toString())
                                    val createRes = createEntry(newEntry)
                                    if (createRes is com.vaultpass.desktop.domain.RepositoryResult.Error) {
                                        throw IllegalStateException("Failed to insert duplicate entry '${entry.title}': ${createRes.error.message}")
                                    }
                                    importedCount++
                                }
                            }
                        }
                    }
                    Triple(importedCount, skippedCount, updatedCount)
                }
            }

            when (result) {
                is com.vaultpass.desktop.domain.RepositoryResult.Success -> {
                    val (imported, skipped, updated) = result.data
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = "Successfully imported ${imported + updated} entries ($skipped skipped, $updated updated)."
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

    fun cancelUnifiedImport() {
        _uiState.update { it.copy(pendingImportSession = null, errorMessage = null, successMessage = null) }
    }
    
    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
