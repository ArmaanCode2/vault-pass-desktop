package com.vaultpass.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.security.SecurityAnalyzer
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel

@Composable
fun SecurityCenterScreen(
    vaultViewModel: VaultViewModel,
    onNavigateToVault: () -> Unit
) {
    val vaultState by vaultViewModel.uiState.collectAsState()
    val report = SecurityAnalyzer.analyze(vaultState.entries)

    val scoreColor = when {
        report.score >= 90 -> MaterialTheme.colorScheme.primary
        report.score >= 70 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 400.dp),
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
                            .background(scoreColor.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = report.score.toString(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor
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
                        val desc = if (report.score == 100) "Your passwords are very secure." else "Address the warnings below to achieve a perfect score."
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Recommendations
        if (report.recommendations.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Recommendations",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        report.recommendations.forEach { rec ->
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("•", modifier = Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text(rec, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            }
        }

        // Metrics / Issue Sections
        item {
            IssueCard(
                title = "Weak Passwords",
                entries = report.weakEntries,
                icon = if (report.weakEntries.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Warning,
                color = if (report.weakEntries.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                vaultViewModel = vaultViewModel,
                onNavigateToVault = onNavigateToVault
            )
        }
        item {
            IssueCard(
                title = "Reused Passwords",
                entries = report.reusedEntries,
                icon = if (report.reusedEntries.isEmpty()) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                color = if (report.reusedEntries.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                vaultViewModel = vaultViewModel,
                onNavigateToVault = onNavigateToVault
            )
        }
        item {
            IssueCard(
                title = "Duplicate Entries",
                entries = report.duplicateEntries,
                icon = if (report.duplicateEntries.isEmpty()) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                color = if (report.duplicateEntries.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                vaultViewModel = vaultViewModel,
                onNavigateToVault = onNavigateToVault
            )
        }
        item {
            IssueCard(
                title = "Old Passwords",
                entries = report.oldEntries,
                icon = if (report.oldEntries.isEmpty()) Icons.Default.CheckCircle else Icons.Default.History,
                color = if (report.oldEntries.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                vaultViewModel = vaultViewModel,
                onNavigateToVault = onNavigateToVault
            )
        }
    }
}

@Composable
private fun IssueCard(
    title: String,
    entries: List<VaultEntry>,
    icon: ImageVector,
    color: Color,
    vaultViewModel: VaultViewModel,
    onNavigateToVault: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = entries.size.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (entries.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isExpanded) "Hide Details" else "Review Issues", color = MaterialTheme.colorScheme.primary)
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        entries.forEach { entry ->
                            val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        vaultViewModel.showEditDialog(entry.id)
                                        onNavigateToVault()
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = displayTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                    if (entry.username.isNotBlank()) {
                                        Text(text = entry.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("Fix", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
