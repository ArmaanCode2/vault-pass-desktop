package com.vaultpass.desktop.ui.sync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vaultpass.desktop.domain.sync.diff.EntryDiffItem
import com.vaultpass.desktop.domain.sync.diff.EntrySyncCategory
import com.vaultpass.desktop.domain.sync.diff.FieldChangeType
import com.vaultpass.desktop.ui.viewmodels.SyncViewModel

@Composable
fun SyncReviewDialog(
    syncViewModel: SyncViewModel,
    onDismiss: () -> Unit,
    onSuccessMerge: (Int) -> Unit
) {
    val diffResult by syncViewModel.activeDiffResult.collectAsState()
    val syncingDevice by syncViewModel.activeSyncingDevice.collectAsState()
    var showCancelConfirm by remember { mutableStateOf(false) }

    if (diffResult == null) return

    val result = diffResult!!
    val items = result.diffItems
    val allSelected = items.isNotEmpty() && items.all { it.isSelectedForSync }
    val mergeCandidateCount = items.count {
        it.isSelectedForSync && (it.category == EntrySyncCategory.NEW_REMOTE || it.category == EntrySyncCategory.MODIFIED || it.category == EntrySyncCategory.DELETED_LOCAL)
    }

    Dialog(
        onDismissRequest = { showCancelConfirm = true },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
            border = BorderStroke(1.dp, Color(0xFF262626))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Review Changes — ${syncingDevice?.deviceName ?: "Mobile Device"}",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Summary Badges
                            BadgePill(
                                label = "${result.newRemoteCount} New",
                                backgroundColor = Color(0xFF143823),
                                textColor = Color(0xFF4ADE80)
                            )
                            BadgePill(
                                label = "${result.modifiedCount} Modified",
                                backgroundColor = Color(0xFF3B2F10),
                                textColor = Color(0xFFFBBF24)
                            )
                            BadgePill(
                                label = "${result.unchangedCount} Unchanged",
                                backgroundColor = Color(0xFF262626),
                                textColor = Color(0xFFA3A3A3)
                            )
                            if (result.deletedLocalCount > 0) {
                                BadgePill(
                                    label = "${result.deletedLocalCount} Deleted Locally",
                                    backgroundColor = Color(0xFF381414),
                                    textColor = Color(0xFFF87171)
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { showCancelConfirm = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF262626), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF262626))

                // 2. Selection Control Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF171717))
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = { checked -> syncViewModel.toggleSelectAll(checked) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF0EA5A1),
                            uncheckedColor = Color(0xFF666666),
                            checkmarkColor = Color.Black
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (allSelected) "Deselect All Entries" else "Select All Entries",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFCCCCCC)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${items.count { it.isSelectedForSync }} of ${items.size} selected",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }

                HorizontalDivider(color = Color(0xFF262626))

                // 3. Scrollable List of Entry Cards
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items, key = { it.syncKey }) { item ->
                        DiffEntryCard(
                            item = item,
                            onToggleSelect = { isChecked ->
                                syncViewModel.toggleEntrySelection(item.syncKey, isChecked)
                            },
                            onUpdateField = { field, value ->
                                syncViewModel.updateEntryFieldOverride(item.syncKey, field, value)
                            },
                            onUsePhoneValue = {
                                syncViewModel.applyRemoteValue(item.syncKey)
                            },
                            onKeepLocalValue = {
                                syncViewModel.applyLocalValue(item.syncKey)
                            }
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF262626))

                // 4. Bottom Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A1A))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { showCancelConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF999999)),
                        border = BorderStroke(1.dp, Color(0xFF333333))
                    ) {
                        Text("Cancel")
                    }

                    if (mergeCandidateCount == 0) {
                        Button(
                            onClick = {
                                syncViewModel.confirmAndMerge { count ->
                                    onSuccessMerge(count)
                                }
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "All Entries Up-To-Date (Finish)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                syncViewModel.confirmAndMerge { count ->
                                    onSuccessMerge(count)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0EA5A1),
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Apply Sync (Merge $mergeCandidateCount Entries)",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // Cancel Confirmation Dialog
    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Cancel Synchronization?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Cancel synchronization? Any unsaved edits will be discarded.",
                    color = Color(0xFFCCCCCC),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirm = false
                        syncViewModel.cancelSync()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4F), contentColor = Color.White)
                ) {
                    Text("Discard & Cancel")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) {
                    Text("Continue Syncing", color = Color(0xFF0EA5A1))
                }
            },
            containerColor = Color(0xFF222222),
            shape = RoundedCornerShape(12.dp)
        )
    }
}

