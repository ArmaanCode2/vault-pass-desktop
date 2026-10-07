package com.vaultpass.desktop.data.platform

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A folder outside the data folder that holds a vault; [lastChanged] is in epoch milliseconds. */
data class VaultCandidate(val folder: File, val lastChanged: Long)

/** Result of looking for a usable data folder. */
sealed interface StorageResolution {
    val directory: File

    data class Ok(override val directory: File) : StorageResolution

    /** [directory] is the folder VaultPass must use but can't write to. */
    data class Problem(override val directory: File, val reason: String) : StorageResolution
}

/**
 * Central authority for resolving storage paths across VaultPass Desktop.
 *
 * Priority order for resolving the active storage directory:
 * 1. CLI/JVM property override: -Dvaultpass.data.dir="C:/CustomPath"
 * 2. Dev mode flag: -Dvaultpass.env=dev -> resolves to ~/.vaultpass-dev/
 * 3. Local Companion Data folder: <AppDir>/data (created by installer or portable mode)
 * 4. Default user directory: ~/.vaultpass/
 *
 * The chosen folder must be writable. If it isn't, the result is a [StorageResolution.Problem];
 * VaultPass never falls back to another folder.
 */
object AppStorageConfig {
    private const val METADATA_FILE_NAME = "metadata.json"

    private val isDevEnvironment: Boolean by lazy {
        System.getProperty("vaultpass.env")?.equals("dev", ignoreCase = true) == true ||
        System.getProperty("idea.active") != null
    }

    @Volatile
    private var resolvedDirectory: File? = null

    /**
     * Resolves the root directory where the application binary or JAR is located.
     */
    val appInstallationDirectory: File by lazy {
        try {
            val codeSource = AppStorageConfig::class.java.protectionDomain?.codeSource
            val location = codeSource?.location?.toURI()?.let { File(it) }
            when {
                location == null -> File(System.getProperty("user.dir"))
                location.isFile -> {
                    // Running from JAR inside <InstallDir>/app/
                    val parent = location.parentFile
                    if (parent != null && parent.name.equals("app", ignoreCase = true)) {
                        parent.parentFile ?: parent
                    } else {
                        parent ?: File(System.getProperty("user.dir"))
                    }
                }
                location.isDirectory -> location
                else -> File(System.getProperty("user.dir"))
            }
        } catch (_: Exception) {
            File(System.getProperty("user.dir"))
        }
    }

    /**
     * Tests whether the application can create, read and delete a file in [dir], creating [dir]
     * if needed. Antivirus can briefly lock a new file, so a failure is retried.
     */
    internal fun isDirectoryWritable(dir: File, attempts: Int = 3, delayMillis: Long = 200): Boolean {
        repeat(attempts) { attempt ->
            if (probeOnce(dir)) return true
            if (attempt < attempts - 1) Thread.sleep(delayMillis)
        }
        return false
    }

    private fun probeOnce(dir: File): Boolean {
        val probe = dir.toPath().resolve(".vaultpass-write-test-${UUID.randomUUID()}")
        return try {
            Files.createDirectories(dir.toPath())
            Files.writeString(probe, "write_test")
            val readBack = Files.readString(probe) == "write_test"
            Files.delete(probe)
            readBack
        } catch (_: Exception) {
            try { Files.deleteIfExists(probe) } catch (_: Exception) { }
            false
        }
    }

    /**
     * Finds the data folder and checks that it is writable. Runs again on every call, so Retry
     * picks up a fixed folder. On success the folder becomes [dataDirectory].
     */
    fun resolveDataDirectory(): StorageResolution {
        val customPath = System.getProperty("vaultpass.data.dir")
        val result = resolveDataDirectory(
            customPath = customPath,
            devMode = isDevEnvironment,
            appDirectory = appInstallationDirectory,
            userHome = File(System.getProperty("user.home")),
            isWritable = { isDirectoryWritable(it) }
        )
        if (result is StorageResolution.Ok) {
            resolvedDirectory = result.directory
            // A folder set with -Dvaultpass.data.dir applies to that run only; the installer never sees it.
            if (!isDevEnvironment && customPath.isNullOrBlank()) rememberDataDirectory(result.directory)
        }
        return result
    }

