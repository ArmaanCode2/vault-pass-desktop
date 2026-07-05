package com.vaultpass.desktop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimary,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onBackground = TextHighContrastDark,
    onSurface = TextHighContrastDark,
    onSurfaceVariant = TextMediumContrastDark,
    error = ErrorDark,
    onError = TextHighContrastDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimary,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onBackground = TextHighContrastLight,
    onSurface = TextHighContrastLight,
    onSurfaceVariant = TextMediumContrastLight,
    error = ErrorLight,
    onError = TextHighContrastLight
)

@Composable
fun VaultPassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        DarkColorScheme
    } else {
        LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DesktopTypography,
        content = content
    )
}
