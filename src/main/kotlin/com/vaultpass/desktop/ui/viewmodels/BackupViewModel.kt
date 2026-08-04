package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.backup.BackupManager
import com.vaultpass.desktop.domain.backup.models.BackupMetadata
import com.vaultpass.desktop.domain.backup.models.BackupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BackupUiState(
    val isLoading: Boolean = false,
    val pendingRestoreMetadata: BackupMetadata? = null,
    val lastBackupMetadata: BackupMetadata? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class BackupViewModel(
    private val backupManager: BackupManager,
    private val appSettingsRepository: com.vaultpass.desktop.domain.AppSettingsRepository
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
    
    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
