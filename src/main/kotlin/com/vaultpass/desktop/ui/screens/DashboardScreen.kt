@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.vaultpass.desktop.ui.screens

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.ui.theme.LocalSpacing
import com.vaultpass.desktop.ui.theme.LocalVaultPassExtendedColors
import com.vaultpass.desktop.ui.theme.SuccessGreen
import com.vaultpass.desktop.ui.theme.WarningYellow
import com.vaultpass.desktop.ui.theme.DangerRed
import com.vaultpass.desktop.ui.viewmodels.AuthViewModel
import com.vaultpass.desktop.ui.viewmodels.DashboardViewModel
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    vaultViewModel: VaultViewModel,
    authViewModel: AuthViewModel,
    dashboardViewModel: DashboardViewModel,
    searchFocusRequester: androidx.compose.ui.focus.FocusRequester = remember { androidx.compose.ui.focus.FocusRequester() },
    settingsState: com.vaultpass.desktop.domain.models.AppSettings
) {
    val dashboardState by dashboardViewModel.uiState.collectAsState()
    val lastOpenedAt by authViewModel.lastOpenedAt.collectAsState()
    val spacing = LocalSpacing.current
    val clipboardManager = LocalClipboardManager.current

    val securityScore = dashboardState.securityReport.score
    val weakPasswords = dashboardState.securityReport.weakEntries.size
    val reusedPasswords = dashboardState.securityReport.reusedEntries.size
    val totalPasswords = dashboardState.totalPasswords

    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    val lastUnlockTime = if (lastOpenedAt > 0) dateFormat.format(Date(lastOpenedAt)) else "First Launch"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D)) // Canvas background
            .padding(spacing.lg)
    ) {
        // Welcome and Search Bar Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Dashboard",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "Last Unlock: $lastUnlockTime",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF888888)
                )
            }

            // Minimal search card
            Box(
                modifier = Modifier
                    .width(320.dp)
                    .height(36.dp)
                    .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = dashboardState.searchQuery,
                        onValueChange = { dashboardViewModel.updateSearchQuery(it) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color(0xFFE8E8E8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (dashboardState.searchQuery.isEmpty()) {
                                Text("Search vault...", fontSize = 12.sp, color = Color(0xFF888888))
                            }
                            innerTextField()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 1. Stats Grid: 4 cards in a row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            // Card 1: Security Score
            StatCard(
                title = "Security Score",
                value = "$securityScore%",
                description = "Overall health score",
                icon = Icons.Default.Shield,
                color = when {
                    securityScore >= 90 -> SuccessGreen
                    securityScore >= 70 -> WarningYellow
                    else -> DangerRed
                },
                modifier = Modifier.weight(1f)
            )

            // Card 2: Total Items
            StatCard(
                title = "Total Items",
                value = "$totalPasswords",
                description = "Secured passwords",
                icon = Icons.Default.Lock,
                color = Color(0xFF0EA5A1), // Accent Teal
                modifier = Modifier.weight(1f)
            )

            // Card 3: Weak Passwords
            StatCard(
                title = "Weak Passwords",
                value = "$weakPasswords",
                description = "Vulnerable credentials",
                icon = Icons.Default.Warning,
                color = if (weakPasswords == 0) SuccessGreen else DangerRed,
                modifier = Modifier.weight(1f)
            )

            // Card 4: Reused Passwords
            StatCard(
                title = "Reused Passwords",
                value = "$reusedPasswords",
                description = "Shared secrets found",
                icon = Icons.Default.Refresh,
                color = if (reusedPasswords == 0) SuccessGreen else WarningYellow,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 2. Activity & Favorites Side-by-Side columns
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Left Column: Recent Activity
            Column(modifier = Modifier.weight(1.5f)) {
                Text(
                    text = "Recent Activity",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1A1A))
                        .padding(16.dp)
                ) {
                    if (dashboardState.recentActivity.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No recent activity.", color = Color(0xFF888888), fontSize = 14.sp)
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(dashboardState.recentActivity.size) { index ->
                                val entry = dashboardState.recentActivity[index]
                                val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
                                val action = if (entry.createdAt == entry.updatedAt) "Added to vault" else "Updated password"
                                val icon = if (entry.createdAt == entry.updatedAt) Icons.Default.Add else Icons.Default.Update
                                val dateFormatShort = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                                val timeString = dateFormatShort.format(Date(entry.updatedAt))

                                ActivityRowItem(
                                    title = displayTitle,
                                    action = action,
                                    time = timeString,
                                    icon = icon
                                ) {
                                    vaultViewModel.showEditDialog(entry.id)
                                }
                            }
                        }
                    }
                }
            }

            // Right Column: Favorites
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Favorites",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1A1A))
                        .padding(16.dp)
                ) {
                    if (dashboardState.favorites.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No favorites yet.", color = Color(0xFF888888), fontSize = 14.sp)
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(dashboardState.favorites.size) { index ->
                                val entry = dashboardState.favorites[index]
                                val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
                                FavoriteRowItem(
                                    title = displayTitle,
                                    username = entry.username,
                                    onSelect = { vaultViewModel.showEditDialog(entry.id) },
                                    onCopy = {
                                        vaultViewModel.copyPasswordToClipboard(
                                            entry.secret,
                                            clipboardManager
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    description: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 12.sp, color = Color(0xFF888888), fontWeight = FontWeight.Normal)
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, fontSize = 11.sp, color = Color(0xFF888888))
        }
    }
}

@Composable
private fun ActivityRowItem(
    title: String,
    action: String,
    time: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) Color(0xFF242424) else Color.Transparent,
        animationSpec = tween(150)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .hoverable(interactionSource)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF0D0D0D)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(16.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(action, fontSize = 11.sp, color = Color(0xFF888888))
        }
        Text(time, fontSize = 11.sp, color = Color(0xFF888888))
    }
}

@Composable
private fun FavoriteRowItem(
    title: String,
    username: String,
    onSelect: () -> Unit,
    onCopy: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) Color(0xFF242424) else Color.Transparent,
        animationSpec = tween(150)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
            .hoverable(interactionSource)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF0EA5A1).copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.take(1).uppercase(),
                color = Color(0xFF0EA5A1),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            if (username.isNotBlank()) {
                Text(username, fontSize = 11.sp, color = Color(0xFF888888))
            }
        }
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy Password",
                tint = Color(0xFF0EA5A1),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
