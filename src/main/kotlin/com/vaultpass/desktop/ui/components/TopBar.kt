package com.vaultpass.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultpass.desktop.domain.clipboard.ClipboardCountdown
import com.vaultpass.desktop.ui.navigation.Screen
import com.vaultpass.desktop.ui.theme.LocalSpacing

@Composable
fun TopBar(
    currentScreen: Screen,
    modifier: Modifier = Modifier,
    clipboardCountdown: ClipboardCountdown = ClipboardCountdown(),
    onClearClipboard: () -> Unit = {},
    onAddClick: () -> Unit = {},
    onLockClick: () -> Unit = {},
    onShowSnackbar: (String) -> Unit = {}
) {
    val spacing = LocalSpacing.current
    // Design System: subtle 1px border at bottom
    val borderCol = Color(0xFF242424)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF0D0D0D)) // Canvas background
            .border(width = 1.dp, color = borderCol)
            .padding(horizontal = spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Side: Title
        Text(
            text = when (currentScreen) {
                Screen.Dashboard -> "Dashboard"
                Screen.Vault -> "Vault"
                Screen.SecurityCenter -> "Security Center"
                Screen.Generator -> "Generator"
                Screen.RecycleBin -> "Recycle Bin"
                Screen.Settings -> "Settings"
                else -> "VaultPass"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        Spacer(modifier = Modifier.weight(1f))

        // Center: Animated countdown pill when active
        AnimatedVisibility(
            visible = clipboardCountdown.isActive,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally()
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1A1A1A),
                border = BorderStroke(1.dp, Color(0xFF0EA5A1)),
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Clipboard Protected",
                        tint = Color(0xFF0EA5A1),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "${clipboardCountdown.label} copied (${clipboardCountdown.remainingSeconds}s)",
                        fontSize = 12.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    )
                    if (clipboardCountdown.totalSeconds > 0) {
                        val progress = clipboardCountdown.remainingSeconds.toFloat() / clipboardCountdown.totalSeconds.toFloat()
                        CircularProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.size(14.dp),
                            color = Color(0xFF0EA5A1),
                            trackColor = Color(0xFF2E2E2E),
                            strokeWidth = 2.dp
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "Clear Now",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF6B6B),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onClearClipboard() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Right Side: Actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            // Dashboard Lock indicator
            if (currentScreen == Screen.Dashboard) {
                IconButton(
                    onClick = onLockClick,
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Lock Vault",
                        tint = Color(0xFFE8E8E8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }


            // Add Password - Dashboard and Vault only
            if (currentScreen == Screen.Dashboard || currentScreen == Screen.Vault) {
                IconButton(
                    onClick = onAddClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary) // #0ea5a1
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Entry",
                        tint = Color.Black, // Text color for active buttons
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
