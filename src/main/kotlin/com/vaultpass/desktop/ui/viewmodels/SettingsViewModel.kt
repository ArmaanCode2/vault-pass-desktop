package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.models.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.vaultpass.desktop.domain.platform.FilePicker
import java.io.File
import java.awt.Desktop

class SettingsViewModel(private val repository: AppSettingsRepository, private val filePicker: FilePicker) {

    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    private val _settingsState = MutableStateFlow(AppSettings())
    val settingsState: StateFlow<AppSettings> = _settingsState.asStateFlow()

    init {
        refreshSettings()
    }

    fun refreshSettings() {
        viewModelScope.launch {
            _settingsState.value = repository.getSettings()
        }
    }

    fun updateSettings(mutate: (AppSettings) -> AppSettings) {
        _settingsState.update { current ->
            val updated = mutate(current)
            viewModelScope.launch {
                repository.updateSettings(updated)
            }
            updated
        }
    }

    fun changeBackupFolder() {
        viewModelScope.launch {
            val folder = filePicker.showFolderPickerDialog("Select Default Backup Folder")
            if (folder != null) {
                updateSettings { it.copy(backupFolder = folder.absolutePath.replace("\\\\", "/")) }
            }
        }
    }

    fun openBackupFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val folderPath = _settingsState.value.backupFolder
            val folder = File(folderPath)
            if (!folder.exists()) {
                folder.mkdirs()
            }
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(folder)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
