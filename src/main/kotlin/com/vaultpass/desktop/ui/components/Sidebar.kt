package com.vaultpass.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.ui.navigation.NavigationState
import com.vaultpass.desktop.ui.navigation.Screen
import com.vaultpass.desktop.ui.theme.LocalAnimationsEnabled
import com.vaultpass.desktop.ui.theme.LocalSpacing

@Composable
fun Sidebar(
    navigationState: NavigationState,
    isCollapsed: Boolean,
    onLock: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = LocalSpacing.current
    // Design System: Background #121212
    val sidebarBgColor = Color(0xFF121212)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(if (isCollapsed) 72.dp else 220.dp)
            .background(sidebarBgColor)
            .padding(vertical = spacing.md)
    ) {
        // Logo Section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                .padding(bottom = spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon (40px)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            val animationsEnabled = LocalAnimationsEnabled.current
            AnimatedVisibility(
                visible = !isCollapsed,
                enter = if (animationsEnabled) fadeIn(tween(150)) else fadeIn(snap()),
                exit = if (animationsEnabled) fadeOut(tween(100)) else fadeOut(snap())
            ) {
                Column(modifier = Modifier.padding(start = spacing.sm)) {
                    Text(
                        text = "VaultPass",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = "Offline Manager",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF888888), // Text Muted #888
                        maxLines = 1
                    )
                }
            }
        }

        // Space instead of divider
        Spacer(modifier = Modifier.height(spacing.sm))

        // Navigation Items
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SidebarItem(
                icon = Icons.Default.Dashboard,
                label = "Dashboard",
                isSelected = navigationState.currentScreen == Screen.Dashboard,
                isCollapsed = isCollapsed,
                onClick = { navigationState.navigateTo(Screen.Dashboard) }
            )
            SidebarItem(
                icon = Icons.Default.Lock,
                label = "Vault",
                isSelected = navigationState.currentScreen == Screen.Vault,
                isCollapsed = isCollapsed,
                onClick = { navigationState.navigateTo(Screen.Vault) }
            )
            SidebarItem(
                icon = Icons.Default.Security,
                label = "Security Center",
                isSelected = navigationState.currentScreen == Screen.SecurityCenter,
                isCollapsed = isCollapsed,
                onClick = { navigationState.navigateTo(Screen.SecurityCenter) }
            )
            SidebarItem(
                icon = Icons.Default.VpnKey,
                label = "Generator",
                isSelected = navigationState.currentScreen == Screen.Generator,
                isCollapsed = isCollapsed,
                onClick = { navigationState.navigateTo(Screen.Generator) }
            )
            SidebarItem(
                icon = Icons.Default.Delete,
                label = "Recycle Bin",
                isSelected = navigationState.currentScreen == Screen.RecycleBin,
                isCollapsed = isCollapsed,
                onClick = { navigationState.navigateTo(Screen.RecycleBin) }
            )
        }

        // Bottom Actions - Space instead of divider
        Column(modifier = Modifier.padding(horizontal = spacing.sm), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(modifier = Modifier.height(spacing.sm))
            SidebarItem(
                icon = Icons.Default.Settings,
                label = "Settings",
                isSelected = navigationState.currentScreen == Screen.Settings,
                isCollapsed = isCollapsed,
                onClick = { navigationState.navigateTo(Screen.Settings) }
            )
            SidebarItem(
                icon = Icons.Default.LockClock,
                label = "Lock Vault",
                isSelected = false,
                isCollapsed = isCollapsed,
                onClick = onLock
            )
        }
    }
}

@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    isCollapsed: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(if (isPressed) 0.98f else 1f)

    // Design System active/inactive states
    val targetBackgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else if (isHovered) {
        Color.White.copy(alpha = 0.05f)
    } else {
        Color.Transparent
    }
    
    val backgroundColor by animateColorAsState(
        targetValue = targetBackgroundColor,
        animationSpec = tween(durationMillis = 150)
    )

    val targetContentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary // #0ea5a1 (Teal)
    } else if (isHovered) {
        Color.White
    } else {
        Color(0xFF888888) // #888 Text Muted
    }
    
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = tween(durationMillis = 150)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .hoverable(interactionSource = interactionSource)
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        val animationsEnabled = LocalAnimationsEnabled.current
        
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = if (animationsEnabled) fadeIn(tween(150)) else fadeIn(snap()),
            exit = if (animationsEnabled) fadeOut(tween(100)) else fadeOut(snap())
        ) {
            Row {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
