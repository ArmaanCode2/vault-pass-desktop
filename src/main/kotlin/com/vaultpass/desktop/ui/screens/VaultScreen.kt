package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.data.models.VaultEntryPayload
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel
import kotlinx.coroutines.launch

@Composable
fun VaultScreen(viewModel: VaultViewModel) {
    val state by viewModel.uiState.collectAsState()
    var selectedEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var sortAscending by remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Clear selection if it was deleted
    LaunchedEffect(state.entries) {
        if (selectedEntry != null && state.entries.none { it.id == selectedEntry?.id }) {
            selectedEntry = null
        } else if (selectedEntry != null) {
            // Update selected entry with new data
            selectedEntry = state.entries.find { it.id == selectedEntry?.id }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Error: $it")
                viewModel.clearError()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Row(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Left Pane: Master List
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                VaultToolbar(
                    searchQuery = state.query.searchQuery,
                    onSearchChange = { viewModel.updateSearchQuery(it) },
                    sortAscending = sortAscending,
                    onToggleSort = { sortAscending = !sortAscending }
                )

                Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

                if (state.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (state.entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.SearchOff, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(if (state.query.searchQuery.isBlank()) "Vault is empty" else "No entries found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        val sortedEntries = state.entries.sortedWith(
                            compareBy<VaultEntry> { it.title.lowercase() }.let { if (sortAscending) it else it.reversed() }
                        )
                        items(sortedEntries) { entry ->
                            VaultRowItem(
                                entry = entry,
                                isSelected = selectedEntry?.id == entry.id,
                                onClick = { selectedEntry = entry },
                                onCopyPassword = {
                                    viewModel.copyPasswordToClipboard(entry.secret, clipboardManager)
                                    coroutineScope.launch { snackbarHostState.showSnackbar("Password copied (clears in 15s)") }
                                },
                                onEdit = { viewModel.showEditDialog(entry.id) }
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
                    VaultDetailPanel(
                        entry = selectedEntry!!,
                        onEdit = { viewModel.showEditDialog(it.id) },
                        onDelete = { viewModel.deleteEntry(it.id) },
                        onCopyPassword = {
                            viewModel.copyPasswordToClipboard(it, clipboardManager)
                            coroutineScope.launch { snackbarHostState.showSnackbar("Password copied (clears in 15s)") }
                        },
                        onCopyUsername = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(it))
                            coroutineScope.launch { snackbarHostState.showSnackbar("Username copied") }
                        }
                    )
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultToolbar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortAscending: Boolean,
    onToggleSort: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        BasicTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.weight(1f).height(40.dp),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            interactionSource = interactionSource,
            decorationBox = @Composable { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = searchQuery,
                    innerTextField = innerTextField,
                    enabled = true,
                    singleLine = true,
                    visualTransformation = VisualTransformation.None,
                    interactionSource = interactionSource,
                    placeholder = { Text("Search vault...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    contentPadding = PaddingValues(top = 0.dp, bottom = 0.dp, start = 12.dp, end = 12.dp),
                    container = {
                        OutlinedTextFieldDefaults.ContainerBox(
                            enabled = true,
                            isError = false,
                            interactionSource = interactionSource,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            ),
                            shape = MaterialTheme.shapes.small
                        )
                    }
                )
            }
        )
        Spacer(modifier = Modifier.width(16.dp))
        IconButton(onClick = onToggleSort) {
            Icon(
                imageVector = Icons.Default.SortByAlpha,
                contentDescription = "Sort",
                tint = if (sortAscending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun VaultRowItem(
    entry: VaultEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onCopyPassword: () -> Unit,
    onEdit: () -> Unit
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
        val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
        val displayInitial = displayTitle.take(1).uppercase()
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(displayInitial, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (entry.username.isNotBlank()) {
                Text(entry.username, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        
        if (isHovered || isSelected || isFocused) {
            IconButton(onClick = onCopyPassword, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VaultDetailPanel(
    entry: VaultEntry,
    onEdit: (VaultEntry) -> Unit,
    onDelete: (VaultEntry) -> Unit,
    onCopyPassword: (String) -> Unit,
    onCopyUsername: (String) -> Unit
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var isPasswordVisible by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
    val displayInitial = displayTitle.take(1).uppercase()

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
                Text(displayInitial, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(modifier = Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(displayTitle, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("Login Item", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            
            Button(onClick = { onEdit(entry) }) {
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
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; showDeleteConfirm = true })
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Username Field
        DetailField(label = "Username", value = entry.username, canCopy = true, onCopy = { onCopyUsername(entry.username) })
        Spacer(modifier = Modifier.height(16.dp))

        // Password Field
        DetailField(
            label = "Password",
            value = entry.secret,
            isPassword = true,
            isPasswordVisible = isPasswordVisible,
            onToggleVisibility = { isPasswordVisible = !isPasswordVisible },
            canCopy = true,
            onCopy = { onCopyPassword(entry.secret) }
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Website Field
        DetailField(
            label = "Website", 
            value = entry.url, 
            canCopy = true, 
            onCopy = {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(entry.url))
            },
            icon = Icons.Default.OpenInNew,
            onIconClick = {
                try {
                    if (entry.url.isNotBlank() && java.awt.Desktop.isDesktopSupported()) {
                        val uri = if (!entry.url.startsWith("http")) java.net.URI("https://${entry.url}") else java.net.URI(entry.url)
                        java.awt.Desktop.getDesktop().browse(uri)
                    }
                } catch (e: Exception) {
                    // Ignore or log error
                }
            }
        )
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Entry") },
            text = { Text("Are you sure you want to delete '$displayTitle'? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry)
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
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
    onCopy: (() -> Unit)? = null,
    icon: ImageVector? = null,
    onIconClick: (() -> Unit)? = null
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

            if (canCopy && onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (icon != null) {
                IconButton(onClick = { onIconClick?.invoke() }, modifier = Modifier.size(32.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun EntryDialog(
    entry: VaultEntry?,
    onDismiss: () -> Unit,
    onSave: (VaultEntryPayload.PasswordPayload) -> Unit,
    pendingPassword: String? = null
) {
    var title by remember { mutableStateOf(entry?.title ?: "") }
    var username by remember { mutableStateOf(entry?.username ?: "") }
    var password by remember { mutableStateOf(entry?.secret ?: pendingPassword ?: "") }
    var url by remember { mutableStateOf(entry?.url ?: "") }
    var notes by remember { mutableStateOf(entry?.notes ?: "") }
    var isPasswordVisible by remember { mutableStateOf(false) }

    var titleError by remember { mutableStateOf(false) }

    val generateRandomPassword = {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        val sb = java.lang.StringBuilder()
        for (i in 0 until 16) {
            sb.append(chars[kotlin.random.Random.nextInt(chars.length)])
        }
        password = sb.toString()
        isPasswordVisible = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "Add Password" else "Edit Password") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; titleError = false },
                    label = { Text("Title") },
                    isError = titleError,
                    modifier = Modifier.fillMaxWidth()
                )
                if (titleError) {
                    Text("Title is required", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle Visibility"
                                )
                            }
                            IconButton(onClick = { generateRandomPassword() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Generate inline")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                PasswordStrengthIndicator(password)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Website URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                        return@Button
                    }
                    onSave(
                        VaultEntryPayload.PasswordPayload(
                            title = title,
                            username = username,
                            secret = password,
                            url = url,
                            notes = notes
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
