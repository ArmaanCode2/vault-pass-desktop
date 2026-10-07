package com.vaultpass.desktop.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// Core Semantic Colors
// ============================================================================
val SuccessGreen = Color(0xFF10B981)
val WarningYellow = Color(0xFFFFB900)
val DangerRed = Color(0xFFFF6B6B)

// ============================================================================
// Base Themes (Neutrals, Backgrounds, Surfaces)
// ============================================================================

// 1. Light Base
val light_background = Color(0xFFF8F9FA)
val light_surface = Color(0xFFFFFFFF)
val light_surfaceVariant = Color(0xFFF1F3F5)
val light_onSurface = Color(0xFF1B1B1F)
val light_onSurfaceVariant = Color(0xFF44474F)
val light_outline = Color(0xFFE0E0E0)
val light_glassBase = Color(0xFFF1F3F5)

// 2. Dark Base (Bitwarden-Inspired Redesign Defaults)
val dark_background = Color(0xFF0D0D0D)
val dark_surface = Color(0xFF1A1A1A)
val dark_surfaceVariant = Color(0xFF1E1E1E)
val dark_onSurface = Color(0xFFE8E8E8)
val dark_onSurfaceVariant = Color(0xFF888888)
val dark_outline = Color(0xFF242424)
val dark_glassBase = Color(0xFF1A1A1A)

// 3. AMOLED Base
val amoled_background = Color(0xFF000000)
val amoled_surface = Color(0xFF0C0C0C)
val amoled_surfaceVariant = Color(0xFF121212)
val amoled_onSurface = Color(0xFFE8E8E8)
val amoled_onSurfaceVariant = Color(0xFF888888)
val amoled_outline = Color(0xFF242424)
val amoled_glassBase = Color(0xFF0C0C0C)

// ============================================================================
// Accent Colors (Primary, Selection, Hover highlights)
// ============================================================================

// Blue
val blue_primary = Color(0xFF3B82F6)
val blue_onPrimary = Color(0xFFFFFFFF)
val blue_selection = Color(0xFF2563EB).copy(alpha = 0.2f)
val blue_hover = Color(0xFF3B82F6).copy(alpha = 0.15f)

// Green
val green_primary = Color(0xFF10B981)
val green_onPrimary = Color(0xFFFFFFFF)
val green_selection = Color(0xFF059669).copy(alpha = 0.2f)
val green_hover = Color(0xFF10B981).copy(alpha = 0.15f)

// Purple
val purple_primary = Color(0xFF8B5CF6)
val purple_onPrimary = Color(0xFFFFFFFF)
val purple_selection = Color(0xFF7C3AED).copy(alpha = 0.2f)
val purple_hover = Color(0xFF8B5CF6).copy(alpha = 0.15f)

// Orange
val orange_primary = Color(0xFFF97316)
val orange_onPrimary = Color(0xFFFFFFFF)
val orange_selection = Color(0xFFEA580C).copy(alpha = 0.2f)
val orange_hover = Color(0xFFF97316).copy(alpha = 0.15f)

// Red
val red_primary = Color(0xFFEF4444)
val red_onPrimary = Color(0xFFFFFFFF)
val red_selection = Color(0xFFDC2626).copy(alpha = 0.2f)
val red_hover = Color(0xFFEF4444).copy(alpha = 0.15f)

// Cyan
val cyan_primary = Color(0xFF06B6D4)
val cyan_onPrimary = Color(0xFFFFFFFF)
val cyan_selection = Color(0xFF0891B2).copy(alpha = 0.2f)
val cyan_hover = Color(0xFF06B6D4).copy(alpha = 0.15f)

// Teal (Teal Accent default #0ea5a1)
val teal_primary = Color(0xFF0EA5A1)
val teal_onPrimary = Color(0xFF000000)
val teal_selection = Color(0xFF0EA5A1).copy(alpha = 0.2f)
val teal_hover = Color(0xFF0EA5A1).copy(alpha = 0.15f)

// Pink
val pink_primary = Color(0xFFEC4899)
val pink_onPrimary = Color(0xFFFFFFFF)
val pink_selection = Color(0xFFDB2777).copy(alpha = 0.2f)
val pink_hover = Color(0xFFEC4899).copy(alpha = 0.15f)
