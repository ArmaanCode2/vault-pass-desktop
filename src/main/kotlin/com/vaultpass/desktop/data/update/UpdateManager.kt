package com.vaultpass.desktop.data.update

import com.sun.nio.file.ExtendedOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.http.HttpClient
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.PublicKey
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class UpdateManager(
    /** `<data folder>\updates`. Nothing reads or creates it until a download, a launch or [runStartupTasks]. */
    private val downloadDir: File,
    val currentVersion: String = com.vaultpass.desktop.AppVersion.CURRENT,
    private val repoOwner: String = configuredRepository().substringBefore('/'),
    private val repoName: String = configuredRepository().substringAfter('/'),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    /** Trust anchor for installers; null (no embedded key) disables updates. */
    private val signingPublicKey: PublicKey? = UpdateSignatureVerifier.loadEmbeddedPublicKey(),
    private val fetcher: UpdateFetcher = HttpsUpdateFetcher(httpClient, "VaultPass-Desktop/$currentVersion"),
    private val installerLauncher: InstallerLauncher = WindowsInstallerLauncher,
    private val releaseSource: ReleaseSource = HttpsReleaseSource(httpClient, "VaultPass-Desktop/$currentVersion"),
    /** Passed to Setup as /DIR, so the update goes where this copy is installed. */
    private val installDirectory: File = com.vaultpass.desktop.data.platform.AppStorageConfig.appInstallationDirectory,
    /** How long the launch waits for [beforeExit] before it goes ahead anyway. */
    private val beforeExitTimeoutMillis: Long = BEFORE_EXIT_TIMEOUT_MILLIS,
    /** Where Setup registered VaultPass; a copy elsewhere doesn't download or install updates. */
    private val setupRegistration: SetupRegistration = WindowsSetupRegistration,
    /** Setup started this copy with [UPDATE_NOT_INSTALLED_ARGUMENT]: the update it ran has failed. */
    private val startedAfterFailedUpdate: Boolean = false
) {
    private data class VerifiedInstaller(
        val file: File,
        val version: String,
        val signedName: String,
        val signature: String,
        val sha256: String
    )

    @Volatile
    private var verifiedInstaller: VerifiedInstaller? = null

    @Volatile
    private var downloadJob: Job? = null

    private val launching = AtomicBoolean(false)
    private val startupTasksDone = AtomicBoolean(false)

    /** True from the start of [launchInstallerAndExit] until it returns; Main ignores window close meanwhile. */
    val launchInProgress: Boolean get() = launching.get()

    /**
     * Runs once per launch, after the installer passed its locked re-check and before it is started.
     * Main sets it to stop sync networking and lock the session. Failures are logged, not fatal.
     */
    @Volatile
    var beforeExit: (() -> Unit)? = null

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private val _installOnExit = MutableStateFlow(false)

    /** True after [installWhenClosing]; cleared as soon as the state leaves [UpdateState.ReadyToInstall]. */
    val installOnExit: StateFlow<Boolean> = _installOnExit.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun setState(state: UpdateState) {
        _updateState.value = state
        if (state !is UpdateState.ReadyToInstall && _installOnExit.value) {
            _installOnExit.value = false
            // "Later" no longer applies, so its marker would wrongly report a failed update. A launch
            // that fails keeps its marker: that update really didn't install.
            if (!launching.get()) deleteQuietly(File(downloadDir, PENDING_MARKER_FILE_NAME))
        }
    }

    /**
     * Startup housekeeping in the updates folder: removes old installers, partial downloads and
     * old Setup logs, and turns a pending-update marker into [UpdateState.UpdateFailed] when this
     * copy is still older than the update it started. Runs once; call it after the data folder is resolved.
     */
    suspend fun runStartupTasks() = withContext(Dispatchers.IO) {
        if (!startupTasksDone.compareAndSet(false, true)) return@withContext
        cleanUpdatesFolder()
        checkPendingUpdate()
    }

    /**
     * Checks the GitHub Releases API for newer version tags. While a download is running or an
     * update is ready to install, the current state is kept and returned.
     */
    suspend fun checkForUpdates(): UpdateState = withContext(Dispatchers.IO) {
        val current = _updateState.value
        if (current is UpdateState.ReadyToInstall || current is UpdateState.Downloading || current is UpdateState.Verifying) {
            return@withContext current
        }
        if (signingPublicKey == null) {
            val state = UpdateState.Error(UPDATES_DISABLED_MESSAGE)
            setState(state)
            return@withContext state
        }
        setState(UpdateState.Checking)
        try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val response = releaseSource.fetchLatestRelease(url)

            if (response.statusCode == 404) {
                // No releases published yet
                val state = UpdateState.UpToDate(System.currentTimeMillis())
                setState(state)
                return@withContext state
            }

            if (response.statusCode == 403) {
                // Fall back to GitHub web redirect which has no 60-request/hr API rate limit
                return@withContext checkUpdatesViaWebFallback()
            }

            if (response.statusCode != 200) {
                val state = UpdateState.Error("GitHub API returned HTTP ${response.statusCode}")
                setState(state)
                return@withContext state
            }

            val release = json.decodeFromString<GitHubRelease>(response.body)
            val latestVersion = release.tagName.removePrefix("v").trim()

            // isNewerVersion is false for anything but "major.minor.patch", so pre-release tags are no update.
            if (isNewerVersion(currentVersion, latestVersion)) {
                val state = stateForRelease(release, latestVersion)
                setState(state)
                return@withContext state
            } else {
                val state = UpdateState.UpToDate(System.currentTimeMillis())
                setState(state)
                return@withContext state
            }
        } catch (e: Exception) {
            val errorMsg = when {
                e.message?.contains("timed out", ignoreCase = true) == true -> "Network timeout while connecting to GitHub."
                e.message?.contains("UnknownHost", ignoreCase = true) == true -> "Offline: Unable to resolve GitHub.com."
                else -> "Update check failed: ${e.localizedMessage ?: e.javaClass.simpleName}"
            }
            val state = UpdateState.Error(errorMsg)
            setState(state)
            return@withContext state
        }
    }

    /**
     * Fallback update checker that queries https://github.com/{owner}/{repo}/releases/latest.
     * Web requests bypass GitHub's 60 req/hour REST API rate limit.
     */
    internal fun checkUpdatesViaWebFallback(): UpdateState {
        return try {
            val webUrl = "https://github.com/$repoOwner/$repoName/releases/latest"
            val redirectLocation = releaseSource.latestReleaseRedirect(webUrl)
            if (!redirectLocation.isNullOrBlank() && redirectLocation.contains("/tag/")) {
                // e.g. https://github.com/ArmaanCode2/vault-pass-desktop/releases/tag/v1.0.1
                val tag = redirectLocation.substringAfterLast("/tag/").trim()
                val latestVersion = tag.removePrefix("v")
                // isNewerVersion accepts only "major.minor.patch", so the tag in the URLs below is exactly
                // "x.y.z" or "vx.y.z".
                if (isNewerVersion(currentVersion, latestVersion)) {
                    val directDownloadUrl = "https://github.com/$repoOwner/$repoName/releases/download/$tag/${installerFileName(latestVersion)}"
                    val state = UpdateState.Available(
                        version = latestVersion,
                        releaseNotes = "New release $tag published on GitHub.",
                        downloadUrl = directDownloadUrl,
                        fileSize = 0L, // Determined dynamically during download
                        // A missing .sig (404) means the release is unsigned; the download step refuses it.
                        signatureUrl = directDownloadUrl + UpdateSignatureVerifier.SIGNATURE_SUFFIX
                    )
                    setState(state)
                    return state
                } else {
                    val state = UpdateState.UpToDate(System.currentTimeMillis())
                    setState(state)
                    return state
                }
            }
            val state = UpdateState.UpToDate(System.currentTimeMillis())
            setState(state)
            state
        } catch (e: Exception) {
            val err = UpdateState.Error(WEB_FALLBACK_OFFLINE_MESSAGE)
            setState(err)
            err
        }
    }

    /**
     * Downloads the release installer to the updates folder with live progress tracking.
     */
    suspend fun downloadUpdate(update: UpdateState.Available): File? = downloadAndPrepareUpdate(update)

    /**
     * Downloads the release installer and its signature to the updates folder, then verifies the
     * installer against the embedded update signing key (and GitHub's SHA-256 digest when one was
     * published). The installer is written as `<name>.part` and gets its real name only after it
     * verified. Any failure deletes the download and leaves a plain error in [updateState].
     * [cancelDownload] stops it: the state goes back to [available] and the CancellationException
     * is rethrown.
     */
    suspend fun downloadAndPrepareUpdate(available: UpdateState.Available): File? = withContext(Dispatchers.IO) {
        verifiedInstaller = null
        val publicKey = signingPublicKey
        if (publicKey == null) {
            setState(UpdateState.Error(UPDATES_DISABLED_MESSAGE))
            return@withContext null
        }
        if (!isSafeVersion(available.version)) {
            setState(UpdateState.Error(INVALID_VERSION_MESSAGE))
            return@withContext null
        }
        if (!isSetupInstall()) {
            setState(UpdateState.Error(NOT_INSTALLED_BY_SETUP_MESSAGE))
            return@withContext null
        }
        val job = coroutineContext.job
        downloadJob = job
        setState(UpdateState.Downloading(0f, 0L, available.fileSize))
        val expectedName = installerFileName(available.version)
        val installerFile = File(downloadDir, expectedName)
        val partFile = File(downloadDir, expectedName + PART_SUFFIX)
        try {
            Files.createDirectories(downloadDir.toPath())
            cleanUpdatesFolder()

            // The signature is small, so fetch it first: an unsigned release fails before the large download.
            val signature = try {
                runInterruptible { fetcher.fetchBytes(available.signatureUrl, MAX_SIGNATURE_BYTES) }
                    .toString(Charsets.US_ASCII).trim()
            } catch (e: UpdateHttpException) {
                if (e.statusCode == 404) throw UpdateRejectedException(UNSIGNED_RELEASE_MESSAGE)
                throw UpdateRejectedException("Failed to download the update signature (HTTP ${e.statusCode})")
            }
            if (signature.isEmpty()) throw UpdateRejectedException(UNSIGNED_RELEASE_MESSAGE)

            var lastEmittedProgress = 0
            try {
                // Cancelling interrupts this thread, which ends a blocked read.
                runInterruptible {
                    fetcher.downloadToFile(available.downloadUrl, partFile, MAX_INSTALLER_BYTES) { bytesDownloaded, contentLength ->
                        job.ensureActive()
                        val totalBytes = if (available.fileSize > 0) available.fileSize else contentLength
                        val progress = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes.toFloat() else 0f
                        val currentPercent = (progress * 100).toInt()
                        if (currentPercent != lastEmittedProgress) {
                            lastEmittedProgress = currentPercent
                            setState(UpdateState.Downloading(progress.coerceIn(0f, 1f), bytesDownloaded, totalBytes))
                        }
                    }
                }
            } catch (e: UpdateHttpException) {
                throw UpdateRejectedException("Failed to download update (HTTP ${e.statusCode})")
            }
            job.ensureActive()
            setState(UpdateState.Verifying(available.version))

            val actualSha256 = UpdateSignatureVerifier.sha256Hex(partFile)
            val expectedSha256 = available.expectedSha256
            if (expectedSha256 != null && expectedSha256 != actualSha256) {
                throw UpdateRejectedException(CHECKSUM_MISMATCH_MESSAGE)
            }
            // The signature binds the file name the release tag implies, so an older signed installer
            // republished under this version's name does not verify.
            if (!UpdateSignatureVerifier.verify(publicKey, expectedName, actualSha256, signature)) {
                throw UpdateRejectedException(BAD_SIGNATURE_MESSAGE)
            }
            Files.move(partFile.toPath(), installerFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

            verifiedInstaller = VerifiedInstaller(installerFile.absoluteFile, available.version, expectedName, signature, actualSha256)
            setState(UpdateState.ReadyToInstall(installerFile, available.version))
            installerFile
        } catch (e: CancellationException) {
            setState(available)
            throw e
        } catch (e: UpdateRejectedException) {
            deleteQuietly(installerFile)
            setState(UpdateState.Error(e.message ?: BAD_SIGNATURE_MESSAGE))
            null
        } catch (e: Exception) {
            if (!job.isActive) {
                // An interrupted read can surface as an IOException; it is still a cancel.
                setState(available)
                throw CancellationException("Download cancelled").apply { initCause(e) }
            }
            deleteQuietly(installerFile)
            setState(UpdateState.Error("Download failed: ${e.message ?: e.javaClass.simpleName}"))
            null
        } finally {
            // The fetcher has closed its streams by now, so the partial file can be deleted.
            deleteQuietly(partFile)
            if (downloadJob === job) downloadJob = null
        }
    }

    /** Stops a running [downloadAndPrepareUpdate]. Does nothing when no download is running. */
    fun cancelDownload() {
        downloadJob?.cancel(CancellationException("Download cancelled"))
    }

    /**
     * Launches the downloaded installer and terminates the current app process
     * so Inno Setup can update files cleanly.
     */
    fun applyUpdate(state: UpdateState.ReadyToInstall, mode: LaunchMode = LaunchMode.RestartNow) {
        launchInstallerAndExit(state.installerFile, mode)
    }

    /**
     * "Later": the ready update is installed when VaultPass closes. False when no update is ready.
     * The pending-update marker is written now, so a close that never starts the installer (Windows
     * shutting down, the process killed) shows "wasn't installed" on the next start.
     */
    fun installWhenClosing(): Boolean {
        val ready = _updateState.value as? UpdateState.ReadyToInstall ?: return false
        _installOnExit.value = true
        writePendingMarker(ready.version, setupLogFile(ready.version))
        return true
    }

    /**
     * Called while VaultPass closes: starts the ready installer with [LaunchMode.OnExit] when
     * [installOnExit] is set. Returns when there was nothing to install or the launch failed.
     */
    fun installOnExitIfRequested() {
        val ready = _updateState.value as? UpdateState.ReadyToInstall ?: return
        if (!_installOnExit.value) return
        launchInstallerAndExit(ready.installerFile, LaunchMode.OnExit)
    }

    /**
     * Runs [installerFile] only if it is the installer this manager downloaded and verified.
     *
     * On Windows the file is opened with a handle that denies other writers and deletes; its SHA-256 and
     * signature are checked again through that handle, which stays open until the launcher has started
     * the installer. This narrows the window for swapping the file after the check but does not remove
     * it: the file still sits in the updates folder between the download and this call.
     *
     * The check, [beforeExit], the launch and the exit run on one dedicated thread; this call waits for
     * it, so it returns only when the installer wasn't started. Call it off the UI thread.
     */
    fun launchInstallerAndExit(installerFile: File, mode: LaunchMode = LaunchMode.RestartNow) {
        if (!launching.compareAndSet(false, true)) return
        try {
            val worker = Thread({ launchOnThisThread(installerFile, mode) }, LAUNCH_THREAD_NAME)
            // Callers run on daemon threads; this one must keep the JVM alive until it has exited on purpose.
            worker.isDaemon = false
            worker.start()
            worker.join()
        } finally {
            launching.set(false)
        }
    }

    private fun launchOnThisThread(installerFile: File, mode: LaunchMode) {
        var hook = BeforeExitResult.NOT_SET
        try {
            if (!isSetupInstall()) {
                setState(UpdateState.Error(NOT_INSTALLED_BY_SETUP_MESSAGE))
                return
            }
            if (!installerFile.exists()) {
                setState(UpdateState.Error("Installer file not found at: ${installerFile.absolutePath}"))
                return
            }
            val publicKey = signingPublicKey
            if (publicKey == null) {
                setState(UpdateState.Error(UPDATES_DISABLED_MESSAGE))
                return
            }
            val verified = verifiedInstaller
            if (verified == null || verified.file != installerFile.absoluteFile) {
                setState(UpdateState.Error(NOT_VERIFIED_MESSAGE))
                return
            }
            var changed = false
            openInstallerLocked(installerFile.toPath()).use { channel ->
                val currentSha256 = UpdateSignatureVerifier.sha256Hex(channel)
                val unchanged = currentSha256 == verified.sha256 &&
                    UpdateSignatureVerifier.verify(publicKey, verified.signedName, currentSha256, verified.signature)
                if (unchanged) {
                    hook = runBeforeExit()
                    val logFile = setupLogFile(verified.version)
                    writePendingMarker(verified.version, logFile)
                    // Still inside use {}: the handle keeps the file locked while the installer starts.
                    installerLauncher.launchAndExit(installerFile, InstallerArguments.build(mode, installDirectory, logFile))
                } else {
                    changed = true
                }
            }
            // Deleted only after the handle is closed, because the handle denies deletes.
            if (changed) {
                verifiedInstaller = null
                deleteQuietly(installerFile)
                setState(UpdateState.Error(CHANGED_AFTER_CHECK_MESSAGE))
            }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            setState(UpdateState.Error(
                when (hook) {
                    BeforeExitResult.COMPLETED -> "The update couldn't be started: $reason. VaultPass is locked; unlock to continue."
                    // The hook may still be running or have failed, so VaultPass may not be locked.
                    BeforeExitResult.NOT_COMPLETED -> "The update couldn't be started: $reason."
                    BeforeExitResult.NOT_SET -> "Failed to launch installer: ${e.message}"
                }
            ))
        }
    }

    private enum class BeforeExitResult { NOT_SET, COMPLETED, NOT_COMPLETED }

    /**
     * Runs [beforeExit] if one is set, waiting at most [beforeExitTimeoutMillis]. COMPLETED only when it
     * returned in time without throwing. A hook that takes longer is left running: the database is in
     * WAL mode, so closing it is best effort, and the update goes ahead.
     */
    private fun runBeforeExit(): BeforeExitResult {
        val hook = beforeExit ?: return BeforeExitResult.NOT_SET
        val completed = AtomicBoolean(false)
        val worker = Thread({
            try {
                hook()
                completed.set(true)
            } catch (e: Exception) {
                System.err.println("[UpdateManager] Preparing to exit for the update failed: ${e.message}")
                e.printStackTrace()
            }
        }, BEFORE_EXIT_THREAD_NAME)
        worker.isDaemon = true
        worker.start()
        worker.join(beforeExitTimeoutMillis)
        if (worker.isAlive) {
            System.err.println(
                "[UpdateManager] Preparing to exit for the update took longer than ${beforeExitTimeoutMillis} ms; starting the update anyway."
            )
            return BeforeExitResult.NOT_COMPLETED
        }
        return if (completed.get()) BeforeExitResult.COMPLETED else BeforeExitResult.NOT_COMPLETED
    }

    /** True when Setup registered this copy's folder as VaultPass's install folder. */
    private fun isSetupInstall(): Boolean {
        val registered = try { setupRegistration.installedAppPath() } catch (_: Exception) { null }
        return SetupRegistration.isSetupInstall(registered, installDirectory)
    }

    private fun setupLogFile(version: String): File = File(downloadDir, "setup-$version.log").absoluteFile

    private fun writePendingMarker(version: String, logFile: File) {
        try {
            Files.createDirectories(downloadDir.toPath())
            File(downloadDir, PENDING_MARKER_FILE_NAME)
                .writeText(json.encodeToString(PendingUpdateMarker(version, logFile.absolutePath)))
        } catch (e: Exception) {
            System.err.println("[UpdateManager] Couldn't write the pending-update marker: ${e.message}")
        }
    }

    /**
     * Reads `pending-update.json`. If this copy is still older than the version it names, the
     * update didn't install; otherwise (or if it is unreadable) the marker is deleted.
     */
    internal fun checkPendingUpdate(now: Long = System.currentTimeMillis()) {
        val marker = File(downloadDir, PENDING_MARKER_FILE_NAME)
        if (!Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val pending = try {
            json.decodeFromString<PendingUpdateMarker>(marker.readText())
        } catch (_: Exception) {
            null
        }
        if (pending != null && isSafeVersion(pending.version) && isNewerVersion(currentVersion, pending.version)) {
            // The log path is rebuilt from the version rather than taken from the file.
            val logFile = setupLogFile(pending.version)
            val setupRan = Files.isRegularFile(logFile.toPath(), LinkOption.NOFOLLOW_LINKS)
            // Setup wrote its log moments ago: it may still be running (VaultPass reopened during an
            // install on close). Ask again on the next start. Setup itself says when it has failed.
            if (!startedAfterFailedUpdate) {
                val logAge = now - logFile.lastModified()
                if (setupRan && logAge >= 0 && logAge < SETUP_RUNNING_WINDOW_MILLIS) return
            }
            setState(UpdateState.UpdateFailed(pending.version, logFile, setupRan))
        } else {
            deleteQuietly(marker)
        }
    }

    /**
     * The user dismissed "didn't install": deletes the pending-update marker so it isn't shown again,
     * and goes back to [UpdateState.Idle]. Does nothing in any other state.
     */
    fun dismissUpdateFailed() {
        if (_updateState.value !is UpdateState.UpdateFailed) return
        val marker = File(downloadDir, PENDING_MARKER_FILE_NAME)
        if (Files.isRegularFile(marker.toPath(), LinkOption.NOFOLLOW_LINKS)) deleteQuietly(marker)
        setState(UpdateState.Idle)
    }

    /**
     * Deletes, directly in the updates folder, regular files named `VaultPass-Setup-v<x.y.z>.exe`,
     * `VaultPass-Setup-v<x.y.z>.exe.part`, and `setup-*.log` older than 30 days. Links, folders and
     * any other file are left alone.
     */
    internal fun cleanUpdatesFolder(now: Long = System.currentTimeMillis()) {
        val dir = downloadDir.toPath()
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.newDirectoryStream(dir).use { entries ->
                for (entry in entries) {
                    val name = entry.fileName.toString()
                    val attributes = try {
                        Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    } catch (_: Exception) {
                        continue
                    }
                    if (!attributes.isRegularFile) continue
                    val remove = INSTALLER_FILE.matches(name) || INSTALLER_PART_FILE.matches(name) ||
                        (SETUP_LOG_FILE.matches(name) && attributes.lastModifiedTime().toMillis() < now - LOG_RETENTION_MILLIS)
                    if (remove) {
                        try { Files.deleteIfExists(entry) } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[UpdateManager] Couldn't clean the updates folder: ${e.message}")
        }
    }

    /**
     * Turns a newer GitHub release into [UpdateState.Available], or an [UpdateState.Error] when the
     * release has no installer or no signature for it.
     */
    internal fun stateForRelease(release: GitHubRelease, latestVersion: String): UpdateState {
        if (!isSafeVersion(latestVersion)) return UpdateState.Error(INVALID_VERSION_MESSAGE)
        val installerAsset = selectInstallerAsset(release.assets, latestVersion)
            ?: return UpdateState.Error("Release v$latestVersion found, but no Windows installer was attached.")
        val signatureAsset = release.assets.firstOrNull {
            it.name == installerAsset.name + UpdateSignatureVerifier.SIGNATURE_SUFFIX
        } ?: return UpdateState.Error(UNSIGNED_RELEASE_MESSAGE)
        return UpdateState.Available(
            version = latestVersion,
            releaseNotes = release.releaseNotes ?: "No release notes provided.",
            downloadUrl = installerAsset.downloadUrl,
            fileSize = installerAsset.size,
            signatureUrl = signatureAsset.downloadUrl,
            expectedSha256 = parseSha256Digest(installerAsset.digest)
        )
    }

    private fun deleteQuietly(file: File) {
        try { file.delete() } catch (_: Exception) {}
    }

    fun resetState() {
        setState(UpdateState.Idle)
    }

    companion object {
        const val UPDATES_DISABLED_MESSAGE = "Updates are disabled: this build has no update signing key"
        const val UNSIGNED_RELEASE_MESSAGE = "This release isn't signed, so it can't be installed safely."
        const val BAD_SIGNATURE_MESSAGE =
            "The downloaded update failed its signature check and was deleted. It may have been tampered with."
        const val CHECKSUM_MISMATCH_MESSAGE =
            "The downloaded update doesn't match the checksum GitHub published, so it was deleted."
        const val CHANGED_AFTER_CHECK_MESSAGE =
            "The downloaded update changed after it was checked, so it wasn't installed. Download it again."
        const val NOT_VERIFIED_MESSAGE = "This installer wasn't downloaded and checked by VaultPass, so it won't be run."
        const val INVALID_VERSION_MESSAGE = "The latest release has an invalid version number."
        const val NOT_INSTALLED_BY_SETUP_MESSAGE =
            "This copy of VaultPass wasn't installed by its Setup program, so it can't update itself. " +
                "Download the installer from GitHub instead."

        /** Setup starts the old VaultPass with this argument when an update failed. */
        const val UPDATE_NOT_INSTALLED_ARGUMENT = "--update-not-installed"
        const val WEB_FALLBACK_OFFLINE_MESSAGE = "Offline or unable to connect to GitHub."

        /** Installers larger than this are refused (500 MB). */
        const val MAX_INSTALLER_BYTES: Long = 500L * 1024 * 1024
        const val MAX_SIGNATURE_BYTES: Long = 4L * 1024

        const val PART_SUFFIX = ".part"
        const val PENDING_MARKER_FILE_NAME = "pending-update.json"
        const val LAUNCH_THREAD_NAME = "VaultPass-update-launch"
        const val BEFORE_EXIT_THREAD_NAME = "VaultPass-update-before-exit"
        const val BEFORE_EXIT_TIMEOUT_MILLIS = 5_000L

        /** A Setup log newer than this means Setup may still be running; no failure is reported yet. */
        const val SETUP_RUNNING_WINDOW_MILLIS = 2L * 60 * 1000

        const val DEFAULT_REPOSITORY = "ArmaanCode2/vault-pass-desktop"

        /** `-Dvaultpass.update.repo=owner/name` checks another GitHub repository, for testing a release before publishing it. */
        const val REPOSITORY_PROPERTY = "vaultpass.update.repo"

        private val REPOSITORY_OVERRIDE = Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$")

        private const val LOG_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        private val INSTALLER_FILE = Regex("^VaultPass-Setup-v[0-9]+\\.[0-9]+\\.[0-9]+\\.exe$")
        private val INSTALLER_PART_FILE = Regex("^VaultPass-Setup-v[0-9]+\\.[0-9]+\\.[0-9]+\\.exe\\.part$")
        private val SETUP_LOG_FILE = Regex("^setup-.*\\.log$")

        /** Release versions are exactly "major.minor.patch" in ASCII digits; anything else is never an update. */
        private val SAFE_VERSION = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        private val IS_WINDOWS = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        fun installerFileName(version: String): String = "VaultPass-Setup-v$version.exe"

        internal fun isSafeVersion(version: String): Boolean = SAFE_VERSION.matches(version)

        /** The "owner/name" in [value] when it is a valid repository override, else null. */
        internal fun repositoryOverride(value: String?): String? {
            if (value == null || !REPOSITORY_OVERRIDE.matches(value)) return null
            val name = value.substringAfter('/')
            if (name == "." || name == "..") return null
            return value
        }

        /** [REPOSITORY_PROPERTY] when it is set and valid, else [DEFAULT_REPOSITORY]. */
        fun configuredRepository(): String =
            repositoryOverride(System.getProperty(REPOSITORY_PROPERTY)) ?: DEFAULT_REPOSITORY

        /**
         * Picks the asset named exactly "VaultPass-Setup-v<version>.exe", the name the signature covers.
         * There is no fallback to another .exe.
         */
        internal fun selectInstallerAsset(assets: List<GitHubAsset>, version: String): GitHubAsset? {
            val expectedName = installerFileName(version)
            return assets.firstOrNull { it.name == expectedName }
        }

        /**
         * Opens [path] for reading. On Windows the handle denies other processes write and delete access
         * for as long as it is open; elsewhere it is a plain read.
         */
        internal fun openInstallerLocked(path: Path): SeekableByteChannel =
            if (IS_WINDOWS) {
                Files.newByteChannel(path, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)
            } else {
                Files.newByteChannel(path, StandardOpenOption.READ)
            }

        /**
         * Returns the lower-case hex of a GitHub "sha256:<hex>" digest. Other algorithms give null;
         * a malformed sha256 value gives a string no file hash can equal, so the download is refused.
         */
        internal fun parseSha256Digest(digest: String?): String? {
            if (digest == null || !digest.startsWith("sha256:", ignoreCase = true)) return null
            val hex = digest.substringAfter(':').trim().lowercase()
            return if (SHA256_HEX.matches(hex)) hex else "invalid:$hex"
        }

        /**
         * Returns true only if both versions are "major.minor.patch" in ASCII digits and [latest] is
         * numerically greater. Anything else, including pre-release tags and a "v" prefix, is no update.
         */
        fun isNewerVersion(current: String, latest: String): Boolean {
            if (!isSafeVersion(current) || !isSafeVersion(latest)) return false
            val currParts = current.split(".").map { it.toBigInteger() }
            val latestParts = latest.split(".").map { it.toBigInteger() }
            for (i in 0 until 3) {
                if (latestParts[i] > currParts[i]) return true
                if (latestParts[i] < currParts[i]) return false
            }
            return false
        }
    }
}
