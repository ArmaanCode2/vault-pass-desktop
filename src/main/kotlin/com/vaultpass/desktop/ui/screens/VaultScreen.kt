package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

data class MockVaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val website: String,
    val notes: String
)

private val MOCK_ENTRIES = listOf(
    MockVaultEntry("1", "Google Account", "user@gmail.com", "https://google.com", "Main personal account"),
    MockVaultEntry("2", "GitHub", "dev-user", "https://github.com", "Work repositories"),
    MockVaultEntry("3", "Bank of America", "jane.doe", "https://bankofamerica.com", "Checking and savings"),
    MockVaultEntry("4", "Twitter", "@jane_doe", "https://twitter.com", ""),
    MockVaultEntry("5", "Netflix", "family@example.com", "https://netflix.com", "Shared with family"),
    MockVaultEntry("6", "Amazon", "user@gmail.com", "https://amazon.com", "Prime account")
)

@Composable
fun VaultScreen() {
    var selectedEntry by remember { mutableStateOf<MockVaultEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredEntries = MOCK_ENTRIES.filter {
        it.title.contains(searchQuery, ignoreCase = true) || it.username.contains(searchQuery, ignoreCase = true)
    }

    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Left Pane: Master List
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            VaultToolbar(
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it }
            )

            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

            if (filteredEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No entries found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredEntries) { entry ->
                        VaultRowItem(
                            entry = entry,
                            isSelected = selectedEntry?.id == entry.id,
                            onClick = { selectedEntry = entry }
                        )
                    }
                }
            }
        }

        Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = MaterialTheme.colorScheme.outlineVariant)

        // Right Pane: Detail View
        Box(
            modifier = Modifier
                .weight(1.5f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (selectedEntry == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select an item to view details", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                VaultDetailPanel(entry = selectedEntry!!)
            }
        }
    }
}

@Composable
private fun VaultToolbar(
    searchQuery: String,
    onSearchChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.weight(1f).height(40.dp),
            placeholder = { Text("Search vault...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        Spacer(modifier = Modifier.width(16.dp))
        IconButton(onClick = { /* TODO */ }) {
            Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = { /* TODO */ }) {
            Icon(Icons.Default.SortByAlpha, contentDescription = "Sort", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun VaultRowItem(
    entry: MockVaultEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else if (isHovered) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp) // Dense desktop row
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .hoverable(interactionSource = interactionSource)
            .focusable(interactionSource = interactionSource)
            .then(
                if (isFocused && !isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                else Modifier
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(entry.title.take(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Text(entry.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        if (isHovered || isSelected || isFocused) {
            IconButton(onClick = { /* Copy Password */ }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { /* Edit */ }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VaultDetailPanel(entry: MockVaultEntry) {
    var isPasswordVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(entry.title.take(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("Login Item", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            
            Button(onClick = { /* TODO */ }) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Edit")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(text = { Text("Move to Trash") }, onClick = { menuExpanded = false })
                    DropdownMenuItem(text = { Text("Export") }, onClick = { menuExpanded = false })
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Username Field
        DetailField(label = "Username", value = entry.username, canCopy = true)
        Spacer(modifier = Modifier.height(16.dp))

        // Password Field
        DetailField(
            label = "Password",
            value = "placeholder_secure_string", // Mock
            isPassword = true,
            isPasswordVisible = isPasswordVisible,
            onToggleVisibility = { isPasswordVisible = !isPasswordVisible },
            canCopy = true
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Website Field
        DetailField(label = "Website", value = entry.website, canCopy = true, icon = Icons.Default.OpenInNew)
        Spacer(modifier = Modifier.height(32.dp))

        // Notes
        Text("Notes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                text = if (entry.notes.isBlank()) "No notes provided." else entry.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.notes.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun DetailField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    isPasswordVisible: Boolean = true,
    onToggleVisibility: (() -> Unit)? = null,
    canCopy: Boolean = false,
    icon: ImageVector? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val visualTransformation = if (isPassword && !isPasswordVisible) PasswordVisualTransformation() else VisualTransformation.None
            val displayValue = if (isPassword && !isPasswordVisible) "••••••••••••••••" else value

            Text(
                text = displayValue,
                style = if (isPassword) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )

            if (isPassword && onToggleVisibility != null) {
                IconButton(onClick = onToggleVisibility, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle Visibility",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (canCopy) {
                IconButton(onClick = { /* TODO */ }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (icon != null) {
                IconButton(onClick = { /* TODO */ }, modifier = Modifier.size(32.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
