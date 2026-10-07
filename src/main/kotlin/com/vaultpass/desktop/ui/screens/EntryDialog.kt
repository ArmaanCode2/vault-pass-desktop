package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.data.models.CustomFieldDto
import com.vaultpass.desktop.data.models.VaultEntryPayload
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.ui.theme.LocalSpacing
import com.vaultpass.desktop.ui.theme.LocalVaultPassExtendedColors
import com.vaultpass.synccore.EntryLimits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDialog(
    entry: VaultEntry?,
    onDismiss: () -> Unit,
    onSave: (VaultEntryPayload.PasswordPayload) -> Unit,
    onDelete: (() -> Unit)? = null,
    confirmBeforeDelete: Boolean = true,
    hidePasswordsByDefault: Boolean = false,
    pendingPassword: String? = null,
    onGeneratedSnackbar: ((String) -> Unit)? = null
) {
    var title by remember(entry?.id) { mutableStateOf(entry?.title ?: "") }
    var username by remember(entry?.id) { mutableStateOf(entry?.username ?: "") }
    var password by remember(entry?.id) { mutableStateOf(entry?.secret ?: pendingPassword ?: "") }
    var url by remember(entry?.id) { mutableStateOf(entry?.url ?: "") }
    var notes by remember(entry?.id) { mutableStateOf(entry?.notes ?: "") }
    var category by remember(entry?.id) { mutableStateOf(entry?.category ?: "") }
    var tags by remember(entry?.id) { mutableStateOf(entry?.tags?.joinToString(", ") ?: "") }
    
    var isPasswordVisible by remember(entry?.id) { mutableStateOf(!hidePasswordsByDefault) }
    var showDeleteConfirm by remember(entry?.id) { mutableStateOf(false) }

    var titleError by remember(entry?.id) { mutableStateOf(false) }

    val customFields = remember(entry?.id) {
        mutableStateListOf<CustomFieldDraft>().apply {
            entry?.customFields?.forEach { add(CustomFieldDraft(it.key, it.value, !hidePasswordsByDefault)) }
        }
    }
    var showCustomFieldErrors by remember(entry?.id) { mutableStateOf(false) }

    val spacing = LocalSpacing.current
    val passwordGenerator = remember { com.vaultpass.desktop.data.generator.SecurePasswordGeneratorImpl() }

    // The same limits as the Android app (EntryLimits), shown while typing and enforced on save.
    val parsedTags = tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val titleLimitError = tooLongError(title, EntryLimits.TITLE)
    val usernameError = tooLongError(username, EntryLimits.USERNAME)
    val urlError = tooLongError(url, EntryLimits.WEBSITE)
    val passwordError = tooLongError(password, EntryLimits.PASSWORD)
    val notesError = tooLongError(notes, EntryLimits.NOTES)
    val tagsError = tagsError(parsedTags)
    val hasLimitError = listOf(titleLimitError, usernameError, urlError, passwordError, notesError, tagsError).any { it != null }

    val generateRandomPassword = {
        val secret = passwordGenerator.generatePassword(
            com.vaultpass.desktop.domain.generator.PasswordConfig(
                length = 16,
                includeUppercase = true,
                includeLowercase = true,
                includeNumbers = true,
                includeSymbols = true,
                avoidAmbiguous = false
            )
        )
        password = secret.value
        isPasswordVisible = true
        onGeneratedSnackbar?.invoke("Generated strong password")
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 500.dp)
            .fillMaxWidth(0.9f)
            .background(LocalVaultPassExtendedColors.current.glassBase, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(
                text = if (entry == null) "Add Password" else "Edit Password",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it; titleError = false },
                label = { Text("Title") },
                isError = titleError || titleLimitError != null,
                supportingText = titleLimitError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(8.dp)
            )
            if (titleError) {
                Text("Title is required", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(spacing.md))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    isError = usernameError != null,
                    supportingText = usernameError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(8.dp)
                )
                
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Website") },
                    isError = urlError != null,
                    supportingText = urlError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(spacing.md))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                isError = passwordError != null,
                supportingText = passwordError?.let { { Text(it) } },
                singleLine = true,
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(8.dp)
            )
            
            Spacer(modifier = Modifier.height(spacing.md))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(8.dp)
                )
                
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags") },
                    isError = tagsError != null,
                    supportingText = tagsError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(spacing.md))

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                isError = notesError != null,
                supportingText = notesError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(spacing.md))

            CustomFieldsEditor(
                fields = customFields,
                showErrors = showCustomFieldErrors,
                newFieldVisible = !hidePasswordsByDefault
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (entry != null && onDelete != null) {
                    IconButton(onClick = { 
                        if (confirmBeforeDelete) {
                            showDeleteConfirm = true
                        } else {
                            onDelete.invoke()
                            onDismiss()
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))

                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = {
                        if (title.isBlank()) {
                            titleError = true
                            return@Button
                        }
                        // The field that is too long already says so.
                        if (hasLimitError) return@Button
                        // Rows left completely empty are dropped; anything else must be valid.
                        val filledFields = customFields.filter { it.key.isNotBlank() || it.value.isNotEmpty() }
                        if (filledFields.any { customFieldError(it) != null }) {
                            showCustomFieldErrors = true
                            return@Button
                        }
                        onSave(
                            VaultEntryPayload.PasswordPayload(
                                title = title,
                                username = username,
                                secret = password,
                                url = url,
                                notes = notes,
                                category = if (category.isBlank()) null else category.trim(),
                                tags = parsedTags,
                                customFields = filledFields.map { CustomFieldDto(key = it.key.trim(), value = it.value) }
                            )
                        )
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (entry == null) "Create Entry" else "Save Changes")
                }
            }
        }
    }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Entry") },
            text = { Text("Are you sure you want to delete this entry? It will be moved to the Recycle Bin.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete?.invoke()
                    onDismiss()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
            containerColor = LocalVaultPassExtendedColors.current.glassBase
        )
    }
}

