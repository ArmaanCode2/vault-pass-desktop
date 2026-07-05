package com.vaultpass.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.ui.navigation.NavigationState
import com.vaultpass.desktop.ui.navigation.Screen

@Composable
fun Sidebar(
    navigationState: NavigationState,
    isCollapsed: Boolean,
    modifier: Modifier = Modifier
) {
    val sidebarWidth by animateDpAsState(
        targetValue = if (isCollapsed) 64.dp else 240.dp,
        animationSpec = tween(durationMillis = 200)
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(sidebarWidth)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp)
            .clipToBounds()
    ) {
        if (!isCollapsed) {
            Text(
                text = "VaultPass",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        } else {
            Text(
                text = "V",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        SidebarItem(
            icon = Icons.Default.Dashboard,
            label = "Dashboard",
            isSelected = navigationState.currentScreen == Screen.Dashboard,
            isCollapsed = isCollapsed,
            onClick = { navigationState.navigateTo(Screen.Dashboard) }
        )
        SidebarItem(
            icon = Icons.Default.List,
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

        Spacer(modifier = Modifier.weight(1f))

        SidebarItem(
            icon = Icons.Default.Delete,
            label = "Recycle Bin",
            isSelected = navigationState.currentScreen == Screen.RecycleBin,
            isCollapsed = isCollapsed,
            onClick = { navigationState.navigateTo(Screen.RecycleBin) }
        )
        SidebarItem(
            icon = Icons.Default.Info,
            label = "About",
            isSelected = navigationState.currentScreen == Screen.About,
            isCollapsed = isCollapsed,
            onClick = { navigationState.navigateTo(Screen.About) }
        )
        SidebarItem(
            icon = Icons.Default.Settings,
            label = "Settings",
            isSelected = navigationState.currentScreen == Screen.Settings,
            isCollapsed = isCollapsed,
            onClick = { navigationState.navigateTo(Screen.Settings) }
        )
        SidebarItem(
            icon = Icons.Default.Lock,
            label = "Lock Vault",
            isSelected = false,
            isCollapsed = isCollapsed,
            onClick = { navigationState.navigateTo(Screen.Lock) }
        )
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

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else if (isHovered) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else if (isHovered) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .hoverable(interactionSource = interactionSource)
            .background(backgroundColor)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(100))
        ) {
            Row {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
