package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.data.update.InstallerLauncher
import com.vaultpass.desktop.data.update.ReleaseApiResponse
import com.vaultpass.desktop.data.update.ReleaseSource
import com.vaultpass.desktop.data.update.UpdateFetcher
import com.vaultpass.desktop.data.update.UpdateHttpException
import com.vaultpass.desktop.data.update.UpdateManager
import com.vaultpass.desktop.data.update.UpdateSignatureVerifier
import com.vaultpass.desktop.data.update.UpdateState
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.models.AppSettings
import com.vaultpass.desktop.domain.platform.FilePicker
import com.vaultpass.synccore.SyncSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The update banner, the dialog's Later / Restart now / countdown, and what blocks Restart now.
 * Everything runs against fake release, download and launch fakes; nothing is downloaded or started.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelUpdateTest {

    @TempDir
    lateinit var tempDir: File

    private val version = "2.0.0"
    private val installerName = "VaultPass-Setup-v2.0.0.exe"
    private val installerUrl = "https://example.test/download/v2.0.0/$installerName"
    private val signatureUrl = "$installerUrl.sig"
    private val installerBytes = ByteArray(50_000) { (it * 7 % 251).toByte() }
    private val keyPair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun updatesDir() = File(tempDir, "data/updates")

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun signature(): String {
        val signer = Signature.getInstance(UpdateSignatureVerifier.SIGNATURE_ALGORITHM)
        signer.initSign(keyPair.private)
        signer.update(UpdateSignatureVerifier.signedMessage(installerName, sha256Hex(installerBytes)))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private val releaseJson = """
        {"tag_name":"v$version","body":"Notes","assets":[
          {"name":"$installerName","browser_download_url":"$installerUrl","size":${installerBytes.size}},
          {"name":"$installerName.sig","browser_download_url":"$signatureUrl","size":100}
        ]}
    """.trimIndent()

    private val releaseSource = object : ReleaseSource {
        override fun fetchLatestRelease(url: String) = ReleaseApiResponse(200, releaseJson)
        override fun latestReleaseRedirect(url: String): String? = null
    }

    private inner class FakeFetcher : UpdateFetcher {
        private val files = mapOf(installerUrl to installerBytes, signatureUrl to signature().toByteArray())
        override fun fetchBytes(url: String, maxBytes: Long): ByteArray = files[url] ?: throw UpdateHttpException(404)
        override fun downloadToFile(url: String, destination: File, maxBytes: Long, onProgress: (Long, Long) -> Unit) {
            val bytes = files[url] ?: throw UpdateHttpException(404)
            destination.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private class RecordingLauncher : InstallerLauncher {
        val launched = mutableListOf<List<String>>()
        override fun launchAndExit(installerFile: File, arguments: List<String>) {
            launched += arguments
        }
    }

    private class FakeSettingsRepository(var settings: AppSettings) : AppSettingsRepository {
        override suspend fun getSettings(): AppSettings = settings
        override suspend fun updateSettings(settings: AppSettings) {
            this.settings = settings
        }
    }

    private class FakeFilePicker : FilePicker {
        override suspend fun showOpenFileDialog(title: String, allowedExtensions: List<String>): File? = null
        override suspend fun showSaveFileDialog(title: String, defaultFileName: String, allowedExtensions: List<String>): File? = null
        override suspend fun showFolderPickerDialog(title: String): File? = null
    }

    private fun TestScope.viewModel(
        checkOnOpen: Boolean = true,
        launcher: RecordingLauncher = RecordingLauncher(),
        currentVersion: String = "1.0.0",
        source: ReleaseSource = releaseSource
    ): SettingsViewModel {
        val manager = UpdateManager(
            downloadDir = updatesDir(),
            currentVersion = currentVersion,
            repoOwner = "o",
            repoName = "r",
            signingPublicKey = keyPair.public,
            fetcher = FakeFetcher(),
            installerLauncher = launcher,
            releaseSource = source,
            installDirectory = File(tempDir, "install"),
            setupRegistration = { File(tempDir, "install").absolutePath }
        )
        return SettingsViewModel(
            repository = FakeSettingsRepository(AppSettings(checkForUpdatesOnOpen = checkOnOpen)),
            filePicker = FakeFilePicker(),
            providedUpdateManager = manager,
            coroutineScope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        ).also {
            // The window collects the banner from the start; so does the test.
            it.updateBanner
        }
    }

    /** Runs the view model's coroutines (they live in backgroundScope, which advanceUntilIdle skips). */
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private fun available() = UpdateState.Available(
        version = version,
        releaseNotes = "",
        downloadUrl = installerUrl,
        fileSize = installerBytes.size.toLong(),
        signatureUrl = signatureUrl
    )

    /** Downloads through the view model until the update is ready, as the Update button does. */
    private suspend fun TestScope.downloadToReady(vm: SettingsViewModel): UpdateState.ReadyToInstall {
        vm.downloadUpdate(available()).join()
        runCurrent()
        return vm.updateState.value as UpdateState.ReadyToInstall
    }

    // ---- Banner ----

    @Test
    fun bannerShowsWhenTheStartupCheckFoundAnUpdate() = runTest {
        val vm = viewModel(checkOnOpen = true)

        vm.checkUpdatesOnStartupIfEnabled().join()
        runCurrent()

        val banner = vm.updateBanner.value
        assertTrue(banner is UpdateBanner.Available, "Got $banner")
        assertEquals(version, (banner as UpdateBanner.Available).update.version)
        assertFalse(vm.isUpdateDialogOpen.value, "The startup check opens no dialog")
    }

    @Test
    fun noBannerWhenTheUpdateWasFoundByAManualCheck() = runTest {
        val vm = viewModel(checkOnOpen = false)
        vm.checkUpdatesOnStartupIfEnabled().join()
        runCurrent()
        assertEquals(UpdateState.Idle, vm.updateState.value, "Startup check is off")

        vm.updateManager.checkForUpdates()
        runCurrent()

        assertTrue(vm.updateState.value is UpdateState.Available)
        assertNull(vm.updateBanner.value)
    }

    @Test
    fun notNowHidesTheBannerForTheSession() = runTest {
        val vm = viewModel()
        vm.checkUpdatesOnStartupIfEnabled().join()
        runCurrent()
        assertNotNull(vm.updateBanner.value)

        vm.dismissUpdateBanner()
        runCurrent()
        assertNull(vm.updateBanner.value)

        // Another check finding the same version doesn't bring it back.
        vm.updateManager.checkForUpdates()
        runCurrent()
        assertTrue(vm.updateState.value is UpdateState.Available)
        assertNull(vm.updateBanner.value)
    }

    @Test
    fun updateOpensTheDialogAndCancelClosesIt() = runTest {
        val vm = viewModel()
        vm.checkUpdatesOnStartupIfEnabled().join()
        runCurrent()

        vm.downloadUpdate(available())
        assertTrue(vm.isUpdateDialogOpen.value)
        assertEquals(version, vm.downloadingVersion.value)

        vm.cancelDownload()
        assertFalse(vm.isUpdateDialogOpen.value)
    }

    @Test
    fun laterSetsInstallOnExitAndShowsTheInstallOnCloseBanner() = runTest {
        val vm = viewModel()
        val ready = downloadToReady(vm)
        assertTrue(vm.isUpdateDialogOpen.value)
        assertNull(vm.updateBanner.value, "Ready but no choice yet: the dialog asks")

        vm.installLater()
        runCurrent()

        assertTrue(vm.updateManager.installOnExit.value)
        assertFalse(vm.isUpdateDialogOpen.value)
        assertEquals(UpdateBanner.InstallOnClose(ready), vm.updateBanner.value)
    }

    @Test
    fun failedUpdateBannerIsDismissedForGood() = runTest {
        updatesDir().mkdirs()
        val marker = File(updatesDir(), UpdateManager.PENDING_MARKER_FILE_NAME)
        marker.writeText("""{"version":"$version","log":"x"}""")
        val vm = viewModel(currentVersion = "1.0.0")
        vm.checkUpdatesOnStartupIfEnabled().join()
        runCurrent()
        assertTrue(vm.updateBanner.value is UpdateBanner.Failed)

        vm.dismissUpdateFailed()
        runCurrent()

        assertNull(vm.updateBanner.value)
        assertEquals(UpdateState.Idle, vm.updateState.value)
        assertFalse(marker.exists())
    }

    @Test
    fun closingTheDialogDuringTryAgainCancelsTheCheck() = runTest {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slowSource = object : ReleaseSource {
            override fun fetchLatestRelease(url: String): ReleaseApiResponse {
                entered.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return ReleaseApiResponse(200, releaseJson)
            }
            override fun latestReleaseRedirect(url: String): String? = null
        }
        val vm = viewModel(source = slowSource)

        val job = vm.tryUpdateAgain()
        runCurrent()
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(vm.isUpdateDialogOpen.value)

        vm.closeUpdateDialog()
        release.countDown()
        job.join()
        settle()

        assertTrue(job.isCancelled)
        assertFalse(vm.isUpdateDialogOpen.value, "The dialog doesn't reopen")
        assertNull(vm.downloadingVersion.value)
        assertFalse(updatesDir().resolve(installerName).exists(), "Nothing was downloaded")
    }

    // ---- Restart now and the countdown ----

    @Test
    fun restartNowCountsDownThenStartsTheInstallerWithRelaunch() = runTest {
        val launcher = RecordingLauncher()
        val vm = viewModel(launcher = launcher)
        downloadToReady(vm)

        vm.restartNow()
        runCurrent()
        assertEquals(2, vm.restartCountdown.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, vm.restartCountdown.value)
        assertTrue(launcher.launched.isEmpty())

        settle()

        assertTrue("/RELAUNCH=1" in launcher.launched.single())
        assertNull(vm.restartCountdown.value)
    }

    @Test
    fun cancelDuringTheCountdownReturnsToTheReadyDialog() = runTest {
        val launcher = RecordingLauncher()
        val vm = viewModel(launcher = launcher)
        downloadToReady(vm)

        vm.restartNow()
        runCurrent()
        vm.cancelRestart()
        settle()

        assertNull(vm.restartCountdown.value)
        assertTrue(vm.isUpdateDialogOpen.value)
        assertTrue(vm.updateState.value is UpdateState.ReadyToInstall)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun restartNowDoesNothingWhileBlocked() = runTest {
        val launcher = RecordingLauncher()
        val vm = viewModel(launcher = launcher)
        downloadToReady(vm)
        vm.setUpdateBlockedReason(UpdateBlockers.BACKUP)

        vm.restartNow()
        settle()

        assertNull(vm.restartCountdown.value)
        assertTrue(vm.isUpdateDialogOpen.value, "The dialog shows why")
        assertTrue(launcher.launched.isEmpty())

        vm.setUpdateBlockedReason(null)
        vm.restartNow()
        runCurrent()
        assertEquals(2, vm.restartCountdown.value)
    }

    @Test
    fun countdownStopsWhenSomethingBlocksIt() = runTest {
        val launcher = RecordingLauncher()
        val vm = viewModel(launcher = launcher)
        downloadToReady(vm)

        vm.restartNow()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, vm.restartCountdown.value)

        vm.setUpdateBlockedReason(UpdateBlockers.SYNC)
        settle()

        assertNull(vm.restartCountdown.value)
        assertTrue(vm.isUpdateDialogOpen.value, "Back to the ready dialog")
        assertEquals(UpdateBlockers.SYNC, vm.updateBlockedReason.value)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun restartNowAfterLaterStillCountsDown() = runTest {
        val launcher = RecordingLauncher()
        val vm = viewModel(launcher = launcher)
        downloadToReady(vm)
        vm.installLater()

        vm.restartNow()
        runCurrent()

        assertTrue(vm.isUpdateDialogOpen.value)
        assertEquals(2, vm.restartCountdown.value)
        settle()
        assertEquals(1, launcher.launched.size)
    }

    // ---- Blocked reasons ----

    @Test
    fun nothingBlocksWhenAllIsIdle() {
        val syncBusy = UpdateBlockers.isSyncBusy(SyncSessionState.IDLE, false, false, false)
        assertFalse(syncBusy)
        assertNull(UpdateBlockers.reason(syncBusy = false, backupBusy = false, entryDialogOpen = false))
    }

    @Test
    fun aRunningSyncBlocks() {
        val idle = setOf(SyncSessionState.IDLE, SyncSessionState.DONE, SyncSessionState.FAILED)
        for (state in SyncSessionState.entries) {
            assertEquals(state !in idle, UpdateBlockers.isSyncBusy(state, false, false, false), "$state")
        }
        assertTrue(UpdateBlockers.isSyncBusy(SyncSessionState.IDLE, isSyncReviewOpen = true, isAwaitingPeerReview = false, hasPendingPlanApproval = false))
        assertTrue(UpdateBlockers.isSyncBusy(SyncSessionState.IDLE, isSyncReviewOpen = false, isAwaitingPeerReview = true, hasPendingPlanApproval = false))
        assertTrue(UpdateBlockers.isSyncBusy(SyncSessionState.IDLE, isSyncReviewOpen = false, isAwaitingPeerReview = false, hasPendingPlanApproval = true))
    }

    @Test
    fun eachBlockerHasItsOwnReasonAndSyncComesFirst() {
        assertEquals("Finish the sync first.", UpdateBlockers.reason(syncBusy = true, backupBusy = false, entryDialogOpen = false))
        assertEquals("Wait for the backup, import or export to finish.", UpdateBlockers.reason(syncBusy = false, backupBusy = true, entryDialogOpen = false))
        assertEquals("Close the entry you're editing first.", UpdateBlockers.reason(syncBusy = false, backupBusy = false, entryDialogOpen = true))
        assertEquals(UpdateBlockers.SYNC, UpdateBlockers.reason(syncBusy = true, backupBusy = true, entryDialogOpen = true))
        assertEquals(UpdateBlockers.BACKUP, UpdateBlockers.reason(syncBusy = false, backupBusy = true, entryDialogOpen = true))
    }

    @Test
    fun updateDialogSaysHowToGetPastAnOpenEntry() {
        assertEquals(
            "Choose Later, close the entry you're editing, then select Restart now on the banner.",
            UpdateBlockers.dialogText(UpdateBlockers.ENTRY)
        )
        assertEquals(UpdateBlockers.SYNC, UpdateBlockers.dialogText(UpdateBlockers.SYNC))
        assertNull(UpdateBlockers.dialogText(null))
    }
}
