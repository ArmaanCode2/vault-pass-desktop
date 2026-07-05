package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class SettingsCategory(val title: String) {
    Security("Security"),
    Vault("Vault"),
    ImportExport("Import / Export"),
    Privacy("Privacy"),
    Appearance("Appearance"),
    Advanced("Advanced"),
    About("About")
}

@Composable
fun SettingsScreen() {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.Security) }

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Left Pane: Navigation
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))

            SettingsCategory.values().forEach { category ->
                SettingsCategoryItem(
                    category = category,
                    isSelected = selectedCategory == category,
                    onClick = { selectedCategory = category }
                )
            }
        }

        Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)

        // Right Pane: Content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(32.dp)
        ) {
            SettingsContentPane(selectedCategory)
        }
    }
}

@Composable
private fun SettingsCategoryItem(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = androidx.compose.runtime.remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else if (isHovered) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    } else {
        Color.Transparent // Assuming androidx.compose.ui.graphics.Color
    }

    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else if (isHovered) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .hoverable(interactionSource)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = category.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = contentColor
        )
    }
}

@Composable
private fun SettingsContentPane(category: SettingsCategory) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Text(
                text = category.title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (category) {
            SettingsCategory.Security -> {
                item {
                    SettingsToggleRow(
                        title = "Require Master Password on startup",
                        description = "Always prompt for your Master Password when opening VaultPass.",
                        initialValue = true
                    )
                }
                item {
                    SettingsToggleRow(
                        title = "Lock when minimized",
                        description = "Automatically lock the vault when the application window is minimized.",
                        initialValue = false
                    )
                }
                item {
                    SettingsDropdownRow(
                        title = "Auto-lock timeout",
                        description = "Lock the vault after a period of inactivity.",
                        value = "15 Minutes"
                    )
                }
            }
            SettingsCategory.Vault -> {
                item {
                    SettingsActionRow(
                        title = "Clear Clipboard automatically",
                        description = "Remove copied passwords from the system clipboard after 30 seconds.",
                        buttonText = "Clear Now"
                    )
                }
            }
            SettingsCategory.ImportExport -> {
                item {
                    SettingsActionRow(
                        title = "Export Vault",
                        description = "Export all vault entries to an encrypted .vpex file.",
                        buttonText = "Export"
                    )
                }
                item {
                    SettingsActionRow(
                        title = "Import Data",
                        description = "Import passwords from a .vpex backup or supported JSON format.",
                        buttonText = "Import"
                    )
                }
            }
            SettingsCategory.Privacy -> {
                item {
                    SettingsToggleRow(
                        title = "Hide Passwords",
                        description = "Obscure passwords in the detail view by default.",
                        initialValue = true
                    )
                }
            }
            SettingsCategory.Appearance -> {
                item {
                    SettingsDropdownRow(
                        title = "Theme",
                        description = "Choose the visual appearance of VaultPass.",
                        value = "System Default (Dark)"
                    )
                }
            }
            SettingsCategory.Advanced -> {
                item {
                    SettingsActionRow(
                        title = "Developer Mode",
                        description = "Enable advanced debugging features and logs.",
                        buttonText = "Enable"
                    )
                }
            }
            SettingsCategory.About -> {
                item {
                    Column {
                        Text("VaultPass Desktop v1.0.0-mock", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("An offline-first, secure password manager.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(title: String, description: String, initialValue: Boolean) {
    var checked by remember { mutableStateOf(initialValue) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(24.dp))
        Switch(checked = checked, onCheckedChange = { checked = it })
    }
}

@Composable
private fun SettingsActionRow(title: String, description: String, buttonText: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(24.dp))
        OutlinedButton(onClick = { /* TODO */ }) {
            Text(buttonText)
        }
    }
}

@Composable
private fun SettingsDropdownRow(title: String, description: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(24.dp))
        // Mock dropdown button
        OutlinedButton(onClick = { /* TODO */ }) {
            Text(value)
        }
    }
}
