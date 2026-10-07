package com.vaultpass.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.vaultpass.desktop.ui.components.Sidebar
import com.vaultpass.desktop.ui.components.TopBar
import com.vaultpass.desktop.ui.navigation.NavigationState
import com.vaultpass.desktop.ui.navigation.Screen

import com.vaultpass.desktop.ui.theme.VaultPassTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import com.vaultpass.desktop.ui.components.LocalLayoutCoordinator
import com.vaultpass.desktop.ui.components.LayoutCoordinator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.vaultpass.desktop.domain.session.SessionState
import java.io.File

fun main(args: Array<String>) {
    // Setup starts the old copy with this argument when an update failed.
    val startedAfterFailedUpdate = com.vaultpass.desktop.data.update.UpdateManager.UPDATE_NOT_INSTALLED_ARGUMENT in args

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            // The data folder only once it has been resolved; never guess another folder.
            val logDir = com.vaultpass.desktop.data.platform.AppStorageConfig.dataDirectoryOrNull
                ?: File(System.getProperty("java.io.tmpdir"))
            val logFile = File(logDir, "crash.log")
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            throwable.printStackTrace(pw)
            val logContent = """
                ==================== VAULTPASS CRASH REPORT ====================
                Timestamp: ${java.time.LocalDateTime.now()}
                Thread: ${thread.name}
                Error: ${throwable.javaClass.name}: ${throwable.message}
                Stacktrace:
                $sw
                ================================================================
            """.trimIndent()
            logFile.appendText(logContent + "\n\n")

            // Show native error message so the user knows what happened
            javax.swing.JOptionPane.showMessageDialog(
                null,
                "A fatal error occurred in VaultPass:\n${throwable.message}\n\nA crash log has been saved to:\n${logFile.absolutePath}",
                "VaultPass Error",
                javax.swing.JOptionPane.ERROR_MESSAGE
            )
        } catch (_: Exception) {
            // Ignore secondary logging failures
        }
    }

    application {
    val appIcon = androidx.compose.ui.res.painterResource("drawable/icon.png")
    val exitScope = rememberCoroutineScope()
    val appExit = remember { AppExit(exitApplication = ::exitApplication, scope = exitScope) }

    // Resolve the data folder before anything that reads or writes vault files is built, off the UI thread.
    var storageStep by remember { mutableStateOf<StorageStep>(StorageStep.Busy(CHECKING_DATA_FOLDER_TEXT)) }
    val storageScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        storageStep = withContext(Dispatchers.IO) { checkStorage() }
    }
    val currentStorageStep = storageStep
    if (currentStorageStep !is StorageStep.Ready) {
        var copyError by remember { mutableStateOf<String?>(null) }
        // A quick check shows no window at all; only a slow one shows the busy screen.
        var slowCheck by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(400)
            slowCheck = true
        }
        Window(
            onCloseRequest = appExit::requestExit,
            visible = slowCheck || currentStorageStep !is StorageStep.Busy,
            title = "VaultPass",
            state = rememberWindowState(
                position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
                size = DpSize(640.dp, 420.dp)
            ),
            icon = appIcon
        ) {
            VaultPassTheme {
                when (currentStorageStep) {
                    is StorageStep.Busy -> com.vaultpass.desktop.ui.screens.StorageBusyScreen(currentStorageStep.message)
                    is StorageStep.Problem -> com.vaultpass.desktop.ui.screens.StorageProblemScreen(
                        folder = currentStorageStep.folder.absolutePath,
                        reason = currentStorageStep.reason,
                        onRetry = {
                            storageStep = StorageStep.Busy(CHECKING_DATA_FOLDER_TEXT)
                            storageScope.launch {
                                storageStep = withContext(Dispatchers.IO) { checkStorage() }
                            }
                        },
                        onClose = appExit::requestExit
                    )
                    is StorageStep.VaultElsewhere -> com.vaultpass.desktop.ui.screens.VaultFoundElsewhereScreen(
                        found = currentStorageStep.found,
                        others = currentStorageStep.others,
                        resolved = currentStorageStep.resolved.absolutePath,
                        resolvedHasDatabase = currentStorageStep.resolvedHasDatabase,
                        errorMessage = copyError,
                        onCopy = {
                            storageStep = StorageStep.Busy("Copying the vault…")
                            storageScope.launch {
                                val error = withContext(Dispatchers.IO) {
                                    try {
                                        com.vaultpass.desktop.data.platform.AppStorageConfig.copyVaultFiles(
                                            source = currentStorageStep.found.folder,
                                            target = currentStorageStep.resolved
                                        )
                                        null
                                    } catch (e: com.vaultpass.desktop.data.platform.AppStorageConfig.VaultAppearedException) {
                                        e.message
                                    } catch (e: Exception) {
                                        "Couldn't copy the vault: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                                copyError = error
                                storageStep = if (error == null) {
                                    withContext(Dispatchers.IO) { checkStorage() }
                                } else {
                                    currentStorageStep
                                }
                            }
                        },
                        // The folder is already resolved; only the offer is skipped.
                        onStartNew = { storageStep = StorageStep.Ready },
                        onClose = appExit::requestExit
                    )
                    StorageStep.Ready -> Unit
                }
            }
        }
    } else {
    // Window sizing rules from DESKTOP_UI_GUIDELINES.md
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
        size = DpSize(1024.dp, 768.dp)
    )

    // The data folder is resolved here, so the updates folder inside it can be used.
    val updateManager = remember {
        com.vaultpass.desktop.data.update.UpdateManager(
            downloadDir = File(com.vaultpass.desktop.data.platform.AppStorageConfig.dataDirectory, "updates"),
            startedAfterFailedUpdate = startedAfterFailedUpdate
        )
    }
    DisposableEffect(updateManager) {
        appExit.updateManager = updateManager
        onDispose { appExit.updateManager = null }
    }

    Window(
        onCloseRequest = appExit::requestExit,
        title = if (System.getProperty("vaultpass.env") == "dev") "VaultPass [DEV ENVIRONMENT]" else "VaultPass",
        state = windowState,
        icon = appIcon
    ) {
        window.minimumSize = java.awt.Dimension(800, 600)
        
        val appSettingsFile = remember { com.vaultpass.desktop.data.platform.AppStorageConfig.settingsFile }
        val appSettingsRepository = remember { com.vaultpass.desktop.data.repository.AppSettingsRepositoryImpl(appSettingsFile) }
        val filePicker = remember { com.vaultpass.desktop.data.platform.WindowsFilePicker() }
        
        val metadataRepository = remember { 
            com.vaultpass.desktop.data.MetadataRepositoryImpl(
                metadataFile = com.vaultpass.desktop.data.platform.AppStorageConfig.metadataFile,
                vaultDirectory = com.vaultpass.desktop.data.platform.AppStorageConfig.dataDirectory,
                databaseFile = com.vaultpass.desktop.data.platform.AppStorageConfig.databaseFile
            )
        }
        val kdfProvider = remember { com.vaultpass.desktop.data.crypto.PBKDF2KdfProvider() }
        val secureRandomProvider = remember { com.vaultpass.desktop.data.crypto.SecureRandomProviderImpl() }
        val encryptionProvider = remember { com.vaultpass.desktop.data.crypto.AESGcmEncryptionProvider() }
        val keyManager = remember { com.vaultpass.desktop.data.crypto.InMemoryKeyManager() }
        
        val cryptoManager = remember {
            com.vaultpass.desktop.data.crypto.CryptoManagerImpl(
                kdfProvider,
                secureRandomProvider,
                encryptionProvider,
                keyManager
            )
        }
        
        val sessionKeyStorageFile = remember { com.vaultpass.desktop.data.platform.AppStorageConfig.sessionKeyFile }
        val sessionKeyStorage = remember { com.vaultpass.desktop.data.session.DpapiSessionKeyStorage(sessionKeyStorageFile) }

        val authenticationRepository = remember { 
            com.vaultpass.desktop.data.AuthenticationRepositoryImpl(
                metadataRepository, 
                cryptoManager,
                sessionKeyStorage
            ) 
        }
        val migrationRegistry = remember { com.vaultpass.desktop.domain.migration.MigrationRegistry() }
        val migrationRunner = remember { com.vaultpass.desktop.domain.migration.MigrationRunner(migrationRegistry) }
        val databaseConnectionManager = remember { com.vaultpass.desktop.data.database.SQLiteConnectionManager(com.vaultpass.desktop.data.platform.AppStorageConfig.databaseFile.absolutePath) }
        
        val sessionManager = remember<com.vaultpass.desktop.domain.session.SessionManager> { 
            com.vaultpass.desktop.domain.session.SessionManagerImpl(
                metadataRepository, 
                authenticationRepository, 
                migrationRunner,
                keyManager,
                databaseConnectionManager,
                appSettingsRepository,
                sessionKeyStorage
            ) 
        }

        val settingsViewModel = remember {
            com.vaultpass.desktop.ui.viewmodels.SettingsViewModel(appSettingsRepository, filePicker, sessionManager, updateManager)
        }
        val globalSettingsState by settingsViewModel.settingsState.collectAsState()

        LaunchedEffect(Unit) {
            settingsViewModel.checkUpdatesOnStartupIfEnabled()
        }

        DisposableEffect(settingsViewModel) {
            appExit.restartCountdownRunning = { settingsViewModel.restartCountdown.value != null }
            onDispose { appExit.restartCountdownRunning = { false } }
        }

        // The installer leaves a file in the data folder when it couldn't restrict the folder to this account.
        var folderPermissionsNotice by remember { mutableStateOf<String?>(null) }
        var folderPermissionsNoticeDismissed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            folderPermissionsNotice = withContext(Dispatchers.IO) {
                com.vaultpass.desktop.data.platform.AppStorageConfig.folderPermissionsNotice()
            }
        }

        VaultPassTheme(
            baseTheme = globalSettingsState.baseTheme,
            accentColor = globalSettingsState.accentColor,
            density = globalSettingsState.density,
            uiScale = globalSettingsState.uiScale,
            animationsEnabled = globalSettingsState.animationsEnabled
        ) {
            val sessionSecurityManager = remember {
                com.vaultpass.desktop.data.session.WindowsSessionSecurityManager(
                    sessionManager = sessionManager,
                    appSettingsRepository = appSettingsRepository
                )
            }

            DisposableEffect(sessionSecurityManager) {
                sessionSecurityManager.startMonitoring()
                onDispose { sessionSecurityManager.stopMonitoring() }
            }

            DisposableEffect(sessionSecurityManager) {
                val listener = java.awt.event.AWTEventListener {
                    sessionSecurityManager.notifyUserActivity()
                }
                val mask = java.awt.AWTEvent.MOUSE_EVENT_MASK or
                        java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK or
                        java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK or
                        java.awt.AWTEvent.KEY_EVENT_MASK
                java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(listener, mask)
                onDispose {
                    java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
                }
            }

            DisposableEffect(window, sessionSecurityManager) {
                val listener = object : java.awt.event.WindowStateListener {
                    override fun windowStateChanged(e: java.awt.event.WindowEvent) {
                        val isMinimized = (e.newState and java.awt.Frame.ICONIFIED) != 0
                        if (isMinimized) {
                            sessionSecurityManager.onWindowMinimized()
                        }
                    }
                }
                window.addWindowStateListener(listener)
                onDispose { window.removeWindowStateListener(listener) }
            }

            DisposableEffect(window, sessionSecurityManager) {
                var registeredHwnd: com.sun.jna.platform.win32.WinDef.HWND? = null
                var oldWndProc: com.sun.jna.Pointer? = null
                var customWndProc: com.sun.jna.platform.win32.WinUser.WindowProc? = null

                val isWindows = System.getProperty("os.name")?.lowercase()?.contains("win") == true
                if (isWindows) {
                    try {
                        val pointer = com.sun.jna.Native.getWindowPointer(window)
                        if (pointer != null) {
                            val hwnd = com.sun.jna.platform.win32.WinDef.HWND(pointer)
                            val registered = com.sun.jna.platform.win32.Wtsapi32.INSTANCE.WTSRegisterSessionNotification(
                                hwnd,
                                com.sun.jna.platform.win32.Wtsapi32.NOTIFY_FOR_THIS_SESSION
                            )
                            if (registered) {
                                registeredHwnd = hwnd
                                val wndProc = object : com.sun.jna.platform.win32.WinUser.WindowProc {
                                    override fun callback(
                                        hWnd: com.sun.jna.platform.win32.WinDef.HWND,
                                        uMsg: Int,
                                        wParam: com.sun.jna.platform.win32.WinDef.WPARAM,
                                        lParam: com.sun.jna.platform.win32.WinDef.LPARAM
                                    ): com.sun.jna.platform.win32.WinDef.LRESULT {
                                        if (uMsg == 0x02B1) { // WM_WTSSESSION_CHANGE
                                            if (wParam.toInt() == com.sun.jna.platform.win32.Wtsapi32.WTS_SESSION_LOCK) {
                                                sessionSecurityManager.onWorkstationLocked()
                                            }
                                        }
                                        return if (oldWndProc != null) {
                                            com.sun.jna.platform.win32.User32.INSTANCE.CallWindowProc(
                                                oldWndProc,
                                                hWnd,
                                                uMsg,
                                                wParam,
                                                lParam
                                            )
                                        } else {
                                            com.sun.jna.platform.win32.User32.INSTANCE.DefWindowProc(
                                                hWnd,
                                                uMsg,
                                                wParam,
                                                lParam
                                            )
                                        }
                                    }
                                }
                                customWndProc = wndProc
                                val newProcPtr = com.sun.jna.CallbackReference.getFunctionPointer(wndProc)
                                oldWndProc = com.sun.jna.platform.win32.User32.INSTANCE.SetWindowLongPtr(
                                    hwnd,
                                    -4, // GWLP_WNDPROC
                                    newProcPtr
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        // Fallback safely if JNA window hook is unsupported
                    }
                }

                onDispose {
                    try {
                        registeredHwnd?.let { hwnd ->
                            if (oldWndProc != null) {
                                com.sun.jna.platform.win32.User32.INSTANCE.SetWindowLongPtr(hwnd, -4, oldWndProc)
                            }
                            com.sun.jna.platform.win32.Wtsapi32.INSTANCE.WTSUnRegisterSessionNotification(hwnd)
                        }
                        customWndProc = null
                    } catch (t: Throwable) {
                        // Ignore cleanup error
                    }
                }
            }

            val authViewModel = remember { com.vaultpass.desktop.ui.viewmodels.AuthViewModel(sessionManager, metadataRepository) }
            val sessionState by authViewModel.sessionState.collectAsState()
            
            val vaultDataSource = remember { com.vaultpass.desktop.data.database.SQLiteVaultDataSource(databaseConnectionManager) }
            val vaultRepository = remember { com.vaultpass.desktop.data.repository.VaultRepositoryImpl(vaultDataSource, cryptoManager) }
            val dashboardViewModel = remember { com.vaultpass.desktop.ui.viewmodels.DashboardViewModel(vaultRepository) }
            
            val vaultViewModel = remember { com.vaultpass.desktop.ui.viewmodels.VaultViewModel(vaultRepository, appSettingsRepository) }
            
            // Removed hoisted filePicker and settingsViewModel
            val backupRepository = remember { com.vaultpass.desktop.data.backup.BackupRepositoryImpl(filePicker, appSettingsRepository) }
            val backupSerializer = remember { com.vaultpass.desktop.data.backup.BackupSerializerImpl() }
            val backupValidator = remember { com.vaultpass.desktop.data.backup.BackupValidatorImpl() }
            val backupManager = remember { com.vaultpass.desktop.data.backup.BackupManagerImpl(vaultRepository, metadataRepository, cryptoManager, backupRepository, backupSerializer, backupValidator, sessionKeyStorage) }
            val backupViewModel = remember { com.vaultpass.desktop.ui.viewmodels.BackupViewModel(backupManager, appSettingsRepository, vaultRepository) }

            val pairedDeviceRepository = remember { com.vaultpass.desktop.data.sync.PairedDeviceRepository(databaseConnectionManager) }
            val localDeviceId = remember {
                com.vaultpass.desktop.data.sync.DeviceIdentity.loadOrCreate(
                    com.vaultpass.desktop.data.platform.AppStorageConfig.syncDeviceIdFile
                )
            }
            val localDeviceName = remember {
                try {
                    java.net.InetAddress.getLocalHost().hostName
                } catch (e: Exception) {
                    "VaultPass Desktop"
                }
            }
            // Pair keys are stored and used sealed with the vault key.
            val pairKeyProtector = remember { com.vaultpass.desktop.data.sync.DesktopPairKeyProtector(cryptoManager) }
            val lanDiscoveryManager = remember {
                com.vaultpass.desktop.data.sync.LanDiscoveryManager(
                    deviceId = localDeviceId,
                    deviceName = localDeviceName,
                    pairedDeviceRepository = pairedDeviceRepository,
                    pairKeyProtector = pairKeyProtector
                )
            }
            val lanSocketTransport = remember {
                com.vaultpass.desktop.data.sync.LanSocketTransport(
                    localDeviceId = localDeviceId,
                    localDeviceName = localDeviceName,
                    pairedDeviceRepository = pairedDeviceRepository,
                    pairKeyProtector = pairKeyProtector
                )
            }
            val syncViewModel = remember {
                com.vaultpass.desktop.ui.viewmodels.SyncViewModel(
                    pairedDeviceRepository = pairedDeviceRepository,
                    lanDiscoveryManager = lanDiscoveryManager,
                    lanSocketTransport = lanSocketTransport,
                    vaultRepository = vaultRepository
                )
            }

            DisposableEffect(lanDiscoveryManager, lanSocketTransport) {
                onDispose {
                    lanDiscoveryManager.stop()
                    lanSocketTransport.stopServer()
                }
            }

            // Before an update's installer starts: no password left on the clipboard, no sync traffic,
            // database closed, keys wiped. The close is asked for at once (unfinished work is rolled
            // back) and then awaited until the connection really is closed; the update manager runs
            // this on its own thread and goes ahead anyway after its time limit.
            DisposableEffect(updateManager, sessionManager, lanDiscoveryManager, lanSocketTransport, vaultViewModel) {
                updateManager.beforeExit = {
                    try {
                        // The countdown runs only while a password VaultPass copied is on the clipboard.
                        if (vaultViewModel.clipboardCountdown.value.isActive) vaultViewModel.clearClipboardNow()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    try {
                        lanDiscoveryManager.stop()
                        lanSocketTransport.stopServer()
                    } finally {
                        // close() itself ends an unlocked (or unlocking) session Locked, deciding that
                        // together with any unlock finishing at the same moment, and waits until the
                        // database file really is closed.
                        sessionManager.close()
                    }
                }
                onDispose { updateManager.beforeExit = null }
            }

            // "Restart now" waits while a sync, a backup/import/export or an entry dialog is open.
            val syncSessionState by syncViewModel.sessionState.collectAsState()
            val syncReviewOpen by syncViewModel.isSyncReviewOpen.collectAsState()
            val syncAwaitingPeerReview by syncViewModel.isAwaitingPeerReview.collectAsState()
            val syncPlanApproval by syncViewModel.pendingPlanApproval.collectAsState()
            val backupUiState by backupViewModel.uiState.collectAsState()
            val vaultUiState by vaultViewModel.uiState.collectAsState()
            val updateBlockedReason = com.vaultpass.desktop.ui.viewmodels.UpdateBlockers.reason(
                syncBusy = com.vaultpass.desktop.ui.viewmodels.UpdateBlockers.isSyncBusy(
                    sessionState = syncSessionState,
                    isSyncReviewOpen = syncReviewOpen,
                    isAwaitingPeerReview = syncAwaitingPeerReview,
                    hasPendingPlanApproval = syncPlanApproval != null
                ),
                backupBusy = backupUiState.isLoading,
                entryDialogOpen = sessionState == SessionState.Unlocked &&
                    (vaultUiState.isAddDialogVisible || vaultUiState.editEntryId != null)
            )
            LaunchedEffect(updateBlockedReason) {
                settingsViewModel.setUpdateBlockedReason(updateBlockedReason)
            }

            val navigationState = remember { NavigationState(Screen.Dashboard) }
            val dashboardSearchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            val vaultSearchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            val recycleBinSearchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            val snackbarHostState = remember { SnackbarHostState() }
            val coroutineScope = rememberCoroutineScope()
            
            val layoutCoordinator = remember { LayoutCoordinator() }
            CompositionLocalProvider(LocalLayoutCoordinator provides layoutCoordinator) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    layoutCoordinator.windowWidth = maxWidth.value
                
                val settingsState by settingsViewModel.settingsState.collectAsState()
                
                androidx.compose.runtime.LaunchedEffect(sessionState) {
                    if (sessionState == SessionState.Locked) {
                        syncViewModel.onVaultLocked()
                        vaultRepository.clearCache()
                        vaultViewModel.clearState()
                        dashboardViewModel.clearState()
                        if (settingsState.clearSearchOnLock) {
                            vaultViewModel.updateSearchQuery("")
                            dashboardViewModel.updateSearchQuery("")
                        }
                        System.gc()
                    }
                }

                when (sessionState) {
                    SessionState.Unlocking -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    SessionState.FirstLaunch, SessionState.SetupMasterPassword -> {
                        com.vaultpass.desktop.ui.screens.SetupScreen(
                            onCreateVault = authViewModel::createMasterPassword
                        )
                    }
                    SessionState.Locked -> {
                        com.vaultpass.desktop.ui.screens.LockScreen(
                            onUnlock = authViewModel::verifyMasterPassword
                        )
                    }
                    SessionState.Unlocked -> {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            vaultViewModel.refresh()
                            
                            val startPage = settingsState.defaultStartupPage.replace(" ", "")
                            try {
                                val targetScreen = com.vaultpass.desktop.ui.navigation.Screen.valueOf(startPage)
                                navigationState.navigateTo(targetScreen)
                            } catch (e: Exception) {
                                navigationState.navigateTo(com.vaultpass.desktop.ui.navigation.Screen.Dashboard)
                            }
                        }
                        val sidebarWidth = settingsState.sidebarWidth
                        
                        androidx.compose.runtime.DisposableEffect(sidebarWidth) {
                            val minSidebar = if (sidebarWidth <= 72f) 72f else 260f
                            layoutCoordinator.registerComponent("Sidebar", minSidebar)
                            onDispose { layoutCoordinator.unregisterComponent("Sidebar") }
                        }

                        com.vaultpass.desktop.ui.components.ResizableSplitPane(
                            modifier = Modifier.fillMaxSize(),
                            initialWidth = sidebarWidth,
                            minWidth = 72f,
                            maxWidth = 400f,
                            flexiblePaneMinWidth = 500f, // Minimum width for the main content area
                            fixedPane = com.vaultpass.desktop.ui.components.FixedPane.Left,
                            showFixedPane = true,
                            onWidthChangeFinished = { newWidth -> 
                                settingsViewModel.updateSettings { it.copy(sidebarWidth = newWidth) }
                            },
                            leftPane = { modifier ->
                                Sidebar(
                                    navigationState = navigationState,
                                    isCollapsed = sidebarWidth <= 72f,
                                    onLock = authViewModel::lock,
                                    modifier = modifier
                                )
                            },
                            rightPane = { rightModifier ->
                                Column(modifier = rightModifier.fillMaxSize()) {
                                val clipboardCountdown by vaultViewModel.clipboardCountdown.collectAsState()
                                TopBar(
                                    currentScreen = navigationState.currentScreen,
                                    clipboardCountdown = clipboardCountdown,
                                    onClearClipboard = { vaultViewModel.clearClipboardNow() },
                                    onAddClick = { vaultViewModel.showAddDialog(true) },
                                    onLockClick = { authViewModel.lock() },
                                    onShowSnackbar = { msg -> 
                                        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                )

                                com.vaultpass.desktop.ui.components.UpdateBannerHost(settingsViewModel)

                                val permissionsNotice = folderPermissionsNotice
                                if (permissionsNotice != null && !folderPermissionsNoticeDismissed) {
                                    com.vaultpass.desktop.ui.components.FolderPermissionsBanner(
                                        message = permissionsNotice,
                                        onDismiss = { folderPermissionsNoticeDismissed = true }
                                    )
                                }

                                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                    when (navigationState.currentScreen) {
                                        Screen.Dashboard -> com.vaultpass.desktop.ui.screens.DashboardScreen(
                                            vaultViewModel = vaultViewModel,
                                            authViewModel = authViewModel,
                                            dashboardViewModel = dashboardViewModel,
                                            searchFocusRequester = dashboardSearchFocus,
                                            settingsState = settingsState
                                        )
                                        Screen.Vault -> com.vaultpass.desktop.ui.screens.VaultScreen(
                                            viewModel = vaultViewModel,
                                            settingsViewModel = settingsViewModel,
                                            searchFocusRequester = vaultSearchFocus
                                        )
                                        Screen.SecurityCenter -> com.vaultpass.desktop.ui.screens.SecurityCenterScreen(
                                            vaultViewModel = vaultViewModel,
                                            dashboardViewModel = dashboardViewModel,
                                            onNavigateToVault = { navigationState.navigateTo(Screen.Vault) }
                                        )
                                        Screen.Generator -> com.vaultpass.desktop.ui.screens.GeneratorScreen(
                                            vaultViewModel = vaultViewModel,
                                            onNavigateToVault = { navigationState.navigateTo(Screen.Vault) }
                                        )
                                        Screen.Settings -> com.vaultpass.desktop.ui.screens.SettingsScreen(
                                            settingsViewModel = settingsViewModel,
                                            backupViewModel = backupViewModel,
                                            vaultViewModel = vaultViewModel,
                                            syncViewModel = syncViewModel,
                                            databasePath = com.vaultpass.desktop.data.platform.AppStorageConfig.databaseFile.absolutePath
                                        )
                                        Screen.RecycleBin -> com.vaultpass.desktop.ui.screens.RecycleBinScreen(
                                            viewModel = vaultViewModel,
                                            searchFocusRequester = recycleBinSearchFocus
                                        )
                                        Screen.Lock -> { /* handled above */ }
                                    }
                                }
                                SnackbarHost(hostState = snackbarHostState)
                            }
                        }
                        ) // end of ResizableSplitPane

                        val vaultState by vaultViewModel.uiState.collectAsState()
                        if (vaultState.isAddDialogVisible) {
                            val pending = vaultViewModel.consumePendingGeneratedPassword()
                            com.vaultpass.desktop.ui.screens.EntryDialog(
                                entry = null,
                                onDismiss = { vaultViewModel.showAddDialog(false) },
                                onSave = { payload ->
                                    vaultViewModel.addEntry(payload)
                                    vaultViewModel.showAddDialog(false)
                                    coroutineScope.launch { snackbarHostState.showSnackbar("Password added successfully") }
                                },
                                hidePasswordsByDefault = settingsState.hidePasswordsByDefault,
                                pendingPassword = pending,
                                onGeneratedSnackbar = { msg -> coroutineScope.launch { snackbarHostState.showSnackbar(msg) } }
                            )
                        }

                        if (vaultState.editEntryId != null) {
                            val entryToEdit = vaultState.entries.find { it.id == vaultState.editEntryId }
                            if (entryToEdit != null) {
                                com.vaultpass.desktop.ui.screens.EntryDialog(
                                    entry = entryToEdit,
                                    onDismiss = { vaultViewModel.showEditDialog(null) },
                                    onSave = { payload ->
                                        vaultViewModel.updateEntry(entryToEdit.id, payload)
                                        vaultViewModel.showEditDialog(null)
                                        coroutineScope.launch { snackbarHostState.showSnackbar("Password updated successfully") }
                                    },
                                    onDelete = {
                                        vaultViewModel.deleteEntry(entryToEdit.id)
                                        vaultViewModel.showEditDialog(null)
                                    },
                                    confirmBeforeDelete = settingsState.confirmBeforeDelete,
                                    hidePasswordsByDefault = settingsState.hidePasswordsByDefault,
                                    onGeneratedSnackbar = { msg -> coroutineScope.launch { snackbarHostState.showSnackbar(msg) } }
                                )
                            }
                        }

                        val syncStatusMsg by syncViewModel.statusMessage.collectAsState()
                        LaunchedEffect(syncStatusMsg) {
                            syncStatusMsg?.let {
                                snackbarHostState.showSnackbar(it)
                                syncViewModel.clearStatusMessage()
                            }
                        }

                        val isQrOpen by syncViewModel.isQrDialogOpen.collectAsState()
                        if (isQrOpen) {
                            com.vaultpass.desktop.ui.sync.QrPairingDialog(
                                syncViewModel = syncViewModel,
                                onDismiss = { syncViewModel.dismissQrPairing() }
                            )
                        }

                        val isIncomingSyncOpen by syncViewModel.isIncomingSyncDialogOpen.collectAsState()
                        if (isIncomingSyncOpen) {
                            com.vaultpass.desktop.ui.sync.IncomingSyncRequestDialog(
                                syncViewModel = syncViewModel,
                                onDismiss = { syncViewModel.declineIncomingRequest() }
                            )
                        }

                        // This PC started the sync: it reviews and builds the plan.
                        val isSyncReviewOpen by syncViewModel.isSyncReviewOpen.collectAsState()
                        if (isSyncReviewOpen) {
                            com.vaultpass.desktop.ui.sync.SyncReviewDialog(
                                syncViewModel = syncViewModel,
                                onDismiss = { syncViewModel.cancelSync() },
                                // The view model reports "Devices are in sync" itself.
                                onSuccessMerge = { }
                            )
                        }

                        // The other device started the sync: this PC waits while its user reviews...
                        val isAwaitingPeerReview by syncViewModel.isAwaitingPeerReview.collectAsState()
                        val awaitedPeer by syncViewModel.activeSyncingDevice.collectAsState()
                        if (isAwaitingPeerReview) {
                            com.vaultpass.desktop.ui.sync.AwaitingReviewDialog(
                                peerName = awaitedPeer?.deviceName ?: "the other device",
                                onCancel = { syncViewModel.cancelSyncRequest() }
                            )
                        }

                        // ...and then only approves its plan.
                        val pendingPlanApproval by syncViewModel.pendingPlanApproval.collectAsState()
                        pendingPlanApproval?.let { request ->
                            com.vaultpass.desktop.ui.sync.PlanApprovalDialog(
                                request = request,
                                onApply = { syncViewModel.approvePlan() },
                                onDecline = { syncViewModel.declinePlan() }
                            )
                        }

                        val activeError by syncViewModel.activeError.collectAsState()
                        val retryDevice by syncViewModel.retryDevice.collectAsState()
                        activeError?.let { err ->
                            com.vaultpass.desktop.ui.components.ErrorDetailsDialog(
                                errorMessage = err.first,
                                technicalDetails = err.second,
                                onDismiss = { syncViewModel.dismissError() },
                                onCopiedSnackbar = {
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("Error report copied to clipboard")
                                    }
                                },
                                actionLabel = if (retryDevice != null) "Sync again" else null,
                                onAction = { syncViewModel.syncAgain() }
                            )
                        }
                    }
                    is SessionState.FatalError -> {
                        val fatalErrorState = sessionState as SessionState.FatalError
                        val updateState by settingsViewModel.updateState.collectAsState()
                        var updateCheckStarted by remember { mutableStateOf(false) }
                        // Once the check finds a release, the same button downloads it (the update dialog takes over).
                        val foundUpdate = updateState as? com.vaultpass.desktop.data.update.UpdateState.Available
                        com.vaultpass.desktop.ui.screens.FatalErrorScreen(
                            reason = fatalErrorState.reason,
                            updateStatus = if (updateCheckStarted) updateStatusText(updateState) else null,
                            onCheckForUpdates = if (fatalErrorState.offerUpdateCheck) {
                                {
                                    updateCheckStarted = true
                                    if (foundUpdate != null) {
                                        settingsViewModel.downloadUpdate(foundUpdate)
                                    } else {
                                        settingsViewModel.checkForUpdates()
                                    }
                                }
                            } else null,
                            onClose = appExit::requestExit,
                            updateActionLabel = if (foundUpdate != null) "Update to ${foundUpdate.version}" else "Check for updates"
                        )
                    }
                }

                // Over whatever the window shows, so a download started while locked can still finish.
                com.vaultpass.desktop.ui.components.UpdateDialogHost(settingsViewModel)

                // Closing with an update chosen with Later: the window stays until the installer has started.
                if (appExit.startingUpdate) {
                    com.vaultpass.desktop.ui.components.StartingUpdateDialog()
                }
            }
        }
    }
}
}
}
}

