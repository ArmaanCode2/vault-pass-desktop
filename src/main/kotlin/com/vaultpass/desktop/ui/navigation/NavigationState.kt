package com.vaultpass.desktop.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class NavigationState(initialScreen: Screen = Screen.Lock) {
    var currentScreen by mutableStateOf(initialScreen)
        private set

    fun navigateTo(screen: Screen) {
        currentScreen = screen
    }
}
