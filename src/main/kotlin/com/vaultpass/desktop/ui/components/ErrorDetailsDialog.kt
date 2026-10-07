package com.vaultpass.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ErrorDetailsDialog(
    errorMessage: String,
    technicalDetails: String? = null,
    onDismiss: () -> Unit,
    onCopiedSnackbar: () -> Unit = {},
    /** An optional way forward, e.g. "Sync again"; shown next to Close. */
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    val fullCopyText = buildString {
        appendLine("--- VaultPass Desktop Error Report ---")
        appendLine("Timestamp: $timestamp")
        appendLine("OS: ${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        appendLine("Error: $errorMessage")
        if (!technicalDetails.isNullOrBlank()) {
            appendLine("Technical Details:")
            appendLine(technicalDetails)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .widthIn(min = 400.dp, max = 540.dp)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF4D4D).copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFFF4D4D),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Error Occurred",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF6B6B)
                        )
                        Text(
                            text = "Contact Administrator for Solution",
                            fontSize = 12.sp,
                            color = Color(0xFFCCCCCC)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Error Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF141414))
                        .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = fullCopyText,
                        color = Color(0xFFE0E0E0),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(fullCopyText))
                            onCopiedSnackbar()
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0EA5A1))
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Error")
                    }
                    if (actionLabel != null && onAction != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(
                            onClick = onAction,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
                        ) {
                            Text(actionLabel)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
