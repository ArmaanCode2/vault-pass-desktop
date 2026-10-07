package com.vaultpass.desktop.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultpass.desktop.domain.generator.EntropyRating
import com.vaultpass.desktop.domain.generator.GeneratedSecret
import com.vaultpass.desktop.domain.generator.GeneratorMode
import com.vaultpass.desktop.domain.generator.PasswordConfig
import com.vaultpass.desktop.domain.generator.PassphraseConfig
import com.vaultpass.desktop.ui.theme.LocalSpacing
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel

@Composable
fun GeneratorScreen(
    vaultViewModel: VaultViewModel,
    onNavigateToVault: () -> Unit
) {
    var mode by remember { mutableStateOf(GeneratorMode.PASSWORD) }

    // Password Mode State
    var length by remember { mutableFloatStateOf(16f) }
    var includeUppercase by remember { mutableStateOf(true) }
    var includeLowercase by remember { mutableStateOf(true) }
    var includeNumbers by remember { mutableStateOf(true) }
    var includeSymbols by remember { mutableStateOf(true) }
    var avoidAmbiguous by remember { mutableStateOf(false) }

    // Passphrase Mode State
    var wordCount by remember { mutableFloatStateOf(5f) }
    var separator by remember { mutableStateOf("-") }
    var capitalize by remember { mutableStateOf(true) }
    var includeNumber by remember { mutableStateOf(true) }

    var currentSecret by remember {
        mutableStateOf(
            GeneratedSecret(
                value = "",
                entropyBits = 0f,
                rating = EntropyRating.WEAK,
                crackTimeEstimate = ""
            )
        )
    }

    val secretHistory = remember { mutableStateListOf<String>() }
    val clipboardManager = LocalClipboardManager.current
    val spacing = LocalSpacing.current

    val regenerate = {
        val newSecret = if (mode == GeneratorMode.PASSWORD) {
            vaultViewModel.generatePassword(
                PasswordConfig(
                    length = length.toInt(),
                    includeUppercase = includeUppercase,
                    includeLowercase = includeLowercase,
                    includeNumbers = includeNumbers,
                    includeSymbols = includeSymbols,
                    avoidAmbiguous = avoidAmbiguous
                )
            )
        } else {
            vaultViewModel.generatePassphrase(
                PassphraseConfig(
                    wordCount = wordCount.toInt(),
                    separator = separator,
                    capitalize = capitalize,
                    includeNumber = includeNumber
                )
            )
        }

        if (currentSecret.value.isNotBlank() && currentSecret.value != newSecret.value) {
            secretHistory.add(0, currentSecret.value)
            if (secretHistory.size > 10) secretHistory.removeLast()
        }
        currentSecret = newSecret
    }

    LaunchedEffect(
        mode,
        length,
        includeUppercase,
        includeLowercase,
        includeNumbers,
        includeSymbols,
        avoidAmbiguous,
        wordCount,
        separator,
        capitalize,
        includeNumber
    ) {
        regenerate()
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D)) // Canvas background
            .padding(spacing.lg)
            .verticalScroll(scrollState)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Password & Passphrase Generator",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "Generate cryptographically secure passwords and Diceware passphrases.",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Mode Segmented Control
            Row(
                modifier = Modifier
                    .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                    .background(Color(0xFF141414))
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (mode == GeneratorMode.PASSWORD) Color(0xFF0EA5A1) else Color.Transparent)
                        .clickable { mode = GeneratorMode.PASSWORD }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Password",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (mode == GeneratorMode.PASSWORD) Color.Black else Color(0xFF888888)
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (mode == GeneratorMode.PASSPHRASE) Color(0xFF0EA5A1) else Color.Transparent)
                        .clickable { mode = GeneratorMode.PASSPHRASE }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Passphrase",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (mode == GeneratorMode.PASSPHRASE) Color.Black else Color(0xFF888888)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 1. Secret Preview Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (mode == GeneratorMode.PASSWORD) "Generated Password" else "Generated Passphrase",
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = currentSecret.value,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF0EA5A1), // Accent Teal
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                LiveEntropyBar(currentSecret)

                Spacer(modifier = Modifier.height(20.dp))

                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = { regenerate() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E1E1E))
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Regenerate",
                            tint = Color(0xFF0EA5A1),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Button(
                        onClick = {
                            vaultViewModel.copyPasswordToClipboard(currentSecret.value, clipboardManager)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E1E1E),
                            contentColor = Color(0xFFE8E8E8)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            vaultViewModel.setPendingGeneratedPassword(currentSecret.value)
                            vaultViewModel.showAddDialog(true)
                            onNavigateToVault()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0EA5A1),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (mode == GeneratorMode.PASSWORD) "Use Password" else "Use Passphrase",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Options Layout
        val optionsContent = @Composable {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Configuration",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                if (mode == GeneratorMode.PASSWORD) {
                    // Length Slider Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1A1A))
                            .padding(16.dp)
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Length", fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(4.dp))
                                        .background(Color(0xFF0D0D0D))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = length.toInt().toString(),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF0EA5A1)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = length,
                                onValueChange = { length = it },
                                valueRange = 8f..64f,
                                steps = 55,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF0EA5A1),
                                    activeTrackColor = Color(0xFF0EA5A1),
                                    inactiveTrackColor = Color(0xFF242424)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Character sets & ambiguity toggles
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeneratorOptionRow("Uppercase Letters (A-Z)", includeUppercase) { includeUppercase = it }
                        GeneratorOptionRow("Lowercase Letters (a-z)", includeLowercase) { includeLowercase = it }
                        GeneratorOptionRow("Numbers (0-9)", includeNumbers) { includeNumbers = it }
                        GeneratorOptionRow("Symbols (!@#$%)", includeSymbols) { includeSymbols = it }
                        GeneratorOptionRow(
                            "Avoid Ambiguous Characters (0/O, 1/l/I, |)",
                            avoidAmbiguous
                        ) { avoidAmbiguous = it }
                    }
                } else {
                    // Passphrase Mode Controls
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                            .background(Color(0xFF1A1A1A))
                            .padding(16.dp)
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Word Count", fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
                                Box(
                                    modifier = Modifier
                                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(4.dp))
                                        .background(Color(0xFF0D0D0D))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${wordCount.toInt()} words",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF0EA5A1)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = wordCount,
                                onValueChange = { wordCount = it },
                                valueRange = 3f..8f,
                                steps = 4,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF0EA5A1),
                                    activeTrackColor = Color(0xFF0EA5A1),
                                    inactiveTrackColor = Color(0xFF242424)
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Separator Selector
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                            .background(Color(0xFF1A1A1A))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Word Separator",
                                fontSize = 14.sp,
                                color = Color(0xFFE8E8E8),
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("-" to "Hyphen", "." to "Period", "_" to "Underscore", " " to "Space").forEach { (sep, _) ->
                                    val isSelected = separator == sep
                                    Box(
                                        modifier = Modifier
                                            .border(
                                                1.dp,
                                                if (isSelected) Color(0xFF0EA5A1) else Color(0xFF333333),
                                                RoundedCornerShape(6.dp)
                                            )
                                            .background(if (isSelected) Color(0xFF0EA5A1).copy(alpha = 0.2f) else Color(0xFF141414))
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { separator = sep }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (sep == " ") "Space" else sep,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color(0xFF0EA5A1) else Color(0xFF888888)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        GeneratorOptionRow("Capitalize Words", capitalize) { capitalize = it }
                        GeneratorOptionRow("Include Random Number", includeNumber) { includeNumber = it }
                    }
                }
            }
        }

        // History Layout
        val historyContent = @Composable {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "History",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                        .background(Color(0xFF1A1A1A))
                        .padding(16.dp)
                ) {
                    if (secretHistory.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No history yet.", color = Color(0xFF888888), fontSize = 14.sp)
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            secretHistory.forEach { pastSecret ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0D0D0D))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = pastSecret,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = Color(0xFFE8E8E8),
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            vaultViewModel.copyPasswordToClipboard(pastSecret, clipboardManager)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy",
                                            tint = Color(0xFF888888),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < 800.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    optionsContent()
                    historyContent()
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) { optionsContent() }
                    Box(modifier = Modifier.weight(1f)) { historyContent() }
                }
            }
        }
    }
}

@Composable
fun LiveEntropyBar(secret: GeneratedSecret) {
    val ratingColor = Color(secret.rating.colorHex)
    val progressFraction = (secret.entropyBits / 128f).coerceIn(0.05f, 1.0f)

    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(400)
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${secret.entropyBits.toInt()} bits — ${secret.rating.label}",
                color = ratingColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Crack time: ${secret.crackTimeEstimate}",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
        }

        LinearProgressIndicator(
            progress = { animatedProgress },
            color = ratingColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            trackColor = Color(0xFF242424),
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
private fun GeneratorOptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A1A))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = Color(0xFFE8E8E8),
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Color(0xFF0EA5A1),
                    uncheckedThumbColor = Color(0xFF888888),
                    uncheckedTrackColor = Color(0xFF242424)
                )
            )
        }
    }
}
