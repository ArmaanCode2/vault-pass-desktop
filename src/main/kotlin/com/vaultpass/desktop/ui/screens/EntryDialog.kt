package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.vaultpass.desktop.data.models.VaultEntryPayload
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.ui.theme.LocalSpacing
import com.vaultpass.desktop.ui.theme.LocalVaultPassExtendedColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryDialog(
    entry: VaultEntry?,
    onDismiss: () -> Unit,
    onSave: (VaultEntryPayload.PasswordPayload) -> Unit,
    onDelete: (() -> Unit)? = null,
    confirmBeforeDelete: Boolean = true,
    hidePasswordsByDefault: Boolean = false,
    pendingPassword: String? = null
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

    val spacing = LocalSpacing.current

    val generateRandomPassword = {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        val sb = java.lang.StringBuilder()
        for (i in 0 until 16) {
            sb.append(chars[kotlin.random.Random.nextInt(chars.length)])
        }
        password = sb.toString()
        isPasswordVisible = true
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 500.dp)
            .fillMaxWidth(0.9f)
            .background(LocalVaultPassExtendedColors.current.glassBase, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
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
                isError = titleError,
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
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(8.dp)
                )
                
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Website") },
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
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (entry != null && onDelete != null) {
                    IconButton(onClick = { 
                        if (confirmBeforeDelete) showDeleteConfirm = true else {
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
                        onSave(
                            VaultEntryPayload.PasswordPayload(
                                title = title,
                                username = username,
                                secret = password,
                                url = url,
                                notes = notes,
                                category = if (category.isBlank()) null else category.trim(),
                                tags = tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
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
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
            containerColor = LocalVaultPassExtendedColors.current.glassBase
        )
    }
}
