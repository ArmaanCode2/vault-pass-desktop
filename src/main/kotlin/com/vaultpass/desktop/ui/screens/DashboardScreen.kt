package com.vaultpass.desktop.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.ui.viewmodels.AuthViewModel
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(vaultViewModel: VaultViewModel, authViewModel: AuthViewModel, onNavigateToGenerator: () -> Unit) {
    val vaultState by vaultViewModel.uiState.collectAsState()
    val lastOpenedAt by authViewModel.lastOpenedAt.collectAsState()
    
    val entries = vaultState.entries
    val totalPasswords = entries.size
    val weakPasswords = entries.count { it.secret.length < 8 }
    val reusedPasswords = entries.groupBy { it.secret }.count { it.value.size > 1 }
    
    val securityScore = if (totalPasswords == 0) 100 else {
        val weakPenalty = weakPasswords * 10
        val reusedPenalty = reusedPasswords * 15
        val score = 100 - weakPenalty - reusedPenalty
        score.coerceIn(0, 100)
    }
    
    val scoreColor = when {
        securityScore >= 90 -> MaterialTheme.colorScheme.primary
        securityScore >= 70 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    
    val scoreDescription = when {
        securityScore >= 90 -> "Your vault is in great shape."
        securityScore >= 70 -> "Consider updating weak passwords."
        else -> "Action required to secure vault."
    }

    val favorites = entries.filter { it.isFavorite }.take(6)
    val recentActivity = entries.sortedByDescending { it.updatedAt }.take(3)

    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    val lastUnlockTime = if (lastOpenedAt > 0) dateFormat.format(Date(lastOpenedAt)) else "First Launch"

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "Dashboard Overview",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Last Unlock: $lastUnlockTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Vault Statistics / Security Score
        item {
            ScoreCard(
                title = "Security Score",
                score = "$securityScore%",
                description = scoreDescription,
                icon = if (securityScore >= 90) Icons.Default.Shield else Icons.Default.Warning,
                color = scoreColor
            )
        }

        // Health Metrics
        item {
            MetricCard(
                title = "Weak Passwords",
                count = "$weakPasswords",
                description = if (weakPasswords == 0) "Excellent" else "Needs attention",
                icon = if (weakPasswords == 0) Icons.Default.CheckCircle else Icons.Default.Warning,
                color = if (weakPasswords == 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
        }
        item {
            MetricCard(
                title = "Total Passwords",
                count = "$totalPasswords",
                description = "Secured in Vault",
                icon = Icons.Default.Lock,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Recent Activity
        item {
            ActivityCard(recentActivity)
        }

        // Quick Actions
        item {
            QuickActionsCard(
                onAddPassword = { vaultViewModel.showAddDialog(true) },
                onNavigateToGenerator = onNavigateToGenerator
            )
        }

        if (favorites.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Favorites",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // Favorites Grid Items
            items(favorites) { entry ->
                FavoriteItemCard(
                    entry = entry,
                    vaultViewModel = vaultViewModel
                )
            }
        }
    }
}

@Composable
private fun ScoreCard(title: String, score: String, description: String, icon: ImageVector, color: Color) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = score, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, count: String, description: String, icon: ImageVector, color: Color) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = count, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}

@Composable
private fun ActivityCard(recentActivity: List<VaultEntry>) {
    DashboardCard {
        Column {
            Text(text = "Recent Activity", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(16.dp))
            
            if (recentActivity.isEmpty()) {
                Text("No recent activity.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val dateFormat = SimpleDateFormat("MMM dd", Locale.getDefault())
                    recentActivity.forEach { entry ->
                        val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
                        val action = if (entry.createdAt == entry.updatedAt) "Added to vault" else "Updated password"
                        val icon = if (entry.createdAt == entry.updatedAt) Icons.Default.Add else Icons.Default.Update
                        val timeString = dateFormat.format(Date(entry.updatedAt))
                        ActivityRow(displayTitle, action, timeString, icon)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(title: String, action: String, time: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = action,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun QuickActionsCard(
    onAddPassword: () -> Unit,
    onNavigateToGenerator: () -> Unit
) {
    DashboardCard {
        Column {
            Text(text = "Quick Actions", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAddPassword, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Password")
                }
                OutlinedButton(onClick = onNavigateToGenerator, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.VpnKey, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate Password")
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    
    val elevation by animateDpAsState(if (isHovered) 4.dp else 1.dp)

    Surface(
        modifier = Modifier.fillMaxWidth().hoverable(interactionSource),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = elevation,
        shadowElevation = if (isHovered) 2.dp else 0.dp
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

@Composable
private fun FavoriteItemCard(entry: VaultEntry, vaultViewModel: VaultViewModel) {
    val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor = if (isHovered) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    val elevation by animateDpAsState(if (isHovered) 2.dp else 0.dp)

    Card(
        modifier = Modifier.fillMaxWidth().hoverable(interactionSource).clickable { vaultViewModel.showEditDialog(entry.id) },
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = displayTitle.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entry.username.isNotBlank()) {
                    Text(
                        text = entry.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { 
                    vaultViewModel.copyPasswordToClipboard(entry.secret, clipboardManager)
                },
                modifier = Modifier.alpha(if (isHovered) 1f else 0f)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
