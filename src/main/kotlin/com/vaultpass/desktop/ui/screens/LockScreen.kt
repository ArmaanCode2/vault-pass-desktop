package com.vaultpass.desktop.ui.screens

import com.vaultpass.desktop.ui.theme.LocalVaultPassExtendedColors

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.ui.components.GlassSurface
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    onUnlock: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val focusRequester = remember { FocusRequester() }
    val shakeOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val submitAction: () -> Unit = {
        if (password.isNotBlank()) {
            isLoading = true
            errorMessage = null
            onUnlock(password) { success, errorMsg ->
                isLoading = false
                if (!success) {
                    errorMessage = errorMsg ?: "Incorrect Master Password."
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
            }
        } else {
            errorMessage = "Master Password cannot be empty."
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
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.9f)
                .graphicsLayer { translationX = shakeOffset.value }
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Branding
                Text(
                    text = "VaultPass",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Welcome back. Unlock your vault.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        .focusRequester(focusRequester)
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                submitAction()
                                true
                            } else {
                                false
                            }
                        },
                    placeholder = { Text("Master Password") },
                    singleLine = true,
                    isError = errorMessage != null,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitAction() }),
                    trailingIcon = {
                        val image = if (isPasswordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(imageVector = image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                // Error Message Placeholder
                AnimatedVisibility(
                    visible = errorMessage != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.Start)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Unlock Button
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
                        Text("Unlock", style = MaterialTheme.typography.titleMedium)
                    }
                }

            }
        }
    }
}
