package com.vaultpass.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

// ============================================================================
// Core Data Structures
// ============================================================================

data class VaultPassExtendedColors(
    val success: Color,
    val warning: Color,
    val selection: Color,
    val hover: Color,
    val scrollbar: Color,
    val divider: Color,
    val glassBase: Color
)

val LocalVaultPassExtendedColors = staticCompositionLocalOf {
    VaultPassExtendedColors(
        success = SuccessGreen,
        warning = WarningYellow,
        selection = Color.Transparent,
        hover = Color.Transparent,
        scrollbar = Color.Transparent,
        divider = Color.Transparent,
        glassBase = Color.Transparent
    )
}

data class BaseThemeDefinition(
    val name: String,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val glassBase: Color,
    val isLight: Boolean
)

data class AccentColorDefinition(
    val name: String,
    val primary: Color,
    val onPrimary: Color,
    val selection: Color,
    val hover: Color
)

// ============================================================================
// Definitions
// ============================================================================


// ============================================================================
// Spacing & Animations
// ============================================================================

data class Spacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp
)

val ComfortableSpacing = Spacing()
val CompactSpacing = Spacing(
    xxs = 1.dp,
    xs = 2.dp,
    sm = 4.dp,
    md = 8.dp,
    lg = 12.dp,
    xl = 16.dp,
    xxl = 24.dp
)

val LocalSpacing = staticCompositionLocalOf { ComfortableSpacing }
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

val BaseThemes = listOf(
    BaseThemeDefinition(
        name = "Light",
        background = light_background,
        surface = light_surface,
        surfaceVariant = light_surfaceVariant,
        onSurface = light_onSurface,
        onSurfaceVariant = light_onSurfaceVariant,
        outline = light_outline,
        glassBase = light_glassBase,
        isLight = true
    ),
    BaseThemeDefinition(
        name = "Dark",
        background = dark_background,
        surface = dark_surface,
        surfaceVariant = dark_surfaceVariant,
        onSurface = dark_onSurface,
        onSurfaceVariant = dark_onSurfaceVariant,
        outline = dark_outline,
        glassBase = dark_glassBase,
        isLight = false
    ),
    BaseThemeDefinition(
        name = "AMOLED",
        background = amoled_background,
        surface = amoled_surface,
        surfaceVariant = amoled_surfaceVariant,
        onSurface = amoled_onSurface,
        onSurfaceVariant = amoled_onSurfaceVariant,
        outline = amoled_outline,
        glassBase = amoled_glassBase,
        isLight = false
    )
)

val AccentColors = listOf(
    AccentColorDefinition("Blue", blue_primary, blue_onPrimary, blue_selection, blue_hover),
    AccentColorDefinition("Green", green_primary, green_onPrimary, green_selection, green_hover),
    AccentColorDefinition("Purple", purple_primary, purple_onPrimary, purple_selection, purple_hover),
    AccentColorDefinition("Orange", orange_primary, orange_onPrimary, orange_selection, orange_hover),
    AccentColorDefinition("Red", red_primary, red_onPrimary, red_selection, red_hover),
    AccentColorDefinition("Cyan", cyan_primary, cyan_onPrimary, cyan_selection, cyan_hover),
    AccentColorDefinition("Teal", teal_primary, teal_onPrimary, teal_selection, teal_hover),
    AccentColorDefinition("Pink", pink_primary, pink_onPrimary, pink_selection, pink_hover)
)

// ============================================================================
// Composables
// ============================================================================

@Composable
fun VaultPassTheme(
    baseTheme: String = "Dark",
    accentColor: String = "Blue",
    density: String = "Comfortable",
    uiScale: String = "100%",
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    
    // Resolve Base Theme
    val targetBaseName = if (baseTheme == "System Default") {
        if (isSystemDark) "Dark" else "Light"
    } else {
        baseTheme
    }
    
    val base = BaseThemes.find { it.name == targetBaseName } ?: BaseThemes.find { it.name == "Dark" }!!
    val accent = AccentColors.find { it.name == accentColor } ?: AccentColors.find { it.name == "Blue" }!!
    
    val colorScheme = if (base.isLight) {
        lightColorScheme(
            primary = accent.primary,
            onPrimary = accent.onPrimary,
            background = base.background,
            surface = base.surface,
            surfaceVariant = base.surfaceVariant,
            onSurface = base.onSurface,
            onSurfaceVariant = base.onSurfaceVariant,
            outline = base.outline
        )
    } else {
        darkColorScheme(
            primary = accent.primary,
            onPrimary = accent.onPrimary,
            background = base.background,
            surface = base.surface,
            surfaceVariant = base.surfaceVariant,
            onSurface = base.onSurface,
            onSurfaceVariant = base.onSurfaceVariant,
            outline = base.outline
        )
    }
    
    val extendedColors = VaultPassExtendedColors(
        success = SuccessGreen,
        warning = WarningYellow,
        selection = accent.selection,
        hover = accent.hover,
        scrollbar = base.outline.copy(alpha = 0.5f),
        divider = base.outline,
        glassBase = base.glassBase
    )
    
    val spacing = when (density) {
        "Compact" -> CompactSpacing
        else -> ComfortableSpacing
    }

    CompositionLocalProvider(
        LocalVaultPassExtendedColors provides extendedColors,
        LocalSpacing provides spacing,
        LocalAnimationsEnabled provides animationsEnabled
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DesktopTypography,
            content = content
        )
    }
}
