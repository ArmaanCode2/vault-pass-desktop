package com.vaultpass.desktop.data.platform

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

class StorageResolutionTest {

    @TempDir
    lateinit var tempDir: File

    /** Stands in for the registry value. */
    private class FakeRecord(var value: String? = null) : AppStorageConfig.DataDirectoryRecord {
        val written = mutableListOf<String>()
        override fun read(): String? = value
        override fun write(path: String) {
            written += path
            value = path
        }
    }

    private val originalRecord = AppStorageConfig.dataDirectoryRecord
    private val record = FakeRecord()

    @BeforeEach
    fun setUp() {
        AppStorageConfig.clearResolutionForTests()
        AppStorageConfig.dataDirectoryRecord = record
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty("vaultpass.data.dir")
        AppStorageConfig.clearResolutionForTests()
        AppStorageConfig.dataDirectoryRecord = originalRecord
    }

    /** Records every folder the probe was asked about. */
    private class FakeProbe(private val unwritable: Set<File>) : (File) -> Boolean {
        val probed = mutableListOf<File>()
        override fun invoke(dir: File): Boolean {
            probed += dir
            return dir !in unwritable
        }
    }

    private val appDir get() = File(tempDir, "app").apply { mkdirs() }
    private val userHome get() = File(tempDir, "home").apply { mkdirs() }

    @Test
    fun unwritableInstallDataFolderIsAProblemAndNoOtherFolderIsUsed() {
        val dataDir = File(appDir, "data").apply { mkdirs() }
        val probe = FakeProbe(unwritable = setOf(dataDir))

        val result = AppStorageConfig.resolveDataDirectory(null, false, appDir, userHome, probe)

        assertTrue(result is StorageResolution.Problem)
        assertEquals(dataDir, result.directory)
        assertEquals(listOf(dataDir), probe.probed)
    }

    @Test
    fun portableFlagWithUnwritableDataFolderIsAProblemNamingIt() {
        File(appDir, "portable.flag").writeText("")
        val dataDir = File(appDir, "data")
        val probe = FakeProbe(unwritable = setOf(dataDir))

        val result = AppStorageConfig.resolveDataDirectory(null, false, appDir, userHome, probe)

        assertEquals(StorageResolution.Problem::class, result::class)
        assertEquals(dataDir, result.directory)
        assertEquals(listOf(dataDir), probe.probed)
    }

    @Test
    fun unwritableCustomPathIsAProblemAndNoOtherFolderIsUsed() {
        val custom = File(tempDir, "custom")
        File(appDir, "data").mkdirs()
        val probe = FakeProbe(unwritable = setOf(custom))

        val result = AppStorageConfig.resolveDataDirectory(custom.absolutePath, false, appDir, userHome, probe)

        assertTrue(result is StorageResolution.Problem)
        assertEquals(custom, result.directory)
        assertEquals(listOf(custom), probe.probed)
    }

    @Test
    fun unwritableHomeFolderIsAProblem() {
        val homeVault = File(userHome, ".vaultpass")
        val probe = FakeProbe(unwritable = setOf(homeVault))

        val result = AppStorageConfig.resolveDataDirectory(null, false, appDir, userHome, probe)

        assertTrue(result is StorageResolution.Problem)
        assertEquals(homeVault, result.directory)
        assertEquals(listOf(homeVault), probe.probed)
    }

    @Test
    fun writableFoldersResolveInPriorityOrder() {
        val probe = FakeProbe(unwritable = emptySet())
        assertEquals(StorageResolution.Ok(File(userHome, ".vaultpass")),
            AppStorageConfig.resolveDataDirectory(null, false, appDir, userHome, probe))

        val dataDir = File(appDir, "data").apply { mkdirs() }
        assertEquals(StorageResolution.Ok(dataDir),
            AppStorageConfig.resolveDataDirectory(null, false, appDir, userHome, probe))

        assertEquals(StorageResolution.Ok(File(userHome, ".vaultpass-dev")),
            AppStorageConfig.resolveDataDirectory(null, true, appDir, userHome, probe))
    }

    @Test
    fun retryAfterTheFolderIsFixedResolvesOk() {
        // A file where the folder should be: no folder can be created or written there.
        val custom = File(tempDir, "vault-folder")
        custom.writeText("not a folder")
        System.setProperty("vaultpass.data.dir", custom.absolutePath)

        val first = AppStorageConfig.resolveDataDirectory()
        assertTrue(first is StorageResolution.Problem)
        assertEquals(custom, first.directory)
        assertNull(AppStorageConfig.dataDirectoryOrNull)
        assertThrows(IllegalStateException::class.java) { AppStorageConfig.dataDirectory }

        assertTrue(custom.delete())

        val second = AppStorageConfig.resolveDataDirectory()
        assertEquals(StorageResolution.Ok(custom), second)
        assertEquals(custom, AppStorageConfig.dataDirectory)
        assertTrue(custom.isDirectory)
    }

