package com.vaultpass.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vaultpass.desktop.data.update.UpdateState
import com.vaultpass.desktop.ui.viewmodels.SettingsViewModel
import com.vaultpass.desktop.ui.viewmodels.UpdateBanner
import com.vaultpass.desktop.ui.viewmodels.UpdateBlockers
import com.vaultpass.desktop.ui.viewmodels.updateFailedText

private val Accent = Color(0xFF0EA5A1)
private val Success = Color(0xFF10B981)
private val Danger = Color(0xFFFF6B6B)
private val Muted = Color(0xFFAAAAAA)
private val DialogSurface = Color(0xFF1E1E1E)

/** Banner at the top of the main area, after unlock. Shows nothing when there is nothing to say. */
@Composable
fun UpdateBannerHost(settingsViewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val banner by settingsViewModel.updateBanner.collectAsState()
    val blockedReason by settingsViewModel.updateBlockedReason.collectAsState()
    val current = banner ?: return
    when (current) {
        is UpdateBanner.Available -> BannerSurface(
            modifier = modifier,
            color = Accent,
            icon = Icons.Default.SystemUpdate,
            title = "Update to latest version ${current.update.version}"
        ) {
            TextButton(onClick = { settingsViewModel.dismissUpdateBanner() }) {
                Text("Not now", color = Muted, fontSize = 12.sp)
            }
            PrimaryButton("Update", Accent) { settingsViewModel.downloadUpdate(current.update) }
        }
        is UpdateBanner.InstallOnClose -> BannerSurface(
            modifier = modifier,
            color = Success,
            icon = Icons.Default.SystemUpdate,
            title = "Update ${current.ready.version} will install when you close VaultPass",
            note = blockedReason
        ) {
            PrimaryButton("Restart now", Success, enabled = blockedReason == null) { settingsViewModel.restartNow() }
        }
        is UpdateBanner.Failed -> BannerSurface(
            modifier = modifier,
            color = Danger,
            icon = Icons.Default.Warning,
            title = updateFailedText(current.failed)
        ) {
            TextButton(onClick = { settingsViewModel.dismissUpdateFailed() }) {
                Text("Dismiss", color = Muted, fontSize = 12.sp)
            }
            // Without a Setup log there are no details to show.
            if (current.failed.setupRan) {
                TextButton(onClick = { settingsViewModel.showUpdateDetails(current.failed) }) {
                    Text("Show details", color = Color.White, fontSize = 12.sp)
                }
            }
            PrimaryButton("Try again", Accent) { settingsViewModel.tryUpdateAgain() }
        }
    }
}

