package com.vaultpass.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.ui.theme.LocalSpacing
import com.vaultpass.desktop.ui.theme.SuccessGreen
import com.vaultpass.desktop.ui.theme.WarningYellow
import com.vaultpass.desktop.ui.theme.DangerRed
import com.vaultpass.desktop.ui.viewmodels.DashboardViewModel
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel
import kotlin.math.roundToInt

@Composable
fun SecurityCenterScreen(
    vaultViewModel: VaultViewModel,
    dashboardViewModel: DashboardViewModel,
    onNavigateToVault: () -> Unit
) {
    val dashboardState by dashboardViewModel.uiState.collectAsState()
    val report = dashboardState.securityReport
    val spacing = LocalSpacing.current

    val securityScore = report.score
    val scoreColor = when {
        dashboardState.totalPasswords == 0 -> Color(0xFF888888)
        securityScore >= 90 -> SuccessGreen
        securityScore >= 70 -> WarningYellow
        else -> DangerRed
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D)) // Canvas background
            .padding(spacing.lg)
    ) {
        // Master Score & Recommendations (enclosed in a card)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
                .padding(16.dp)
        ) {
            val animatedScore = remember { Animatable(0f) }
            LaunchedEffect(securityScore) {
                animatedScore.animateTo(
                    targetValue = securityScore.toFloat(),
                    animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { animatedScore.value / 100f },
                        color = scoreColor,
                        strokeWidth = 6.dp,
                        trackColor = scoreColor.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxSize()
                    )
                    Text(
                        text = "${animatedScore.value.roundToInt()}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scoreColor
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Security Score",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            dashboardState.totalPasswords == 0 -> "Add some passwords to generate a report."
                            securityScore == 100 -> "Your vault meets all security recommendations."
                            else -> "Review and fix weak or reused passwords to secure your vault."
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Vulnerabilities & Issues",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // List of entries with issues in cards with badges
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
                .padding(16.dp)
        ) {
            val allIssues = remember(report) {
                val weak = report.weakEntries.map { it to "Weak Password" }
                val reused = report.reusedEntries.map { it to "Reused Password" }
                (weak + reused).distinctBy { it.first.id }
            }

            if (allIssues.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No security issues detected",
                            fontSize = 14.sp,
                            color = Color(0xFFE8E8E8)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(allIssues, key = { it.first.id }) { (entry, issueType) ->
                        val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
                        val isWeak = issueType == "Weak Password"
                        val badgeBg = if (isWeak) Color(0xFFFF6B6B).copy(alpha = 0.15f) else Color(0xFFFFB900).copy(alpha = 0.15f)
                        val badgeTextCol = if (isWeak) Color(0xFFFF6B6B) else Color(0xFFFFB900)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                                .background(Color(0xFF0D0D0D)) // Canvas background inside card
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = displayTitle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // Severity Badge
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(badgeBg)
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isWeak) "Weak" else "Reused",
                                            color = badgeTextCol,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isWeak) {
                                        "This password is easy to guess. Update it with a strong, randomized password."
                                    } else {
                                        "This password is used across multiple accounts. Reusing passwords increases breach risks."
                                    },
                                    fontSize = 11.sp,
                                    color = Color(0xFF888888)
                                )
                            }
                            
                            // Action Button: Inactive style by default (#1e1e1e, color #aaa)
                            Button(
                                onClick = {
                                    vaultViewModel.showEditDialog(entry.id)
                                    onNavigateToVault()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF1E1E1E),
                                    contentColor = Color(0xFFE8E8E8)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Fix", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
