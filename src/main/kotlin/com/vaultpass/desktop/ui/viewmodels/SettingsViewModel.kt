package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.models.AppSettings
import com.vaultpass.desktop.domain.session.SessionManager
import com.vaultpass.desktop.data.platform.AppStorageConfig
import com.vaultpass.desktop.data.update.LaunchMode
import com.vaultpass.desktop.data.update.UpdateManager
import com.vaultpass.desktop.data.update.UpdateState
import com.vaultpass.synccore.SyncSessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vaultpass.desktop.domain.platform.FilePicker
import java.io.File
import java.awt.Desktop

/** What the banner under the top bar shows; null shows nothing. */
sealed interface UpdateBanner {
    /** The check when VaultPass opened found a newer version. */
    data class Available(val update: UpdateState.Available) : UpdateBanner
    /** "Later" was chosen: the ready update installs when VaultPass closes. */
    data class InstallOnClose(val ready: UpdateState.ReadyToInstall) : UpdateBanner
    /** An update started earlier didn't install. */
    data class Failed(val failed: UpdateState.UpdateFailed) : UpdateBanner
}

/**
 * "didn't install" when Setup ran and failed (it left a log behind Show details); "wasn't installed"
 * when Setup never ran, for example after Later when Windows shut down.
 */
fun updateFailedText(failed: UpdateState.UpdateFailed): String =
    if (failed.setupRan) "The update to ${failed.version} didn't install."
    else "The update to ${failed.version} wasn't installed."

/** Why "Restart now" is not allowed right now. Checked in this order; the first that applies wins. */
object UpdateBlockers {
    const val SYNC = "Finish the sync first."
    const val BACKUP = "Wait for the backup, import or export to finish."
    const val ENTRY = "Close the entry you're editing first."

    /** The update dialog sits over the entry dialog, so it says how to get there. */
    const val ENTRY_FROM_UPDATE_DIALOG = "Choose Later, close the entry you're editing, then select Restart now on the banner."

    /** [reason] as the update dialog shows it. */
    fun dialogText(reason: String?): String? = if (reason == ENTRY) ENTRY_FROM_UPDATE_DIALOG else reason

    /** True while a sync is running or waiting for either user. */
    fun isSyncBusy(
        sessionState: SyncSessionState,
        isSyncReviewOpen: Boolean,
        isAwaitingPeerReview: Boolean,
        hasPendingPlanApproval: Boolean
    ): Boolean {
        val sessionLive = when (sessionState) {
            SyncSessionState.IDLE, SyncSessionState.DONE, SyncSessionState.FAILED -> false
            else -> true
        }
        return sessionLive || isSyncReviewOpen || isAwaitingPeerReview || hasPendingPlanApproval
    }

    fun reason(syncBusy: Boolean, backupBusy: Boolean, entryDialogOpen: Boolean): String? = when {
        syncBusy -> SYNC
        backupBusy -> BACKUP
        entryDialogOpen -> ENTRY
        else -> null
    }
}

