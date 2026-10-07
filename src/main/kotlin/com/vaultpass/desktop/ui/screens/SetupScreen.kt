package com.vaultpass.desktop.ui.screens

import com.vaultpass.desktop.ui.theme.LocalVaultPassExtendedColors

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.ui.components.GlassSurface
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    onCreateVault: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }
    val shakeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val shakeError = { msg: String ->
        errorMessage = msg
        coroutineScope.launch {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 400
                    10f at 50
                    -10f at 150
                    10f at 250
                    -10f at 350
                    0f at 400
                }
            )
        }
    }

    val submitAction: () -> Unit = {
        errorMessage = null
        if (password.isBlank()) {
            shakeError("Master Password cannot be empty.")
        } else if (password != confirmPassword) {
            shakeError("Passwords do not match.")
        } else if (password.length < 8) {
            shakeError("Master Password must be at least 8 characters long.")
        } else {
            isLoading = true
            
            // 35-second safety watchdog
            val watchdogJob = coroutineScope.launch {
                kotlinx.coroutines.delay(35_000L)
                if (isLoading) {
                    isLoading = false
                    shakeError("Vault initialization timed out. Please check CPU load or folder permissions.")
                }
            }
            onCreateVault(password) { success, errorMsg ->
                watchdogJob.cancel()
                isLoading = false
                if (!success) {
                    shakeError(errorMsg ?: "Failed to create vault.")
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        MaterialTheme.colorScheme.background
                    ),
                    radius = 1200f
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(
            modifier = Modifier
                .widthIn(max = 450.dp)
                .fillMaxWidth(0.9f)
                .graphicsLayer { translationX = shakeOffset.value }
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Welcome to VaultPass",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Create a strong Master Password. This is the only key that can unlock your vault.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Password Field
                OutlinedTextField(
                    value = password,
                    onValueChange = { 
                        password = it
                        errorMessage = null 
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text("Master Password") },
                    singleLine = true,
                    isError = errorMessage != null,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    trailingIcon = {
                        val image = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(imageVector = image, contentDescription = "Toggle password visibility", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                // Password Strength Indicator
                val strengthScore = remember(password) {
                    var score = 0
                    if (password.length >= 8) score++
                    if (password.length >= 12) score++
                    if (password.any { it.isUpperCase() }) score++
                    if (password.any { it.isLowerCase() }) score++
                    if (password.any { !it.isLetterOrDigit() }) score++
                    score
                }
                
                val strengthColor = when (strengthScore) {
                    0, 1 -> MaterialTheme.colorScheme.error
                    2, 3 -> LocalVaultPassExtendedColors.current.warning // Orange
                    else -> LocalVaultPassExtendedColors.current.success // Green
                }
                val strengthText = when (strengthScore) {
                    0, 1 -> "Weak"
                    2, 3 -> "Fair"
                    4, 5 -> "Strong"
                    else -> ""
                }

                if (password.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LinearProgressIndicator(
                            progress = { (strengthScore / 5f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp),
                            color = strengthColor,
                            trackColor = MaterialTheme.colorScheme.outline,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = strengthText,
                            style = MaterialTheme.typography.bodySmall,
                            color = strengthColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(20.dp))
                }

                // Confirm Password Field
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { 
                        confirmPassword = it
                        errorMessage = null 
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onKeyEvent { event ->
                            if (isLoading) return@onKeyEvent true
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                submitAction()
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text("Confirm Master Password") },
                    singleLine = true,
                    isError = errorMessage != null,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitAction() }),
                    trailingIcon = {
                        val image = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(imageVector = image, contentDescription = "Toggle password visibility", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = submitAction,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !isLoading,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Create Vault", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
