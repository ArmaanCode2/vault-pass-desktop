package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.ui.theme.AccentColorDefinition
import com.vaultpass.desktop.ui.theme.AccentColors
import com.vaultpass.desktop.ui.theme.BaseThemeDefinition
import com.vaultpass.desktop.ui.theme.BaseThemes
import com.vaultpass.desktop.ui.theme.LocalSpacing
import com.vaultpass.desktop.ui.theme.LocalVaultPassExtendedColors

@Composable
fun ThemeGallery(
    currentBaseTheme: String,
    currentAccentColor: String,
    onBaseThemeSelected: (String) -> Unit,
    onAccentColorSelected: (String) -> Unit
) {
    val spacing = LocalSpacing.current

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = spacing.xl)) {
        
        // Base Theme Section
        Text(
            text = "Base Theme",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = spacing.xs)
        )
        Text(
            text = "Controls the application's background and surface brightness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.md)
        )

        // Light, Dark, AMOLED
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            BaseThemes.forEach { baseTheme ->
                BaseThemeCard(
                    theme = baseTheme,
                    isSelected = currentBaseTheme == baseTheme.name || (currentBaseTheme == "System Default" && baseTheme.name == "Dark"),
                    onClick = { onBaseThemeSelected(baseTheme.name) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.lg))

        // Accent Color Section
        Text(
            text = "Accent Color",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = spacing.xs)
        )
        Text(
            text = "Highlights interactive elements like selections and buttons.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = spacing.md)
        )

        // 4 items per row layout approx
        Box(modifier = Modifier.height(180.dp).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
                modifier = Modifier.fillMaxSize()
            ) {
                items(AccentColors) { accent ->
                    AccentColorCard(
                        accent = accent,
                        isSelected = currentAccentColor == accent.name,
                        onClick = { onAccentColorSelected(accent.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BaseThemeCard(
    theme: BaseThemeDefinition,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val backgroundColor = theme.glassBase // Preview with the actual theme's surface color!

    Surface(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .border(
                BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) borderColor else MaterialTheme.colorScheme.outline), 
                RoundedCornerShape(8.dp)
            ),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Theme colors preview
            Row(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(theme.background)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(theme.surfaceVariant))
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                text = theme.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = theme.onSurface, // Use the preview's onSurface color!
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AccentColorCard(
    accent: AccentColorDefinition,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val backgroundColor = LocalVaultPassExtendedColors.current.glassBase

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .border(
                BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) borderColor else MaterialTheme.colorScheme.outline), 
                RoundedCornerShape(8.dp)
            ),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(accent.primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = accent.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}