class SettingsViewModel(
    private val repository: AppSettingsRepository,
    private val filePicker: FilePicker,
    private var sessionManager: SessionManager? = null,
    providedUpdateManager: UpdateManager? = null,
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val viewModelScope = coroutineScope ?: CoroutineScope(Dispatchers.Main)

    /** Built on first use when not passed in, so the data folder is read only after startup resolved it. */
    val updateManager: UpdateManager by lazy {
        providedUpdateManager ?: UpdateManager(downloadDir = File(AppStorageConfig.dataDirectory, "updates"))
    }

    private val _settingsState = MutableStateFlow(AppSettings())
    val settingsState: StateFlow<AppSettings> = _settingsState.asStateFlow()

    val updateState: StateFlow<UpdateState> get() = updateManager.updateState

    val installOnExit: StateFlow<Boolean> get() = updateManager.installOnExit

    // The version the check at startup found; only that offer gets the banner.
    private val startupUpdateVersion = MutableStateFlow<String?>(null)

    // "Not now": hidden until VaultPass is started again.
    private val bannerDismissed = MutableStateFlow(false)

    private val _isUpdateDialogOpen = MutableStateFlow(false)
    val isUpdateDialogOpen: StateFlow<Boolean> = _isUpdateDialogOpen.asStateFlow()

    // Downloading has no version of its own, so the one being downloaded is kept here.
    private val _downloadingVersion = MutableStateFlow<String?>(null)
    val downloadingVersion: StateFlow<String?> = _downloadingVersion.asStateFlow()

    /** Seconds left before "Restart now" starts the installer; 0 while it starts; null when no countdown runs. */
    private val _restartCountdown = MutableStateFlow<Int?>(null)
    val restartCountdown: StateFlow<Int?> = _restartCountdown.asStateFlow()

    private val _updateBlockedReason = MutableStateFlow<String?>(null)
    val updateBlockedReason: StateFlow<String?> = _updateBlockedReason.asStateFlow()

    private var restartJob: Job? = null

    val updateBanner: StateFlow<UpdateBanner?> by lazy {
        combine(
            updateManager.updateState,
            updateManager.installOnExit,
            startupUpdateVersion,
            bannerDismissed
        ) { state, onExit, startupVersion, dismissed ->
            bannerFor(state, onExit, startupVersion, dismissed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    init {
        refreshSettings()
    }

    fun attachSessionManager(sessionManager: SessionManager) {
        this.sessionManager = sessionManager
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateManager.checkForUpdates()
        }
    }

    /** "Update": opens the download dialog and starts the download. */
    fun downloadUpdate(available: UpdateState.Available): Job {
        _isUpdateDialogOpen.value = true
        _downloadingVersion.value = available.version
        return viewModelScope.launch {
            try {
                updateManager.downloadAndPrepareUpdate(available)
            } finally {
                if (_downloadingVersion.value == available.version) _downloadingVersion.value = null
            }
        }
    }

    /** Cancel in the download dialog: stops the download and closes the dialog. */
    fun cancelDownload() {
        _isUpdateDialogOpen.value = false
        updateManager.cancelDownload()
    }

    /** "Later": the ready update installs when VaultPass closes. */
    fun installLater() {
        cancelRestart()
        updateManager.installWhenClosing()
        _isUpdateDialogOpen.value = false
    }

    /**
     * "Restart now": opens the ready dialog and counts down, then starts the installer. Does nothing
     * but show the dialog while [updateBlockedReason] is set.
     */
    fun restartNow() {
        if (updateManager.updateState.value !is UpdateState.ReadyToInstall) return
        _isUpdateDialogOpen.value = true
        if (_updateBlockedReason.value != null || restartJob?.isActive == true) return
        restartJob = viewModelScope.launch {
            for (seconds in RESTART_COUNTDOWN_SECONDS downTo 1) {
                _restartCountdown.value = seconds
                delay(1_000)
            }
            val ready = updateManager.updateState.value as? UpdateState.ReadyToInstall
            if (ready == null || _updateBlockedReason.value != null) {
                _restartCountdown.value = null
                return@launch
            }
            _restartCountdown.value = 0
            // Returns only if the installer couldn't be started; the state then says why.
            withContext(ioDispatcher) {
                updateManager.applyUpdate(ready, LaunchMode.RestartNow)
            }
            _restartCountdown.value = null
        }
    }

    /** Cancel during the countdown: back to the ready dialog. Too late once the installer is starting. */
    fun cancelRestart() {
        val countdown = _restartCountdown.value ?: return
        if (countdown <= 0) return
        restartJob?.cancel()
        restartJob = null
        _restartCountdown.value = null
    }

    /** Set by Main from the sync, backup and entry-dialog state. A reason stops a running countdown. */
    fun setUpdateBlockedReason(reason: String?) {
        _updateBlockedReason.value = reason
        if (reason != null) cancelRestart()
    }

    /** "Not now" on the banner: hidden for the rest of this session. */
    fun dismissUpdateBanner() {
        bannerDismissed.value = true
    }

    // The check started by "Try again"; closing the dialog while it runs cancels it.
    private var tryAgainCheckJob: Job? = null

    /** "Try again" after a failed update or a failed download: checks again and downloads what it finds. */
    fun tryUpdateAgain(): Job {
        _isUpdateDialogOpen.value = true
        tryAgainCheckJob?.cancel()
        val job = viewModelScope.launch {
            val state = updateManager.checkForUpdates()
            if (state is UpdateState.Available) {
                downloadUpdate(state).join()
            }
        }
        tryAgainCheckJob = job
        return job
    }

    /** Hides "didn't install" for good: the marker that brings it back is deleted. */
    fun dismissUpdateFailed() {
        updateManager.dismissUpdateFailed()
    }

    /** "Show details": opens the folder that holds Setup's log. */
    fun showUpdateDetails(failed: UpdateState.UpdateFailed) {
        viewModelScope.launch(ioDispatcher) {
            val folder = failed.logFile.parentFile ?: return@launch
            try {
                if (folder.isDirectory && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(folder)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun closeUpdateDialog() {
        cancelRestart()
        // Otherwise a check that finishes later would start a download and open the dialog again.
        tryAgainCheckJob?.cancel()
        tryAgainCheckJob = null
        _isUpdateDialogOpen.value = false
    }

    /**
     * Startup housekeeping for updates, then the update check when the user turned it on. A failed
     * update from the last run is left showing instead of being replaced by the check.
     */
    fun checkUpdatesOnStartupIfEnabled(): Job = viewModelScope.launch {
        updateManager.runStartupTasks()
        if (updateManager.updateState.value is UpdateState.UpdateFailed) return@launch
        val settings = repository.getSettings()
        if (settings.checkForUpdatesOnOpen) {
            val result = updateManager.checkForUpdates()
            if (result is UpdateState.Available) startupUpdateVersion.value = result.version
        }
    }

    fun refreshSettings() {
        viewModelScope.launch {
            _settingsState.value = repository.getSettings()
        }
    }

    fun updateSettings(mutate: (AppSettings) -> AppSettings) {
        _settingsState.update { current ->
            val updated = mutate(current)
            viewModelScope.launch {
                repository.updateSettings(updated)
            }
            updated
        }
    }

    fun updateRequireMasterPasswordOnStartup(required: Boolean) {
        updateSettings { it.copy(requireMasterPasswordOnStartup = required) }
        viewModelScope.launch {
            try {
                sessionManager?.onRequireMasterPasswordOnStartupChanged(required)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun changeBackupFolder() {
        viewModelScope.launch {
            val folder = filePicker.showFolderPickerDialog("Select Default Backup Folder")
            if (folder != null) {
                updateSettings { it.copy(backupFolder = folder.absolutePath.replace("\\\\", "/")) }
            }
        }
    }

    fun openBackupFolder() {
        viewModelScope.launch(Dispatchers.IO) {
            val folderPath = _settingsState.value.backupFolder
            val folder = File(folderPath)
            if (!folder.exists()) {
                folder.mkdirs()
            }
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(folder)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val RESTART_COUNTDOWN_SECONDS = 2

        internal fun bannerFor(
            state: UpdateState,
            installOnExit: Boolean,
            startupVersion: String?,
            dismissed: Boolean
        ): UpdateBanner? = when (state) {
            is UpdateState.Available ->
                if (!dismissed && state.version == startupVersion) UpdateBanner.Available(state) else null
            is UpdateState.ReadyToInstall -> if (installOnExit) UpdateBanner.InstallOnClose(state) else null
            is UpdateState.UpdateFailed -> UpdateBanner.Failed(state)
            else -> null
        }
    }
}
