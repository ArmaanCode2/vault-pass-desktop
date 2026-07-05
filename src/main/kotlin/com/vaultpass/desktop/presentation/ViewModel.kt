package com.vaultpass.desktop.presentation

/**
 * Base interface for ViewModels in the Desktop application.
 * This establishes the presentation layer boundary for the MVVM architecture
 * without relying on Android-specific lifecycle classes, ensuring future
 * Kotlin Multiplatform extraction remains straightforward.
 */
interface ViewModel {
    // Shared coroutine scopes or lifecycle hooks can be defined here in the future.
}
