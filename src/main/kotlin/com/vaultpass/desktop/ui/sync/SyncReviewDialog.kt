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
import com.vaultpass.synccore.EntryLimits
import com.vaultpass.synccore.ItemOutcome

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
    val peerName = syncingDevice?.deviceName ?: "the other device"
    val plannedCount = result.plannedCount
    val unansweredCount = result.unansweredCount

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
                            text = "Review Changes — $peerName",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Summary Badges
                            BadgePill(
                                label = "${result.newRemoteCount} New on $peerName",
                                backgroundColor = Color(0xFF143823),
                                textColor = Color(0xFF4ADE80)
                            )
                            BadgePill(
                                label = "${result.newLocalCount} Only on this PC",
                                backgroundColor = Color(0xFF1F2937),
                                textColor = Color(0xFF93C5FD)
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
                            if (result.conflictCount > 0) {
                                BadgePill(
                                    label = "${result.conflictCount} Changed on Both",
                                    backgroundColor = Color(0xFF3B1F3B),
                                    textColor = Color(0xFFF0ABFC)
                                )
                            }
                            if (result.deletedRemoteCount > 0) {
                                BadgePill(
                                    label = "${result.deletedRemoteCount} Deleted on $peerName",
                                    backgroundColor = Color(0xFF381414),
                                    textColor = Color(0xFFF87171)
                                )
                            }
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

                // 2. What the merge does, and what still needs an answer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF171717))
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Choose what happens to each entry. The result is applied on this PC and on $peerName.",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC),
                        modifier = Modifier.weight(1f)
                    )
                    if (unansweredCount > 0) {
                        Spacer(modifier = Modifier.width(12.dp))
                        BadgePill(
                            label = "$unansweredCount to decide",
                            backgroundColor = Color(0xFF3B1F3B),
                            textColor = Color(0xFFF0ABFC)
                        )
                    }
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
                            peerName = peerName,
                            onChoose = { outcome -> syncViewModel.setOutcome(item.syncKey, outcome) },
                            onUpdateField = { field, value ->
                                syncViewModel.updateEntryFieldOverride(item.syncKey, field, value)
                            },
                            onDiscardEdits = { syncViewModel.discardEdits(item.syncKey) }
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

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (unansweredCount > 0) {
                            Text(
                                text = "Choose a version for every entry changed on both devices",
                                fontSize = 12.sp,
                                color = Color(0xFFF0ABFC)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        // The only way to finish a review. The view model closes the dialog, so
                        // no cancel can race the merge.
                        Button(
                            onClick = { syncViewModel.confirmAndMerge(onSuccessMerge) },
                            enabled = unansweredCount == 0,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (plannedCount == 0) Color(0xFF10B981) else Color(0xFF0EA5A1),
                                contentColor = if (plannedCount == 0) Color.White else Color.Black
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Icon(
                                imageVector = if (plannedCount == 0) Icons.Default.CheckCircle else Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (plannedCount == 0) {
                                    "Devices are up to date (Finish)"
                                } else {
                                    "Apply to both devices ($plannedCount)"
                                },
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
                    "Cancel synchronization? Nothing will be changed on either device.",
                    color = Color(0xFFCCCCCC),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirm = false
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

/** How the review names each outcome. */
private fun outcomeLabel(outcome: ItemOutcome, peerName: String): String = when (outcome) {
    ItemOutcome.USE_REMOTE -> "Use $peerName's version"
    ItemOutcome.USE_LOCAL -> "Keep this device's version"
    ItemOutcome.DELETE_BOTH -> "Delete on both"
    ItemOutcome.SKIP -> "Leave unchanged"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutcomeChooser(item: EntryDiffItem, peerName: String, onChoose: (ItemOutcome) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item.choices.forEach { outcome ->
            // An unanswered conflict shows no choice as picked: the user must pick one.
            val selected = !item.isUnanswered && item.outcome == outcome
            FilterChip(
                selected = selected,
                onClick = { onChoose(outcome) },
                label = { Text(outcomeLabel(outcome, peerName), fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF1E1E1E),
                    labelColor = Color(0xFFCCCCCC),
                    selectedContainerColor = Color(0xFF0E3B3A),
                    selectedLabelColor = Color(0xFF5EEAD4)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected,
                    borderColor = if (item.isUnanswered) Color(0xFF7E3A7E) else Color(0xFF333333),
                    selectedBorderColor = Color(0xFF0EA5A1)
                )
            )
        }
    }
}

@Composable
private fun DiffEntryCard(
    item: EntryDiffItem,
    peerName: String,
    onChoose: (ItemOutcome) -> Unit,
    onUpdateField: (String, String) -> Unit,
    onDiscardEdits: () -> Unit
) {
    var isExpanded by remember {
        mutableStateOf(item.category == EntrySyncCategory.MODIFIED || item.category == EntrySyncCategory.CONFLICT)
    }
    var showPassword by remember { mutableStateOf(false) }

    val displayEntry = item.editedEntry ?: item.chosenEntry
    val title = displayEntry?.title ?: item.syncKey
    val username = displayEntry?.username ?: ""
    val category = displayEntry?.category ?: "Login"
    val editable = item.isEditable

    val (badgeText, badgeBg, badgeFg) = when (item.category) {
        EntrySyncCategory.NEW_REMOTE -> Triple("New on $peerName", Color(0xFF143823), Color(0xFF4ADE80))
        EntrySyncCategory.MODIFIED -> Triple("Modified", Color(0xFF3B2F10), Color(0xFFFBBF24))
        EntrySyncCategory.NEW_LOCAL -> Triple("Only on this PC", Color(0xFF1F2937), Color(0xFF93C5FD))
        EntrySyncCategory.UNCHANGED -> Triple("Unchanged", Color(0xFF262626), Color(0xFFA3A3A3))
        EntrySyncCategory.CONFLICT -> Triple("Changed on both devices", Color(0xFF3B1F3B), Color(0xFFF0ABFC))
        EntrySyncCategory.DELETED_REMOTE -> Triple("Deleted on $peerName", Color(0xFF381414), Color(0xFFF87171))
        EntrySyncCategory.DELETED_LOCAL -> Triple("Deleted on this device", Color(0xFF381414), Color(0xFFF87171))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C)),
        border = BorderStroke(
            if (item.isUnanswered) 2.dp else 1.dp,
            when {
                item.isUnanswered -> Color(0xFFC026D3)
                item.category == EntrySyncCategory.MODIFIED -> Color(0xFF423714)
                item.category == EntrySyncCategory.CONFLICT -> Color(0xFF4A2A4A)
                item.category == EntrySyncCategory.DELETED_LOCAL ||
                    item.category == EntrySyncCategory.DELETED_REMOTE -> Color(0xFF4C1D1D)
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
                    if (item.isUnanswered) {
                        Text(
                            text = "Changed on both devices: choose which version to keep",
                            fontSize = 11.sp,
                            color = Color(0xFFF0ABFC),
                            fontWeight = FontWeight.Medium
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

            // What happens to this entry on both devices
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutcomeChooser(item = item, peerName = peerName, onChoose = onChoose)
                Spacer(modifier = Modifier.weight(1f))
                if (item.editedEntry != null) {
                    TextButton(onClick = onDiscardEdits) {
                        Text("Discard edits", fontSize = 12.sp, color = Color(0xFF999999))
                    }
                }
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

                    if (item.category == EntrySyncCategory.DELETED_REMOTE) {
                        Text(
                            text = "$peerName deleted this entry. Delete it on both devices, keep this PC's copy " +
                                "on both, or leave it unchanged.",
                            fontSize = 12.sp,
                            color = Color(0xFFCCCCCC)
                        )
                    }
                    if (!editable) {
                        Text(
                            text = "Choose a version to keep to edit it here.",
                            fontSize = 11.sp,
                            color = Color(0xFF777777)
                        )
                    }

                    // Field: Title
                    EditableDiffRow(
                        label = "Title",
                        localValue = item.localEntry?.title ?: "",
                        remoteValue = item.remoteEntry?.title ?: "",
                        currentValue = displayEntry?.title ?: "",
                        isModified = item.localEntry?.title != item.remoteEntry?.title && item.localEntry != null && item.remoteEntry != null,
                        peerName = peerName,
                        enabled = editable,
                        limit = EntryLimits.TITLE,
                        onValueChange = { onUpdateField("title", it) }
                    )

                    // Field: Username
                    EditableDiffRow(
                        label = "Username",
                        localValue = item.localEntry?.username ?: "",
                        remoteValue = item.remoteEntry?.username ?: "",
                        currentValue = displayEntry?.username ?: "",
                        isModified = item.localEntry?.username != item.remoteEntry?.username && item.localEntry != null && item.remoteEntry != null,
                        peerName = peerName,
                        enabled = editable,
                        limit = EntryLimits.USERNAME,
                        onValueChange = { onUpdateField("username", it) }
                    )

                    // Field: Password (with Show/Hide)
                    EditableDiffRow(
                        label = "Password",
                        localValue = item.localEntry?.secret ?: "",
                        remoteValue = item.remoteEntry?.secret ?: "",
                        currentValue = displayEntry?.secret ?: "",
                        isModified = item.localEntry?.secret != item.remoteEntry?.secret && item.localEntry != null && item.remoteEntry != null,
                        peerName = peerName,
                        enabled = editable,
                        limit = EntryLimits.PASSWORD,
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
                        peerName = peerName,
                        enabled = editable,
                        limit = EntryLimits.WEBSITE,
                        onValueChange = { onUpdateField("url", it) }
                    )

                    // Field: Notes
                    EditableDiffRow(
                        label = "Notes",
                        localValue = item.localEntry?.notes ?: "",
                        remoteValue = item.remoteEntry?.notes ?: "",
                        currentValue = displayEntry?.notes ?: "",
                        isModified = item.localEntry?.notes != item.remoteEntry?.notes && item.localEntry != null && item.remoteEntry != null,
                        peerName = peerName,
                        enabled = editable,
                        limit = EntryLimits.NOTES,
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
                                peerName = peerName,
                                enabled = editable,
                                onValueChange = { onUpdateField("category", it) }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            val tags = displayEntry?.tags.orEmpty()
                            EditableDiffRow(
                                label = "Tags",
                                localValue = item.localEntry?.tags?.joinToString(", ") ?: "",
                                remoteValue = item.remoteEntry?.tags?.joinToString(", ") ?: "",
                                currentValue = tags.joinToString(", "),
                                isModified = item.localEntry?.tags != item.remoteEntry?.tags && item.localEntry != null && item.remoteEntry != null,
                                peerName = peerName,
                                enabled = editable,
                                error = when {
                                    tags.size > EntryLimits.MAX_TAGS -> "Max ${EntryLimits.MAX_TAGS} tags"
                                    tags.any { it.length > EntryLimits.TAG } -> "Each tag: ${EntryLimits.tooLong(EntryLimits.TAG)}"
                                    else -> null
                                },
                                onValueChange = { onUpdateField("tags", it) }
                            )
                        }
                    }

                    CustomFieldsDiffRow(item = item, peerName = peerName, showValues = showPassword, onToggleValues = { showPassword = !showPassword })

                    val favoriteDiff = item.fieldDiffs.find { it.fieldName == "Favorite" }
                    if (favoriteDiff != null && favoriteDiff.changeType == FieldChangeType.MODIFIED) {
                        Text(
                            text = "Favorite • Modified — This PC: ${favoriteDiff.localValue}   $peerName: ${favoriteDiff.remoteValue}",
                            fontSize = 12.sp,
                            color = Color(0xFFFBBF24)
                        )
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
    peerName: String,
    enabled: Boolean = true,
    limit: Int? = null,
    error: String? = null,
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
                    text = "This PC: ${maskIfPassword(localValue, isPassword, showPassword)}",
                    fontSize = 11.sp,
                    color = Color(0xFF93C5FD),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$peerName: ${maskIfPassword(remoteValue, isPassword, showPassword)}",
                    fontSize = 11.sp,
                    color = Color(0xFF4ADE80),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Editable input for user fine-tuning
        val fieldError = error ?: limit?.takeIf { currentValue.length > it }?.let { EntryLimits.tooLong(it) }
        OutlinedTextField(
            value = currentValue,
            onValueChange = onValueChange,
            enabled = enabled,
            isError = fieldError != null,
            supportingText = fieldError?.let { { Text(it) } },
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
                unfocusedTextColor = Color(0xFFE0E0E0),
                disabledContainerColor = Color(0xFF1A1A1A),
                disabledBorderColor = Color(0xFF262626),
                disabledTextColor = Color(0xFF999999)
            ),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

/** Read-only comparison of the custom fields on each side; values are masked like passwords. */
@Composable
private fun CustomFieldsDiffRow(item: EntryDiffItem, peerName: String, showValues: Boolean, onToggleValues: () -> Unit) {
    val local = item.localEntry?.customFields.orEmpty()
    val remote = item.remoteEntry?.customFields.orEmpty()
    if (local.isEmpty() && remote.isEmpty()) return
    val isModified = item.fieldDiffs.find { it.fieldName == "Custom Fields" }?.changeType == FieldChangeType.MODIFIED

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Custom Fields",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isModified) Color(0xFFFBBF24) else Color(0xFFAAAAAA)
                )
                if (isModified) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "• Modified", fontSize = 10.sp, color = Color(0xFFFBBF24), fontWeight = FontWeight.Medium)
                }
            }
            IconButton(onClick = onToggleValues, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = if (showValues) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = "Toggle Visibility",
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("This PC", local.takeIf { item.localEntry != null }, Color(0xFF93C5FD)),
                Triple(peerName, remote.takeIf { item.remoteEntry != null }, Color(0xFF4ADE80))
            ).forEach { (side, fields, color) ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                        .border(1.dp, if (isModified) Color(0xFF66551B) else Color(0xFF2C2C2C), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(side, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
                    when {
                        fields == null -> Text("—", fontSize = 12.sp, color = Color(0xFF777777))
                        fields.isEmpty() -> Text("(none)", fontSize = 12.sp, color = Color(0xFF777777))
                        else -> fields.forEach { field ->
                            Text(
                                text = "${field.key}: ${maskIfPassword(field.value, true, showValues)}",
                                fontSize = 12.sp,
                                color = Color(0xFFE0E0E0),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
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