    /** Where the data folder is remembered between runs (HKCU\Software\VaultPass, DataDir). Replaced in tests. */
    internal interface DataDirectoryRecord {
        fun read(): String?
        fun write(path: String)
    }

    private object WindowsRegistryRecord : DataDirectoryRecord {
        private const val KEY = "Software\\VaultPass"
        private const val VALUE = "DataDir"

        override fun read(): String? {
            if (!isWindows()) return null
            return try {
                val root = com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
                if (com.sun.jna.platform.win32.Advapi32Util.registryValueExists(root, KEY, VALUE)) {
                    com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(root, KEY, VALUE)
                } else null
            } catch (_: Throwable) {
                null
            }
        }

        override fun write(path: String) {
            if (!isWindows()) return
            try {
                val root = com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
                com.sun.jna.platform.win32.Advapi32Util.registryCreateKey(root, KEY)
                com.sun.jna.platform.win32.Advapi32Util.registrySetStringValue(root, KEY, VALUE, path)
            } catch (_: Throwable) {
                // Only a hint for the next run and the installer.
            }
        }

        private fun isWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }

    @Volatile
    internal var dataDirectoryRecord: DataDirectoryRecord = WindowsRegistryRecord

    // The value from before this run wrote its own, read once; findVaultsElsewhere looks there.
    @Volatile
    private var recordedAtStartup: String? = null
    @Volatile
    private var recordedAtStartupRead = false

    private fun recordedDataDirectory(): String? {
        if (!recordedAtStartupRead) {
            recordedAtStartup = try { dataDirectoryRecord.read() } catch (_: Exception) { null }
            recordedAtStartupRead = true
        }
        return recordedAtStartup
    }

    /**
     * Writes [directory] as the remembered data folder. A remembered folder that holds a vault is not
     * replaced by one that doesn't, so closing the "vault found elsewhere" window doesn't lose it.
     */
    internal fun rememberDataDirectory(directory: File) {
        val path = directory.absolutePath
        val previous = recordedDataDirectory()
        if (previous == path) return
        if (previous != null && !File(directory, METADATA_FILE_NAME).isFile &&
            File(previous, METADATA_FILE_NAME).isFile) return
        try { dataDirectoryRecord.write(path) } catch (_: Exception) { }
    }

    internal fun resolveDataDirectory(
        customPath: String?,
        devMode: Boolean,
        appDirectory: File,
        userHome: File,
        isWritable: (File) -> Boolean
    ): StorageResolution {
        // Priority 1: Explicit System Property
        if (!customPath.isNullOrBlank()) {
            val customDir = File(customPath)
            return if (isWritable(customDir)) {
                StorageResolution.Ok(customDir)
            } else {
                StorageResolution.Problem(customDir, "This folder was set with -Dvaultpass.data.dir, but VaultPass can't write to it.")
            }
        }

        // Priority 2: Development Environment Isolation
        if (devMode) {
            return StorageResolution.Ok(File(userHome, ".vaultpass-dev").apply { mkdirs() })
        }

        // Priority 3: Local "data" companion directory in installation folder
        val localDataDir = File(appDirectory, "data")
        val portableFlag = File(appDirectory, "portable.flag")
        if (localDataDir.exists() || portableFlag.exists()) {
            return if (isWritable(localDataDir)) {
                StorageResolution.Ok(localDataDir)
            } else {
                StorageResolution.Problem(localDataDir, "VaultPass can't write to the data folder in its install folder.")
            }
        }

        // Priority 4: Default folder in user home
        val defaultDir = File(userHome, ".vaultpass")
        return if (isWritable(defaultDir)) {
            StorageResolution.Ok(defaultDir)
        } else {
            StorageResolution.Problem(defaultDir, "VaultPass can't write to its folder in your user profile.")
        }
    }

