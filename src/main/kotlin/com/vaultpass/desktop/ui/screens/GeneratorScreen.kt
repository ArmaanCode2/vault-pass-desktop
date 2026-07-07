package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
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
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel
import kotlin.random.Random

@Composable
fun GeneratorScreen(
    vaultViewModel: VaultViewModel,
    onNavigateToVault: () -> Unit
) {
    var length by remember { mutableFloatStateOf(16f) }
    var includeUppercase by remember { mutableStateOf(true) }
    var includeLowercase by remember { mutableStateOf(true) }
    var includeNumbers by remember { mutableStateOf(true) }
    var includeSymbols by remember { mutableStateOf(true) }
    var currentPassword by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    val generatePassword = {
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

    LaunchedEffect(length, includeUppercase, includeLowercase, includeNumbers, includeSymbols) {
        generatePassword()
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
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                    IconButton(onClick = { generatePassword() }) {
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { 
                        vaultViewModel.setPendingGeneratedPassword(currentPassword)
                        vaultViewModel.showAddDialog(true)
                        onNavigateToVault()
                    }) {
                        Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Use Password")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                PasswordStrengthIndicator(currentPassword)
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
                        steps = 55,
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
fun PasswordStrengthIndicator(password: String) {
    val strength = calculatePasswordScore(password)

    val color = when (strength) {
        0 -> MaterialTheme.colorScheme.error
        1 -> androidx.compose.ui.graphics.Color(0xFFE6C129) // Yellow
        else -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // Green
    }
    
    val text = when (strength) {
        0 -> "Weak"
        1 -> "Medium"
        else -> "Strong"
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = when (strength) {
                0 -> 0.33f
                1 -> 0.66f
                else -> 1.0f
            },
            color = color,
            modifier = Modifier.weight(1f).height(8.dp),
            trackColor = MaterialTheme.colorScheme.surface
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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

private fun calculatePasswordScore(password: String): Int {
    if (password.isEmpty()) return 0
    
    var score = 0.0
    val length = password.length

    // 1. Character Diversity (Pool Size)
    var poolSize = 0
    if (password.any { it.isLowerCase() }) poolSize += 26
    if (password.any { it.isUpperCase() }) poolSize += 26
    if (password.any { it.isDigit() }) poolSize += 10
    if (password.any { !it.isLetterOrDigit() }) poolSize += 32

    // 2. Entropy
    // log2(poolSize) * length
    val entropy = if (poolSize > 0) length * (Math.log(poolSize.toDouble()) / Math.log(2.0)) else 0.0
    score += entropy

    // 3. Length Bonuses
    if (length >= 16) score += 10.0
    if (length >= 24) score += 15.0

    // 4. Deductions for Repeated Patterns
    var consecutiveCount = 0
    for (i in 0 until length - 1) {
        if (password[i] == password[i+1]) {
            consecutiveCount++
        }
    }
    score -= (consecutiveCount * 5.0)

    // Subtract points for common sequences/dictionary words
    val lowerPass = password.lowercase()
    val dictionaryWords = listOf("password", "qwerty", "12345", "admin", "welcome", "letmein", "123123")
    for (word in dictionaryWords) {
        if (lowerPass.contains(word)) {
            score -= 30.0
        }
    }

    // Subtract points for sequential characters (abc, 123)
    var sequentialCount = 0
    for (i in 0 until length - 2) {
        val c1 = password[i].code
        val c2 = password[i+1].code
        val c3 = password[i+2].code
        if ((c1 + 1 == c2 && c2 + 1 == c3) || (c1 - 1 == c2 && c2 - 1 == c3)) {
            sequentialCount++
        }
    }
    score -= (sequentialCount * 15.0)

    // Normalize final output
    return when {
        score < 40.0 -> 0     // Weak
        score < 75.0 -> 1     // Medium
        else -> 2             // Strong
    }
}