/** One editable custom field row; [isVisible] is its show/hide state. */
private class CustomFieldDraft(key: String, value: String, isVisible: Boolean) {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
    var isVisible by mutableStateOf(isVisible)
}

/** "Max N characters" when [value] is longer than [limit]. */
private fun tooLongError(value: String, limit: Int): String? =
    if (value.length > limit) EntryLimits.tooLong(limit) else null

private fun tagsError(tags: List<String>): String? = when {
    tags.size > EntryLimits.MAX_TAGS -> "Max ${EntryLimits.MAX_TAGS} tags"
    tags.any { it.length > EntryLimits.TAG } -> "Each tag: ${EntryLimits.tooLong(EntryLimits.TAG)}"
    else -> null
}

private fun customFieldError(field: CustomFieldDraft): String? = when {
    field.key.isBlank() -> "Enter a name for this field"
    field.key.trim().length > EntryLimits.CUSTOM_FIELD_KEY -> "Name: ${EntryLimits.tooLong(EntryLimits.CUSTOM_FIELD_KEY)}"
    field.value.length > EntryLimits.CUSTOM_FIELD_VALUE -> "Value: ${EntryLimits.tooLong(EntryLimits.CUSTOM_FIELD_VALUE)}"
    else -> null
}

@Composable
private fun CustomFieldsEditor(
    fields: MutableList<CustomFieldDraft>,
    showErrors: Boolean,
    newFieldVisible: Boolean
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Custom fields",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${fields.size}/${EntryLimits.MAX_CUSTOM_FIELDS}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        fields.forEachIndexed { index, field ->
            Spacer(modifier = Modifier.height(spacing.sm))
            // Length checks show while typing; a missing name only once the user tries to save.
            val isFilled = field.key.isNotBlank() || field.value.isNotEmpty()
            val error = customFieldError(field)?.takeIf { field.key.isNotBlank() || (showErrors && isFilled) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = field.key,
                    onValueChange = { field.key = it },
                    label = { Text("Name") },
                    isError = error != null && (field.key.isBlank() || field.key.trim().length > EntryLimits.CUSTOM_FIELD_KEY),
                    modifier = Modifier.weight(0.4f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(8.dp)
                )
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { field.value = it },
                    label = { Text("Value") },
                    isError = field.value.length > EntryLimits.CUSTOM_FIELD_VALUE,
                    singleLine = true,
                    visualTransformation = if (field.isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { field.isVisible = !field.isVisible }) {
                            Icon(
                                imageVector = if (field.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Visibility"
                            )
                        }
                    },
                    modifier = Modifier.weight(0.6f),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(8.dp)
                )
                IconButton(onClick = { fields.removeAt(index) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove field", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(spacing.sm))
        val atMaximum = fields.size >= EntryLimits.MAX_CUSTOM_FIELDS
        TextButton(
            onClick = { fields.add(CustomFieldDraft("", "", newFieldVisible)) },
            enabled = !atMaximum
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add custom field")
        }
        if (atMaximum) {
            Text(
                "Max ${EntryLimits.MAX_CUSTOM_FIELDS} custom fields",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
