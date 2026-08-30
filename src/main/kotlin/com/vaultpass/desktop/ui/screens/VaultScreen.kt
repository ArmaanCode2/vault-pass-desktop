package com.vaultpass.desktop.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.ui.components.LocalLayoutCoordinator
import com.vaultpass.desktop.ui.theme.LocalSpacing
import com.vaultpass.desktop.ui.theme.LocalVaultPassExtendedColors
import com.vaultpass.desktop.ui.viewmodels.VaultViewModel
import kotlinx.coroutines.launch

@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    settingsViewModel: com.vaultpass.desktop.ui.viewmodels.SettingsViewModel,
    searchFocusRequester: androidx.compose.ui.focus.FocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
) {
    val state by viewModel.uiState.collectAsState()
    var selectedEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var sortAscending by remember { mutableStateOf(true) }
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Clear selection if it was deleted
    LaunchedEffect(state.entries) {
        if (selectedEntry != null && state.entries.none { it.id == selectedEntry?.id }) {
            selectedEntry = null
        } else if (selectedEntry != null) {
            selectedEntry = state.entries.find { it.id == selectedEntry?.id }
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Error: $it")
                viewModel.clearError()
            }
        }
    }

    val coordinator = LocalLayoutCoordinator.current
    val settingsState by settingsViewModel.settingsState.collectAsState()
    val listMinWidth = 300f
    var pendingPasswordToCopy by remember { mutableStateOf<String?>(null) }
    var showClipboardWarning by remember { mutableStateOf(false) }

    val detailsMinWidth = 420f

    androidx.compose.runtime.DisposableEffect(Unit) {
        coordinator.registerComponent("VaultList", listMinWidth)
        onDispose { coordinator.unregisterComponent("VaultList") }
    }

    val showDetails = coordinator.hasSpaceFor(detailsMinWidth, excludeId = "VaultDetails")

    androidx.compose.runtime.DisposableEffect(showDetails) {
        if (showDetails) {
            coordinator.registerComponent("VaultDetails", detailsMinWidth)
        } else {
            coordinator.unregisterComponent("VaultDetails")
        }
        onDispose { coordinator.unregisterComponent("VaultDetails") }
    }

    var forceShowDetailsOverlay by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(selectedEntry) {
        if (selectedEntry != null && !showDetails) {
            forceShowDetailsOverlay = true
        }
    }

    androidx.compose.runtime.LaunchedEffect(state.entries) {
        if (settingsState.defaultViewRememberLastSelected && selectedEntry == null) {
            val lastId = settingsState.lastSelectedEntryId
            if (lastId != null) {
                val match = state.entries.find { it.id == lastId }
                if (match != null) {
                    selectedEntry = match
                }
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(showDetails) {
        if (showDetails) {
            forceShowDetailsOverlay = false
        }
    }

    val listViewContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier = modifier
                .fillMaxHeight()
                .background(Color(0xFF0D0D0D)) // Canvas background
        ) {
            // Sticky Search & Sort bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                VaultToolbar(
                    searchQuery = state.query.searchQuery,
                    onSearchChange = { viewModel.updateSearchQuery(it) },
                    sortAscending = sortAscending,
                    onToggleSort = { sortAscending = !sortAscending },
                    searchFocusRequester = searchFocusRequester
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF0EA5A1))
                }
            } else if (state.entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color(0xFF888888)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (state.query.searchQuery.isBlank()) "Vault is empty" else "No entries found",
                            color = Color(0xFF888888),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sortedEntries = state.entries.sortedWith(
                        compareBy<VaultEntry> { it.title.lowercase() }.let { if (sortAscending) it else it.reversed() }
                    )
                    items(sortedEntries, key = { it.id }) { entry ->
                        VaultRowItem(
                            entry = entry,
                            isSelected = selectedEntry?.id == entry.id,
                            onClick = {
                                selectedEntry = entry
                                if (settingsState.defaultViewRememberLastSelected) {
                                    settingsViewModel.updateSettings { it.copy(lastSelectedEntryId = entry.id) }
                                }
                            },
                            onDoubleClick = {
                                when (settingsState.doubleClickBehaviour) {
                                    "Open Details" -> {
                                        selectedEntry = entry
                                        if (settingsState.defaultViewRememberLastSelected) {
                                            settingsViewModel.updateSettings { it.copy(lastSelectedEntryId = entry.id) }
                                        }
                                        if (!showDetails) forceShowDetailsOverlay = true
                                    }
                                    "Edit Entry" -> {
                                        viewModel.showEditDialog(entry.id)
                                    }
                                    "Copy Password" -> {
                                        if (settingsState.showClipboardHistoryWarning) {
                                            pendingPasswordToCopy = entry.secret
                                            showClipboardWarning = true
                                        } else {
                                            viewModel.copyPasswordToClipboard(entry.secret, clipboardManager)
                                            coroutineScope.launch { snackbarHostState.showSnackbar("Password copied (clears in 30s)") }
                                        }
                                    }
                                }
                            },
                            onCopyPassword = {
                                if (settingsState.showClipboardHistoryWarning) {
                                    pendingPasswordToCopy = entry.secret
                                    showClipboardWarning = true
                                } else {
                                    viewModel.copyPasswordToClipboard(entry.secret, clipboardManager)
                                    coroutineScope.launch { snackbarHostState.showSnackbar("Password copied") }
                                }
                            },
                            onEdit = { viewModel.showEditDialog(entry.id) },
                            onDelete = { viewModel.deleteEntry(entry.id) },
                            onCopyUsername = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(entry.username))
                                coroutineScope.launch { snackbarHostState.showSnackbar("Username copied") }
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(entry.id) }
                        )
                    }
                }
            }
        }
    }

    val detailViewContent: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier = modifier.background(Color(0xFF0D0D0D))) {
            if (selectedEntry != null) {
                VaultDetailPanel(
                    entry = selectedEntry!!,
                    confirmBeforeDelete = settingsState.confirmBeforeDelete,
                    hidePasswordsByDefault = settingsState.hidePasswordsByDefault,
                    onEdit = { viewModel.showEditDialog(it.id) },
                    onDelete = {
                        viewModel.deleteEntry(it.id)
                        selectedEntry = null
                    },
                    onCopyPassword = { secret ->
                        if (settingsState.showClipboardHistoryWarning) {
                            pendingPasswordToCopy = secret
                            showClipboardWarning = true
                        } else {
                            viewModel.copyPasswordToClipboard(secret, clipboardManager)
                            coroutineScope.launch { snackbarHostState.showSnackbar("Password copied") }
                        }
                    },
                    onCopyUsername = { username ->
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(username))
                        coroutineScope.launch { snackbarHostState.showSnackbar("Username copied") }
                    }
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Select an item to view details", color = Color(0xFF888888), fontSize = 14.sp)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { paddingValues ->
        if (!showDetails && forceShowDetailsOverlay && selectedEntry != null) {
            Column(modifier = Modifier.fillMaxSize().padding(paddingValues).background(Color(0xFF0D0D0D))) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { forceShowDetailsOverlay = false; selectedEntry = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text("Back to Vault", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
                detailViewContent(Modifier.fillMaxSize())
            }
        } else {
            if (showDetails) {
                val vaultDivider = settingsState.vaultListWidth
                com.vaultpass.desktop.ui.components.ResizableSplitPane(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    initialWidth = vaultDivider,
                    minWidth = listMinWidth,
                    maxWidth = 1200f,
                    flexiblePaneMinWidth = detailsMinWidth,
                    fixedPane = com.vaultpass.desktop.ui.components.FixedPane.Left,
                    showFixedPane = true,
                    onWidthChangeFinished = { newWidth ->
                        settingsViewModel.updateSettings { it.copy(vaultListWidth = newWidth) }
                    },
                    leftPane = listViewContent,
                    rightPane = { rightModifier ->
                        BoxWithConstraints(modifier = rightModifier.fillMaxHeight()) {
                            if (maxWidth < detailsMinWidth.dp) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Expand this panel to view details", color = Color(0xFF888888))
                                }
                            } else {
                                detailViewContent(Modifier.fillMaxSize())
                            }
                        }
                    }
                )
            } else {
                listViewContent(Modifier.fillMaxSize().padding(paddingValues))
            }
        }
    }

    if (showClipboardWarning && pendingPasswordToCopy != null) {
        AlertDialog(
            onDismissRequest = { showClipboardWarning = false; pendingPasswordToCopy = null },
            title = { Text("Clipboard History Warning", color = Color.White) },
            text = { Text("Windows clipboard history might retain copied passwords. Do you want to proceed?", color = Color(0xFFE8E8E8)) },
            confirmButton = {
                TextButton(onClick = {
                    showClipboardWarning = false
                    viewModel.copyPasswordToClipboard(pendingPasswordToCopy!!, clipboardManager)
                    coroutineScope.launch { snackbarHostState.showSnackbar("Password copied") }
                    pendingPasswordToCopy = null
                }) { Text("Copy anyway", color = Color(0xFF0EA5A1)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClipboardWarning = false
                    settingsViewModel.updateSettings { it.copy(showClipboardHistoryWarning = false) }
                    viewModel.copyPasswordToClipboard(pendingPasswordToCopy!!, clipboardManager)
                    coroutineScope.launch { snackbarHostState.showSnackbar("Password copied") }
                    pendingPasswordToCopy = null
                }) { Text("Don't show again", color = Color(0xFF888888)) }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }
}

@Composable
private fun VaultToolbar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    sortAscending: Boolean,
    onToggleSort: () -> Unit,
    searchFocusRequester: androidx.compose.ui.focus.FocusRequester
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sticky Search card
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Color(0xFFE8E8E8),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text("Search vault...", fontSize = 12.sp, color = Color(0xFF888888))
                        }
                        innerTextField()
                    }
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        IconButton(
            onClick = onToggleSort,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SortByAlpha,
                contentDescription = "Sort",
                tint = if (sortAscending) Color(0xFF0EA5A1) else Color(0xFF888888),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VaultRowItem(
    entry: VaultEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
    onCopyPassword: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit = {},
    onCopyUsername: () -> Unit = {},
    onToggleFavorite: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // Highlight selected or hovered row item as custom card shape with border/color
    val cardBg = if (isSelected) {
        Color(0xFF0EA5A1).copy(alpha = 0.15f)
    } else if (isHovered) {
        Color(0xFF1E1E1E)
    } else {
        Color(0xFF1A1A1A)
    }

    val cardBorder = if (isSelected) {
        Color(0xFF0EA5A1)
    } else {
        Color(0xFF242424)
    }

    com.vaultpass.desktop.ui.components.PointerContextMenu(
        items = { closeMenu ->
            DropdownMenuItem(
                text = { Text("Open Details") },
                onClick = { closeMenu(); onClick() },
                leadingIcon = { Icon(Icons.Default.OpenInNew, null) }
            )
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { closeMenu(); onEdit() },
                leadingIcon = { Icon(Icons.Default.Edit, null) }
            )
            DropdownMenuItem(
                text = { Text("Copy Username") },
                onClick = { closeMenu(); onCopyUsername() },
                leadingIcon = { Icon(Icons.Default.Person, null) }
            )
            DropdownMenuItem(
                text = { Text("Copy Password") },
                onClick = { closeMenu(); onCopyPassword() },
                leadingIcon = { Icon(Icons.Default.ContentCopy, null) }
            )
            DropdownMenuItem(
                text = { Text(if (entry.isFavorite) "Unfavorite" else "Favorite") },
                onClick = { closeMenu(); onToggleFavorite() },
                leadingIcon = { Icon(if (entry.isFavorite) Icons.Default.StarBorder else Icons.Default.Star, null) }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { closeMenu(); onDelete() },
                leadingIcon = { Icon(Icons.Default.Delete, null) }
            )
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = onClick,
                    onDoubleClick = onDoubleClick
                )
                .hoverable(interactionSource = interactionSource)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
            val displayInitial = displayTitle.take(1).uppercase()
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0EA5A1).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayInitial,
                    color = Color(0xFF0EA5A1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(displayTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.username.isNotBlank()) {
                    Text(entry.username, fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            
            if (isHovered || isSelected) {
                IconButton(onClick = onCopyPassword, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = Color(0xFF0EA5A1))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = Color(0xFF888888))
                }
            }
        }
    }
}