/** The installer couldn't restrict the install folder to this account; shown after unlock until dismissed. */
@Composable
fun FolderPermissionsBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    BannerSurface(
        modifier = modifier,
        color = Danger,
        icon = Icons.Default.Warning,
        title = message
    ) {
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BannerSurface(
    modifier: Modifier,
    color: Color,
    icon: ImageVector,
    title: String,
    note: String? = null,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (note != null) {
                    Text(note, color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The download / check / ready / restart dialog, over whatever the main window shows. It opens when
 * the user starts an update and closes on Cancel, Later or Close; it can't be dismissed by clicking outside.
 */
@Composable
fun UpdateDialogHost(settingsViewModel: SettingsViewModel) {
    val isOpen by settingsViewModel.isUpdateDialogOpen.collectAsState()
    if (!isOpen) return
    val state by settingsViewModel.updateState.collectAsState()
    val downloadingVersion by settingsViewModel.downloadingVersion.collectAsState()
    val countdown by settingsViewModel.restartCountdown.collectAsState()
    val blockedReason by settingsViewModel.updateBlockedReason.collectAsState()

    when (val current = state) {
        UpdateState.Checking -> UpdateDialogFrame(title = "Checking for updates…", showProgress = true) {
            TextButton(onClick = { settingsViewModel.closeUpdateDialog() }) { Text("Close", color = Muted) }
        }
        is UpdateState.Downloading -> UpdateDialogFrame(
            title = if (downloadingVersion != null) "Downloading update $downloadingVersion" else "Downloading update",
            progress = current.progress,
            message = downloadedText(current.bytesDownloaded, current.totalBytes)
        ) {
            TextButton(onClick = { settingsViewModel.cancelDownload() }) { Text("Cancel", color = Danger) }
        }
        is UpdateState.Verifying -> UpdateDialogFrame(title = "Checking the download…", showProgress = true) {}
        is UpdateState.ReadyToInstall -> {
            val seconds = countdown
            when {
                seconds == null -> UpdateDialogFrame(
                    title = "Update ${current.version} is ready",
                    message = "VaultPass will close, install the update and open again. Your vault stays on this PC.",
                    note = UpdateBlockers.dialogText(blockedReason)
                ) {
                    OutlinedButton(
                        onClick = { settingsViewModel.installLater() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFF333333)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Later", fontSize = 12.sp)
                    }
                    PrimaryButton("Restart now", Accent, enabled = blockedReason == null) { settingsViewModel.restartNow() }
                }
                seconds > 0 -> UpdateDialogFrame(title = "Restarting VaultPass in $seconds…") {
                    TextButton(onClick = { settingsViewModel.cancelRestart() }) { Text("Cancel", color = Danger) }
                }
                else -> UpdateDialogFrame(title = "Restarting VaultPass…", showProgress = true) {}
            }
        }
        is UpdateState.Error -> UpdateDialogFrame(
            title = "Couldn't update VaultPass",
            message = current.message,
            messageColor = Color(0xFFFF8888)
        ) {
            TextButton(onClick = { settingsViewModel.closeUpdateDialog() }) { Text("Close", color = Muted) }
            PrimaryButton("Try again", Accent) { settingsViewModel.tryUpdateAgain() }
        }
        is UpdateState.UpToDate -> UpdateDialogFrame(
            title = "VaultPass is up to date",
            message = "No newer version is available yet."
        ) {
            TextButton(onClick = { settingsViewModel.closeUpdateDialog() }) { Text("Close", color = Muted) }
        }
        // Between steps (or after Cancel) there is nothing to show.
        UpdateState.Idle, is UpdateState.Available, is UpdateState.UpdateFailed -> Unit
    }
}

/** Shown while VaultPass closes and starts an update chosen with Later. It has no buttons. */
@Composable
fun StartingUpdateDialog() {
    UpdateDialogFrame(title = "Starting the update…", showProgress = true) {}
}

@Composable
private fun UpdateDialogFrame(
    title: String,
    message: String? = null,
    messageColor: Color = Color(0xFFCCCCCC),
    note: String? = null,
    progress: Float? = null,
    showProgress: Boolean = false,
    actions: @Composable RowScope.() -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.width(420.dp).wrapContentHeight().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DialogSurface),
            border = BorderStroke(1.dp, Color(0xFF2C2C2C))
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                if (progress != null || showProgress) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    if (progress != null) {
                        LinearProgressIndicator(progress = progress, modifier = barModifier, color = Accent, trackColor = Color(0xFF282828))
                    } else {
                        LinearProgressIndicator(modifier = barModifier, color = Accent, trackColor = Color(0xFF282828))
                    }
                }
                if (message != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = message, fontSize = 13.sp, color = messageColor, textAlign = TextAlign.Center)
                }
                if (note != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = note, fontSize = 12.sp, color = Danger, textAlign = TextAlign.Center)
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
        }
    }
}

/**
 * Settings > About: the same states and buttons as the banner and the dialog. Checking and the
 * "up to date" and error results are shown here too, since this is where a manual check starts.
 */
