package com.vaultpass.desktop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultpass.desktop.domain.exportimport.DetectedFormat
import com.vaultpass.desktop.domain.exportimport.ImportConflictStrategy
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.desktop.domain.sync.SyncState
import com.vaultpass.desktop.ui.components.ResizableSplitPane
import com.vaultpass.desktop.ui.components.LocalLayoutCoordinator
import com.vaultpass.desktop.ui.sync.IncomingSyncRequestDialog
import com.vaultpass.desktop.ui.sync.QrPairingDialog
import com.vaultpass.desktop.ui.sync.SyncReviewDialog
import com.vaultpass.desktop.ui.viewmodels.PendingImportSession
import com.vaultpass.desktop.ui.viewmodels.SettingsViewModel
import com.vaultpass.desktop.ui.viewmodels.SyncViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SettingsCategory(val title: String) {
    Security("Security"),
    Vault("Vault"),
    Sync("LAN Sync"),
    Backup("Backup"),
    Privacy("Privacy"),
    Appearance("Appearance"),
    About("About")
}

enum class ExportFormat(val title: String, val subtitle: String, val extension: String, val defaultFilename: String) {
    Vpex("Encrypted Backup (.vpex)", "VaultPass Android & Desktop", ".vpex", "vaultpass_backup.vpex"),
    Json("JSON (.json)", "Compatible with Android VaultPass", ".json", "vaultpass_export.json"),
    Csv("Standard CSV (.csv)", "Excel, Google Sheets, 1Password", ".csv", "vaultpass_export.csv"),
    PlainText("Plain Text (.txt)", "Human-readable format", ".txt", "vaultpass_export.txt")
}

enum class ImportOption(val title: String, val detectedFormat: DetectedFormat?, val extensionFilter: List<String>) {
    AutoDetect("Auto-Detect Format (Recommended)", null, listOf(".vpex", ".json", ".csv", ".xml", ".txt")),
    Vpex("VaultPass Encrypted (.vpex)", DetectedFormat.VAULTPASS_VPEX, listOf(".vpex")),
    VaultPassJson("VaultPass JSON (.json)", DetectedFormat.VAULTPASS_JSON, listOf(".json")),
    GenericCsv("Standard CSV (.csv)", DetectedFormat.GENERIC_CSV, listOf(".csv")),
    BitwardenJson("Bitwarden JSON (.json)", DetectedFormat.BITWARDEN_JSON, listOf(".json")),
    BitwardenCsv("Bitwarden CSV (.csv)", DetectedFormat.BITWARDEN_CSV, listOf(".csv")),
    KeePassXml("KeePass 2.x XML (.xml)", DetectedFormat.KEEPASS_XML, listOf(".xml")),
    KeePassCsv("KeePass CSV (.csv)", DetectedFormat.KEEPASS_CSV, listOf(".csv")),
    PlainText("VaultPass Plain Text (.txt)", DetectedFormat.VAULTPASS_TXT, listOf(".txt"))
}

enum class CrossPlatformFormat(val title: String, val extension: String, val defaultFilename: String) {
    PlainText("Plain Text (.txt)", ".txt", "vaultpass_export.txt"),
    Json("JSON (.json)", ".json", "vaultpass_export.json"),
    Vpex("Encrypted (.vpex)", ".vpex", "vaultpass_backup.vpex")
}

data class PasswordStrength(
    val score: Float,
    val label: String,
    val color: Color
)

fun calculatePasswordStrength(password: String): PasswordStrength {
    if (password.length < 6) {
        return PasswordStrength(0.2f, "Too short (min 6 chars)", Color(0xFFFF6B6B))
    }
    var score = 0.3f
    if (password.length >= 8) score += 0.2f
    if (password.length >= 12) score += 0.1f
    if (password.any { it.isDigit() }) score += 0.15f
    if (password.any { it.isUpperCase() } && password.any { it.isLowerCase() }) score += 0.15f
    if (password.any { !it.isLetterOrDigit() }) score += 0.1f

    score = score.coerceIn(0.2f, 1.0f)

    return when {
        score < 0.5f -> PasswordStrength(score, "Weak", Color(0xFFFF6B6B))
        score < 0.8f -> PasswordStrength(score, "Medium", Color(0xFFFFB900))
        else -> PasswordStrength(score, "Strong", Color(0xFF0EA5A1))
    }
}

