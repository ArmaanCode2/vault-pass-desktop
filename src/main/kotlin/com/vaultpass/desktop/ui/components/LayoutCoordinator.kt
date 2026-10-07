package com.vaultpass.desktop.ui.components

import androidx.compose.runtime.*

class LayoutCoordinator {
    var windowWidth by mutableStateOf(0f)
    
    // Minimum widths for all active components
    private val componentMinWidths = mutableStateMapOf<String, Float>()
    
    fun registerComponent(id: String, minWidth: Float) {
        componentMinWidths[id] = minWidth
    }
    
    fun unregisterComponent(id: String) {
        componentMinWidths.remove(id)
    }
    
    val totalRequiredMinWidth: Float
        get() = componentMinWidths.values.sum()
        
    
    fun hasSpaceFor(width: Float, excludeId: String? = null): Boolean {
        val currentTotal = totalRequiredMinWidth
        val excludedWidth = if (excludeId != null) componentMinWidths[excludeId] ?: 0f else 0f
        return (windowWidth - (currentTotal - excludedWidth)) >= width
    }

    val availableWidthForResizing: Float
        get() = windowWidth - totalRequiredMinWidth
}

val LocalLayoutCoordinator = compositionLocalOf { LayoutCoordinator() }
