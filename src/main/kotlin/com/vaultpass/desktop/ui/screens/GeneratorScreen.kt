package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

@Composable
fun GeneratorScreen() {
    var length by remember { mutableFloatStateOf(16f) }
    var includeUppercase by remember { mutableStateOf(true) }
    var includeLowercase by remember { mutableStateOf(true) }
    var includeNumbers by remember { mutableStateOf(true) }
    var includeSymbols by remember { mutableStateOf(true) }
    var currentPassword by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    // Helper to generate a placeholder password
    val generatePlaceholder = {
        val uppercase = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val lowercase = "abcdefghijklmnopqrstuvwxyz"
        val numbers = "0123456789"
        val symbols = "!@#$%^&*()_+-=[]{}|;':\",./<>?"

        var pool = ""
        if (includeUppercase) pool += uppercase
        if (includeLowercase) pool += lowercase
        if (includeNumbers) pool += numbers
        if (includeSymbols) pool += symbols

        if (pool.isEmpty()) {
            currentPassword = ""
        } else {
            val sb = java.lang.StringBuilder()
            for (i in 0 until length.toInt()) {
                sb.append(pool[Random.nextInt(pool.length)])
            }
            currentPassword = sb.toString()
        }
    }

    // Generate initially and whenever options change
    LaunchedEffect(length, includeUppercase, includeLowercase, includeNumbers, includeSymbols) {
        generatePlaceholder()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp)
    ) {
        Text(
            text = "Password Generator",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create strong, secure passwords.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Password Preview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentPassword,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { generatePlaceholder() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { 
                    clipboardManager.setText(AnnotatedString(currentPassword))
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Options
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Options",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Length Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Length",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.width(100.dp)
                    )
                    Slider(
                        value = length,
                        onValueChange = { length = it },
                        valueRange = 8f..64f,
                        steps = 55, // 55 steps between 8 and 64 gives step size 1
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = length.toInt().toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(40.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(16.dp))

                // Character types
                GeneratorOptionRow("Uppercase Letters (A-Z)", includeUppercase) { includeUppercase = it }
                GeneratorOptionRow("Lowercase Letters (a-z)", includeLowercase) { includeLowercase = it }
                GeneratorOptionRow("Numbers (0-9)", includeNumbers) { includeNumbers = it }
                GeneratorOptionRow("Symbols (!@#\$%)", includeSymbols) { includeSymbols = it }
            }
        }
    }
}

@Composable
private fun GeneratorOptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
