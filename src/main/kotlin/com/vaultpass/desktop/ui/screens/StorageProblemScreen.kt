package com.vaultpass.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaultpass.desktop.data.platform.VaultCandidate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Shown before anything else when the data folder can't be used. */
@Composable
fun StorageProblemScreen(
    folder: String,
    reason: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    MessageScreen(
        title = "VaultPass can't use its data folder",
        lines = listOf(
            folder,
            reason,
            "Make sure the folder exists and your Windows account can write to it, then select Retry. No vault was created anywhere else."
        ),
        primaryLabel = "Retry",
        onPrimary = onRetry,
        onClose = onClose
    )
}

/**
 * Shown when the data folder is empty but an older version left a vault in another folder.
 * [found] is the most recently changed vault; [others] are the rest. Start a new vault is offered
 * only when [resolvedHasDatabase] is false.
 */
@Composable
fun VaultFoundElsewhereScreen(
    found: VaultCandidate,
    others: List<VaultCandidate>,
    resolved: String,
    resolvedHasDatabase: Boolean,
    errorMessage: String?,
    onCopy: () -> Unit,
    onStartNew: () -> Unit,
    onClose: () -> Unit
) {
    val foundIn = found.folder.absolutePath
    // Start a new vault needs a second click; the first says what it means.
    var confirmStartNew by remember(foundIn) { mutableStateOf(false) }
    MessageScreen(
        title = "Vault found in another folder",
        lines = vaultFoundElsewhereLines(found, others, resolved, resolvedHasDatabase),
        errorLines = listOfNotNull(
            errorMessage,
            if (confirmStartNew) "Your vault in $foundIn won't be used. Select Start a new vault again to continue." else null
        ),
        primaryLabel = "Copy it here",
        onPrimary = onCopy,
        secondaryLabel = if (resolvedHasDatabase) null else "Start a new vault",
        onSecondary = {
            if (confirmStartNew) onStartNew() else confirmStartNew = true
        },
        onClose = onClose
    )
}

/** The text of [VaultFoundElsewhereScreen]. */
internal fun vaultFoundElsewhereLines(
    found: VaultCandidate,
    others: List<VaultCandidate>,
    resolved: String,
    resolvedHasDatabase: Boolean,
    formatTime: (Long) -> String = ::lastChangedText
): List<String> {
    val foundIn = found.folder.absolutePath
    return listOfNotNull(
        "A VaultPass vault was found in $foundIn (last changed ${formatTime(found.lastChanged)}), not in $resolved.",
        if (others.isEmpty()) null
        else "Other vaults were also found: " +
            others.joinToString("; ") { "${it.folder.absolutePath} (last changed ${formatTime(it.lastChanged)})" } + ".",
        "Copy it here copies the vault files into $resolved and leaves the original where it is." +
            if (resolvedHasDatabase) {
                " The vault database already in $resolved isn't overwritten; it is kept as " +
                    "vaultpass.db.replaced-<date and time>."
            } else "",
        "The original stays in $foundIn. Delete it yourself once you've checked the copy, " +
            "especially if other Windows accounts can open that folder.",
        if (resolvedHasDatabase) "Start a new vault isn't possible here: $resolved already has a vault database."
        else "Start a new vault creates an empty vault in $resolved. The vault in $foundIn is left untouched."
    )
}

/** [millis] as a date and time in this PC's time zone and format. */
internal fun lastChangedText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()))

/** Shown while the data folder is checked or a vault is copied into it, off the UI thread. */
@Composable
fun StorageBusyScreen(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Replaces the main window content when the vault can't be opened. */
@Composable
fun FatalErrorScreen(
    reason: String,
    updateStatus: String?,
    onCheckForUpdates: (() -> Unit)?,
    onClose: () -> Unit,
    updateActionLabel: String = "Check for updates"
) {
    MessageScreen(
        title = "VaultPass can't open your vault",
        lines = listOfNotNull(reason, updateStatus),
        primaryLabel = if (onCheckForUpdates != null) updateActionLabel else null,
        onPrimary = onCheckForUpdates ?: {},
        onClose = onClose
    )
}

@Composable
private fun MessageScreen(
    title: String,
    lines: List<String>,
    primaryLabel: String?,
    onPrimary: () -> Unit,
    onClose: () -> Unit,
    errorLines: List<String> = emptyList(),
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            SelectionContainer {
                Column {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    errorLines.forEach { errorLine ->
                        Text(
                            text = errorLine,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
            ) {
                OutlinedButton(onClick = onClose, shape = RoundedCornerShape(8.dp)) {
                    Text("Close")
                }
                if (secondaryLabel != null) {
                    OutlinedButton(onClick = onSecondary, shape = RoundedCornerShape(8.dp)) {
                        Text(secondaryLabel)
                    }
                }
                if (primaryLabel != null) {
                    Button(onClick = onPrimary, shape = RoundedCornerShape(8.dp)) {
                        Text(primaryLabel)
                    }
                }
            }
        }
    }
}