/**
 * Every way out of VaultPass (the window's X, the Close buttons) goes through [requestExit]: it
 * starts an update chosen with "Later", and ignores the request while an update is about to start.
 */
private class AppExit(private val exitApplication: () -> Unit, private val scope: CoroutineScope) {
    var updateManager: com.vaultpass.desktop.data.update.UpdateManager? = null
    var restartCountdownRunning: () -> Boolean = { false }
    private var exiting = false

    /** True while the update chosen with Later is being started; the window shows that it is. */
    var startingUpdate by mutableStateOf(false)
        private set

    fun requestExit() {
        val manager = updateManager
        // Closing now would cut off the countdown or the installer that is starting.
        if (manager != null && (manager.launchInProgress || restartCountdownRunning())) return
        if (exiting) return
        exiting = true
        // "Later" installs on close, but never while Windows is shutting down. Not from a shutdown hook.
        if (manager != null && manager.installOnExit.value && !isWindowsShuttingDown()) {
            startingUpdate = true
            scope.launch {
                // Returns only if the installer couldn't be started; then VaultPass closes as asked.
                try {
                    withContext(Dispatchers.IO) { manager.installOnExitIfRequested() }
                } finally {
                    startingUpdate = false
                }
                exitApplication()
            }
        } else {
            exitApplication()
        }
    }
}