@Composable
fun SettingsScreen(
    settingsViewModel: SettingsViewModel,
    backupViewModel: com.vaultpass.desktop.ui.viewmodels.BackupViewModel,
    vaultViewModel: com.vaultpass.desktop.ui.viewmodels.VaultViewModel? = null,
    syncViewModel: SyncViewModel? = null,
    databasePath: String
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.Security) }

    // LAN sync listens only while the Sync page is open. This is keyed on the page, not on
    // the sync card, because lazy-list items are disposed when scrolled out of view.
    val isSyncPageOpen = selectedCategory == SettingsCategory.Sync
    androidx.compose.runtime.DisposableEffect(syncViewModel, isSyncPageOpen) {
        if (isSyncPageOpen) syncViewModel?.startNetworking()
        onDispose {
            if (isSyncPageOpen) syncViewModel?.stopNetworking()
        }
    }

    val settingsState by settingsViewModel.settingsState.collectAsState()
    val vaultState by (vaultViewModel?.uiState?.collectAsState() ?: remember { mutableStateOf(com.vaultpass.desktop.ui.viewmodels.VaultUiState()) })
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val syncStatusMsg by (syncViewModel?.statusMessage?.collectAsState() ?: remember { mutableStateOf(null) })
    LaunchedEffect(syncStatusMsg) {
        syncStatusMsg?.let {
            snackbarHostState.showSnackbar(it)
            syncViewModel?.clearStatusMessage()
        }
    }

    var showVpexExportPasswordDialog by remember { mutableStateOf(false) }
    var vpexExportTargetFile by remember { mutableStateOf<java.io.File?>(null) }
    var showVpexImportPasswordDialog by remember { mutableStateOf(false) }
    var vpexImportSourceFile by remember { mutableStateOf<java.io.File?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->

        val backupState by backupViewModel.uiState.collectAsState()

        LaunchedEffect(backupState.successMessage) {
            backupState.successMessage?.let {
                snackbarHostState.showSnackbar(it)
                backupViewModel.clearMessages()
            }
        }
        LaunchedEffect(backupState.lastBackupMetadata) {
            settingsViewModel.refreshSettings()
        }
        if (backupState.errorMessage != null) {
            AlertDialog(
                onDismissRequest = { backupViewModel.clearMessages() },
                title = { Text("Backup Error", color = Color.White) },
                text = { Text(backupState.errorMessage ?: "Unknown error", color = Color(0xFFE8E8E8)) },
                confirmButton = {
                    TextButton(onClick = { backupViewModel.clearMessages() }) {
                        Text("OK", color = Color(0xFF0EA5A1))
                    }
                },
                containerColor = Color(0xFF1A1A1A)
            )
        }

        if (backupState.pendingRestoreMetadata != null) {
            var password by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { backupViewModel.cancelRestore() },
                title = { Text("Confirm Restore", color = Color.White) },
                text = {
                    Column {
                        Text("Are you sure you want to restore this backup? It will overwrite any existing entries.", color = Color(0xFFE8E8E8))
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Master Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF0EA5A1),
                                unfocusedBorderColor = Color(0xFF242424)
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { backupViewModel.confirmRestore(password.toCharArray()) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
                    ) {
                        Text("Restore", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { backupViewModel.cancelRestore() }) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                },
                containerColor = Color(0xFF1A1A1A)
            )
        }

        // Export .vpex Password Dialog
        if (showVpexExportPasswordDialog && vpexExportTargetFile != null) {
            var password by remember { mutableStateOf("") }
            var confirmPassword by remember { mutableStateOf("") }
            var passwordVisible by remember { mutableStateOf(false) }
            var confirmPasswordVisible by remember { mutableStateOf(false) }

            val strength = calculatePasswordStrength(password)
            val isValidLength = password.length >= 6
            val passwordsMatch = password == confirmPassword
            val canExport = isValidLength && passwordsMatch

            AlertDialog(
                onDismissRequest = {
                    showVpexExportPasswordDialog = false
                    vpexExportTargetFile = null
                },
                title = { Text("Export Password Required", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Enter a password to encrypt this .vpex file. Minimum 6 characters.", color = Color(0xFFE8E8E8), fontSize = 13.sp)

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Export Password") },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "Hide" else "Show", color = Color(0xFF0EA5A1), fontSize = 12.sp)
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF0EA5A1),
                                unfocusedBorderColor = Color(0xFF242424)
                            )
                        )

                        // Password Strength Bar
                        if (password.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Strength:", fontSize = 11.sp, color = Color(0xFF888888))
                                    Text(strength.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = strength.color)
                                }
                                LinearProgressIndicator(
                                    progress = strength.score,
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = strength.color,
                                    trackColor = Color(0xFF242424)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text("Confirm Export Password") },
                            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                                    Text(if (confirmPasswordVisible) "Hide" else "Show", color = Color(0xFF0EA5A1), fontSize = 12.sp)
                                }
                            },
                            isError = confirmPassword.isNotEmpty() && !passwordsMatch,
                            supportingText = if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                                { Text("Passwords do not match", color = Color(0xFFFF6B6B)) }
                            } else null,
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF0EA5A1),
                                unfocusedBorderColor = Color(0xFF242424)
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = canExport,
                        onClick = {
                            if (canExport) {
                                val file = vpexExportTargetFile!!
                                showVpexExportPasswordDialog = false
                                vpexExportTargetFile = null
                                // null: the whole vault, read in the export's own transaction (a
                                // locked vault fails it), not this screen's cached list.
                                backupViewModel.exportVpex(file, password, null)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
                    ) {
                        Text("Export .vpex", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showVpexExportPasswordDialog = false
                        vpexExportTargetFile = null
                    }) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                },
                containerColor = Color(0xFF1A1A1A)
            )
        }

        // Import .vpex Password Dialog
        if (showVpexImportPasswordDialog && vpexImportSourceFile != null) {
            var password by remember { mutableStateOf("") }
            var passwordVisible by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = {
                    showVpexImportPasswordDialog = false
                    vpexImportSourceFile = null
                },
                title = { Text("Enter Export Password", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Enter the password used when encrypting this .vpex file.", color = Color(0xFFE8E8E8), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Export Password") },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "Hide" else "Show", color = Color(0xFF0EA5A1), fontSize = 12.sp)
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF0EA5A1),
                                unfocusedBorderColor = Color(0xFF242424)
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = password.isNotBlank(),
                        onClick = {
                            val file = vpexImportSourceFile!!
                            showVpexImportPasswordDialog = false
                            vpexImportSourceFile = null
                            backupViewModel.prepareImport(
                                file = file,
                                explicitFormat = DetectedFormat.VAULTPASS_VPEX,
                                vpexPassword = password,
                                existingEntries = vaultState.allActiveEntries
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
                    ) {
                        Text("Decrypt & Import", fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showVpexImportPasswordDialog = false
                        vpexImportSourceFile = null
                    }) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                },
                containerColor = Color(0xFF1A1A1A)
            )
        }

        // Upgraded Unified Import Preview Modal
        if (backupState.pendingImportSession != null) {
            ImportPreviewDialog(
                session = backupState.pendingImportSession!!,
                onStrategySelected = { strategy -> backupViewModel.setImportConflictStrategy(strategy) },
                onConfirm = { backupViewModel.confirmUnifiedImport() },
                onDismiss = { backupViewModel.cancelUnifiedImport() }
            )
        } else {
            // Legacy Import Preview Modal (fallback for existing callers)
            val activeImportResult = backupState.pendingPlainTextImport ?: backupState.pendingJsonImport ?: backupState.pendingVpexImport
            if (activeImportResult != null) {
                val formatLabel = when {
                    backupState.pendingPlainTextImport != null -> "Plain Text (.txt)"
                    backupState.pendingJsonImport != null -> "JSON (.json)"
                    else -> "Encrypted (.vpex)"
                }

                AlertDialog(
                    onDismissRequest = {
                        backupViewModel.cancelPlainTextImport()
                        backupViewModel.cancelJsonImport()
                        backupViewModel.cancelVpexImport()
                    },
                    title = { Text("$formatLabel Import Preview", color = Color.White) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Total Entries Found: ${activeImportResult.totalCount}", color = Color(0xFFE8E8E8))
                            Text("Valid Entries: ${activeImportResult.validEntries.size}", color = Color(0xFF0EA5A1), fontWeight = FontWeight.Bold)
                            Text("Duplicate Entries: ${activeImportResult.duplicateCount}", color = if (activeImportResult.duplicateCount > 0) Color(0xFFFFB900) else Color(0xFF888888))
                            Text("Invalid / Skipped Entries: ${activeImportResult.invalidCount}", color = if (activeImportResult.invalidCount > 0) Color(0xFFFF6B6B) else Color(0xFF888888))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Clicking Confirm will import all valid entries inside a single atomic database transaction. If an unexpected error occurs, changes will be completely rolled back.",
                                fontSize = 12.sp,
                                color = Color(0xFF888888)
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                when {
                                    backupState.pendingPlainTextImport != null -> backupViewModel.confirmPlainTextImport()
                                    backupState.pendingJsonImport != null -> backupViewModel.confirmJsonImport()
                                    backupState.pendingVpexImport != null -> backupViewModel.confirmVpexImport()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
                        ) {
                            Text("Confirm Import", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            backupViewModel.cancelPlainTextImport()
                            backupViewModel.cancelJsonImport()
                            backupViewModel.cancelVpexImport()
                        }) {
                            Text("Cancel", color = Color(0xFF888888))
                        }
                    },
                    containerColor = Color(0xFF1A1A1A)
                )
            }
        }

        val settingsCategoriesWidth = settingsState.settingsCategoriesWidth
        val coordinator = LocalLayoutCoordinator.current
        val navMinWidth = 200f
        val contentMinWidth = 420f

        androidx.compose.runtime.DisposableEffect(Unit) {
            coordinator.registerComponent("SettingsNavigation", navMinWidth)
            onDispose { coordinator.unregisterComponent("SettingsNavigation") }
        }

        val showContentSideBySide = coordinator.hasSpaceFor(contentMinWidth, excludeId = "SettingsContent")

        androidx.compose.runtime.DisposableEffect(showContentSideBySide) {
            if (showContentSideBySide) {
                coordinator.registerComponent("SettingsContent", contentMinWidth)
            } else {
                coordinator.unregisterComponent("SettingsContent")
            }
            onDispose {
                if (showContentSideBySide) {
                    coordinator.unregisterComponent("SettingsContent")
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (showContentSideBySide) {
                ResizableSplitPane(
                    initialWidth = settingsCategoriesWidth,
                    minWidth = navMinWidth,
                    maxWidth = 320f,
                    flexiblePaneMinWidth = 420f,
                    onWidthChangeFinished = { newWidth -> settingsViewModel.updateSettings { it.copy(settingsCategoriesWidth = newWidth) } },
                    leftPane = { modifier ->
                        SettingsNavigationPane(
                            selectedCategory = selectedCategory,
                            onCategorySelected = { selectedCategory = it }
                        )
                    },
                    rightPane = { modifier ->
                        SettingsContentPane(
                            selectedCategory = selectedCategory,
                            settingsViewModel = settingsViewModel,
                            backupViewModel = backupViewModel,
                            vaultState = vaultState,
                            syncViewModel = syncViewModel,
                            databasePath = databasePath,
                            onShowSaveFileDialog = { title, defaultName, exts, onSelect -> showSaveFileDialog(title, defaultName, exts, onSelect) },
                            onShowOpenFileDialog = { title, exts, onSelect -> showOpenFileDialog(title, exts, onSelect) },
                            onTriggerVpexExport = { file ->
                                vpexExportTargetFile = file
                                showVpexExportPasswordDialog = true
                            },
                            onTriggerVpexImport = { file ->
                                vpexImportSourceFile = file
                                showVpexImportPasswordDialog = true
                            },
                            onShowSnackbar = { msg ->
                                coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        )
                    }
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    SettingsCategorySelector(
                        selectedCategory = selectedCategory,
                        onCategorySelected = { selectedCategory = it }
                    )
                    Divider(color = Color(0xFF242424))
                    SettingsContentPane(
                        selectedCategory = selectedCategory,
                        settingsViewModel = settingsViewModel,
                        backupViewModel = backupViewModel,
                        vaultState = vaultState,
                        syncViewModel = syncViewModel,
                        databasePath = databasePath,
                        onShowSaveFileDialog = { title, defaultName, exts, onSelect -> showSaveFileDialog(title, defaultName, exts, onSelect) },
                        onShowOpenFileDialog = { title, exts, onSelect -> showOpenFileDialog(title, exts, onSelect) },
                        onTriggerVpexExport = { file ->
                            vpexExportTargetFile = file
                            showVpexExportPasswordDialog = true
                        },
                        onTriggerVpexImport = { file ->
                            vpexImportSourceFile = file
                            showVpexImportPasswordDialog = true
                        },
                        onShowSnackbar = { msg ->
                            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    )
                }
            }

            // Loading Progress Overlay
            if (backupState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = Color(0xFF0EA5A1))
                            Text("Processing... Please wait", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationPane(
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 16.dp)
        ) {
            Text(
                text = "Settings",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsCategory.values().forEach { category ->
                SettingsCategoryItem(
                    category = category,
                    isSelected = category == selectedCategory,
                    onClick = { onCategorySelected(category) }
                )
            }
        }
    }
}

@Composable
private fun SettingsCategorySelector(
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = selectedCategory.ordinal,
        containerColor = Color(0xFF121212),
        contentColor = Color(0xFF0EA5A1),
        edgePadding = 16.dp,
        divider = {}
    ) {
        SettingsCategory.values().forEach { category ->
            Tab(
                selected = category == selectedCategory,
                onClick = { onCategorySelected(category) },
                text = {
                    Text(
                        text = category.title,
                        fontWeight = if (category == selectedCategory) FontWeight.Bold else FontWeight.Normal
                    )
                },
                selectedContentColor = Color(0xFF0EA5A1),
                unselectedContentColor = Color(0xFF888888)
            )
        }
    }
}

@Composable
private fun SettingsCategoryItem(
    category: SettingsCategory,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val backgroundColor = when {
        isSelected -> Color(0xFF1E1E1E)
        isHovered -> Color(0xFF181818)
        else -> Color.Transparent
    }

    val contentColor = when {
        isSelected -> Color(0xFF0EA5A1)
        isHovered -> Color.White
        else -> Color(0xFFAAAAAA)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = category.title,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}

@Composable
private fun SettingsContentPane(
    selectedCategory: SettingsCategory,
    settingsViewModel: SettingsViewModel,
    backupViewModel: com.vaultpass.desktop.ui.viewmodels.BackupViewModel,
    vaultState: com.vaultpass.desktop.ui.viewmodels.VaultUiState,
    syncViewModel: SyncViewModel? = null,
    databasePath: String,
    onShowSaveFileDialog: (String, String, List<String>, (java.io.File) -> Unit) -> Unit,
    onShowOpenFileDialog: (String, List<String>, (java.io.File) -> Unit) -> Unit,
    onTriggerVpexExport: (java.io.File) -> Unit,
    onTriggerVpexImport: (java.io.File) -> Unit,
    onShowSnackbar: (String) -> Unit = {}
) {
    val settingsState by settingsViewModel.settingsState.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF161616)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = selectedCategory.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                when (selectedCategory) {
                    SettingsCategory.Security -> {
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    SettingsToggleRow(
                                        title = "Require Master Password on Startup",
                                        description = "Prompt for Master Password when opening VaultPass.",
                                        checked = settingsState.requireMasterPasswordOnStartup,
                                        onCheckedChange = { settingsViewModel.updateRequireMasterPasswordOnStartup(it) }
                                    )
                                    SettingsToggleRow(
                                        title = "Lock When Minimized",
                                        description = "Automatically lock the vault immediately whenever the window is minimized.",
                                        checked = settingsState.lockWhenMinimized,
                                        onCheckedChange = { settingsViewModel.updateSettings { s -> s.copy(lockWhenMinimized = it) } }
                                    )
                                    val timeoutOptions = listOf("1 Minute", "5 Minutes", "15 Minutes (Default)", "30 Minutes", "60 Minutes", "Never")
                                    SettingsDropdownRow(
                                        title = "Auto-Lock Inactivity Timeout",
                                        description = "Lock the vault automatically after a period of inactivity.",
                                        currentValue = when (settingsState.autoLockTimeoutMinutes) {
                                            1 -> "1 Minute"
                                            5 -> "5 Minutes"
                                            15 -> "15 Minutes (Default)"
                                            30 -> "30 Minutes"
                                            60 -> "60 Minutes"
                                            0 -> "Never"
                                            else -> "${settingsState.autoLockTimeoutMinutes} Minutes"
                                        },
                                        options = timeoutOptions,
                                        onSelect = { selected ->
                                            val mins = when (selected) {
                                                "1 Minute" -> 1
                                                "5 Minutes" -> 5
                                                "15 Minutes (Default)" -> 15
                                                "30 Minutes" -> 30
                                                "60 Minutes" -> 60
                                                "Never" -> 0
                                                else -> 15
                                            }
                                            settingsViewModel.updateSettings { it.copy(autoLockTimeoutMinutes = mins) }
                                        }
                                    )
                                    SettingsToggleRow(
                                        title = "Lock on System Lock (Win+L)",
                                        description = "Automatically lock the vault when Windows is locked or suspended.",
                                        checked = settingsState.lockOnWorkstationLock,
                                        onCheckedChange = { settingsViewModel.updateSettings { s -> s.copy(lockOnWorkstationLock = it) } }
                                    )
                                    SettingsToggleRow(
                                        title = "Clear Search Filters on Lock",
                                        description = "Reset active search queries and filters when the vault locks.",
                                        checked = settingsState.clearSearchOnLock,
                                        onCheckedChange = { settingsViewModel.updateSettings { s -> s.copy(clearSearchOnLock = it) } }
                                    )
                                    Divider(color = Color(0xFF242424))
                                    val clipboardOptions = listOf("10 Seconds", "20 Seconds", "30 Seconds (Default)", "60 Seconds", "120 Seconds", "Never Auto-Clear")
                                    SettingsDropdownRow(
                                        title = "Clipboard Clear Timeout",
                                        description = "Duration before copied passwords are erased from clipboard.",
                                        currentValue = when (settingsState.clearClipboardTimeoutSeconds) {
                                            10 -> "10 Seconds"
                                            20 -> "20 Seconds"
                                            30 -> "30 Seconds (Default)"
                                            60 -> "60 Seconds"
                                            120 -> "120 Seconds"
                                            0 -> "Never Auto-Clear"
                                            else -> "${settingsState.clearClipboardTimeoutSeconds} Seconds"
                                        },
                                        options = clipboardOptions,
                                        onSelect = { selected ->
                                            val secs = when (selected) {
                                                "10 Seconds" -> 10
                                                "20 Seconds" -> 20
                                                "30 Seconds (Default)" -> 30
                                                "60 Seconds" -> 60
                                                "120 Seconds" -> 120
                                                "Never Auto-Clear" -> 0
                                                else -> 30
                                            }
                                            settingsViewModel.updateSettings { it.copy(clearClipboardTimeoutSeconds = secs) }
                                        }
                                    )
                                    SettingsInfoRow(
                                        title = "Windows Clipboard Protection",
                                        description = "Active: Passwords are automatically shielded from Windows 10/11 Clipboard History (Win+V) and Cloud Clipboard Sync."
                                    )
                                }
                            }
                        }
                    }
                    SettingsCategory.Vault -> {
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    SettingsDropdownRow(
                                        title = "Double-Click Entry Action",
                                        description = "Choose what happens when double-clicking a vault entry.",
                                        currentValue = settingsState.doubleClickBehaviour,
                                        options = listOf("Copy Password", "Copy Username", "Edit Entry", "View Details"),
                                        onSelect = { action -> settingsViewModel.updateSettings { it.copy(doubleClickBehaviour = action) } }
                                    )
                                    SettingsDropdownRow(
                                        title = "Recycle Bin Auto-Delete Period",
                                        description = "Automatically and permanently purge deleted entries after this time.",
                                        currentValue = when (settingsState.recycleBinRetentionDays) {
                                            7 -> "7 Days"
                                            14 -> "14 Days"
                                            30 -> "30 Days (Default)"
                                            60 -> "60 Days"
                                            90 -> "90 Days"
                                            0 -> "Never Auto-Delete"
                                            else -> "${settingsState.recycleBinRetentionDays} Days"
                                        },
                                        options = listOf("7 Days", "14 Days", "30 Days (Default)", "60 Days", "90 Days", "Never Auto-Delete"),
                                        onSelect = { option ->
                                            val days = when (option) {
                                                "7 Days" -> 7
                                                "14 Days" -> 14
                                                "30 Days (Default)" -> 30
                                                "60 Days" -> 60
                                                "90 Days" -> 90
                                                "Never Auto-Delete" -> 0
                                                else -> 30
                                            }
                                            settingsViewModel.updateSettings { it.copy(recycleBinRetentionDays = days) }
                                        }
                                    )
                                }
                            }
                        }
                        item {
                            SettingsCard {
                                SettingsInfoRow(
                                    title = "Vault Database Location",
                                    description = databasePath
                                )
                            }
                        }
                    }
                    SettingsCategory.Sync -> {
                        item {
                            LanSyncSettingsCard(
                                syncViewModel = syncViewModel,
                                onShowSnackbar = onShowSnackbar
                            )
                        }
                    }
                    SettingsCategory.Backup -> {
                        item {
                            var selectedExportFormat by remember { mutableStateOf(ExportFormat.Vpex) }
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Export Vault Data", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(
                                        "Export your vault data to encrypted backup, Android-compatible JSON, standard CSV, or plain text.",
                                        fontSize = 12.sp,
                                        color = Color(0xFF888888)
                                    )

                                    ExportFormat.values().forEach { format ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { selectedExportFormat = format }
                                                .padding(vertical = 4.dp)
                                        ) {
                                            RadioButton(
                                                selected = (selectedExportFormat == format),
                                                onClick = { selectedExportFormat = format },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0EA5A1))
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(format.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                Text(format.subtitle, color = Color(0xFF888888), fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Button(
                                        onClick = {
                                            onShowSaveFileDialog(
                                                "Export Vault (${selectedExportFormat.title})",
                                                selectedExportFormat.defaultFilename,
                                                listOf(selectedExportFormat.extension)
                                            ) { file ->
                                                // null: the whole vault, read in the export's own transaction
                                                // (a locked vault fails it), not this screen's cached list.
                                                when (selectedExportFormat) {
                                                    ExportFormat.PlainText -> backupViewModel.exportPlainText(file, null)
                                                    ExportFormat.Json -> backupViewModel.exportJson(file, null)
                                                    ExportFormat.Csv -> backupViewModel.exportCsv(file, null)
                                                    ExportFormat.Vpex -> onTriggerVpexExport(file)
                                                }
                                            }
                                        },
                                        modifier = Modifier.align(Alignment.End),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
                                    ) {
                                        Text("Export", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        item {
                            var selectedImportOption by remember { mutableStateOf(ImportOption.AutoDetect) }
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Import Vault Data", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(
                                        "Import passwords from VaultPass Android/Desktop, Bitwarden, KeePass, or standard CSV.",
                                        fontSize = 12.sp,
                                        color = Color(0xFF888888)
                                    )

                                    ImportOption.values().forEach { option ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { selectedImportOption = option }
                                                .padding(vertical = 3.dp)
                                        ) {
                                            RadioButton(
                                                selected = (selectedImportOption == option),
                                                onClick = { selectedImportOption = option },
                                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0EA5A1))
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                option.title,
                                                color = if (selectedImportOption == option) Color.White else Color(0xFFCCCCCC),
                                                fontSize = 13.sp,
                                                fontWeight = if (selectedImportOption == option) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Button(
                                        onClick = {
                                            onShowOpenFileDialog(
                                                "Import Vault (${selectedImportOption.title})",
                                                selectedImportOption.extensionFilter
                                            ) { file ->
                                                val activeEntries = vaultState.allActiveEntries
                                                if (file.name.endsWith(".vpex", ignoreCase = true) || selectedImportOption == ImportOption.Vpex) {
                                                    onTriggerVpexImport(file)
                                                } else {
                                                    backupViewModel.prepareImport(
                                                        file = file,
                                                        explicitFormat = selectedImportOption.detectedFormat,
                                                        vpexPassword = null,
                                                        existingEntries = activeEntries,
                                                        onPasswordRequired = { onTriggerVpexImport(file) }
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.align(Alignment.End),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
                                    ) {
                                        Text("Import", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Desktop Vault Backup Operations", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    SettingsActionRow(
                                        title = "Create .vpb Encrypted Vault Backup",
                                        description = "Export all vault entries to an encrypted desktop .vpb backup file.",
                                        buttonText = "Export Backup",
                                        onClick = { backupViewModel.createBackup() }
                                    )
                                    SettingsActionRow(
                                        title = "Restore from .vpb Vault Backup",
                                        description = "Restore passwords from a desktop .vpb backup file.",
                                        buttonText = "Restore Backup",
                                        onClick = { backupViewModel.initiateRestore() }
                                    )
                                    SettingsActionRow(
                                        title = "Open Backup Directory",
                                        description = "Open the local folder where desktop backups are saved.",
                                        buttonText = "Open Folder",
                                        onClick = { settingsViewModel.openBackupFolder() }
                                    )
                                }
                            }
                        }
                    }
                    SettingsCategory.Privacy -> {
                        // Header & Core Guarantees
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Privacy Policy for VaultPass Desktop", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Text("Effective Date: September 10, 2026", color = Color(0xFF0EA5A1), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                try {
                                                    if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                                                        java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://github.com/ArmaanCode2/vault-pass-desktop/blob/master/PRIVACY.md"))
                                                    }
                                                } catch (_: Exception) {}
                                            },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0EA5A1)),
                                            border = BorderStroke(1.dp, Color(0xFF0EA5A1).copy(alpha = 0.5f)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("View on GitHub", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }

                                    Text(
                                        "VaultPass Desktop is engineered from the ground up as a local-first, zero-cloud password manager. We believe your private credentials belong exclusively to you. The Application operates entirely on your personal machine and does not require a user account, cloud subscription, or continuous internet connection to function.",
                                        color = Color(0xFFCCCCCC),
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        PrivacyBadge("Zero Cloud")
                                        PrivacyBadge("Zero Telemetry")
                                        PrivacyBadge("100% Local Storage")
                                        PrivacyBadge("Open Source")
                                    }
                                }
                            }
                        }

                        // 1. Information Stored & Local Device Storage
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("1. Information Stored & Local Device Storage", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PrivacyItem(
                                            title = "Zero Access to Stored Information",
                                            description = "VaultPass Desktop allows you to manage sensitive information including account logins, passwords, usernames, web URLs, secure notes, and custom fields. We do not collect, transmit, store, or have access to any of this information."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "No Accounts or User Registration",
                                            description = "VaultPass Desktop requires no user registration, email address, phone number, or personal identifiers. There is no account creation process or identity tracking."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Zero Telemetry, Analytics, or Tracking",
                                            description = "The Application contains no tracking code, telemetry SDKs, analytics suites, or behavioral monitoring tools. We do not track how you use the Application."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "No Developer Cloud Infrastructure",
                                            description = "We do not operate cloud databases, sync servers, or proprietary relay backends. Your data never touches any server owned or operated by us. All encrypted vault databases, encryption metadata, and configurations reside locally on your computer (within your chosen installation folder's 'data' directory or user application directory)."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Zero Telemetry or Remote Crash Reports",
                                            description = "Operational logs and crash traces remain local to your machine and are never transmitted automatically to us or any third party."
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Cryptography & Security Architecture
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("2. Cryptography & Security Architecture", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PrivacyItem(
                                            title = "Master Password & Key Derivation",
                                            description = "All vault records are secured using AES-256-GCM before being written to disk. Encryption keys are derived locally from your Master Password with PBKDF2-HMAC-SHA256. Your Master Password is never transmitted off your machine and is never stored in plain text."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Session Protection (DPAPI & Memory Scrubbing)",
                                            description = "Sensitive cryptographic keys in system memory are protected using the Windows Data Protection API (DPAPI) and are actively zeroed out from RAM immediately when the vault is locked."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Zero Knowledge & No Recovery Backdoors",
                                            description = "Because encryption occurs locally and we have zero knowledge of your Master Password, we cannot reset your password or recover your data if you lose your Master Password."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Brute-Force Protection",
                                            description = "The Application incorporates rate-limiting cooldown timers to resist local brute-force attempts on vault unlocking."
                                        )
                                    }
                                }
                            }
                        }

                        // 3. Network Communication & Software Updates
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("3. Network Communication & Software Updates", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PrivacyItem(
                                            title = "A. Software Update Checks (GitHub Releases)",
                                            description = "VaultPass Desktop maintains a strict separation between your local encrypted vault and external network requests:\n• Public Endpoint Queried: When checking for updates, the Application sends a standard HTTPS GET request to GitHub's public API or web fallback (api.github.com / github.com/ArmaanCode2/vault-pass-desktop/releases/latest) and downloads updated installation packages from GitHub Releases.\n• What Data is Sent: In accordance with standard Internet protocol communication, this request transmits only generic technical network headers (your IP address as visible to GitHub, and a standard User-Agent header such as VaultPass-Desktop/1.0.2).\n• What Data is NEVER Sent: Vault records, master passwords, encryption keys, usernames, URLs, machine identifiers, hardware fingerprints, and usage statistics are NEVER included in update requests.\n• Third-Party Infrastructure: Update binaries and release metadata are hosted on GitHub (Microsoft Corporation) and subject to the GitHub Privacy Statement. VaultPass operates no intermediate proxy or custom server.\n• Air-Gapped Operation: Update checks are off for new installs and can be turned on or off at any time under Settings -> About. VaultPass Desktop operates with complete feature parity in air-gapped or firewall-blocked environments."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "B. Local Network Synchronization (LAN Sync)",
                                            description = "VaultPass Desktop can synchronize credentials directly with the VaultPass Android companion app:\n• Local Subnet Only: Synchronization occurs directly between devices over your local Wi-Fi subnet using end-to-end encrypted sockets.\n• Zero Cloud Relays: Sync data is never transmitted over the public internet, external proxies, or third-party relay servers. Both devices must reside on the same local subnet to communicate."
                                        )
                                    }
                                }
                            }
                        }

                        // 4. Features, Clipboard & Third-Party Boundaries
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("4. Import/Export, Clipboard & Third-Party Boundaries", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PrivacyItem(
                                            title = "Import and Export Features",
                                            description = "VaultPass Desktop provides tools to import credentials from external formats (CSV, Bitwarden, KeePass) and export your vault data (unencrypted CSV/JSON or encrypted .vpex backups). Exported files are written directly to your chosen local directory path. You are solely responsible for securing exported plain text files."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Clipboard Security & History Exclusion",
                                            description = "When you copy a username, password, or note to your system clipboard, VaultPass places the data on the OS clipboard with an automatic clipboard clearing timer (configurable in Settings) and sets Windows clipboard history exclusion flags (CanIncludeInClipboardHistory = 0, CanUploadToCloudClipboard = 0) to minimize exposure to other local desktop applications."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Third-Party Services: Zero Ads & Zero Brokerage",
                                            description = "VaultPass Desktop contains no advertisements or advertising SDKs, no analytics or user profiling services, and no data brokerage. Because we do not collect or possess your information, we do not (and cannot) sell, lease, or distribute your data to any third party."
                                        )
                                    }
                                }
                            }
                        }

                        // 5. Data Sovereignty & User Responsibilities
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("5. Data Sovereignty & User Responsibilities", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PrivacyItem(
                                            title = "Data Retention and Deletion",
                                            description = "Because your data is stored exclusively on your computer, you have complete control over data retention and deletion. To delete all stored credentials and configuration files, delete the 'data' directory in your VaultPass installation folder or uninstall the application and remove the data files. Soft-deleted items in the Recycle Bin are automatically purged after 7 days, or can be permanently deleted manually at any time."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Privacy Rights (GDPR & CCPA Autonomous Sovereignty)",
                                            description = "Under regulations such as GDPR and CCPA, individuals have rights regarding access, rectification, portability, and erasure of personal information. Because VaultPass Desktop does not collect, process, or transmit personal data to any server, you maintain direct, autonomous sovereignty:\n• Access & Portability: View, search, and export all stored records at any time.\n• Rectification: Edit or update any entry directly in the vault interface.\n• Erasure: Immediately and irrevocably delete any credential, folder, or the entire vault file directly on your local system without submitting requests to any company."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "User Security Responsibilities",
                                            description = "The privacy and security of your vault also rely on your local computing practices. By using VaultPass Desktop, you acknowledge responsibility for:\n• Selecting a strong, complex Master Password and memorizing it, as it cannot be recovered by anyone if lost.\n• Maintaining physical and administrative security over your personal computer.\n• Protecting any unencrypted export files created on your local disk.\n• Keeping your operating system secure against malware, keyloggers, and unauthorized physical access."
                                        )
                                    }
                                }
                            }
                        }

                        // 6. Policy Updates & Contact Information
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Text("6. Policy Updates & Contact Information", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        PrivacyItem(
                                            title = "Changes to This Privacy Policy",
                                            description = "We may update this Privacy Policy periodically to reflect enhancements in application features or security practices. Because we do not collect contact details, updates are published directly in the open-source repository. We encourage users to review this document as new releases are issued."
                                        )
                                        HorizontalDivider(color = Color(0xFF282828))
                                        PrivacyItem(
                                            title = "Contact Information",
                                            description = "If you have questions, inquiries, or feedback concerning this Privacy Policy or the security architecture of VaultPass Desktop, please contact us:\n• GitHub Issues: https://github.com/ArmaanCode2/vault-pass-desktop\n• Email: armaanweb100@gmail.com"
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    try {
                                                        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                                                            java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://github.com/ArmaanCode2/vault-pass-desktop/issues"))
                                                        }
                                                    } catch (_: Exception) {}
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                                border = BorderStroke(1.dp, Color(0xFF333333)),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("GitHub Issues", fontSize = 12.sp)
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    try {
                                                        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.MAIL)) {
                                                            java.awt.Desktop.getDesktop().mail(java.net.URI.create("mailto:armaanweb100@gmail.com?subject=VaultPass%20Desktop%20Privacy%20Inquiry"))
                                                        }
                                                    } catch (_: Exception) {}
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                                border = BorderStroke(1.dp, Color(0xFF333333)),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Email Developer", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    SettingsCategory.Appearance -> {
                        item {
                            SettingsCard {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Appearance", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text("Dark Mode is active.", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    SettingsCategory.About -> {
                        item {
                            val updateState by settingsViewModel.updateState.collectAsState()
                            val dateFormat = remember { java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()) }
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                // App Information Card
                                SettingsCard {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("VaultPass Desktop", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                Text("Version ${settingsViewModel.updateManager.currentVersion}", color = Color(0xFF0EA5A1), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            // Check for Updates Button
                                            Button(
                                                onClick = { settingsViewModel.checkForUpdates() },
                                                enabled = updateState !is com.vaultpass.desktop.data.update.UpdateState.Checking &&
                                                          updateState !is com.vaultpass.desktop.data.update.UpdateState.Downloading &&
                                                          updateState !is com.vaultpass.desktop.data.update.UpdateState.Verifying,
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                if (updateState is com.vaultpass.desktop.data.update.UpdateState.Checking) {
                                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.Black, strokeWidth = 2.dp)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Checking...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                } else {
                                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("Check for Updates", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        Text("Zero Cloud. Zero Telemetry. 100% Local Vault.", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                                        HorizontalDivider(color = Color(0xFF242424))
                                        // Auto-Check Toggle
                                        SettingsToggleRow(
                                            title = "Check for updates when VaultPass opens",
                                            description = "Asks GitHub for a newer version. Nothing from your vault is sent.",
                                            checked = settingsState.checkForUpdatesOnOpen,
                                            onCheckedChange = { settingsViewModel.updateSettings { s -> s.copy(checkForUpdatesOnOpen = it) } }
                                        )
                                    }
                                }
                                // Same states and buttons as the banner and the update dialog
                                com.vaultpass.desktop.ui.components.UpdateAboutStatus(settingsViewModel) { checkedAt ->
                                    dateFormat.format(java.util.Date(checkedAt))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun PrivacyBadge(label: String) {
    Surface(
        color = Color(0xFF0EA5A1).copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, Color(0xFF0EA5A1).copy(alpha = 0.3f))
    ) {
        Text(
            text = label,
            color = Color(0xFF0EA5A1),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PrivacyItem(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0EA5A1))
        Text(description, color = Color(0xFFAAAAAA), fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(description, fontSize = 12.sp, color = Color(0xFF888888))
        }
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

@Composable
private fun SettingsActionRow(
    title: String,
    description: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(description, fontSize = 12.sp, color = Color(0xFF888888))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2B2B), contentColor = Color.White),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(buttonText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SettingsInfoRow(
    title: String,
    description: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
        Text(description, fontSize = 12.sp, color = Color(0xFF888888))
    }
}

@Composable
private fun SettingsDropdownRow(
    title: String,
    description: String,
    currentValue: String,
    options: List<String>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(description, fontSize = 12.sp, color = Color(0xFF888888))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Box {
            Button(
                modifier = Modifier.widthIn(min = 120.dp),
                onClick = { if (enabled) expanded = true },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E1E1E),
                    contentColor = Color(0xFFE8E8E8)
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(currentValue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                        trailingIcon = if (option == currentValue) { { Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF0EA5A1)) } } else null
                    )
                }
            }
        }
    }
}

@Composable
fun ImportPreviewDialog(
    session: PendingImportSession,
    onStrategySelected: (ImportConflictStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Import Preview", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Surface(
                    color = Color(0xFF0EA5A1).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0xFF0EA5A1).copy(alpha = 0.5f))
                ) {
                    Text(
                        session.format.displayName,
                        color = Color(0xFF0EA5A1),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(min = 480.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Source: ${session.sourceFileName}",
                    color = Color(0xFF888888),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Counter Badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCard(
                        title = "Total Found",
                        value = "${session.parseResult.totalCount}",
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "Valid",
                        value = "${session.parseResult.validEntries.size}",
                        color = Color(0xFF0EA5A1),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "Duplicates",
                        value = "${session.parseResult.duplicateCount}",
                        color = if (session.parseResult.duplicateCount > 0) Color(0xFFFFB900) else Color(0xFF888888),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "Skipped",
                        value = "${session.parseResult.invalidCount}",
                        color = if (session.parseResult.invalidCount > 0) Color(0xFFFF6B6B) else Color(0xFF888888),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Conflict Resolution Radio Group (if duplicateCount > 0)
                if (session.parseResult.duplicateCount > 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Duplicate Conflict Resolution",
                            color = Color(0xFFFFB900),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        val options = listOf(
                            ImportConflictStrategy.SKIP_DUPLICATES to "Skip duplicates (Recommended)",
                            ImportConflictStrategy.OVERWRITE_EXISTING to "Overwrite existing entries with imported data",
                            ImportConflictStrategy.KEEP_BOTH to "Keep both (create duplicate copies)"
                        )

                        options.forEach { (strategy, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onStrategySelected(strategy) }
                                    .padding(vertical = 3.dp)
                            ) {
                                RadioButton(
                                    selected = session.selectedStrategy == strategy,
                                    onClick = { onStrategySelected(strategy) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0EA5A1))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(label, color = Color(0xFFE8E8E8), fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Entry List Preview (Max height ~200dp with LazyColumn)
                Text(
                    "Entries Preview (${session.parseResult.validEntries.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8E8E8)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 190.dp)
                        .background(Color(0xFF161616), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                        .padding(4.dp)
                ) {
                    if (session.parseResult.validEntries.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("No valid entries to display.", color = Color(0xFF888888), fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth().padding(4.dp)
                        ) {
                            items(session.parseResult.validEntries.size) { idx ->
                                val entry = session.parseResult.validEntries[idx]
                                val isDup = session.parseResult.duplicateKeys.contains(
                                    "${entry.title.trim().lowercase()}|${entry.username.trim().lowercase()}"
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                entry.title,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (isDup) {
                                                Surface(
                                                    color = Color(0xFFFFB900).copy(alpha = 0.2f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        "Duplicate",
                                                        color = Color(0xFFFFB900),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        if (entry.username.isNotBlank()) {
                                            Text(
                                                entry.username,
                                                color = Color(0xFF888888),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = Color(0xFF242424),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            entry.category ?: "Personal",
                                            color = Color(0xFFB0B0B0),
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Security Note
                Text(
                    "Import executes inside an atomic database transaction. If an unexpected error occurs, all changes will be completely rolled back.",
                    fontSize = 11.sp,
                    color = Color(0xFF888888)
                )
            }
        },
        confirmButton = {
            val countToImport = when (session.selectedStrategy) {
                ImportConflictStrategy.SKIP_DUPLICATES -> (session.parseResult.validEntries.size - session.parseResult.duplicateCount).coerceAtLeast(0)
                else -> session.parseResult.validEntries.size
            }
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black)
            ) {
                Text("Import ($countToImport Entries)", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF888888))
            }
        },
        containerColor = Color(0xFF1A1A1A)
    )
}

@Composable
private fun StatCard(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Color(0xFF888888), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val windowsFilePicker by lazy { com.vaultpass.desktop.data.platform.WindowsFilePicker() }

private fun showSaveFileDialog(
    title: String,
    defaultFilename: String,
    allowedExtensions: List<String> = emptyList(),
    onFileSelected: (java.io.File) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
        var comFailed = false
        val file = try {
            windowsFilePicker.showSaveFileDialog(title, defaultFilename, allowedExtensions)
        } catch (e: Throwable) {
            comFailed = true
            null
        }
        withContext(Dispatchers.Main) {
            if (file != null) {
                onFileSelected(file)
            } else if (comFailed) {
                // Fallback to AWT if on non-Windows or COM initialization fails
                val fallbackDialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.SAVE)
                fallbackDialog.file = defaultFilename
                fallbackDialog.isVisible = true
                if (fallbackDialog.directory != null && fallbackDialog.file != null) {
                    onFileSelected(java.io.File(fallbackDialog.directory, fallbackDialog.file))
                }
            }
        }
    }
}

private fun showOpenFileDialog(
    title: String,
    allowedExtensions: List<String> = emptyList(),
    onFileSelected: (java.io.File) -> Unit
) {
    kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
        var comFailed = false
        val file = try {
            windowsFilePicker.showOpenFileDialog(title, allowedExtensions.map { it.removePrefix(".") })
        } catch (e: Throwable) {
            comFailed = true
            null
        }
        withContext(Dispatchers.Main) {
            if (file != null) {
                onFileSelected(file)
            } else if (comFailed) {
                // Fallback to AWT
                val fallbackDialog = java.awt.FileDialog(null as java.awt.Frame?, title, java.awt.FileDialog.LOAD)
                fallbackDialog.isVisible = true
                if (fallbackDialog.directory != null && fallbackDialog.file != null) {
                    onFileSelected(java.io.File(fallbackDialog.directory, fallbackDialog.file))
                }
            }
        }
    }
}

@Composable
fun LanSyncSettingsCard(
    syncViewModel: SyncViewModel?,
    onShowSnackbar: (String) -> Unit = {}
) {
    val pairedDevices by (syncViewModel?.pairedDevices?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val isWaitingForRemoteApproval by (syncViewModel?.isWaitingForRemoteApproval?.collectAsState() ?: remember { mutableStateOf(false) })
    val activeSyncDevice by (syncViewModel?.activeSyncingDevice?.collectAsState() ?: remember { mutableStateOf(null) })
    var deviceToUnpair by remember { mutableStateOf<PairedDevice?>(null) }
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }

    if (isWaitingForRemoteApproval) {
        AlertDialog(
            onDismissRequest = { syncViewModel?.cancelSyncRequest() },
            title = {
                Text(
                    text = "Synchronizing Vault",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF0EA5A1),
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = "Connecting to ${activeSyncDevice?.deviceName ?: "device"}...\nWaiting for other device to accept sync request.",
                        color = Color(0xFFE8E8E8),
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { syncViewModel?.cancelSyncRequest() }) {
                    Text("Cancel", color = Color(0xFFFF6B6B))
                }
            },
            containerColor = Color(0xFF1E1E1E),
            shape = RoundedCornerShape(12.dp)
        )
    }

    if (deviceToUnpair != null) {
        AlertDialog(
            onDismissRequest = { deviceToUnpair = null },
            title = { Text("Unpair Mobile Device", color = Color.White) },
            text = {
                Text(
                    "Are you sure you want to unpair \"${deviceToUnpair?.deviceName}\"? It will be removed from your paired devices list.",
                    color = Color(0xFFE8E8E8)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val devId = deviceToUnpair?.deviceId
                        deviceToUnpair = null
                        if (devId != null) {
                            syncViewModel?.unpairDevice(devId)
                            onShowSnackbar("Device unpaired successfully")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B), contentColor = Color.White)
                ) {
                    Text("Unpair", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToUnpair = null }) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }

    SettingsCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("LAN Sync & Paired Devices", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "Synchronize your vault peer-to-peer over your local Wi-Fi or mobile hotspot with zero cloud dependencies.",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            syncViewModel?.refreshDevices()
                            onShowSnackbar("Scanning for local network devices...")
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFF333333)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Refresh", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            syncViewModel?.startQrPairing()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5A1), contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pair Mobile Device", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Divider(color = Color(0xFF242424))

            if (pairedDevices.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF161616),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color(0xFF252525))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = Color(0xFF555555),
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            "No mobile devices paired yet",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "To sync without internet, connect your computer and phone to the same Wi-Fi network (or phone hotspot), click \"Pair Mobile Device\" above, and scan the QR code using VaultPass on your phone.",
                            color = Color(0xFF888888),
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.widthIn(max = 460.dp)
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    pairedDevices.forEach { device ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF181818),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (device.isOnline) Color(0xFF0EA5A1).copy(alpha = 0.3f) else Color(0xFF282828))
                        ) {
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val isNarrow = maxWidth < 480.dp
                                val lastSyncText = if (device.lastSyncAt > 0L) {
                                    dateFormat.format(Date(device.lastSyncAt))
                                } else {
                                    "Never"
                                }

                                if (isNarrow) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .background(
                                                        if (device.isOnline) Color(0xFF0EA5A1).copy(alpha = 0.15f) else Color(0xFF242424),
                                                        CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PhoneAndroid,
                                                    contentDescription = null,
                                                    tint = if (device.isOnline) Color(0xFF0EA5A1) else Color(0xFF888888),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Text(
                                                        text = device.deviceName,
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    Surface(
                                                        color = if (device.isOnline) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFF333333),
                                                        shape = RoundedCornerShape(12.dp),
                                                        border = BorderStroke(1.dp, if (device.isOnline) Color(0xFF10B981).copy(alpha = 0.4f) else Color.Transparent)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(6.dp)
                                                                    .background(
                                                                        if (device.isOnline) Color(0xFF10B981) else Color(0xFF888888),
                                                                        CircleShape
                                                                    )
                                                            )
                                                            Spacer(modifier = Modifier.width(5.dp))
                                                            Text(
                                                                text = if (device.isOnline) "Online" else "Offline",
                                                                color = if (device.isOnline) Color(0xFF10B981) else Color(0xFF888888),
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))

                                                Text(
                                                    text = "IP: ${device.ipAddress ?: "Unknown"}:${device.port}",
                                                    fontSize = 12.sp,
                                                    color = Color(0xFFAAAAAA),
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Last sync: $lastSyncText",
                                                    fontSize = 12.sp,
                                                    color = Color(0xFF777777),
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    syncViewModel?.initiateSyncWith(device)
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF0EA5A1),
                                                    contentColor = Color.Black
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Sync Now", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    deviceToUnpair = device
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B)),
                                                border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.4f)),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Unpair", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(42.dp)
                                                    .background(
                                                        if (device.isOnline) Color(0xFF0EA5A1).copy(alpha = 0.15f) else Color(0xFF242424),
                                                        CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PhoneAndroid,
                                                    contentDescription = null,
                                                    tint = if (device.isOnline) Color(0xFF0EA5A1) else Color(0xFF888888),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(14.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = device.deviceName,
                                                        color = Color.White,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Surface(
                                                        color = if (device.isOnline) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFF333333),
                                                        shape = RoundedCornerShape(12.dp),
                                                        border = BorderStroke(1.dp, if (device.isOnline) Color(0xFF10B981).copy(alpha = 0.4f) else Color.Transparent)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(6.dp)
                                                                    .background(
                                                                        if (device.isOnline) Color(0xFF10B981) else Color(0xFF888888),
                                                                        CircleShape
                                                                    )
                                                            )
                                                            Spacer(modifier = Modifier.width(5.dp))
                                                            Text(
                                                                text = if (device.isOnline) "Online" else "Offline",
                                                                color = if (device.isOnline) Color(0xFF10B981) else Color(0xFF888888),
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        }
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))

                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "IP: ${device.ipAddress ?: "Unknown"}:${device.port}",
                                                        fontSize = 12.sp,
                                                        color = Color(0xFFAAAAAA),
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "Last sync: $lastSyncText",
                                                        fontSize = 12.sp,
                                                        color = Color(0xFF777777),
                                                        maxLines = 1,
                                                        softWrap = false,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = {
                                                    syncViewModel?.initiateSyncWith(device)
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF0EA5A1),
                                                    contentColor = Color.Black
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                            ) {
                                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Sync Now", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    deviceToUnpair = device
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B)),
                                                border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.4f)),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text("Unpair", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
