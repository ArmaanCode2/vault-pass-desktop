package com.vaultpass.desktop.data.repository

import com.vaultpass.desktop.domain.models.AppSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** checkForUpdatesOnOpen: new installs off, settings files from older versions keep what they had. */
class AppSettingsMigrationTest {

    @TempDir
    lateinit var tempDir: File

    private val settingsFile get() = File(tempDir, "settings.json")

    private fun storedKeys(): JsonObject = Json.parseToJsonElement(settingsFile.readText()).jsonObject

    @Test
    fun freshInstallHasTheCheckOff() = runTest {
        val settings = AppSettingsRepositoryImpl(settingsFile).getSettings()

        assertFalse(settings.checkForUpdatesOnOpen)
        assertFalse(storedKeys().getValue("checkForUpdatesOnOpen").jsonPrimitive.boolean)
    }

    @Test
    fun oldFileWithoutEitherKeyHadTheCheckOn() = runTest {
        // Older versions didn't write default values, so "on" left no key at all.
        settingsFile.writeText("""{ "lockWhenMinimized": true }""")

        val settings = AppSettingsRepositoryImpl(settingsFile).getSettings()

        assertTrue(settings.checkForUpdatesOnOpen)
        assertTrue(settings.lockWhenMinimized)
        assertTrue(storedKeys().getValue("checkForUpdatesOnOpen").jsonPrimitive.boolean)
    }

    @Test
    fun oldFileWithTheCheckTurnedOffKeepsItOff() = runTest {
        settingsFile.writeText("""{ "autoCheckForUpdates": false }""")

        val settings = AppSettingsRepositoryImpl(settingsFile).getSettings()

        assertFalse(settings.checkForUpdatesOnOpen)
        assertFalse(storedKeys().getValue("checkForUpdatesOnOpen").jsonPrimitive.boolean)
    }

    @Test
    fun explicitNewValueIsKept() = runTest {
        for (value in listOf(false, true)) {
            settingsFile.writeText("""{ "checkForUpdatesOnOpen": $value }""")
            val before = settingsFile.readText()

            assertEquals(value, AppSettingsRepositoryImpl(settingsFile).getSettings().checkForUpdatesOnOpen)
            assertEquals(before, settingsFile.readText(), "Not rewritten")
        }
    }

    @Test
    fun migratedFileIsWrittenOnce() = runTest {
        settingsFile.writeText("""{ "baseTheme": "Dark" }""")
        val repository = AppSettingsRepositoryImpl(settingsFile)
        repository.getSettings()
        val migrated = settingsFile.readText()
        val stamp = System.currentTimeMillis() - 60_000
        settingsFile.setLastModified(stamp)

        repeat(3) { assertTrue(repository.getSettings().checkForUpdatesOnOpen) }
        assertTrue(AppSettingsRepositoryImpl(settingsFile).getSettings().checkForUpdatesOnOpen)

        assertEquals(migrated, settingsFile.readText())
        assertEquals(stamp / 1000, settingsFile.lastModified() / 1000, "No second write")
    }

    @Test
    fun corruptFileGivesDefaultsAndIsLeftAlone() = runTest {
        for (content in listOf("{ not json", "[]", """{ "autoLockTimeoutMinutes": "soon" }""", "")) {
            settingsFile.writeText(content)

            assertEquals(AppSettings(), AppSettingsRepositoryImpl(settingsFile).getSettings(), "Content: $content")
            assertEquals(content, settingsFile.readText(), "Content: $content")
        }
    }

    @Test
    fun vaultWithoutSettingsFileIsALegacyInstallWithTheCheckOn() = runTest {
        File(tempDir, "metadata.json").writeText("""{"initialized":true}""")

        val settings = AppSettingsRepositoryImpl(settingsFile).getSettings()

        assertTrue(settings.checkForUpdatesOnOpen)
        assertTrue(storedKeys().getValue("checkForUpdatesOnOpen").jsonPrimitive.boolean)

        // Written once: later reads use the stored value and don't rewrite the file.
        val written = settingsFile.readText()
        val stamp = System.currentTimeMillis() - 60_000
        settingsFile.setLastModified(stamp)
        assertTrue(AppSettingsRepositoryImpl(settingsFile).getSettings().checkForUpdatesOnOpen)
        assertEquals(written, settingsFile.readText())
        assertEquals(stamp / 1000, settingsFile.lastModified() / 1000, "No second write")
    }

    @Test
    fun missingFileWhoseDefaultsCantBeWrittenStillGivesSettings() = runTest {
        File(tempDir, "metadata.json").writeText("""{"initialized":true}""")
        // A folder where the temporary settings file goes makes the write fail.
        File(tempDir, "settings.json.tmp").apply { mkdirs() }.resolve("blocker.txt").writeText("x")

        val settings = AppSettingsRepositoryImpl(settingsFile).getSettings()

        assertEquals(AppSettings(checkForUpdatesOnOpen = true), settings)
        assertFalse(settingsFile.exists())
    }

    @Test
    fun settingsAreWrittenAtomically() = runTest {
        val repository = AppSettingsRepositoryImpl(settingsFile)
        repository.updateSettings(AppSettings(autoLockTimeoutMinutes = 7))
        assertFalse(File(tempDir, "settings.json.tmp").exists(), "No temp file is left")
        assertEquals(listOf("settings.json"), tempDir.list()!!.sorted())

        // A write that fails leaves the previous file whole.
        val before = settingsFile.readText()
        File(tempDir, "settings.json.tmp").apply { mkdirs() }.resolve("blocker.txt").writeText("x")
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.updateSettings(AppSettings(autoLockTimeoutMinutes = 9)) }
        }
        assertEquals(before, settingsFile.readText())
        assertEquals(7, repository.getSettings().autoLockTimeoutMinutes)
    }

    @Test
    fun everyValueIsWritten() = runTest {
        AppSettingsRepositoryImpl(settingsFile).updateSettings(AppSettings())

        val keys = storedKeys().keys
        for (name in listOf("autoLockTimeoutMinutes", "baseTheme", "requireMasterPasswordOnStartup", "autoCheckForUpdates", "checkForUpdatesOnOpen")) {
            assertTrue(name in keys, "$name is stored")
        }
    }
}