@Composable
private fun DiffEntryCard(
    item: EntryDiffItem,
    onToggleSelect: (Boolean) -> Unit,
    onUpdateField: (String, String) -> Unit,
    onUsePhoneValue: () -> Unit,
    onKeepLocalValue: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(item.category == EntrySyncCategory.MODIFIED) }
    var showPassword by remember { mutableStateOf(false) }

    val displayEntry = item.editedEntry ?: item.remoteEntry ?: item.localEntry
    val title = displayEntry?.title ?: item.syncKey
    val username = displayEntry?.username ?: ""
    val category = displayEntry?.category ?: "Login"

    val (badgeText, badgeBg, badgeFg) = when (item.category) {
        EntrySyncCategory.NEW_REMOTE -> Triple("New from Phone", Color(0xFF143823), Color(0xFF4ADE80))
        EntrySyncCategory.MODIFIED -> Triple("Modified", Color(0xFF3B2F10), Color(0xFFFBBF24))
        EntrySyncCategory.NEW_LOCAL -> Triple("Local Only", Color(0xFF1F2937), Color(0xFF93C5FD))
        EntrySyncCategory.UNCHANGED -> Triple("Unchanged", Color(0xFF262626), Color(0xFFA3A3A3))
        EntrySyncCategory.DELETED_LOCAL -> Triple("Deleted Locally", Color(0xFF381414), Color(0xFFF87171))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C)),
        border = BorderStroke(
            1.dp,
            when (item.category) {
                EntrySyncCategory.MODIFIED -> Color(0xFF423714)
                EntrySyncCategory.DELETED_LOCAL -> Color(0xFF4C1D1D)
                else -> Color(0xFF2A2A2A)
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Collapsed Row Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = item.isSelectedForSync,
                    onCheckedChange = onToggleSelect,
                    colors = CheckboxDefaults.colors(
                        checkedColor = Color(0xFF0EA5A1),
                        uncheckedColor = Color(0xFF666666),
                        checkmarkColor = Color.Black
                    )
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (category.isNotBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "[$category]",
                                fontSize = 11.sp,
                                color = Color(0xFF777777)
                            )
                        }
                    }
                    if (username.isNotBlank()) {
                        Text(
                            text = username,
                            fontSize = 12.sp,
                            color = Color(0xFF999999),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Category pill
                BadgePill(label = badgeText, backgroundColor = badgeBg, textColor = badgeFg)

                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = Color(0xFF888888)
                )
            }

            // Expanded Field Inspector & Editor
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161616))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HorizontalDivider(color = Color(0xFF262626))

                    // Quick Resolution Buttons for MODIFIED items
                    if (item.category == EntrySyncCategory.MODIFIED) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Quick Resolve:", fontSize = 12.sp, color = Color(0xFF888888))
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = onUsePhoneValue,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                                border = BorderStroke(1.dp, Color(0xFF333333)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4ADE80))
                            ) {
                                Text("Use Phone Value", fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            OutlinedButton(
                                onClick = onKeepLocalValue,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(28.dp),
                                border = BorderStroke(1.dp, Color(0xFF333333)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF93C5FD))
                            ) {
                                Text("Keep PC Value", fontSize = 11.sp)
                            }
                        }
                    }

                    // Field: Title
                    EditableDiffRow(
                        label = "Title",
                        localValue = item.localEntry?.title ?: "",
                        remoteValue = item.remoteEntry?.title ?: "",
                        currentValue = displayEntry?.title ?: "",
                        isModified = item.localEntry?.title != item.remoteEntry?.title && item.localEntry != null && item.remoteEntry != null,
                        onValueChange = { onUpdateField("title", it) }
                    )

                    // Field: Username
                    EditableDiffRow(
                        label = "Username",
                        localValue = item.localEntry?.username ?: "",
                        remoteValue = item.remoteEntry?.username ?: "",
                        currentValue = displayEntry?.username ?: "",
                        isModified = item.localEntry?.username != item.remoteEntry?.username && item.localEntry != null && item.remoteEntry != null,
                        onValueChange = { onUpdateField("username", it) }
                    )

                    // Field: Password (with Show/Hide)
                    EditableDiffRow(
                        label = "Password",
                        localValue = item.localEntry?.secret ?: "",
                        remoteValue = item.remoteEntry?.secret ?: "",
                        currentValue = displayEntry?.secret ?: "",
                        isModified = item.localEntry?.secret != item.remoteEntry?.secret && item.localEntry != null && item.remoteEntry != null,
                        isPassword = true,
                        showPassword = showPassword,
                        onTogglePassword = { showPassword = !showPassword },
                        onValueChange = { onUpdateField("password", it) }
                    )

                    // Field: URL
                    EditableDiffRow(
                        label = "URL",
                        localValue = item.localEntry?.url ?: "",
                        remoteValue = item.remoteEntry?.url ?: "",
                        currentValue = displayEntry?.url ?: "",
                        isModified = item.localEntry?.url != item.remoteEntry?.url && item.localEntry != null && item.remoteEntry != null,
                        onValueChange = { onUpdateField("url", it) }
                    )

                    // Field: Notes
                    EditableDiffRow(
                        label = "Notes",
                        localValue = item.localEntry?.notes ?: "",
                        remoteValue = item.remoteEntry?.notes ?: "",
                        currentValue = displayEntry?.notes ?: "",
                        isModified = item.localEntry?.notes != item.remoteEntry?.notes && item.localEntry != null && item.remoteEntry != null,
                        isMultiLine = true,
                        onValueChange = { onUpdateField("notes", it) }
                    )

                    // Field: Category & Tags
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            EditableDiffRow(
                                label = "Category",
                                localValue = item.localEntry?.category ?: "",
                                remoteValue = item.remoteEntry?.category ?: "",
                                currentValue = displayEntry?.category ?: "",
                                isModified = (item.localEntry?.category ?: "") != (item.remoteEntry?.category ?: "") && item.localEntry != null && item.remoteEntry != null,
                                onValueChange = { onUpdateField("category", it) }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            EditableDiffRow(
                                label = "Tags",
                                localValue = item.localEntry?.tags?.joinToString(", ") ?: "",
                                remoteValue = item.remoteEntry?.tags?.joinToString(", ") ?: "",
                                currentValue = displayEntry?.tags?.joinToString(", ") ?: "",
                                isModified = item.localEntry?.tags != item.remoteEntry?.tags && item.localEntry != null && item.remoteEntry != null,
                                onValueChange = { onUpdateField("tags", it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditableDiffRow(
    label: String,
    localValue: String,
    remoteValue: String,
    currentValue: String,
    isModified: Boolean,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    isMultiLine: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isModified) Color(0xFFFBBF24) else Color(0xFFAAAAAA)
                )
                if (isModified) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• Modified",
                        fontSize = 10.sp,
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (isPassword && onTogglePassword != null) {
                IconButton(onClick = onTogglePassword, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Toggle Visibility",
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Side-by-side comparison indicator if modified
        if (isModified) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Local: ${maskIfPassword(localValue, isPassword, showPassword)}",
                    fontSize = 11.sp,
                    color = Color(0xFF93C5FD),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Remote: ${maskIfPassword(remoteValue, isPassword, showPassword)}",
                    fontSize = 11.sp,
                    color = Color(0xFF4ADE80),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Editable input for user fine-tuning
        OutlinedTextField(
            value = currentValue,
            onValueChange = onValueChange,
            singleLine = !isMultiLine,
            maxLines = if (isMultiLine) 3 else 1,
            visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF222222),
                unfocusedContainerColor = Color(0xFF1E1E1E),
                focusedBorderColor = if (isModified) Color(0xFFFBBF24) else Color(0xFF0EA5A1),
                unfocusedBorderColor = if (isModified) Color(0xFF66551B) else Color(0xFF2C2C2C),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color(0xFFE0E0E0)
            ),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

private fun maskIfPassword(value: String, isPassword: Boolean, showPassword: Boolean): String {
    return if (isPassword && !showPassword) "••••••••" else value.ifBlank { "(empty)" }
}

@Composable
private fun BadgePill(label: String, backgroundColor: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}