private const val CHECKING_DATA_FOLDER_TEXT = "Checking the data folder…"

/** Where startup stands with the data folder. */
private sealed interface StorageStep {
    /** The folder is being checked, or a vault copied into it. */
    data class Busy(val message: String) : StorageStep
    data class Problem(val folder: File, val reason: String) : StorageStep
    /** [found] is the most recently changed vault elsewhere; [others] are the rest, newest first. */
    data class VaultElsewhere(
        val found: com.vaultpass.desktop.data.platform.VaultCandidate,
        val others: List<com.vaultpass.desktop.data.platform.VaultCandidate>,
        val resolved: File,
        val resolvedHasDatabase: Boolean
    ) : StorageStep
    data object Ready : StorageStep
}

private fun checkStorage(): StorageStep {
    return when (val resolution = com.vaultpass.desktop.data.platform.AppStorageConfig.resolveDataDirectory()) {
        is com.vaultpass.desktop.data.platform.StorageResolution.Problem ->
            StorageStep.Problem(resolution.directory, resolution.reason)
        is com.vaultpass.desktop.data.platform.StorageResolution.Ok -> {
            val found = com.vaultpass.desktop.data.platform.AppStorageConfig.findVaultsElsewhere(resolution.directory)
            if (found.isEmpty()) {
                StorageStep.Ready
            } else {
                StorageStep.VaultElsewhere(
                    found = found.first(),
                    others = found.drop(1),
                    resolved = resolution.directory,
                    resolvedHasDatabase = File(resolution.directory, "vaultpass.db").exists()
                )
            }
        }
    }
}