    /** The active data folder. Throws until [resolveDataDirectory] has returned Ok. */
    val dataDirectory: File
        get() = resolvedDirectory ?: throw IllegalStateException("The data folder hasn't been resolved yet.")

    /** The active data folder, or null while it is unresolved. */
    val dataDirectoryOrNull: File?
        get() = resolvedDirectory

    internal fun clearResolutionForTests() {
        resolvedDirectory = null
        recordedAtStartup = null
        recordedAtStartupRead = false
    }

    val metadataFile: File get() = File(dataDirectory, METADATA_FILE_NAME)
    val databaseFile: File get() = File(dataDirectory, "vaultpass.db")
    val settingsFile: File get() = File(dataDirectory, "settings.json")
    val sessionKeyFile: File get() = File(dataDirectory, "session.dpapi")
    val syncDeviceIdFile: File get() = File(dataDirectory, "sync-device-id")

    /**
     * Returns the folders holding a vault when [resolved] has none, the most recently changed first:
     * the data folder remembered from an earlier run (another install folder), the old default
     * install's `C:\VaultPass\data`, or `~/.vaultpass` and `%TEMP%\.vaultpass`, which older
     * versions used silently when the data folder wasn't writable.
     */
    fun findVaultsElsewhere(resolved: File): List<VaultCandidate> {
        if (isDevEnvironment) return emptyList()
        return findVaultsElsewhere(
            resolved,
            vaultElsewhereCandidates(
                recorded = recordedDataDirectory(),
                userHome = File(System.getProperty("user.home")),
                tempDirectory = File(System.getProperty("java.io.tmpdir"))
            )
        )
    }

    /** The folders [findVaultsElsewhere] looks in. */
    internal fun vaultElsewhereCandidates(recorded: String?, userHome: File, tempDirectory: File): List<File> =
        listOfNotNull(
            recorded?.takeIf { it.isNotBlank() }?.let { File(it) },
            File("C:\\VaultPass\\data"),
            File(userHome, ".vaultpass"),
            File(tempDirectory, ".vaultpass")
        )

    /**
     * The [candidates] (other than [resolved]) that hold a metadata.json, newest change first. A
     * vault's last change is the newest modification time of its metadata.json, vaultpass.db and
     * vaultpass.db-wal. Empty when [resolved] has its own vault.
     */
    internal fun findVaultsElsewhere(resolved: File, candidates: List<File>): List<VaultCandidate> {
        if (File(resolved, METADATA_FILE_NAME).exists()) return emptyList()
        val resolvedPath = canonicalPath(resolved)
        return candidates
            .distinctBy { canonicalPath(it).lowercase() }
            .filter { canonicalPath(it) != resolvedPath && File(it, METADATA_FILE_NAME).isFile }
            .map { VaultCandidate(it, lastChanged(it)) }
            .sortedByDescending { it.lastChanged }
    }

    private fun lastChanged(folder: File): Long =
        listOf(METADATA_FILE_NAME, DATABASE_FILE_NAME, "$DATABASE_FILE_NAME-wal")
            .maxOf { name -> File(folder, name).lastModified() }

    /**
     * Copies a vault from [source] into [target] and leaves [source] as it is.
     *
     * The database is copied by SQLite (`VACUUM INTO`, which includes what is still in its -wal file),
     * checked, and then moved into place. Every other regular file directly in [source] is copied,
     * except crash.log, the installer's folder-permissions notice and pending-update.json; folders
     * (such as updates) are skipped. A database already in [target] is renamed, with its -wal, -shm
     * and -journal files, to `vaultpass.db.replaced-<yyyyMMdd-HHmmss>` (and `…-wal`, `…-shm`,
     * `…-journal`), never overwritten.
     *
     * Throws [VaultAppearedException] when [target] gets a metadata.json of its own before or while copying.
     * [beforeMetadataMove] runs just before metadata.json is moved into place (tests use it).
     */
    fun copyVaultFiles(
        source: File,
        target: File,
        now: LocalDateTime = LocalDateTime.now(),
        beforeMetadataMove: () -> Unit = {}
    ) {
        val targetPath = target.toPath()
        ensureNoVault(target)
        Files.createDirectories(targetPath)
        val files = Files.list(source.toPath()).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .filter { file ->
                    val name = file.fileName.toString()
                    NOT_COPIED.none { it.equals(name, ignoreCase = true) } &&
                        DATABASE_SUFFIXES.none { (DATABASE_FILE_NAME + it).equals(name, ignoreCase = true) }
                }
                .toList()
        }

