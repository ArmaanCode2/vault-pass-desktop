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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

data class VaultUiState(
    val query: VaultQuery = VaultQuery(),
    val entries: List<VaultEntry> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isAddDialogVisible: Boolean = false,
    val editEntryId: String? = null
)

class VaultViewModel(
    private val vaultRepository: VaultRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private var queryJob: Job? = null
    private var clipboardClearJob: Job? = null

    init {
        observeQuery()
    }

    private fun observeQuery() {
        queryJob?.cancel()
        queryJob = scope.launch(Dispatchers.IO) {
            val currentQuery = _uiState.value.query
            vaultRepository.observeQuery(currentQuery).collectLatest { result ->
                when (result) {
                    is RepositoryResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            entries = result.data.items,
                            totalCount = result.data.totalItems,
                            isLoading = false,
                            error = null
                        )
                    }
                    is RepositoryResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = result.error.message
                        )
                    }
                    is RepositoryResult.Loading -> {
                        _uiState.value = _uiState.value.copy(isLoading = true)
                    }
                }
            }
        }
    }

    fun updateSearchQuery(search: String) {
        _uiState.value = _uiState.value.copy(
            query = _uiState.value.query.copy(searchQuery = search, page = 1)
        )
        observeQuery()
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
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            val result = vaultRepository.createEntry(entry)
            if (result is RepositoryResult.Error) {
                _uiState.value = _uiState.value.copy(error = result.error.message)
            }
        }
    }

    fun updateEntry(id: String, payload: VaultEntryPayload.PasswordPayload) {
        scope.launch(Dispatchers.IO) {
            val getResult = vaultRepository.getEntryById(id)
            if (getResult is RepositoryResult.Success && getResult.data != null) {
                val oldEntry = getResult.data
                val updatedEntry = oldEntry.copy(
                    title = payload.title,
                    username = payload.username,
                    secret = payload.secret,
                    url = payload.url,
                    notes = payload.notes,
                    updatedAt = System.currentTimeMillis()
                )
                val updateResult = vaultRepository.updateEntry(updatedEntry)
                if (updateResult is RepositoryResult.Error) {
                    _uiState.value = _uiState.value.copy(error = updateResult.error.message)
                }
            } else if (getResult is RepositoryResult.Error) {
                _uiState.value = _uiState.value.copy(error = getResult.error.message)
            }
        }
    }

    fun deleteEntry(id: String) {
        scope.launch(Dispatchers.IO) {
            val result = vaultRepository.hardDeleteEntry(id)
            if (result is RepositoryResult.Error) {
                _uiState.value = _uiState.value.copy(error = result.error.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun copyPasswordToClipboard(password: String, clipboardManager: ClipboardManager) {
        clipboardManager.setText(AnnotatedString(password))
        
        clipboardClearJob?.cancel()
        clipboardClearJob = scope.launch(Dispatchers.Default) {
            delay(15000)
            val currentClip = clipboardManager.getText()?.text
            if (currentClip == password) {
                clipboardManager.setText(AnnotatedString(""))
            }
        }
    }

    fun showAddDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(isAddDialogVisible = show)
    }

    fun showEditDialog(entryId: String?) {
        _uiState.value = _uiState.value.copy(editEntryId = entryId)
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
        observeQuery()
    }
}
