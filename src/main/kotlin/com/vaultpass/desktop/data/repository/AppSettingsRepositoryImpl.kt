package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.models.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AppSettingsRepositoryImpl(private val settingsFile: File) : AppSettingsRepository {

    // encodeDefaults: every value is stored, so changing a default later never changes saved settings.
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val fileLock = Any()

    override suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            if (!settingsFile.exists()) {
                // A vault without settings.json comes from a version where the check was on by default.
                val legacyInstall = File(settingsFile.absoluteFile.parentFile, METADATA_FILE_NAME).exists()
                val defaultSettings = AppSettings(checkForUpdatesOnOpen = legacyInstall)
                // A failed write only means the defaults are worked out again next time.
                try {
                    writeSettings(defaultSettings)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return@withContext defaultSettings
            }

            val stored = try {
                val element = json.parseToJsonElement(settingsFile.readText())
                json.decodeFromJsonElement<AppSettings>(element) to element
            } catch (e: Exception) {
                // Fallback if file is corrupted
                return@withContext AppSettings()
            }
            val (settings, element) = stored
            val keys = element as? JsonObject ?: return@withContext settings
            if (CHECK_ON_OPEN_KEY in keys) return@withContext settings

            // Written before checkForUpdatesOnOpen existed: keep what the user had. The old key was
            // only stored when it differed from its default, true.
            val legacy = (keys[LEGACY_AUTO_CHECK_KEY] as? JsonPrimitive)?.booleanOrNull ?: true
            val migrated = settings.copy(checkForUpdatesOnOpen = legacy)
            try {
                writeSettings(migrated)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            migrated
        }
    }

    override suspend fun updateSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            writeSettings(settings)
        }
    }

    private fun writeSettings(settings: AppSettings) {
        val parent = settingsFile.parentFile
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val content = json.encodeToString(settings)
        // A temp file moved over the old one, so a failed write never leaves half a file.
        val tempFile = File(settingsFile.path + ".tmp")
        try {
            FileOutputStream(tempFile).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(
                tempFile.toPath(),
                settingsFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private companion object {
        const val METADATA_FILE_NAME = "metadata.json"
        const val CHECK_ON_OPEN_KEY = "checkForUpdatesOnOpen"
        const val LEGACY_AUTO_CHECK_KEY = "autoCheckForUpdates"
    }
}