private fun updateStatusText(state: com.vaultpass.desktop.data.update.UpdateState): String? = when (state) {
    com.vaultpass.desktop.data.update.UpdateState.Idle -> null
    com.vaultpass.desktop.data.update.UpdateState.Checking -> "Checking for updates…"
    is com.vaultpass.desktop.data.update.UpdateState.UpToDate -> "No newer version is available yet."
    is com.vaultpass.desktop.data.update.UpdateState.Available -> "VaultPass ${state.version} is available."
    is com.vaultpass.desktop.data.update.UpdateState.Downloading -> "Downloading the update…"
    is com.vaultpass.desktop.data.update.UpdateState.Verifying -> "Checking the download…"
    is com.vaultpass.desktop.data.update.UpdateState.ReadyToInstall -> "VaultPass ${state.version} is ready to install."
    is com.vaultpass.desktop.data.update.UpdateState.Error -> "Update failed: ${state.message}"
    is com.vaultpass.desktop.data.update.UpdateState.UpdateFailed -> com.vaultpass.desktop.ui.viewmodels.updateFailedText(state)
}

private const val SM_SHUTTINGDOWN = 0x2000

/** True while Windows is shutting down or signing out; an update is never started then. */
private fun isWindowsShuttingDown(): Boolean {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return false
    return try {
        com.sun.jna.platform.win32.User32.INSTANCE.GetSystemMetrics(SM_SHUTTINGDOWN) != 0
    } catch (_: Throwable) {
        false
    }
}
