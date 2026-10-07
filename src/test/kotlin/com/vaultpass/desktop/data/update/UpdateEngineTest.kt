package com.vaultpass.desktop.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Download folder, cancel, launch modes, the beforeExit hook, the pending-update marker and
 * install-on-close, all with fake fetchers and launchers. Nothing is downloaded or started.
 */
class UpdateEngineTest {

    @TempDir
    lateinit var tempDir: File

    private val version = "2.0.0"
    private val installerName = "VaultPass-Setup-v2.0.0.exe"
    private val installerUrl = "https://example.test/download/v2.0.0/$installerName"
    private val signatureUrl = "$installerUrl.sig"
    private val installerBytes = ByteArray(120_000) { (it * 13 % 241).toByte() }
    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private fun updatesDir() = File(tempDir, "data/updates")
    private fun installDir() = File(tempDir, "Program Files x/Vault Pass")

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sign(privateKey: PrivateKey, bytes: ByteArray): String {
        val signer = Signature.getInstance(UpdateSignatureVerifier.SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(UpdateSignatureVerifier.signedMessage(installerName, sha256Hex(bytes)))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun signedFiles() = mapOf(installerUrl to installerBytes, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())

    private class FakeFetcher(private val files: Map<String, ByteArray>) : UpdateFetcher {
        override fun fetchBytes(url: String, maxBytes: Long): ByteArray = files[url] ?: throw UpdateHttpException(404)

        override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
            val bytes = files[url] ?: throw UpdateHttpException(404)
            destination.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    /** Records each launch, the thread it ran on and, optionally, what happened before it. */
    private class RecordingLauncher(private val events: MutableList<String>? = null) : InstallerLauncher {
        val launched = mutableListOf<List<String>>()
        val threads = mutableListOf<String>()
        override fun launchAndExit(installerFile: File, arguments: List<String>) {
            events?.add("launch")
            threads += Thread.currentThread().name
            launched += arguments
        }
    }

    private fun manager(
        fetcher: UpdateFetcher = FakeFetcher(signedFiles()),
        launcher: InstallerLauncher = RecordingLauncher(),
        currentVersion: String = "1.0.0",
        releaseSource: ReleaseSource? = null,
        beforeExitTimeoutMillis: Long = UpdateManager.BEFORE_EXIT_TIMEOUT_MILLIS,
        setupRegistration: SetupRegistration = SetupRegistration { installDir().absolutePath },
        startedAfterFailedUpdate: Boolean = false
    ) = UpdateManager(
        downloadDir = updatesDir(),
        currentVersion = currentVersion,
        repoOwner = "o",
        repoName = "r",
        signingPublicKey = keyPair.public,
        fetcher = fetcher,
        installerLauncher = launcher,
        releaseSource = releaseSource ?: object : ReleaseSource {
            override fun fetchLatestRelease(url: String): ReleaseApiResponse = throw AssertionError("No release lookup was expected")
            override fun latestReleaseRedirect(url: String): String? = throw AssertionError("No release lookup was expected")
        },
        installDirectory = installDir(),
        beforeExitTimeoutMillis = beforeExitTimeoutMillis,
        setupRegistration = setupRegistration,
        startedAfterFailedUpdate = startedAfterFailedUpdate
    )

    private fun available() = UpdateState.Available(
        version = version,
        releaseNotes = "",
        downloadUrl = installerUrl,
        fileSize = installerBytes.size.toLong(),
        signatureUrl = signatureUrl,
        expectedSha256 = sha256Hex(installerBytes)
    )

    private fun installerFile() = File(updatesDir(), installerName)
    private fun partFile() = File(updatesDir(), "$installerName.part")
    private fun logFile() = File(updatesDir(), "setup-2.0.0.log").absoluteFile

    /** Splits a command line the way Inno Setup does: quotes group and are dropped, no escapes. */
    private fun parseLikeInnoSetup(commandLine: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var started = false
        for (c in commandLine) {
            when {
                c == '"' -> { inQuotes = !inQuotes; started = true }
                c == ' ' && !inQuotes -> {
                    if (started) result += current.toString()
                    current.clear()
                    started = false
                }
                else -> { current.append(c); started = true }
            }
        }
        if (started) result += current.toString()
        return result
    }

    // ---- Installer arguments ----

    @Test
    fun restartNowRelaunchesAndOnExitDoesNotBothPassDirAndLog() {
        val dir = File(tempDir, "install dir with spaces")
        val log = File(tempDir, "data folder/updates/setup-2.0.0.log")

        val restart = InstallerArguments.build(LaunchMode.RestartNow, dir, log)
        val onExit = InstallerArguments.build(LaunchMode.OnExit, dir, log)

        assertEquals(listOf("/SILENT", "/NORESTART", "/LOG=${log.absolutePath}", "/DIR=${dir.absolutePath}", "/RELAUNCH=1"), restart)
        assertEquals(listOf("/SILENT", "/NORESTART", "/LOG=${log.absolutePath}", "/DIR=${dir.absolutePath}"), onExit)
        assertFalse(restart.contains("/SUPPRESSMSGBOXES") || onExit.contains("/SUPPRESSMSGBOXES"))

        // Paths with spaces survive the ShellExecute string.
        assertEquals(restart, parseLikeInnoSetup(InstallerArguments.toCommandLine(restart)))
        assertEquals(onExit, parseLikeInnoSetup(InstallerArguments.toCommandLine(onExit)))
    }

    @Test
    fun commandLineQuotesPathsWithSpaces() {
        assumeTrue(isWindows, "Windows paths")
        val dir = File("E:\\antigravity\\vaultpass dekstop\\vaultpass desktop")
        val log = File("E:\\antigravity\\vaultpass dekstop\\vaultpass desktop\\data\\updates\\setup-2.0.0.log")

        assertEquals(
            "/SILENT /NORESTART /LOG=\"E:\\antigravity\\vaultpass dekstop\\vaultpass desktop\\data\\updates\\setup-2.0.0.log\" " +
                "/DIR=\"E:\\antigravity\\vaultpass dekstop\\vaultpass desktop\" /RELAUNCH=1",
            InstallerArguments.toCommandLine(InstallerArguments.build(LaunchMode.RestartNow, dir, log))
        )
        assertEquals(
            "/SILENT /NORESTART /LOG=\"E:\\antigravity\\vaultpass dekstop\\vaultpass desktop\\data\\updates\\setup-2.0.0.log\" " +
                "/DIR=\"E:\\antigravity\\vaultpass dekstop\\vaultpass desktop\"",
            InstallerArguments.toCommandLine(InstallerArguments.build(LaunchMode.OnExit, dir, log))
        )
    }

    @Test
    fun commandLineRefusesQuotesAndSpacesOutsideValues() {
        assertThrows(IllegalArgumentException::class.java) { InstallerArguments.toCommandLine(listOf("/DIR=C:\\a\" /x")) }
        assertThrows(IllegalArgumentException::class.java) { InstallerArguments.toCommandLine(listOf("/SILENT /X")) }
    }

    // ---- Launch ----

    @Test
    fun launchModesReachTheLauncherWithDirAndLog() = runTest {
        for (mode in LaunchMode.entries) {
            val launcher = RecordingLauncher()
            val manager = manager(launcher = launcher)
            val ready = manager.downloadAndPrepareUpdate(available())!!

            manager.launchInstallerAndExit(ready, mode)

            val args = launcher.launched.single()
            assertTrue("/DIR=${installDir().absolutePath}" in args, "$mode: $args")
            assertTrue("/LOG=${logFile().absolutePath}" in args, "$mode: $args")
            assertEquals(mode == LaunchMode.RestartNow, "/RELAUNCH=1" in args, "$mode: $args")
            assertEquals(listOf(UpdateManager.LAUNCH_THREAD_NAME), launcher.threads, "Launched on its own thread")
        }
    }

    @Test
    fun hookRunsOnceAfterVerificationAndBeforeTheLaunch() = runTest {
        val events = mutableListOf<String>()
        val manager = manager(launcher = RecordingLauncher(events))
        manager.beforeExit = {
            // The installer has been checked again and is held locked by now.
            events += if (isWindows && !canWrite(installerFile())) "hook (installer locked)" else "hook"
        }
        manager.downloadAndPrepareUpdate(available())
        val ready = manager.updateState.value as UpdateState.ReadyToInstall

        manager.applyUpdate(ready)

        val expectedHook = if (isWindows) "hook (installer locked)" else "hook"
        assertEquals(listOf(expectedHook, "launch"), events)
    }

    private fun canWrite(file: File): Boolean = try {
        FileOutputStream(file, true).use { }
        true
    } catch (_: IOException) {
        false
    }

    @Test
    fun tamperedInstallerRunsNoHookAndNoLaunch() = runTest {
        val launcher = RecordingLauncher()
        val manager = manager(launcher = launcher)
        var hookCalls = 0
        manager.beforeExit = { hookCalls++ }
        manager.downloadAndPrepareUpdate(available())
        val ready = manager.updateState.value as UpdateState.ReadyToInstall
        ready.installerFile.writeBytes(installerBytes + byteArrayOf(7))

        manager.applyUpdate(ready)

        assertEquals(0, hookCalls)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(UpdateState.Error(UpdateManager.CHANGED_AFTER_CHECK_MESSAGE), manager.updateState.value)
        assertFalse(File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME).exists(), "No marker without a launch")
    }

    @Test
    fun failingHookIsNotFatal() = runTest {
        val launcher = RecordingLauncher()
        val manager = manager(launcher = launcher)
        manager.beforeExit = { throw IllegalStateException("sync wouldn't stop") }
        manager.downloadAndPrepareUpdate(available())

        manager.applyUpdate(manager.updateState.value as UpdateState.ReadyToInstall)

        assertEquals(1, launcher.launched.size)
    }

    @Test
    fun failedLaunchAfterTheHookSaysVaultPassIsLocked() = runTest {
        val manager = manager(launcher = { _, _ -> throw IOException("CreateProcess error=5, Access is denied") })
        var hookCalls = 0
        manager.beforeExit = { hookCalls++ }
        manager.downloadAndPrepareUpdate(available())

        manager.applyUpdate(manager.updateState.value as UpdateState.ReadyToInstall)

        assertEquals(1, hookCalls)
        assertEquals(
            UpdateState.Error("The update couldn't be started: CreateProcess error=5, Access is denied. VaultPass is locked; unlock to continue."),
            manager.updateState.value
        )
    }

    @Test
    fun failedLaunchAfterAHookThatTimedOutDoesNotClaimVaultPassIsLocked() = runTest {
        val manager = manager(
            launcher = { _, _ -> throw IOException("CreateProcess error=5, Access is denied") },
            beforeExitTimeoutMillis = 100
        )
        val release = CountDownLatch(1)
        manager.beforeExit = { release.await(30, TimeUnit.SECONDS) }
        manager.downloadAndPrepareUpdate(available())

        try {
            manager.applyUpdate(manager.updateState.value as UpdateState.ReadyToInstall)
        } finally {
            release.countDown()
        }

        assertEquals(
            UpdateState.Error("The update couldn't be started: CreateProcess error=5, Access is denied."),
            manager.updateState.value
        )
    }

    @Test
    fun failedLaunchAfterAHookThatThrewDoesNotClaimVaultPassIsLocked() = runTest {
        val manager = manager(launcher = { _, _ -> throw IOException("no such file") })
        manager.beforeExit = { throw IllegalStateException("lock failed") }
        manager.downloadAndPrepareUpdate(available())

        manager.applyUpdate(manager.updateState.value as UpdateState.ReadyToInstall)

        assertEquals(UpdateState.Error("The update couldn't be started: no such file."), manager.updateState.value)
    }

    // ---- Copies Setup didn't install ----

    @Test
    fun registeredFolderMustBeThisCopysFolder() {
        val dir = installDir()
        assertTrue(SetupRegistration.isSetupInstall(dir.absolutePath, dir))
        assertTrue(SetupRegistration.isSetupInstall(dir.absolutePath.uppercase(), dir), "Case doesn't matter")
        assertTrue(SetupRegistration.isSetupInstall(dir.absolutePath + File.separator, dir), "Trailing separator")
        assertTrue(SetupRegistration.isSetupInstall(File(dir, "app/..").path, dir), "Canonicalised")
        assertFalse(SetupRegistration.isSetupInstall(null, dir))
        assertFalse(SetupRegistration.isSetupInstall("", dir))
        assertFalse(SetupRegistration.isSetupInstall(File(tempDir, "elsewhere").absolutePath, dir))
    }

    @Test
    fun copyNotInstalledBySetupChecksButRefusesToDownload() = runTest {
        for (registered in listOf<String?>(null, File(tempDir, "other install").absolutePath)) {
            val source = object : ReleaseSource {
                override fun fetchLatestRelease(url: String) = ReleaseApiResponse(
                    200,
                    """{"tag_name":"v$version","assets":[
                        {"name":"$installerName","browser_download_url":"$installerUrl","size":${installerBytes.size}},
                        {"name":"$installerName.sig","browser_download_url":"$signatureUrl","size":100}]}"""
                )
                override fun latestReleaseRedirect(url: String): String? = null
            }
            var fetched = false
            val fetcher = object : UpdateFetcher {
                override fun fetchBytes(url: String, maxBytes: Long): ByteArray { fetched = true; throw UpdateHttpException(404) }
                override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
                    fetched = true
                }
            }
            val manager = manager(fetcher = fetcher, releaseSource = source, setupRegistration = { registered })

            val found = manager.checkForUpdates()
            assertTrue(found is UpdateState.Available, "The check still works: $found")

            assertNull(manager.downloadAndPrepareUpdate(found as UpdateState.Available))
            assertEquals(UpdateState.Error(UpdateManager.NOT_INSTALLED_BY_SETUP_MESSAGE), manager.updateState.value)
            assertFalse(fetched, "Nothing was downloaded ($registered)")
        }
    }

    @Test
    fun copyNotInstalledBySetupNeverStartsAnInstaller() = runTest {
        var registered: String? = installDir().absolutePath
        val launcher = RecordingLauncher()
        val manager = manager(launcher = launcher, setupRegistration = { registered })
        var hookCalls = 0
        manager.beforeExit = { hookCalls++ }
        manager.downloadAndPrepareUpdate(available())
        val ready = manager.updateState.value as UpdateState.ReadyToInstall

        // Setup was uninstalled or moved elsewhere in the meantime.
        registered = null
        manager.applyUpdate(ready)

        assertTrue(launcher.launched.isEmpty())
        assertEquals(0, hookCalls)
        assertEquals(UpdateState.Error(UpdateManager.NOT_INSTALLED_BY_SETUP_MESSAGE), manager.updateState.value)
    }

    @Test
    fun hookThatHangsIsGivenUpOnAndTheLaunchGoesAhead() = runTest {
        val launcher = RecordingLauncher()
        val manager = manager(launcher = launcher, beforeExitTimeoutMillis = 200)
        val release = CountDownLatch(1)
        manager.beforeExit = { release.await(30, TimeUnit.SECONDS) }
        manager.downloadAndPrepareUpdate(available())

        val started = System.nanoTime()
        try {
            manager.applyUpdate(manager.updateState.value as UpdateState.ReadyToInstall)
        } finally {
            release.countDown()
        }

        assertEquals(1, launcher.launched.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "Didn't wait for the hook")
    }

    @Test
    fun launchRunsOnANonDaemonThreadAndIsReportedInProgress() = runTest {
        var daemon: Boolean? = null
        var inProgress: Boolean? = null
        lateinit var manager: UpdateManager
        manager = manager(launcher = { _, _ ->
            daemon = Thread.currentThread().isDaemon
            inProgress = manager.launchInProgress
        })
        manager.downloadAndPrepareUpdate(available())
        assertFalse(manager.launchInProgress)

        // Called from a daemon thread, as the IO dispatcher does.
        val caller = Thread { manager.applyUpdate(manager.updateState.value as UpdateState.ReadyToInstall) }
        caller.isDaemon = true
        caller.start()
        caller.join(10_000)

        assertEquals(false, daemon)
        assertEquals(true, inProgress)
        assertFalse(manager.launchInProgress)
    }

    @Test
    fun laterWritesTheMarkerRightAway() = runTest {
        val marker = File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME)
        val manager = manager()
        manager.downloadAndPrepareUpdate(available())
        assertFalse(marker.exists())

        assertTrue(manager.installWhenClosing())

        val written = kotlinx.serialization.json.Json.decodeFromString<PendingUpdateMarker>(marker.readText())
        assertEquals(PendingUpdateMarker(version, logFile().absolutePath), written)
        // VaultPass never got to start the installer (shutdown, killed): the next start says so. Setup
        // never ran, so there is no log.
        val nextStart = manager(currentVersion = "1.0.0")
        nextStart.runStartupTasks()
        val failed = nextStart.updateState.value
        assertEquals(UpdateState.UpdateFailed(version, logFile(), setupRan = false), failed)
        assertEquals(
            "The update to 2.0.0 wasn't installed.",
            com.vaultpass.desktop.ui.viewmodels.updateFailedText(failed as UpdateState.UpdateFailed)
        )
    }