    @Test
    fun writeProbeLeavesNothingBehindAndRetriesBeforeFailing() {
        val writable = File(tempDir, "writable")
        assertTrue(AppStorageConfig.isDirectoryWritable(writable, attempts = 3, delayMillis = 1))
        assertEquals(0, writable.listFiles()!!.size)

        val blocked = File(tempDir, "blocked").apply { writeText("file") }
        val started = System.nanoTime()
        assertFalse(AppStorageConfig.isDirectoryWritable(blocked, attempts = 3, delayMillis = 50))
        // Two waits between three attempts.
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 100)
    }

    // ---- Vault found elsewhere ----

    private fun vaultIn(folder: File, changedAt: Long): File {
        folder.mkdirs()
        File(folder, "metadata.json").apply { writeText("{}") }.setLastModified(changedAt)
        return folder
    }

    @Test
    fun vaultElsewhereIsFoundOnlyWhenTheResolvedFolderHasNone() {
        val resolved = File(tempDir, "resolved").apply { mkdirs() }
        val home = File(tempDir, "home-vault").apply { mkdirs() }
        val temp = File(tempDir, "temp-vault").apply { mkdirs() }

        // Nothing anywhere.
        assertTrue(AppStorageConfig.findVaultsElsewhere(resolved, listOf(home, temp)).isEmpty())

        // A vault in the old %TEMP% fallback.
        vaultIn(temp, 1_000_000_000_000)
        assertEquals(
            listOf(VaultCandidate(temp, 1_000_000_000_000)),
            AppStorageConfig.findVaultsElsewhere(resolved, listOf(home, temp))
        )

        // Never offered once the resolved folder has its own vault.
        vaultIn(home, 1_000_000_000_000)
        File(resolved, "metadata.json").writeText("{}")
        assertTrue(AppStorageConfig.findVaultsElsewhere(resolved, listOf(home, temp)).isEmpty())
    }

    @Test
    fun vaultsElsewhereAreRankedByTheirNewestChange() {
        val resolved = File(tempDir, "resolved").apply { mkdirs() }
        val minute = 60_000L
        val base = 1_700_000_000_000L
        val remembered = vaultIn(File(tempDir, "remembered"), base)
        val oldDefault = vaultIn(File(tempDir, "old-default"), base + 1 * minute)
        val home = vaultIn(File(tempDir, "home-vault"), base + 2 * minute)
        val temp = vaultIn(File(tempDir, "temp-vault"), base)
        // The database changed later than metadata.json: its time counts.
        File(remembered, "vaultpass.db").apply { writeText("db") }.setLastModified(base + 3 * minute)
        // So does a -wal file that is newer still.
        File(temp, "vaultpass.db").apply { writeText("db") }.setLastModified(base + 1 * minute)
        File(temp, "vaultpass.db-wal").apply { writeText("wal") }.setLastModified(base + 5 * minute)
        // Other files don't.
        File(oldDefault, "settings.json").apply { writeText("{}") }.setLastModified(base + 9 * minute)
        // A folder without metadata.json isn't a vault, however new its database.
        val noVault = File(tempDir, "no-vault").apply { mkdirs() }
        File(noVault, "vaultpass.db").apply { writeText("db") }.setLastModified(base + 10 * minute)

        val found = AppStorageConfig.findVaultsElsewhere(resolved, listOf(remembered, oldDefault, home, noVault, temp))

        assertEquals(
            listOf(
                VaultCandidate(temp, base + 5 * minute),
                VaultCandidate(remembered, base + 3 * minute),
                VaultCandidate(home, base + 2 * minute),
                VaultCandidate(oldDefault, base + 1 * minute)
            ),
            found
        )
    }

    @Test
    fun theSameFolderListedTwiceIsOfferedOnce() {
        val resolved = File(tempDir, "resolved").apply { mkdirs() }
        val vault = vaultIn(File(tempDir, "VaultPass/data"), 1_700_000_000_000)

        val found = AppStorageConfig.findVaultsElsewhere(resolved, listOf(vault, File(tempDir, "VaultPass/../VaultPass/data")))

        assertEquals(1, found.size)
    }

    @Test
    fun resolvedFolderIsNotReportedAsElsewhere() {
        val resolved = File(tempDir, "resolved").apply { mkdirs() }
        assertTrue(AppStorageConfig.findVaultsElsewhere(resolved, listOf(File(tempDir, "resolved"))).isEmpty())
    }

    // ---- Copy it here ----

    private fun createDatabase(file: File, entries: Int): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode = WAL")
            // Leaves the rows in the -wal file while this connection stays open.
            stmt.execute("PRAGMA wal_autocheckpoint = 0")
            stmt.execute("CREATE TABLE vault_entry (id TEXT PRIMARY KEY, payload BLOB)")
            repeat(entries) { i -> stmt.execute("INSERT INTO vault_entry VALUES ('entry-$i', x'0102')") }
            stmt.execute("PRAGMA user_version = 2")
        }
        return conn
    }

    private fun entryCount(file: File): Long =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM vault_entry").use { rs -> rs.next(); rs.getLong(1) }
            }
        }

    @Test
    fun copyCopiesTheVaultAndLeavesTheSource() {
        val source = File(tempDir, "old").apply { mkdirs() }
        val sourceDb = File(source, "vaultpass.db")
        val writer = createDatabase(sourceDb, entries = 25)
        try {
            assertTrue(File(source, "vaultpass.db-wal").length() > 0, "The rows are still in the -wal file")
            File(source, "metadata.json").writeText("""{"initialized":true}""")
            File(source, "settings.json").writeText("{}")
            File(source, "session.dpapi").writeBytes(byteArrayOf(1, 2, 3))
            File(source, "crash.log").writeText("old crash")
            File(source, AppStorageConfig.FOLDER_PERMISSIONS_NOTICE_FILE).writeText("icacls failed")
            File(source, "pending-update.json").writeText("{}")
            File(source, "updates").mkdirs()
            File(source, "updates/VaultPass-Setup-v1.0.0.exe").writeText("installer")
            // The -shm file is SQLite's shared memory; readers use it, so it isn't compared.
            val sourceHashes = hashes(source).filterKeys { !it.endsWith("-shm") }

            val target = File(tempDir, "new").apply { mkdirs() }
            File(target, "vaultpass.db-wal").writeText("stale wal of another database")

            AppStorageConfig.copyVaultFiles(source, target, java.time.LocalDateTime.of(2026, 10, 3, 14, 5, 9))

            assertEquals(sourceHashes, hashes(source).filterKeys { !it.endsWith("-shm") }, "source must be left unchanged")
            for (name in listOf("metadata.json", "settings.json", "session.dpapi")) {
                assertArrayEquals(File(source, name).readBytes(), File(target, name).readBytes(), name)
            }
            // The copy holds the rows that were only in the source's -wal file, and stands on its own.
            assertEquals(25, entryCount(File(target, "vaultpass.db")))
            for (name in listOf("crash.log", AppStorageConfig.FOLDER_PERMISSIONS_NOTICE_FILE, "pending-update.json", "updates",
                "vaultpass.db-wal", "vaultpass.db-shm", "vaultpass.db-journal")) {
                assertFalse(File(target, name).exists(), "$name isn't copied")
            }
            assertEquals("stale wal of another database", File(target, "vaultpass.db.replaced-20261003-140509-wal").readText())
            assertTrue(target.listFiles()!!.none { it.name.contains(".copying") })
            assertTrue(AppStorageConfig.findVaultsElsewhere(target, listOf(source)).isEmpty())
        } finally {
            writer.close()
        }
    }

    @Test
    fun copyRenamesADatabaseAlreadyInTheTargetWithItsCompanions() {
        val source = File(tempDir, "old").apply { mkdirs() }
        createDatabase(File(source, "vaultpass.db"), entries = 3).close()
        File(source, "metadata.json").writeText("""{"initialized":true}""")
        val target = File(tempDir, "new").apply { mkdirs() }
        val existingDb = ByteArray(4096) { 3 }
        File(target, "vaultpass.db").writeBytes(existingDb)
        File(target, "vaultpass.db-wal").writeText("its wal")
        File(target, "vaultpass.db-shm").writeText("its shm")
        File(target, "vaultpass.db-journal").writeText("its journal")
        // A -wal from an earlier copy in the same second is not overwritten either.
        File(target, "vaultpass.db.replaced-20261003-140509-wal").writeText("earlier")
        val now = java.time.LocalDateTime.of(2026, 10, 3, 14, 5, 9)

        AppStorageConfig.copyVaultFiles(source, target, now)

        assertEquals(3, entryCount(File(target, "vaultpass.db")))
        assertEquals("earlier", File(target, "vaultpass.db.replaced-20261003-140509-wal").readText())
        assertFalse(File(target, "vaultpass.db.replaced-20261003-140509").exists())
        // One name for the database and its companions, so SQLite still pairs them.
        assertArrayEquals(existingDb, File(target, "vaultpass.db.replaced-20261003-140509-2").readBytes())
        assertEquals("its wal", File(target, "vaultpass.db.replaced-20261003-140509-2-wal").readText())
        assertEquals("its shm", File(target, "vaultpass.db.replaced-20261003-140509-2-shm").readText())
        assertEquals("its journal", File(target, "vaultpass.db.replaced-20261003-140509-2-journal").readText())
        for (name in listOf("vaultpass.db-wal", "vaultpass.db-shm", "vaultpass.db-journal")) {
            assertFalse(File(target, name).exists(), name)
        }
    }

    @Test
    fun copyIsRefusedWhenTheTargetAlreadyHasAVault() {
        val source = File(tempDir, "old").apply { mkdirs() }
        createDatabase(File(source, "vaultpass.db"), entries = 1).close()
        File(source, "metadata.json").writeText("""{"source":true}""")
        val target = File(tempDir, "new").apply { mkdirs() }
        File(target, "metadata.json").writeText("""{"target":true}""")
        File(target, "vaultpass.db").writeText("target db")
        val before = hashes(target)

        val error = assertThrows(AppStorageConfig.VaultAppearedException::class.java) {
            AppStorageConfig.copyVaultFiles(source, target)
        }

        assertEquals("A vault appeared in ${target.absolutePath} while copying. Nothing was overwritten.", error.message)
        assertEquals(before, hashes(target))
    }

    @Test
    fun copyStopsWhenAVaultAppearsInTheTargetWhileCopying() {
        val source = File(tempDir, "old").apply { mkdirs() }
        createDatabase(File(source, "vaultpass.db"), entries = 1).close()
        File(source, "metadata.json").writeText("""{"source":true}""")
        val target = File(tempDir, "new").apply { mkdirs() }

        val error = assertThrows(AppStorageConfig.VaultAppearedException::class.java) {
            AppStorageConfig.copyVaultFiles(source, target, beforeMetadataMove = {
                File(target, "metadata.json").writeText("""{"appeared":true}""")
            })
        }

        assertEquals("A vault appeared in ${target.absolutePath} while copying. Nothing was overwritten.", error.message)
        assertEquals("""{"appeared":true}""", File(target, "metadata.json").readText())
        assertFalse(File(target, "metadata.json.copying").exists())
    }

    @Test
    fun damagedSourceDatabaseIsNotCopied() {
        val source = File(tempDir, "old").apply { mkdirs() }
        File(source, "vaultpass.db").writeText("this is not a SQLite database".repeat(200))
        File(source, "metadata.json").writeText("""{"initialized":true}""")
        val target = File(tempDir, "new").apply { mkdirs() }
        File(target, "vaultpass.db").writeText("target db")

        assertThrows(Exception::class.java) { AppStorageConfig.copyVaultFiles(source, target) }

        assertEquals("target db", File(target, "vaultpass.db").readText(), "The target's database stays where it was")
        assertFalse(File(target, "metadata.json").exists(), "Startup still offers the copy")
        assertTrue(target.listFiles()!!.none { it.name.contains(".copying") })
    }

    // ---- Remembered data folder ----

    @Test
    fun okResolutionIsRemembered() {
        val userHomeBefore = System.getProperty("user.home")
        try {
            System.setProperty("user.home", userHome.absolutePath)

            val result = AppStorageConfig.resolveDataDirectory()

            assertEquals(StorageResolution.Ok(File(userHome, ".vaultpass")), result)
            assertEquals(listOf(File(userHome, ".vaultpass").absolutePath), record.written)
        } finally {
            System.setProperty("user.home", userHomeBefore)
        }
    }

    @Test
    fun customFolderIsNotRemembered() {
        val custom = File(tempDir, "data")
        System.setProperty("vaultpass.data.dir", custom.absolutePath)

        assertEquals(StorageResolution.Ok(custom), AppStorageConfig.resolveDataDirectory())

        assertTrue(record.written.isEmpty())
    }

    @Test
    fun problemIsNotRemembered() {
        val custom = File(tempDir, "not-a-folder").apply { writeText("file") }
        System.setProperty("vaultpass.data.dir", custom.absolutePath)

        assertTrue(AppStorageConfig.resolveDataDirectory() is StorageResolution.Problem)

        assertTrue(record.written.isEmpty())
    }

    @Test
    fun rememberedFolderWithAVaultIsOfferedAndKeptUntilTheNewFolderHasOne() {
        val oldFolder = File(tempDir, "D-Apps/VaultPass/data").apply { mkdirs() }
        File(oldFolder, "metadata.json").writeText("{}")
        record.value = oldFolder.absolutePath
        val resolved = File(tempDir, "Programs/VaultPass/data").apply { mkdirs() }
        System.setProperty("vaultpass.data.dir", resolved.absolutePath)

        assertEquals(StorageResolution.Ok(resolved), AppStorageConfig.resolveDataDirectory())
        AppStorageConfig.rememberDataDirectory(resolved)

        // The empty new folder doesn't replace the remembered vault, and that vault is offered first.
        assertTrue(record.written.isEmpty())
        assertEquals(oldFolder, AppStorageConfig.findVaultsElsewhere(resolved).first().folder)

        // Once the new folder has its own vault (copied or created), it is remembered.
        File(resolved, "metadata.json").writeText("{}")
        AppStorageConfig.rememberDataDirectory(resolved)
        assertEquals(listOf(resolved.absolutePath), record.written)
    }

    @Test
    fun candidatesAreTheRememberedFolderThenTheOldDefaults() {
        val home = File(tempDir, "home")
        val temp = File(tempDir, "tmp")

        assertEquals(
            listOf(File("D:\\Apps\\VaultPass\\data"), File("C:\\VaultPass\\data"), File(home, ".vaultpass"), File(temp, ".vaultpass")),
            AppStorageConfig.vaultElsewhereCandidates("D:\\Apps\\VaultPass\\data", home, temp)
        )
        assertEquals(
            listOf(File("C:\\VaultPass\\data"), File(home, ".vaultpass"), File(temp, ".vaultpass")),
            AppStorageConfig.vaultElsewhereCandidates(null, home, temp)
        )
    }

    @Test
    fun rememberedFolderEqualToTheResolvedOneIsNotOffered() {
        val resolved = File(tempDir, "data").apply { mkdirs() }
        val sameFolder = File(tempDir, "data")
        assertTrue(AppStorageConfig.findVaultsElsewhere(resolved, listOf(sameFolder)).isEmpty())
    }

    // ---- Folder-permission notice ----

    @Test
    fun folderPermissionNoticeOnlyWhenTheInstallerLeftItsFile() {
        val install = File(tempDir, "VaultPass")
        val dataDir = File(install, "data").apply { mkdirs() }
        assertNull(AppStorageConfig.folderPermissionsNotice(dataDir))
        assertNull(AppStorageConfig.folderPermissionsNotice(null))

        File(dataDir, AppStorageConfig.FOLDER_PERMISSIONS_NOTICE_FILE).writeText("icacls returned 5\r\nsecond line\r\n")
        assertEquals(
            "Other Windows accounts on this PC may be able to open VaultPass's folder (${install.absolutePath}): " +
                "icacls returned 5",
            AppStorageConfig.folderPermissionsNotice(dataDir)
        )

        // A folder of that name is not the installer's file.
        File(dataDir, AppStorageConfig.FOLDER_PERMISSIONS_NOTICE_FILE).delete()
        File(dataDir, AppStorageConfig.FOLDER_PERMISSIONS_NOTICE_FILE).mkdirs()
        assertNull(AppStorageConfig.folderPermissionsNotice(dataDir))
    }

    @Test
    fun folderPermissionNoticeReasonIsCappedAndAnEmptyFileStillWarns() {
        val install = File(tempDir, "VaultPass")
        val dataDir = File(install, "data").apply { mkdirs() }
        val notice = File(dataDir, AppStorageConfig.FOLDER_PERMISSIONS_NOTICE_FILE)
        val prefix = "Other Windows accounts on this PC may be able to open VaultPass's folder (${install.absolutePath})"

        notice.writeText("x".repeat(5_000))
        assertEquals("$prefix: ${"x".repeat(300)}", AppStorageConfig.folderPermissionsNotice(dataDir))

        notice.writeText("")
        assertEquals("$prefix.", AppStorageConfig.folderPermissionsNotice(dataDir))

        assertFalse(AppStorageConfig.folderPermissionsNotice(dataDir)!!.contains("Reinstall"))
    }

    private fun hashes(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(dir).path to MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        }
}
