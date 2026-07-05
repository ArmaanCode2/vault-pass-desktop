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
import com.vaultpass.desktop.ui.screens.PlaceholderScreen
import com.vaultpass.desktop.ui.theme.VaultPassTheme

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.CircularProgressIndicator
import com.vaultpass.desktop.ui.viewmodels.AuthState

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
            val authRepository = remember { com.vaultpass.desktop.data.AuthRepositoryImpl() }
            val authViewModel = remember { com.vaultpass.desktop.ui.viewmodels.AuthViewModel(authRepository) }
            val authState by authViewModel.authState.collectAsState()

            val navigationState = remember { NavigationState(Screen.Dashboard) }
            
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                val isCompact = maxWidth < 850.dp
                
                when (authState) {
                    AuthState.LOADING -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    AuthState.FIRST_LAUNCH -> {
                        com.vaultpass.desktop.ui.screens.SetupScreen(
                            onCreateVault = authViewModel::createMasterPassword
                        )
                    }
                    AuthState.LOCKED -> {
                        com.vaultpass.desktop.ui.screens.LockScreen(
                            onUnlock = authViewModel::verifyMasterPassword
                        )
                    }
                    AuthState.UNLOCKED -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Sidebar(
                                navigationState = navigationState,
                                isCollapsed = isCompact
                            )
                            
                            Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                                TopBar()
                                
                                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                                    when (navigationState.currentScreen) {
                                        Screen.Dashboard -> com.vaultpass.desktop.ui.screens.DashboardScreen()
                                        Screen.Vault -> com.vaultpass.desktop.ui.screens.VaultScreen()
                                        Screen.SecurityCenter -> com.vaultpass.desktop.ui.screens.SecurityCenterScreen()
                                        Screen.Generator -> com.vaultpass.desktop.ui.screens.GeneratorScreen()
                                        Screen.Settings -> com.vaultpass.desktop.ui.screens.SettingsScreen()
                                        Screen.RecycleBin -> PlaceholderScreen("Recycle Bin")
                                        Screen.About -> PlaceholderScreen("About VaultPass")
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}