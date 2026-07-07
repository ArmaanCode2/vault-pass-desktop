package com.vaultpass.desktop.domain

import com.vaultpass.desktop.domain.models.AppSettings

interface AppSettingsRepository {
    suspend fun getSettings(): AppSettings
    suspend fun updateSettings(settings: AppSettings)
}
