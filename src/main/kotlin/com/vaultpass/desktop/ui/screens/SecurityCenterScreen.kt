package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SecurityCenterScreen() {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        contentPadding = PaddingValues(32.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    text = "Security Center",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Review and improve your vault's security.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Master Score
        item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "94",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(24.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Overall Security Score",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Your passwords are very secure. Address the warnings below to achieve a perfect score.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { /* TODO */ }) {
                        Text("Re-scan Vault")
                    }
                }
            }
        }

        // Metrics
        item {
            SecurityMetricCard(
                title = "Weak Passwords",
                count = "2",
                icon = Icons.Default.Warning,
                color = MaterialTheme.colorScheme.error,
                actionText = "Review"
            )
        }
        item {
            SecurityMetricCard(
                title = "Reused Passwords",
                count = "0",
                icon = Icons.Default.CheckCircle,
                color = MaterialTheme.colorScheme.primary,
                actionText = "View all"
            )
        }
        item {
            SecurityMetricCard(
                title = "Compromised Passwords",
                count = "0",
                icon = Icons.Default.Security,
                color = MaterialTheme.colorScheme.primary,
                actionText = "Learn more"
            )
        }
    }
}

@Composable
private fun SecurityMetricCard(
    title: String,
    count: String,
    icon: ImageVector,
    color: Color,
    actionText: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = icon, contentDescription = null, tint = color)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = count,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = { /* TODO */ }, modifier = Modifier.fillMaxWidth()) {
                Text(actionText)
            }
        }
    }
}
