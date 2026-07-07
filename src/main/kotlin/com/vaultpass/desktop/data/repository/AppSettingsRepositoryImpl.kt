package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.models.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AppSettingsRepositoryImpl(private val settingsFile: File) : AppSettingsRepository {
    
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        if (!settingsFile.exists()) {
            val defaultSettings = AppSettings()
            updateSettings(defaultSettings)
            return@withContext defaultSettings
        }
        
        try {
            val content = settingsFile.readText()
            json.decodeFromString(content)
        } catch (e: Exception) {
            // Fallback if file is corrupted
            AppSettings()
        }
    }

    override suspend fun updateSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        val parent = settingsFile.parentFile
        if (!parent.exists()) {
            parent.mkdirs()
        }
        
        val content = json.encodeToString(settings)
        settingsFile.writeText(content)
    }
}
