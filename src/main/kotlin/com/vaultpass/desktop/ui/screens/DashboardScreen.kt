package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Dashboard Overview",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Vault Statistics / Security Score
        item {
            ScoreCard(
                title = "Security Score",
                score = "94%",
                description = "Your vault is in great shape.",
                icon = Icons.Default.Shield,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Health Metrics
        item {
            MetricCard(
                title = "Weak Passwords",
                count = "2",
                description = "Needs attention",
                icon = Icons.Default.Warning,
                color = MaterialTheme.colorScheme.error
            )
        }
        item {
            MetricCard(
                title = "Reused Passwords",
                count = "0",
                description = "Excellent",
                icon = Icons.Default.CheckCircle,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Recent Activity
        item {
            ActivityCard()
        }

        // Quick Actions
        item {
            QuickActionsCard()
        }

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
        items(6) { index ->
            FavoriteItemCard(index = index)
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
private fun ActivityCard() {
    DashboardCard {
        Column {
            Text(text = "Recent Activity", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ActivityRow("Google Account", "Password updated", "2 hrs ago", Icons.Default.Update)
                ActivityRow("Netflix", "Added to vault", "1 day ago", Icons.Default.Add)
                ActivityRow("Old Bank", "Moved to Recycle Bin", "3 days ago", Icons.Default.Delete)
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
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = action,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun QuickActionsCard() {
    DashboardCard {
        Column {
            Text(text = "Quick Actions", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Password")
                }
                OutlinedButton(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.VpnKey, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Generate Password")
                }
                OutlinedButton(onClick = { }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Review Security")
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(content: @Composable () -> Unit) {
    val interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    
    val elevation = androidx.compose.animation.core.animateDpAsState(if (isHovered) 4.dp else 1.dp)

    Surface(
        modifier = Modifier.fillMaxWidth().hoverable(interactionSource),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = elevation.value,
        shadowElevation = if (isHovered) 2.dp else 0.dp
    ) {
        Box(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

@Composable
private fun FavoriteItemCard(index: Int) {
    val items = listOf("GitHub", "AWS Console", "ProtonMail", "Slack", "Bank of America", "Twitter")
    val title = items.getOrNull(index) ?: "Service $index"
    
    val interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor = if (isHovered) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    val elevation = androidx.compose.animation.core.animateDpAsState(if (isHovered) 2.dp else 0.dp)

    Card(
        modifier = Modifier.fillMaxWidth().hoverable(interactionSource),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation.value),
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
                Text(text = title.take(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = "jane.doe@example.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { },
                modifier = Modifier.alpha(if (isHovered) 1f else 0f)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy Password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
