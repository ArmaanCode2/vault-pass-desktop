package com.vaultpass.desktop.data.platform

import com.vaultpass.desktop.data.MetadataRepositoryImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AppStorageConfigTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var dataDir: File

    private val originalRecord = AppStorageConfig.dataDirectoryRecord

    @BeforeEach
    fun setUp() {
        // Never the real registry.
        AppStorageConfig.dataDirectoryRecord = object : AppStorageConfig.DataDirectoryRecord {
            override fun read(): String? = null
            override fun write(path: String) {}
        }
        // Always a temp folder, never the real user profile.
        dataDir = File(tempDir, "data")
        System.setProperty("vaultpass.data.dir", dataDir.absolutePath)
        assertEquals(StorageResolution.Ok(dataDir), AppStorageConfig.resolveDataDirectory())
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("vaultpass.env")
        System.clearProperty("vaultpass.data.dir")
        AppStorageConfig.clearResolutionForTests()
        AppStorageConfig.dataDirectoryRecord = originalRecord
    }

    @Test
    fun testFilePathsDerivedFromDataDirectory() {
        val dataDir = AppStorageConfig.dataDirectory
        assertEquals(this.dataDir, dataDir)
        assertEquals(File(dataDir, "metadata.json").absolutePath, AppStorageConfig.metadataFile.absolutePath)
        assertEquals(File(dataDir, "vaultpass.db").absolutePath, AppStorageConfig.databaseFile.absolutePath)
        assertEquals(File(dataDir, "settings.json").absolutePath, AppStorageConfig.settingsFile.absolutePath)
        assertEquals(File(dataDir, "session.dpapi").absolutePath, AppStorageConfig.sessionKeyFile.absolutePath)
        assertEquals(File(dataDir, "sync-device-id").absolutePath, AppStorageConfig.syncDeviceIdFile.absolutePath)
    }

    @Test
    fun testMetadataRepositoryDefaultConstructor() {
        val repo = MetadataRepositoryImpl()
        assertNotNull(repo)
    }

    @Test
    fun testStorageModeDescription() {
        val desc = AppStorageConfig.storageModeDescription
        assertNotNull(desc)
        assertTrue(desc.isNotBlank())
    }
}