@Composable
fun UpdateAboutStatus(settingsViewModel: SettingsViewModel, checkedAtText: (Long) -> String) {
    val state by settingsViewModel.updateState.collectAsState()
    val installOnExit by settingsViewModel.installOnExit.collectAsState()
    val blockedReason by settingsViewModel.updateBlockedReason.collectAsState()
    val downloadingVersion by settingsViewModel.downloadingVersion.collectAsState()

    when (val current = state) {
        is UpdateState.UpToDate -> AboutPanel(Success, Icons.Default.CheckCircle, "You are using the latest version", "Checked at ${checkedAtText(current.checkedAt)}") {}
        is UpdateState.Available -> AboutPanel(
            color = Accent,
            icon = Icons.Default.SystemUpdate,
            title = "Update to latest version ${current.version}",
            subtitle = if (current.fileSize > 0) "Windows Setup Package • ${megabytes(current.fileSize)}" else "Windows Setup Package",
            details = current.releaseNotes.takeIf { it.isNotBlank() }
        ) {
            PrimaryButton("Update", Accent) { settingsViewModel.downloadUpdate(current) }
        }
        is UpdateState.Downloading -> AboutPanel(
            color = Accent,
            icon = Icons.Default.SystemUpdate,
            title = if (downloadingVersion != null) "Downloading update $downloadingVersion" else "Downloading update",
            subtitle = downloadedText(current.bytesDownloaded, current.totalBytes),
            progress = current.progress
        ) {
            TextButton(onClick = { settingsViewModel.cancelDownload() }) { Text("Cancel", color = Danger, fontSize = 12.sp) }
        }
        is UpdateState.Verifying -> AboutPanel(Accent, Icons.Default.SystemUpdate, "Checking the download…", null, showProgress = true) {}
        is UpdateState.ReadyToInstall -> if (installOnExit) {
            AboutPanel(
                color = Success,
                icon = Icons.Default.SystemUpdate,
                title = "Update ${current.version} will install when you close VaultPass",
                subtitle = blockedReason
            ) {
                PrimaryButton("Restart now", Success, enabled = blockedReason == null) { settingsViewModel.restartNow() }
            }
        } else {
            AboutPanel(
                color = Success,
                icon = Icons.Default.SystemUpdate,
                title = "Update ${current.version} is ready",
                subtitle = blockedReason ?: "VaultPass will close, install the update and open again. Your vault stays on this PC."
            ) {
                TextButton(onClick = { settingsViewModel.installLater() }) { Text("Later", color = Color.White, fontSize = 12.sp) }
                PrimaryButton("Restart now", Success, enabled = blockedReason == null) { settingsViewModel.restartNow() }
            }
        }
        is UpdateState.UpdateFailed -> AboutPanel(Danger, Icons.Default.Warning, updateFailedText(current), null) {
            TextButton(onClick = { settingsViewModel.dismissUpdateFailed() }) { Text("Dismiss", color = Muted, fontSize = 12.sp) }
            if (current.setupRan) {
                TextButton(onClick = { settingsViewModel.showUpdateDetails(current) }) { Text("Show details", color = Color.White, fontSize = 12.sp) }
            }
            PrimaryButton("Try again", Accent) { settingsViewModel.tryUpdateAgain() }
        }
        is UpdateState.Error -> AboutPanel(Danger, Icons.Default.Warning, current.message, null, titleColor = Color(0xFFFF8888)) {
            TextButton(onClick = { settingsViewModel.checkForUpdates() }) { Text("Retry", color = Color.White, fontSize = 12.sp) }
        }
        UpdateState.Idle, UpdateState.Checking -> Unit
    }
}

@Composable
private fun AboutPanel(
    color: Color,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    titleColor: Color = Color.White,
    details: String? = null,
    progress: Float? = null,
    showProgress: Boolean = false,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(subtitle, color = Muted, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
            if (progress != null || showProgress) {
                val barModifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                if (progress != null) {
                    LinearProgressIndicator(progress = progress, modifier = barModifier, color = color, trackColor = Color(0xFF282828))
                } else {
                    LinearProgressIndicator(modifier = barModifier, color = color, trackColor = Color(0xFF282828))
                }
            }
            if (details != null) {
                Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF141414), shape = RoundedCornerShape(6.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Release Notes:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(details, color = Color(0xFFBBBBBB), fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun megabytes(bytes: Long): String = String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))

private fun downloadedText(bytesDownloaded: Long, totalBytes: Long): String =
    if (totalBytes > 0) "${megabytes(bytesDownloaded)} of ${megabytes(totalBytes)}" else megabytes(bytesDownloaded)