@Composable
private fun VaultDetailPanel(
    entry: VaultEntry,
    confirmBeforeDelete: Boolean,
    hidePasswordsByDefault: Boolean,
    onEdit: (VaultEntry) -> Unit,
    onDelete: (VaultEntry) -> Unit,
    onCopyPassword: (String) -> Unit,
    onCopyUsername: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var isPasswordVisible by remember(entry.id) { mutableStateOf(!hidePasswordsByDefault) }
    var menuExpanded by remember(entry.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember(entry.id) { mutableStateOf(false) }

    val displayTitle = if (entry.title.isBlank()) "Untitled" else entry.title
    val displayInitial = displayTitle.take(1).uppercase()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Details Header Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF242424), RoundedCornerShape(12.dp))
                .background(Color(0xFF1A1A1A))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF0EA5A1).copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(displayInitial, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5A1), fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(displayTitle, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Login Item", fontSize = 11.sp, color = Color(0xFF888888))
                }
                
                // Edit Button (Teal Accent #0ea5a1, color Black)
                Button(
                    onClick = { onEdit(entry) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0EA5A1),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menuExpanded = false
                                if (confirmBeforeDelete) showDeleteConfirm = true else onDelete(entry)
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Username Card
        DetailField(label = "Username", value = entry.username, canCopy = true, onCopy = { onCopyUsername(entry.username) })
        Spacer(modifier = Modifier.height(12.dp))

        // Password Card
        DetailField(
            label = "Password",
            value = entry.secret,
            isPassword = true,
            isPasswordVisible = isPasswordVisible,
            onToggleVisibility = { isPasswordVisible = !isPasswordVisible },
            canCopy = true,
            onCopy = { onCopyPassword(entry.secret) }
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Website Card
        DetailField(
            label = "Website",
            value = entry.url,
            canCopy = true,
            onCopy = {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(entry.url))
            },
            icon = Icons.Default.OpenInNew,
            onIconClick = {
                try {
                    if (entry.url.isNotBlank() && java.awt.Desktop.isDesktopSupported()) {
                        val uri = if (!entry.url.startsWith("http")) java.net.URI("https://${entry.url}") else java.net.URI(entry.url)
                        java.awt.Desktop.getDesktop().browse(uri)
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Notes Card
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Notes", fontSize = 12.sp, color = Color(0xFF888888), modifier = Modifier.padding(bottom = 6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1A1A))
                    .padding(12.dp)
            ) {
                Text(
                    text = if (entry.notes.isBlank()) "No notes provided." else entry.notes,
                    fontSize = 14.sp,
                    color = if (entry.notes.isBlank()) Color(0xFF888888) else Color(0xFFE8E8E8)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Entry", color = Color.White) },
            text = { Text("Are you sure you want to delete '$displayTitle'? This action cannot be undone.", color = Color(0xFFE8E8E8)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(entry)
                    showDeleteConfirm = false
                }) { Text("Delete", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = Color(0xFF888888)) }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }
}

@Composable
private fun DetailField(
    label: String,
    value: String,
    isPassword: Boolean = false,
    isPasswordVisible: Boolean = true,
    onToggleVisibility: (() -> Unit)? = null,
    canCopy: Boolean = false,
    onCopy: (() -> Unit)? = null,
    icon: ImageVector? = null,
    onIconClick: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = Color(0xFF888888), modifier = Modifier.padding(bottom = 6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF242424), RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val displayValue = if (isPassword && !isPasswordVisible) "••••••••••••••••" else value

            Text(
                text = displayValue,
                fontSize = 14.sp,
                color = Color(0xFFE8E8E8),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (isPassword && onToggleVisibility != null) {
                IconButton(onClick = onToggleVisibility, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle Visibility",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF888888)
                    )
                }
            }

            if (canCopy && onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp), tint = Color(0xFF0EA5A1))
                }
            }
            if (icon != null) {
                IconButton(onClick = { onIconClick?.invoke() }, modifier = Modifier.size(32.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF0EA5A1))
                }
            }
        }
    }
}