        val sourceDatabase = File(source, DATABASE_FILE_NAME)
        val copiedDatabase = if (Files.isRegularFile(sourceDatabase.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            copyDatabase(sourceDatabase, target)
        } else null
        try {
            ensureNoVault(target)
            // The database files in the target belong to another vault (or none); keep them aside.
            moveDatabaseAside(targetPath, now)
            if (copiedDatabase != null) {
                Files.move(copiedDatabase, targetPath.resolve(DATABASE_FILE_NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (copiedDatabase != null) deleteWithCompanions(copiedDatabase)
        }

        // metadata.json goes last: until it is there, startup still offers the copy.
        val (metadata, others) = files.partition { it.fileName.toString() == METADATA_FILE_NAME }
        for (file in others) {
            val name = file.fileName.toString()
            val partial = targetPath.resolve("$name.copying")
            Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial, targetPath.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        for (file in metadata) {
            val partial = targetPath.resolve("$METADATA_FILE_NAME.copying")
            Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING)
            try {
                beforeMetadataMove()
                ensureNoVault(target)
                // No REPLACE_EXISTING: a metadata.json that appears now makes the move fail instead.
                Files.move(partial, targetPath.resolve(METADATA_FILE_NAME))
            } catch (_: FileAlreadyExistsException) {
                throw VaultAppearedException(target)
            } finally {
                Files.deleteIfExists(partial)
            }
        }
    }

    /** [copyVaultFiles] stopped because the target got a vault of its own. */
    class VaultAppearedException(target: File) :
        IOException("A vault appeared in ${target.absolutePath} while copying. Nothing was overwritten.")

    private fun ensureNoVault(target: File) {
        if (Files.exists(File(target, METADATA_FILE_NAME).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw VaultAppearedException(target)
        }
    }

    /**
     * Copies [sourceDatabase] with `VACUUM INTO` (read-only on the source) to `vaultpass.db.copying`
     * in [target] and checks the copy. Returns the copy, which the caller moves into place.
     */
    private fun copyDatabase(sourceDatabase: File, target: File): Path {
        val partial = File(target, "$DATABASE_FILE_NAME.copying").toPath()
        deleteWithCompanions(partial)
        try {
            val readOnly = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }.toProperties()
            val expectedEntries = DriverManager.getConnection("jdbc:sqlite:${sourceDatabase.absolutePath}", readOnly).use { source ->
                source.prepareStatement("VACUUM INTO ?").use { statement ->
                    statement.setString(1, partial.toString())
                    statement.execute()
                }
                vaultEntryCount(source)
            }
            DriverManager.getConnection("jdbc:sqlite:$partial").use { copy ->
                // VACUUM INTO keeps the WAL flag of a WAL source; the copy is moved as one self-contained file.
                copy.createStatement().use { it.execute("PRAGMA journal_mode = DELETE") }
                val check = copy.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA quick_check").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
                if (check != listOf("ok")) throw IOException("the copied database failed its integrity check")
                if (vaultEntryCount(copy) != expectedEntries) {
                    throw IOException("the copied database doesn't have the same number of entries")
                }
            }
            for (suffix in DATABASE_SUFFIXES.drop(1)) Files.deleteIfExists(Path.of(partial.toString() + suffix))
            return partial
        } catch (e: Exception) {
            deleteWithCompanions(partial)
            throw e
        }
    }

    /** Rows in vault_entry, or null when the table doesn't exist. */
    private fun vaultEntryCount(conn: Connection): Long? =
        conn.createStatement().use { statement ->
            val hasTable = statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'vault_entry'"
            ).use { it.next() }
            if (!hasTable) null
            else statement.executeQuery("SELECT COUNT(*) FROM vault_entry").use { rs -> rs.next(); rs.getLong(1) }
        }

    /**
     * Renames vaultpass.db and its -wal, -shm and -journal files in [targetPath] to one
     * `vaultpass.db.replaced-<stamp>` name (plus the same suffixes), so SQLite still pairs them.
     */
    private fun moveDatabaseAside(targetPath: Path, now: LocalDateTime) {
        val existing = DATABASE_SUFFIXES.filter {
            Files.exists(targetPath.resolve(DATABASE_FILE_NAME + it), LinkOption.NOFOLLOW_LINKS)
        }
        if (existing.isEmpty()) return
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var aside = "$DATABASE_FILE_NAME.replaced-$stamp"
        var counter = 2
        while (DATABASE_SUFFIXES.any { Files.exists(targetPath.resolve(aside + it), LinkOption.NOFOLLOW_LINKS) }) {
            aside = "$DATABASE_FILE_NAME.replaced-$stamp-$counter"
            counter++
        }
        for (suffix in existing) {
            Files.move(targetPath.resolve(DATABASE_FILE_NAME + suffix), targetPath.resolve(aside + suffix))
        }
    }

    private fun deleteWithCompanions(database: Path) {
        for (suffix in DATABASE_SUFFIXES) {
            try { Files.deleteIfExists(Path.of(database.toString() + suffix)) } catch (_: Exception) { }
        }
    }

    /** Left in the data folder by the installer when it couldn't restrict the install folder to this account. */
    const val FOLDER_PERMISSIONS_NOTICE_FILE = "folder-permissions-not-restricted.txt"

    private const val NOTICE_REASON_MAX_LENGTH = 300

    /**
     * The notice to show after unlock when the installer left [FOLDER_PERMISSIONS_NOTICE_FILE] in
     * [dataDir], with the reason from the file's first line; else null.
     */
    fun folderPermissionsNotice(dataDir: File? = dataDirectoryOrNull): String? {
        if (dataDir == null) return null
        val noticeFile = File(dataDir, FOLDER_PERMISSIONS_NOTICE_FILE).toPath()
        if (!Files.isRegularFile(noticeFile, LinkOption.NOFOLLOW_LINKS)) return null
        val folder = dataDir.absoluteFile.parentFile ?: dataDir.absoluteFile
        val reason = try { firstLine(noticeFile) } catch (_: Exception) { "" }
        val notice = "Other Windows accounts on this PC may be able to open VaultPass's folder (${folder.path})"
        return if (reason.isEmpty()) "$notice." else "$notice: $reason"
    }

    /** The first line of [file], without control characters, at most [NOTICE_REASON_MAX_LENGTH] characters. */
    private fun firstLine(file: Path): String {
        val bytes = Files.newInputStream(file).use { it.readNBytes(4096) }
        return String(bytes, Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .lineSequence().firstOrNull().orEmpty()
            .filterNot { it.isISOControl() }
            .trim()
            .take(NOTICE_REASON_MAX_LENGTH)
    }

    private const val DATABASE_FILE_NAME = "vaultpass.db"

    /** The database file itself, then the files SQLite pairs with it by name. */
    private val DATABASE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    /** Never copied by [copyVaultFiles]: they describe the old folder or an old update, not the vault. */
    private val NOT_COPIED = listOf("crash.log", FOLDER_PERMISSIONS_NOTICE_FILE, "pending-update.json")

    private fun canonicalPath(dir: File): String =
        try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }

    /**
     * Human-readable identifier of the active storage mode for UI/logging.
     */
    val storageModeDescription: String
        get() = when {
            isDevEnvironment -> "Development Isolation (~/.vaultpass-dev)"
            File(appInstallationDirectory, "data").exists() && dataDirectory == File(appInstallationDirectory, "data") ->
                "Isolated Folder (${dataDirectory.absolutePath})"
            else -> "User Home Directory (${dataDirectory.absolutePath})"
        }
}
