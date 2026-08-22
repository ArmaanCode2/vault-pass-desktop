package com.vaultpass.desktop.ui.viewmodels

import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.vaultpass.desktop.domain.models.PagedVaultResult
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.data.models.VaultEntryPayload
import com.vaultpass.desktop.domain.models.VaultQuery
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vaultpass.desktop.domain.AppSettingsRepository
import java.util.UUID

data class VaultUiState(
    val query: VaultQuery = VaultQuery(),
    val recycleBinQuery: VaultQuery = VaultQuery(isDeleted = true),
    val entries: List<VaultEntry> = emptyList(),
    val allActiveEntries: List<VaultEntry> = emptyList(),
    val allDeletedEntries: List<VaultEntry> = emptyList(),
    val totalCount: Int = 0,
    val availableCategories: List<String> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val recycledEntries: List<VaultEntry> = emptyList(),
    val recycledCount: Int = 0,
    val isLoading: Boolean = true,
    val isRecycleBinLoading: Boolean = true,
    val error: String? = null,
    val isAddDialogVisible: Boolean = false,
    val editEntryId: String? = null
)

class VaultViewModel(
    private val vaultRepository: VaultRepository,
    private val appSettingsRepository: AppSettingsRepository? = null
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            val settings = appSettingsRepository?.getSettings()
            if (settings != null) {
                val (sortField, sortDirection) = when (settings.defaultSortOrder) {
                    "Alphabetical Z-A" -> Pair(com.vaultpass.desktop.domain.models.SortField.TITLE, com.vaultpass.desktop.domain.models.SortDirection.DESCENDING)
                    "Recently Modified" -> Pair(com.vaultpass.desktop.domain.models.SortField.UPDATED_AT, com.vaultpass.desktop.domain.models.SortDirection.DESCENDING)
                    "Recently Created" -> Pair(com.vaultpass.desktop.domain.models.SortField.CREATED_AT, com.vaultpass.desktop.domain.models.SortDirection.DESCENDING)
                    "Favorites First" -> Pair(com.vaultpass.desktop.domain.models.SortField.FAVORITES_FIRST, com.vaultpass.desktop.domain.models.SortDirection.ASCENDING)
                    else -> Pair(com.vaultpass.desktop.domain.models.SortField.TITLE, com.vaultpass.desktop.domain.models.SortDirection.ASCENDING)
                }
                _uiState.update {
                    it.copy(query = it.query.copy(sortField = sortField, sortDirection = sortDirection))
                }
            }
        }
    }

    private var queryJob: Job? = null
    private var recycledQueryJob: Job? = null
    private var clipboardClearJob: Job? = null
    private var allEntriesJob: Job? = null
    private var allDeletedEntriesJob: Job? = null

    fun clearState() {
        allEntriesJob?.cancel()
        allDeletedEntriesJob?.cancel()
        clipboardClearJob?.cancel()
        _uiState.value = VaultUiState()
    }

    private fun observeAllEntries() {
        allEntriesJob?.cancel()
        allEntriesJob = scope.launch(Dispatchers.IO) {
            vaultRepository.observeAllEntries(isDeleted = false).collectLatest { result ->
                if (result is RepositoryResult.Success) {
                    _uiState.update { it.copy(allActiveEntries = result.data, isLoading = false) }
                    refreshEntries()
                }
            }
        }
        
        allDeletedEntriesJob?.cancel()
        allDeletedEntriesJob = scope.launch(Dispatchers.IO) {
            vaultRepository.observeAllEntries(isDeleted = true).collectLatest { result ->
                if (result is RepositoryResult.Success) {
                    _uiState.update { it.copy(allDeletedEntries = result.data, isRecycleBinLoading = false) }
                    refreshRecycleBinEntries()
                }
            }
        }
    }

    private fun refreshEntries() {
        scope.launch(Dispatchers.Default) {
            val currentState = _uiState.value
            val query = currentState.query
            val queryText = query.searchQuery.trim()
            val allEntries = currentState.allActiveEntries
            
            val filtered = allEntries.filter { entry ->
                if (query.filterType != null && entry.javaClass.simpleName != query.filterType.name) {
                    return@filter false
                }
                if (query.isFavorite == true && !entry.isFavorite) {
                    return@filter false
                }
                if (query.category != null && entry.category != query.category) {
                    return@filter false
                }
                if (query.tags != null && query.tags.isNotEmpty() && !query.tags.all { it in entry.tags }) {
                    return@filter false
                }
                if (queryText.isNotBlank()) {
                    val matchesSearch = entry.title.contains(queryText, ignoreCase = true) ||
                                        entry.username.contains(queryText, ignoreCase = true) ||
                                        entry.url.contains(queryText, ignoreCase = true) ||
                                        entry.notes.contains(queryText, ignoreCase = true)
                    if (!matchesSearch) return@filter false
                }
                true
            }
            
            val sorted = when (query.sortField) {
                com.vaultpass.desktop.domain.models.SortField.TITLE -> if (query.sortDirection == com.vaultpass.desktop.domain.models.SortDirection.ASCENDING) {
                    filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                } else {
                    filtered.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.title })
                }
                com.vaultpass.desktop.domain.models.SortField.CREATED_AT -> if (query.sortDirection == com.vaultpass.desktop.domain.models.SortDirection.ASCENDING) {
                    filtered.sortedBy { it.createdAt }
                } else {
                    filtered.sortedByDescending { it.createdAt }
                }
                com.vaultpass.desktop.domain.models.SortField.UPDATED_AT -> if (query.sortDirection == com.vaultpass.desktop.domain.models.SortDirection.ASCENDING) {
                    filtered.sortedBy { it.updatedAt }
                } else {
                    filtered.sortedByDescending { it.updatedAt }
                }
                com.vaultpass.desktop.domain.models.SortField.FAVORITES_FIRST -> {
                    val favs = filtered.filter { it.isFavorite }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                    val nonFavs = filtered.filter { !it.isFavorite }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                    if (query.sortDirection == com.vaultpass.desktop.domain.models.SortDirection.ASCENDING) favs + nonFavs else nonFavs + favs
                }
            }
            
            val totalItems = sorted.size
            val startIndex = (query.page - 1) * query.pageSize
            val endIndex = minOf(startIndex + query.pageSize, totalItems)
            
            val pagedItems = if (startIndex < totalItems) {
                sorted.subList(startIndex, endIndex)
            } else {
                emptyList()
            }
            
            val allCats = allEntries.mapNotNull { it.category }.filter { it.isNotBlank() }.distinct().sorted()
            val allTags = allEntries.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sorted()

            _uiState.update { state -> 
                state.copy(
                    entries = pagedItems,
                    totalCount = totalItems,
                    availableCategories = allCats,
                    availableTags = allTags,
                    error = null
                )
            }
        }
    }

    private fun refreshRecycleBinEntries() {
        scope.launch(Dispatchers.Default) {
            val currentState = _uiState.value
            val query = currentState.recycleBinQuery
            val queryText = query.searchQuery.trim()
            val allDeleted = currentState.allDeletedEntries
            
            val filtered = allDeleted.filter { entry ->
                if (queryText.isNotBlank()) {
                    val matchesSearch = entry.title.contains(queryText, ignoreCase = true) ||
                                        entry.username.contains(queryText, ignoreCase = true)
                    if (!matchesSearch) return@filter false
                }
                true
            }
            
            val totalItems = filtered.size
            val startIndex = (query.page - 1) * query.pageSize
            val endIndex = minOf(startIndex + query.pageSize, totalItems)
            val pagedItems = if (startIndex < totalItems) {
                filtered.subList(startIndex, endIndex)
            } else {
                emptyList()
            }
            
            _uiState.update { state -> 
                state.copy(
                    recycledEntries = pagedItems,
                    recycledCount = totalItems
                )
            }
        }
    }

    fun updateSearchQuery(search: String) {
        _uiState.update {
            it.copy(query = it.query.copy(searchQuery = search, page = 1))
        }
        refreshEntries()
    }

    fun updateRecycleBinSearchQuery(search: String) {
        _uiState.update {
            it.copy(recycleBinQuery = it.recycleBinQuery.copy(searchQuery = search, page = 1))
        }
        refreshRecycleBinEntries()
    }

    fun updateSort(field: com.vaultpass.desktop.domain.models.SortField, direction: com.vaultpass.desktop.domain.models.SortDirection) {
        _uiState.update {
            it.copy(query = it.query.copy(sortField = field, sortDirection = direction, page = 1))
        }
        refreshEntries()
    }

    fun updateFavoriteFilter(isFavorite: Boolean?) {
        _uiState.update {
            it.copy(query = it.query.copy(isFavorite = isFavorite, page = 1))
        }
        refreshEntries()
    }

    fun nextPage() {
        _uiState.update {
            val totalPages = if (it.totalCount == 0) 1 else (it.totalCount + it.query.pageSize - 1) / it.query.pageSize
            if (it.query.page < totalPages) {
                it.copy(query = it.query.copy(page = it.query.page + 1))
            } else {
                it
            }
        }
        refreshEntries()
    }

    fun previousPage() {
        _uiState.update {
            if (it.query.page > 1) {
                it.copy(query = it.query.copy(page = it.query.page - 1))
            } else {
                it
            }
        }
        refreshEntries()
    }

    fun updateCategoryFilter(category: String?) {
        _uiState.update {
            it.copy(query = it.query.copy(category = category, page = 1))
        }
        refreshEntries()
    }

    fun updateTagsFilter(tags: List<String>) {
        _uiState.update {
            it.copy(query = it.query.copy(tags = tags, page = 1))
        }
        refreshEntries()
    }

    fun addEntry(payload: VaultEntryPayload.PasswordPayload) {
        scope.launch(Dispatchers.IO) {
            val entry = VaultEntry(
                id = UUID.randomUUID().toString(),
                title = payload.title,
                username = payload.username,
                secret = payload.secret,
                url = payload.url,
                notes = payload.notes,
                category = payload.category,
                tags = payload.tags,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val result = vaultRepository.createEntry(entry)
            if (result is RepositoryResult.Error) {
                _uiState.update { it.copy(error = result.error.message) }
            }
        }
    }

    fun updateEntry(id: String, payload: VaultEntryPayload.PasswordPayload) {
        scope.launch(Dispatchers.IO) {
            val getResult = vaultRepository.getEntryById(id)
            if (getResult is RepositoryResult.Success && getResult.data != null) {
                val oldEntry = getResult.data
                val updatedHistory = if (oldEntry.secret != payload.secret) {
                    oldEntry.history + com.vaultpass.desktop.domain.models.VaultEntryHistory(
                        previousSecret = oldEntry.secret,
                        changedAt = System.currentTimeMillis()
                    )
                } else {
                    oldEntry.history
                }

                val updatedEntry = oldEntry.copy(
                    title = payload.title,
                    username = payload.username,
                    secret = payload.secret,
                    url = payload.url,
                    notes = payload.notes,
                    category = payload.category,
                    tags = payload.tags,
                    history = updatedHistory,
                    updatedAt = System.currentTimeMillis()
                )
                val updateResult = vaultRepository.updateEntry(updatedEntry)
                if (updateResult is RepositoryResult.Error) {
                    _uiState.update { it.copy(error = updateResult.error.message) }
                }
            } else if (getResult is RepositoryResult.Error) {
                _uiState.update { it.copy(error = getResult.error.message) }
            }
        }
    }

    fun restorePassword(id: String, historyItem: com.vaultpass.desktop.domain.models.VaultEntryHistory) {
        scope.launch(Dispatchers.IO) {
            val getResult = vaultRepository.getEntryById(id)
            if (getResult is RepositoryResult.Success && getResult.data != null) {
                val oldEntry = getResult.data
                
                val updatedHistory = oldEntry.history + com.vaultpass.desktop.domain.models.VaultEntryHistory(
                    previousSecret = oldEntry.secret,
                    changedAt = System.currentTimeMillis(),
                    changeReason = "Restored from history"
                )

                val updatedEntry = oldEntry.copy(
                    secret = historyItem.previousSecret,
                    history = updatedHistory,
                    updatedAt = System.currentTimeMillis()
                )
                val updateResult = vaultRepository.updateEntry(updatedEntry)
                if (updateResult is RepositoryResult.Error) {
                    _uiState.update { it.copy(error = updateResult.error.message) }
                }
            } else if (getResult is RepositoryResult.Error) {
                _uiState.update { it.copy(error = getResult.error.message) }
            }
        }
    }

    fun toggleFavorite(id: String) {
        scope.launch(Dispatchers.IO) {
            val getResult = vaultRepository.getEntryById(id)
            if (getResult is RepositoryResult.Success && getResult.data != null) {
                val oldEntry = getResult.data
                val updatedEntry = oldEntry.copy(
                    isFavorite = !oldEntry.isFavorite,
                    updatedAt = System.currentTimeMillis()
                )
                val updateResult = vaultRepository.updateEntry(updatedEntry)
                if (updateResult is RepositoryResult.Error) {
                    _uiState.update { it.copy(error = updateResult.error.message) }
                }
            } else if (getResult is RepositoryResult.Error) {
                _uiState.update { it.copy(error = getResult.error.message) }
            }
        }
    }

    fun deleteEntry(id: String) {
        scope.launch(Dispatchers.IO) {
            val result = vaultRepository.softDeleteEntry(id)
            if (result is RepositoryResult.Error) {
                _uiState.update { it.copy(error = result.error.message) }
            }
        }
    }

    fun permanentlyDeleteEntry(id: String) {
        scope.launch(Dispatchers.IO) {
            val result = vaultRepository.hardDeleteEntry(id)
            if (result is RepositoryResult.Error) {
                _uiState.update { it.copy(error = result.error.message) }
            }
        }
    }

    fun restoreEntry(id: String) {
        scope.launch(Dispatchers.IO) {
            val result = vaultRepository.restoreEntry(id)
            if (result is RepositoryResult.Error) {
                _uiState.update { it.copy(error = result.error.message) }
            }
        }
    }

    fun emptyRecycleBin() {
        scope.launch(Dispatchers.IO) {
            val result = vaultRepository.emptyRecycleBin()
            if (result is RepositoryResult.Error) {
                _uiState.update { it.copy(error = result.error.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun copyPasswordToClipboard(secret: String, clipboardManager: androidx.compose.ui.platform.ClipboardManager) {
        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(secret))
        
        clipboardClearJob?.cancel()
        clipboardClearJob = scope.launch {
            val timeoutSeconds = appSettingsRepository?.getSettings()?.clearClipboardTimeoutSeconds ?: 30
            if (timeoutSeconds > 0) {
                kotlinx.coroutines.delay(timeoutSeconds * 1000L)
                val currentClip = clipboardManager.getText()?.text
                if (currentClip == secret) {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(""))
                }
            }
        }
    }

    fun showAddDialog(show: Boolean) {
        _uiState.update { it.copy(isAddDialogVisible = show) }
    }

    fun showEditDialog(entryId: String?) {
        _uiState.update { it.copy(editEntryId = entryId) }
    }

    private var _pendingGeneratedPassword: String? = null

    fun setPendingGeneratedPassword(password: String) {
        _pendingGeneratedPassword = password
    }

    fun consumePendingGeneratedPassword(): String? {
        val pwd = _pendingGeneratedPassword
        _pendingGeneratedPassword = null
        return pwd
    }

    fun refresh() {
println("[TRACE] VaultViewModel: refresh called")
        observeAllEntries()
    }
}
