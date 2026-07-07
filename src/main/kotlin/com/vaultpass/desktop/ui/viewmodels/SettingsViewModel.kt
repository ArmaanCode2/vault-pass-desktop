package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.models.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: AppSettingsRepository) {

    private val viewModelScope = CoroutineScope(Dispatchers.Main)

    private val _settingsState = MutableStateFlow(AppSettings())
    val settingsState: StateFlow<AppSettings> = _settingsState.asStateFlow()

    init {
        viewModelScope.launch {
            _settingsState.value = repository.getSettings()
        }
    }

    fun updateSettings(mutate: (AppSettings) -> AppSettings) {
        val current = _settingsState.value
        val updated = mutate(current)
        _settingsState.value = updated
        viewModelScope.launch {
            repository.updateSettings(updated)
        }
    }
}
