@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.vaultpass.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.ui.theme.LocalSpacing
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    viewModel: VaultViewModel,
    searchFocusRequester: androidx.compose.ui.focus.FocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
) {
    val state by viewModel.uiState.collectAsState()
    var selectedEntryIds by remember { mutableStateOf(setOf<String>()) }
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // Clear selection if deleted/restored
    LaunchedEffect(state.recycledEntries) {
        val currentIds = state.recycledEntries.map { it.id }.toSet()
        selectedEntryIds = selectedEntryIds.filter { it in currentIds }.toSet()
    }

    val spacing = LocalSpacing.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D)) // Canvas background
                .padding(paddingValues)
                .padding(spacing.lg)
        ) {
            // Toolbar (Title & Search)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recycle Bin",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    val retentionDays = state.recycleBinRetentionDays
                    Text(
                        text = if (retentionDays > 0) {
                            "Items deleted from your vault. Entries older than $retentionDays days are automatically purged forever."
                        } else {
                            "Items deleted from your vault. Auto-delete is disabled."
                        },
                        fontSize = 11.sp,
                        color = Color(0xFF888888)
                    )
                }

                // Sticky Search Card
                Box(
                    modifier = Modifier
                        .width(320.dp)
                        .height(36.dp)
                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = state.recycleBinQuery.searchQuery,
                            onValueChange = { viewModel.updateRecycleBinSearchQuery(it) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color(0xFFE8E8E8),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                            decorationBox = { innerTextField ->
                                if (state.recycleBinQuery.searchQuery.isEmpty()) {
                                    Text("Search bin...", fontSize = 12.sp, color = Color(0xFF888888))
                                }
                                innerTextField()
                            }
                        )
                    }
                }

                if (state.recycledEntries.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { showEmptyConfirm = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E1E1E), // Button Inactive style background
                            contentColor = Color(0xFFFF6B6B) // Error text color
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFFFF6B6B))
                        Spacer(Modifier.width(8.dp))
                        Text("Empty Bin", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedEntryIds.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    color = Color(0xFF1A1A1A),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF2E2E2E))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedEntryIds.size} item(s) selected",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        val isAllSelected = state.recycledEntries.isNotEmpty() && selectedEntryIds.size == state.recycledEntries.size
                        OutlinedButton(
                            onClick = {
                                selectedEntryIds = if (isAllSelected) emptySet() else state.recycledEntries.map { it.id }.toSet()
                            },
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(0xFF3E3E3E)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCCCCCC)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(if (isAllSelected) "Deselect All" else "Select All", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                val count = selectedEntryIds.size
                                viewModel.restoreSelectedEntries(selectedEntryIds.toList())
                                selectedEntryIds = emptySet()
                                coroutineScope.launch { snackbarHostState.showSnackbar("Restored $count item(s)") }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore (${selectedEntryIds.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { showBatchDeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF242424), contentColor = Color(0xFFFF6B6B)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Delete Forever (${selectedEntryIds.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (state.isRecycleBinLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF0EA5A1))
                }
            } else if (state.recycledEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color(0xFF888888)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Recycle Bin is empty",
                            color = Color(0xFF888888),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                RecycleBinTable(
                    entries = state.recycledEntries.sortedByDescending { it.deletedAt ?: it.updatedAt },
                    selectedIds = selectedEntryIds,
                    retentionDays = state.recycleBinRetentionDays,
                    onSelectionChange = { id ->
                        selectedEntryIds = if (id in selectedEntryIds) selectedEntryIds - id else selectedEntryIds + id
                    },
                    onRestore = {
                        viewModel.restoreEntry(it.id)
                        coroutineScope.launch { snackbarHostState.showSnackbar("Entry restored") }
                    },
                    onDelete = {
                        viewModel.permanentlyDeleteEntry(it.id)
                        coroutineScope.launch { snackbarHostState.showSnackbar("Entry deleted forever") }
                    }
                )
            }
        }
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = { Text("Empty Recycle Bin", color = Color.White) },
            text = { Text("Are you sure you want to permanently delete all ${state.recycledEntries.size} items in the Recycle Bin? This action cannot be undone.", color = Color(0xFFE8E8E8)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emptyRecycleBin()
                        showEmptyConfirm = false
                        coroutineScope.launch { snackbarHostState.showSnackbar("Recycle Bin emptied") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B), contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Empty Bin", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) { Text("Cancel", color = Color(0xFF888888)) }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }

    if (showBatchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteConfirm = false },
            title = { Text("Permanently Delete Selected Items", color = Color.White) },
            text = { Text("Are you sure you want to permanently delete ${selectedEntryIds.size} items? This cannot be undone.", color = Color(0xFFE8E8E8)) },
            confirmButton = {
                Button(
                    onClick = {
                        val count = selectedEntryIds.size
                        viewModel.permanentlyDeleteSelectedEntries(selectedEntryIds.toList())
                        selectedEntryIds = emptySet()
                        showBatchDeleteConfirm = false
                        coroutineScope.launch { snackbarHostState.showSnackbar("Permanently deleted $count item(s)") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B), contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp)
                ) { Text("Delete Forever", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) { Text("Cancel", color = Color(0xFF888888)) }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }
}

@Composable
private fun RecycleBinTable(
    entries: List<VaultEntry>,
    selectedIds: Set<String>,
    retentionDays: Int,
    onSelectionChange: (String) -> Unit,
    onRestore: (VaultEntry) -> Unit,
    onDelete: (VaultEntry) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries, key = { it.id }) { entry ->
            RecycleBinRow(
                entry = entry,
                isSelected = entry.id in selectedIds,
                retentionDays = retentionDays,
                onToggleSelect = { onSelectionChange(entry.id) },
                onRestore = { onRestore(entry) },
                onDelete = { onDelete(entry) }
            )
        }
    }
}

@Composable
private fun RecycleBinRow(
    entry: VaultEntry,
    isSelected: Boolean,
    retentionDays: Int,
    onToggleSelect: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    var showContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(androidx.compose.ui.unit.DpOffset.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    val cardBg = if (isSelected) {
        Color(0xFFFF6B6B).copy(alpha = 0.15f)
    } else if (isHovered) {
        Color(0xFF1E1E1E)
    } else {
        Color(0xFF1A1A1A)
    }

    val cardBorder = if (isSelected) {
        Color(0xFFFF6B6B)
    } else {
        Color(0xFF242424)
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                .clickable { onToggleSelect() }
                .hoverable(interactionSource)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                                if (event.button?.let { it == androidx.compose.ui.input.pointer.PointerButton.Secondary } == true) {
                                    val pos = event.changes.first().position
                                    contextMenuOffset = with(density) {
                                        androidx.compose.ui.unit.DpOffset(pos.x.toDp() + 8.dp, pos.y.toDp() + 8.dp)
                                    }
                                    showContextMenu = true
                                }
                            }
                        }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFFFF6B6B),
                    uncheckedColor = Color(0xFF888888)
                )
            )
            Spacer(modifier = Modifier.width(16.dp))

            val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title

            Row(modifier = Modifier.weight(2f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF242424)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(displayTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.username.isNotBlank()) {
                        Text(entry.username, fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1.2f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (entry.deletedAt != null) dateFormat.format(Date(entry.deletedAt)) else "Unknown",
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (retentionDays > 0 && entry.deletedAt != null) {
                    val daysPassed = ((System.currentTimeMillis() - entry.deletedAt) / (1000L * 60 * 60 * 24)).toInt()
                    val daysLeft = maxOf(0, retentionDays - daysPassed)
                    val (badgeText, badgeBg, badgeFg) = when {
                        daysLeft == 0 -> Triple("Expires today", Color(0xFFFF6B6B).copy(alpha = 0.2f), Color(0xFFFF6B6B))
                        daysLeft == 1 -> Triple("Expires tomorrow", Color(0xFFFFB900).copy(alpha = 0.2f), Color(0xFFFFB900))
                        else -> Triple("Expires in $daysLeft days", Color(0xFF242424), Color(0xFF888888))
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = badgeFg
                        )
                    }
                } else if (retentionDays == 0) {
                    Text(
                        text = "No auto-expiry",
                        fontSize = 10.sp,
                        color = Color(0xFF666666)
                    )
                }
            }

            // Actions: Inactive background, teal restore, red delete
            Row(modifier = Modifier.width(96.dp), horizontalArrangement = Arrangement.End) {
                if (isHovered || isSelected) {
                    IconButton(
                        onClick = onRestore,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E1E))
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = "Restore", modifier = Modifier.size(16.dp), tint = Color(0xFF0EA5A1))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E1E))
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "Delete Forever", modifier = Modifier.size(16.dp), tint = Color(0xFFFF6B6B))
                    }
                }
            }
        }
        com.vaultpass.desktop.ui.components.AppContextMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = contextMenuOffset
        ) {
            DropdownMenuItem(
                text = { Text("Restore") },
                onClick = { showContextMenu = false; onRestore() },
                leadingIcon = { Icon(Icons.Default.Restore, null, tint = Color(0xFF0EA5A1)) }
            )
            DropdownMenuItem(
                text = { Text("Delete Forever", color = Color(0xFFFF6B6B)) },
                onClick = { showContextMenu = false; onDelete() },
                leadingIcon = { Icon(Icons.Default.DeleteForever, null, tint = Color(0xFFFF6B6B)) }
            )
        }
    }
}
