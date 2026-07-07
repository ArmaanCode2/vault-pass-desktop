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
import kotlinx.coroutines.launch

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.vaultpass.desktop.domain.session.SessionState

fun main() = application {
    // Window sizing rules from DESKTOP_UI_GUIDELINES.md
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
        size = DpSize(1024.dp, 768.dp)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "VaultPass",
        state = windowState
    ) {
        window.minimumSize = java.awt.Dimension(800, 600)
        
        VaultPassTheme {
            val metadataRepository = remember { com.vaultpass.desktop.data.MetadataRepositoryImpl() }
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
            
            val authenticationRepository = remember { 
                com.vaultpass.desktop.data.AuthenticationRepositoryImpl(
                    metadataRepository, 
                    cryptoManager
                ) 
            }
            val migrationRegistry = remember { com.vaultpass.desktop.domain.migration.MigrationRegistry() }
            val migrationRunner = remember { com.vaultpass.desktop.domain.migration.MigrationRunner(migrationRegistry) }
            val databaseConnectionManager = remember { com.vaultpass.desktop.data.database.SQLiteConnectionManager(System.getProperty("user.home") + "/.vaultpass/vaultpass.db") }
            val sessionManager = remember<com.vaultpass.desktop.domain.session.SessionManager> { 
                com.vaultpass.desktop.domain.session.SessionManagerImpl(
                    metadataRepository, 
                    authenticationRepository, 
                    migrationRunner,
                    keyManager,
                    databaseConnectionManager
                ) 
            }
            val authViewModel = remember { com.vaultpass.desktop.ui.viewmodels.AuthViewModel(sessionManager, metadataRepository) }
            val sessionState by authViewModel.sessionState.collectAsState()
            
            val vaultDataSource = remember { com.vaultpass.desktop.data.database.SQLiteVaultDataSource(databaseConnectionManager) }
            val vaultRepository = remember { com.vaultpass.desktop.data.repository.VaultRepositoryImpl(vaultDataSource, cryptoManager) }
            val vaultViewModel = remember { com.vaultpass.desktop.ui.viewmodels.VaultViewModel(vaultRepository) }

            val appSettingsFile = java.io.File(System.getProperty("user.home"), ".vaultpass/settings.json")
            val appSettingsRepository = remember { com.vaultpass.desktop.data.repository.AppSettingsRepositoryImpl(appSettingsFile) }
            val settingsViewModel = remember { com.vaultpass.desktop.ui.viewmodels.SettingsViewModel(appSettingsRepository) }
            val fileDialogProvider = remember { com.vaultpass.desktop.data.platform.AwtFileDialogProvider() }

            val navigationState = remember { NavigationState(Screen.Dashboard) }
            val snackbarHostState = remember { SnackbarHostState() }
            val coroutineScope = rememberCoroutineScope()
            
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                val isCompact = maxWidth < 850.dp
                
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
                        }
                        Row(modifier = Modifier.fillMaxSize()) {
                            Sidebar(
                                navigationState = navigationState,
                                isCollapsed = isCompact,
                                onLock = authViewModel::lock
                            )
                            
                            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                                TopBar(
                                    onAddClick = { vaultViewModel.showAddDialog(true) },
                                    onSearchClick = { navigationState.navigateTo(Screen.Vault) },
                                    onShowSnackbar = { msg -> 
                                        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                                    }
                                )
                                
                                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                    when (navigationState.currentScreen) {
                                        Screen.Dashboard -> com.vaultpass.desktop.ui.screens.DashboardScreen(
                                            vaultViewModel = vaultViewModel,
                                            authViewModel = authViewModel,
                                            onNavigateToGenerator = { navigationState.navigateTo(Screen.Generator) }
                                        )
                                        Screen.Vault -> com.vaultpass.desktop.ui.screens.VaultScreen(vaultViewModel)
                                        Screen.SecurityCenter -> com.vaultpass.desktop.ui.screens.SecurityCenterScreen(
                                            vaultViewModel = vaultViewModel,
                                            onNavigateToVault = { navigationState.navigateTo(Screen.Vault) }
                                        )
                                        Screen.Generator -> com.vaultpass.desktop.ui.screens.GeneratorScreen(
                                            vaultViewModel = vaultViewModel,
                                            onNavigateToVault = { navigationState.navigateTo(Screen.Vault) }
                                        )
                                        Screen.Settings -> com.vaultpass.desktop.ui.screens.SettingsScreen(
                                            settingsViewModel = settingsViewModel,
                                            fileDialogProvider = fileDialogProvider,
                                            databasePath = System.getProperty("user.home") + "/.vaultpass/vaultpass.db"
                                        )

                                        else -> {}
                                    }
                                }
                                SnackbarHost(hostState = snackbarHostState)
                            }
                        }

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
                                pendingPassword = pending
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
                                    }
                                )
                            }
                        }
                    }
                    is SessionState.FatalError -> {
                        val fatalErrorState = sessionState as SessionState.FatalError
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            androidx.compose.material3.Text(
                                text = "Fatal Error: ${fatalErrorState.reason}\nRecovery Mode Required.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}