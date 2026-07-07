package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.domain.platform.FileDialogProvider
import com.vaultpass.desktop.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

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
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    fileDialogProvider: FileDialogProvider,
    databasePath: String
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.Security) }
    val settingsState by settingsViewModel.settingsState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding)) {
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    item {
                        Text(
                            text = selectedCategory.title,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    when (selectedCategory) {
                        SettingsCategory.Security -> {
                            item {
                                SettingsToggleRow(
                                    title = "Require Master Password on startup",
                                    description = "Always prompt for your Master Password when opening VaultPass.",
                                    value = settingsState.requireMasterPasswordOnStartup,
                                    onValueChange = { checked -> settingsViewModel.updateSettings { it.copy(requireMasterPasswordOnStartup = checked) } }
                                )
                            }
                            item {
                                SettingsToggleRow(
                                    title = "Lock when minimized",
                                    description = "Automatically lock the vault when the application window is minimized.",
                                    value = settingsState.lockWhenMinimized,
                                    onValueChange = { checked -> settingsViewModel.updateSettings { it.copy(lockWhenMinimized = checked) } }
                                )
                            }
                            item {
                                val options = listOf(1, 5, 15, 30, 60)
                                SettingsDropdownRow(
                                    title = "Auto-lock timeout",
                                    description = "Lock the vault after a period of inactivity.",
                                    currentValue = "${settingsState.autoLockTimeoutMinutes} Minutes",
                                    options = options.map { "$it Minutes" },
                                    onSelect = { selected -> 
                                        val mins = selected.split(" ")[0].toIntOrNull() ?: 15
                                        settingsViewModel.updateSettings { it.copy(autoLockTimeoutMinutes = mins) }
                                    }
                                )
                            }
                        }
                        SettingsCategory.Vault -> {
                            item {
                                val options = listOf(15, 30, 60, 120)
                                SettingsDropdownRow(
                                    title = "Clipboard Timeout",
                                    description = "Remove copied passwords from the system clipboard.",
                                    currentValue = "${settingsState.clearClipboardTimeoutSeconds} Seconds",
                                    options = options.map { "$it Seconds" },
                                    onSelect = { selected -> 
                                        val secs = selected.split(" ")[0].toIntOrNull() ?: 30
                                        settingsViewModel.updateSettings { it.copy(clearClipboardTimeoutSeconds = secs) }
                                    }
                                )
                            }
                            item {
                                SettingsInfoRow(
                                    title = "Vault Location",
                                    description = databasePath
                                )
                            }
                        }
                        SettingsCategory.ImportExport -> {
                            item {
                                SettingsActionRow(
                                    title = "Export Vault",
                                    description = "Export all vault entries to an encrypted .vpex file.",
                                    buttonText = "Export",
                                    onClick = {
                                        coroutineScope.launch {
                                            val file = fileDialogProvider.showSaveFileDialog("Export Vault", "vaultpass_export.vpex")
                                            if (file != null) {
                                                snackbarHostState.showSnackbar("Export selected: ${file.absolutePath}")
                                            }
                                        }
                                    }
                                )
                            }
                            item {
                                SettingsActionRow(
                                    title = "Import Data",
                                    description = "Import passwords from a .vpex backup or supported JSON format.",
                                    buttonText = "Import",
                                    onClick = {
                                        coroutineScope.launch {
                                            val file = fileDialogProvider.showOpenFileDialog("Import Vault", listOf("vpex", "json", "csv"))
                                            if (file != null) {
                                                snackbarHostState.showSnackbar("Import selected: ${file.absolutePath}")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        SettingsCategory.Privacy -> {
                            item {
                                SettingsToggleRow(
                                    title = "Hide Passwords",
                                    description = "Obscure passwords in the detail view by default.",
                                    value = settingsState.hidePasswordsByDefault,
                                    onValueChange = { checked -> settingsViewModel.updateSettings { it.copy(hidePasswordsByDefault = checked) } }
                                )
                            }
                        }
                        SettingsCategory.Appearance -> {
                            item {
                                SettingsDropdownRow(
                                    title = "Theme",
                                    description = "Choose the visual appearance of VaultPass.",
                                    currentValue = settingsState.theme,
                                    options = listOf("System Default", "Dark", "Light"),
                                    onSelect = { theme -> settingsViewModel.updateSettings { it.copy(theme = theme) } }
                                )
                            }
                        }
                        SettingsCategory.Advanced -> {
                            item {
                                SettingsToggleRow(
                                    title = "Developer Mode",
                                    description = "Enable advanced debugging features and logs.",
                                    value = settingsState.developerMode,
                                    onValueChange = { checked -> settingsViewModel.updateSettings { it.copy(developerMode = checked) } }
                                )
                            }
                        }
                        SettingsCategory.About -> {
                            item {
                                Column {
                                    Text("VaultPass Desktop v1.0.0", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("An offline-first, secure password manager.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryItem(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else if (isHovered) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    } else {
        Color.Transparent
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
private fun SettingsToggleRow(title: String, description: String, value: Boolean, onValueChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onValueChange(!value) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(24.dp))
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
private fun SettingsActionRow(title: String, description: String, buttonText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(24.dp))
        OutlinedButton(onClick = onClick) {
            Text(buttonText)
        }
    }
}

@Composable
private fun SettingsInfoRow(title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsDropdownRow(title: String, description: String, currentValue: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.width(24.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(currentValue)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { 
                            onSelect(option)
                            expanded = false 
                        },
                        trailingIcon = if (option == currentValue) { { Icon(Icons.Default.Check, contentDescription = null) } } else null
                    )
                }
            }
        }
    }
}