    @Test
    fun failedSetupLeavesALogAndSaysDidntInstall() = runTest {
        writeMarker("2.0.0")
        logFile().writeText("Setup failed")
        logFile().setLastModified(System.currentTimeMillis() - 10 * 60_000)
        val manager = manager(currentVersion = "1.0.0")

        manager.runStartupTasks()

        val failed = manager.updateState.value as UpdateState.UpdateFailed
        assertEquals(UpdateState.UpdateFailed("2.0.0", logFile(), setupRan = true), failed)
        assertEquals("The update to 2.0.0 didn't install.", com.vaultpass.desktop.ui.viewmodels.updateFailedText(failed))
    }

    @Test
    fun leavingTheReadyStateAfterLaterDeletesItsMarker() = runTest {
        val marker = File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME)
        val manager = manager()
        manager.downloadAndPrepareUpdate(available())
        manager.installWhenClosing()
        assertTrue(marker.exists())

        manager.resetState()

        assertFalse(marker.exists())
    }

    @Test
    fun recentSetupLogMeansSetupMayStillBeRunning() = runTest {
        writeMarker("2.0.0")
        val marker = File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME)
        val log = logFile().apply { writeText("Setup is running") }
        val now = System.currentTimeMillis()

        log.setLastModified(now - 60_000)
        val duringSetup = manager(currentVersion = "1.0.0")
        duringSetup.checkPendingUpdate(now)
        assertEquals(UpdateState.Idle, duringSetup.updateState.value)
        assertTrue(marker.exists(), "Kept for the next start")

        log.setLastModified(now - 3 * 60_000)
        val later = manager(currentVersion = "1.0.0")
        later.checkPendingUpdate(now)
        assertEquals(UpdateState.UpdateFailed("2.0.0", logFile(), setupRan = true), later.updateState.value)
        assertTrue(marker.exists())
    }

    @Test
    fun setupSayingTheUpdateFailedIsShownRightAwayDespiteARecentLog() = runTest {
        writeMarker("2.0.0")
        val now = System.currentTimeMillis()
        logFile().apply { writeText("Setup failed") }.setLastModified(now - 5_000)

        // Started by Setup with --update-not-installed: no waiting for a Setup that has already ended.
        val startedBySetup = manager(currentVersion = "1.0.0", startedAfterFailedUpdate = true)
        startedBySetup.checkPendingUpdate(now)
        assertEquals(UpdateState.UpdateFailed("2.0.0", logFile(), setupRan = true), startedBySetup.updateState.value)

        // Opened by the user meanwhile: Setup may still be installing on close, so nothing is said yet.
        val openedByUser = manager(currentVersion = "1.0.0", startedAfterFailedUpdate = false)
        openedByUser.checkPendingUpdate(now)
        assertEquals(UpdateState.Idle, openedByUser.updateState.value)
    }

    @Test
    fun setupFailureArgumentWithAnInstalledUpdateStillDeletesTheMarker() = runTest {
        writeMarker("2.0.0")
        val manager = manager(currentVersion = "2.0.0", startedAfterFailedUpdate = true)

        manager.runStartupTasks()

        assertEquals(UpdateState.Idle, manager.updateState.value)
        assertFalse(File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME).exists())
    }

    @Test
    fun pendingMarkerIsWrittenBeforeTheLaunch() = runTest {
        val marker = File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME)
        var markerAtLaunch: String? = null
        val manager = manager(launcher = { _, _ -> markerAtLaunch = marker.readText() })
        manager.downloadAndPrepareUpdate(available())

        manager.applyUpdate(manager.updateState.value as UpdateState.ReadyToInstall)

        val written = kotlinx.serialization.json.Json.decodeFromString<PendingUpdateMarker>(markerAtLaunch!!)
        assertEquals(PendingUpdateMarker(version, logFile().absolutePath), written)
    }

    // ---- Download folder and cancel ----

    @Test
    fun downloadGoesToPartFileAndIsRenamedOnlyAfterVerifying() = runTest {
        var sawPartOnly = false
        val fetcher = object : UpdateFetcher {
            override fun fetchBytes(url: String, maxBytes: Long) = signedFiles().getValue(url)
            override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
                assertEquals(partFile().absoluteFile, destination.absoluteFile)
                destination.writeBytes(installerBytes)
                sawPartOnly = !installerFile().exists()
                onProgress(installerBytes.size.toLong(), installerBytes.size.toLong())
            }
        }
        val manager = manager(fetcher = fetcher)

        val result = manager.downloadAndPrepareUpdate(available())

        assertTrue(sawPartOnly)
        assertEquals(installerFile().absoluteFile, result?.absoluteFile)
        assertArrayEquals(installerBytes, installerFile().readBytes())
        assertFalse(partFile().exists())
    }

    @Test
    fun rejectedDownloadLeavesNeitherPartNorInstaller() = runTest {
        val tampered = installerBytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val files = mapOf(installerUrl to tampered, signatureUrl to sign(keyPair.private, installerBytes).toByteArray())
        val manager = manager(fetcher = FakeFetcher(files))

        assertNull(manager.downloadAndPrepareUpdate(available().copy(expectedSha256 = null)))

        assertEquals(UpdateState.Error(UpdateManager.BAD_SIGNATURE_MESSAGE), manager.updateState.value)
        assertFalse(partFile().exists())
        assertFalse(installerFile().exists())
    }

    @Test
    fun blockedDownloadIsCancelledWithinOneSecondAndLeavesNoPartFile() = runBlocking {
        val started = CountDownLatch(1)
        val fetcher = object : UpdateFetcher {
            override fun fetchBytes(url: String, maxBytes: Long) = signedFiles().getValue(url)
            override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
                FileOutputStream(destination).use { out ->
                    out.write(installerBytes, 0, 4096)
                    out.flush()
                    onProgress(4096, installerBytes.size.toLong())
                    started.countDown()
                    // A server that stops sending: this read never returns by itself.
                    CountDownLatch(1).await()
                }
            }
        }
        val manager = manager(fetcher = fetcher)
        val job = launch(Dispatchers.Default) { manager.downloadAndPrepareUpdate(available()) }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(partFile().exists())

        manager.cancelDownload()
        withTimeout(1_000) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(partFile().exists(), "The partial download is deleted")
        assertFalse(installerFile().exists())
        assertEquals(available(), manager.updateState.value)
    }

    @Test
    fun endlessDownloadIsStoppedAtTheNextChunk() = runBlocking {
        val started = CountDownLatch(1)
        val fetcher = object : UpdateFetcher {
            override fun fetchBytes(url: String, maxBytes: Long) = signedFiles().getValue(url)
            override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
                FileOutputStream(destination).use { out ->
                    var written = 0L
                    // Ignores interrupts; only the per-chunk check in onProgress can stop it.
                    while (true) {
                        out.write(installerBytes, 0, 16)
                        written += 16
                        onProgress(written, 0L)
                        started.countDown()
                        Thread.onSpinWait()
                    }
                }
            }
        }
        val manager = manager(fetcher = fetcher)
        val job = launch(Dispatchers.Default) { manager.downloadAndPrepareUpdate(available()) }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        manager.cancelDownload()
        withTimeout(1_000) { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(partFile().exists())
        assertEquals(available(), manager.updateState.value)
    }

    @Test
    fun cleanupDeletesOnlyTheExactPatterns() {
        val dir = updatesDir().apply { mkdirs() }
        val day = 24L * 60 * 60 * 1000
        val now = System.currentTimeMillis()
        fun file(name: String, ageDays: Long = 0) = File(dir, name).apply {
            writeText("x")
            setLastModified(now - ageDays * day)
        }
        file("VaultPass-Setup-v1.2.3.exe")
        file("VaultPass-Setup-v1.2.3.exe.part")
        file("setup-1.2.3.log", ageDays = 31)
        file("setup-1.2.4.log", ageDays = 2)
        file("notes.txt", ageDays = 400)
        file("VaultPass-Setup-v1.2.exe")
        file("VaultPass-Setup-v1.2.3.exe.bak")
        file("vaultpass-setup-v1.2.4.exe")
        file("setup-old.txt", ageDays = 400)
        file(UpdateManager.PENDING_MARKER_FILE_NAME, ageDays = 400)
        File(dir, "VaultPass-Setup-v9.9.9.exe").mkdirs()
        File(dir, "sub").mkdirs()
        File(dir, "sub/VaultPass-Setup-v1.0.0.exe").writeText("x")

        manager().cleanUpdatesFolder(now)

        val left = dir.list()!!.sorted()
        assertEquals(
            listOf(
                "VaultPass-Setup-v1.2.3.exe.bak", "VaultPass-Setup-v1.2.exe", "VaultPass-Setup-v9.9.9.exe",
                "notes.txt", UpdateManager.PENDING_MARKER_FILE_NAME, "setup-1.2.4.log", "setup-old.txt", "sub",
                "vaultpass-setup-v1.2.4.exe"
            ).sorted(),
            left
        )
        assertTrue(File(dir, "sub/VaultPass-Setup-v1.0.0.exe").exists(), "Nothing below the folder is touched")
    }

    @Test
    fun startupTasksOnAMissingFolderCreateNothing() = runTest {
        manager().runStartupTasks()

        assertFalse(updatesDir().exists())
    }

    // ---- Pending marker ----

    private fun writeMarker(markerVersion: String) {
        updatesDir().mkdirs()
        File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME)
            .writeText("""{"version":"$markerVersion","log":"C:\\elsewhere\\setup.log"}""")
    }

    @Test
    fun markerForANewerVersionMeansTheUpdateFailed() = runTest {
        writeMarker("2.0.0")
        val manager = manager(currentVersion = "1.0.0")

        manager.runStartupTasks()

        assertEquals(UpdateState.UpdateFailed("2.0.0", logFile(), setupRan = false), manager.updateState.value)
        assertTrue(File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME).exists(), "Kept until the update installs")
    }

    @Test
    fun markerIsDeletedOnceTheUpdateInstalled() = runTest {
        for (running in listOf("2.0.0", "2.1.0")) {
            writeMarker("2.0.0")
            val manager = manager(currentVersion = running)

            manager.runStartupTasks()

            assertEquals(UpdateState.Idle, manager.updateState.value)
            assertFalse(File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME).exists(), "Running $running")
        }
    }

    @Test
    fun unreadableMarkerIsDeleted() = runTest {
        for (content in listOf("not json", """{"version":"2.0.0/../x","log":""}""")) {
            updatesDir().mkdirs()
            val marker = File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME).apply { writeText(content) }
            val manager = manager()

            manager.runStartupTasks()

            assertEquals(UpdateState.Idle, manager.updateState.value)
            assertFalse(marker.exists())
        }
    }

    @Test
    fun dismissingTheFailedUpdateDeletesTheMarker() = runTest {
        writeMarker("2.0.0")
        val marker = File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME)
        val notes = File(updatesDir(), "notes.txt").apply { writeText("x") }
        val manager = manager(currentVersion = "1.0.0")
        manager.runStartupTasks()
        assertTrue(manager.updateState.value is UpdateState.UpdateFailed)

        manager.dismissUpdateFailed()

        assertEquals(UpdateState.Idle, manager.updateState.value)
        assertFalse(marker.exists())
        assertTrue(notes.exists(), "Only the marker is deleted")
        // On the next start there is nothing left to report.
        val nextStart = manager(currentVersion = "1.0.0")
        nextStart.runStartupTasks()
        assertEquals(UpdateState.Idle, nextStart.updateState.value)
    }

    @Test
    fun dismissingOutsideUpdateFailedChangesNothing() = runTest {
        writeMarker("2.0.0")
        val manager = manager(currentVersion = "1.0.0")
        manager.downloadAndPrepareUpdate(available())
        val ready = manager.updateState.value

        manager.dismissUpdateFailed()

        assertEquals(ready, manager.updateState.value)
        assertTrue(File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME).exists())
    }

    @Test
    fun startupTasksRunOnce() = runTest {
        val manager = manager()
        manager.runStartupTasks()
        writeMarker("2.0.0")

        manager.runStartupTasks()

        assertEquals(UpdateState.Idle, manager.updateState.value)
    }

    // ---- Install on close ----

    @Test
    fun installOnExitNeedsAReadyUpdateAndIsClearedWhenTheStateChanges() = runTest {
        val launcher = RecordingLauncher()
        val manager = manager(launcher = launcher)
        assertFalse(manager.installWhenClosing(), "Nothing is ready yet")
        assertFalse(manager.installOnExit.value)

        manager.downloadAndPrepareUpdate(available())
        assertTrue(manager.installWhenClosing())
        assertTrue(manager.installOnExit.value)

        // A manual check while ready keeps the ready update and the choice.
        val ready = manager.updateState.value
        assertEquals(ready, manager.checkForUpdates())
        assertEquals(ready, manager.updateState.value)
        assertTrue(manager.installOnExit.value)

        // Leaving ReadyToInstall clears it.
        manager.resetState()
        assertFalse(manager.installOnExit.value)
        manager.installOnExitIfRequested()
        assertTrue(launcher.launched.isEmpty())

        manager.downloadAndPrepareUpdate(available())
        manager.installWhenClosing()
        manager.downloadAndPrepareUpdate(available())
        assertFalse(manager.installOnExit.value, "A new download clears it")
    }

    @Test
    fun installOnExitLaunchesWithoutRelaunch() = runTest {
        val launcher = RecordingLauncher()
        val manager = manager(launcher = launcher)
        manager.downloadAndPrepareUpdate(available())

        manager.installOnExitIfRequested()
        assertTrue(launcher.launched.isEmpty(), "Not without Later")

        manager.installWhenClosing()
        manager.installOnExitIfRequested()

        val args = launcher.launched.single()
        assertFalse("/RELAUNCH=1" in args)
        assertTrue("/DIR=${installDir().absolutePath}" in args)
    }

    // ---- Test repository override ----

    @Test
    fun repositoryOverrideAcceptsOnlyOwnerSlashName() {
        for (valid in listOf("me/test-repo", "Some-Org/vault.pass_test", "a/b")) {
            assertEquals(valid, UpdateManager.repositoryOverride(valid), valid)
        }
        val invalid = listOf(
            null, "", "me", "me/", "/repo", "a/b/c", "me/te st", "me/..", "me/.", "me_x/repo", "me/repo\n",
            "https://github.com/a/b", "a/b?x=1", "a/b#c", "../b", "me\\repo"
        )
        for (value in invalid) {
            assertNull(UpdateManager.repositoryOverride(value), "Accepted \"$value\"")
        }
    }

    @Test
    fun systemPropertyChangesTheCheckedRepository() = runTest {
        val previous = System.getProperty(UpdateManager.REPOSITORY_PROPERTY)
        val urls = mutableListOf<String>()
        val source = object : ReleaseSource {
            override fun fetchLatestRelease(url: String): ReleaseApiResponse {
                urls += url
                return ReleaseApiResponse(404, "")
            }
            override fun latestReleaseRedirect(url: String): String? = null
        }
        fun managerWithDefaultRepo() = UpdateManager(downloadDir = updatesDir(), currentVersion = "1.0.0", signingPublicKey = keyPair.public, releaseSource = source)
        try {
            System.setProperty(UpdateManager.REPOSITORY_PROPERTY, "tester/update-test")
            managerWithDefaultRepo().checkForUpdates()
            System.setProperty(UpdateManager.REPOSITORY_PROPERTY, "tester/../../evil")
            managerWithDefaultRepo().checkForUpdates()
            System.clearProperty(UpdateManager.REPOSITORY_PROPERTY)
            managerWithDefaultRepo().checkForUpdates()
        } finally {
            if (previous == null) System.clearProperty(UpdateManager.REPOSITORY_PROPERTY)
            else System.setProperty(UpdateManager.REPOSITORY_PROPERTY, previous)
        }

        assertEquals(
            listOf(
                "https://api.github.com/repos/tester/update-test/releases/latest",
                "https://api.github.com/repos/ArmaanCode2/vault-pass-desktop/releases/latest",
                "https://api.github.com/repos/ArmaanCode2/vault-pass-desktop/releases/latest"
            ),
            urls
        )
    }
}
