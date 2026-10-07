package com.vaultpass.desktop.domain.models

import kotlinx.serialization.Serializable

enum class BackupMode {
    ASK_EVERY_TIME,
    USE_DEFAULT_FOLDER
}

@Serializable
data class AppSettings(
    val requireMasterPasswordOnStartup: Boolean = true,
    val lockWhenMinimized: Boolean = false,
    val autoLockTimeoutMinutes: Int = 15,
    val clearClipboardTimeoutSeconds: Int = 30,
    val hidePasswordsByDefault: Boolean = true,
    val baseTheme: String = "Dark",
    val accentColor: String = "Blue",
    val backupMode: BackupMode = BackupMode.USE_DEFAULT_FOLDER,
    val backupFolder: String = System.getProperty("user.home").replace("\\\\", "/") + "/Documents/VaultPass/Backups",
    val lastUsedFolder: String? = null,
    val latestBackupTimestamp: Long = 0L,
    val latestBackupFilename: String? = null,
    val sidebarWidth: Float = 260f,
    val vaultListWidth: Float = 320f,
    val settingsCategoriesWidth: Float = 250f,
    val density: String = "Comfortable",
    val uiScale: String = "100%",
    val animationsEnabled: Boolean = true,
    val defaultStartupPage: String = "Dashboard",
    val confirmBeforeDelete: Boolean = true,
    val defaultSortOrder: String = "Alphabetical A-Z",
    val defaultViewRememberLastSelected: Boolean = true,
    val doubleClickBehaviour: String = "View Details",
    val lastSelectedEntryId: String? = null,
    val clearSearchOnLock: Boolean = true,
    val hideRecentActivity: Boolean = false,
    val showClipboardHistoryWarning: Boolean = true,
    val recycleBinRetentionDays: Int = 30,
    val lockOnWorkstationLock: Boolean = true,
    /** Only read from settings files written before [checkForUpdatesOnOpen] existed. */
    val autoCheckForUpdates: Boolean = true,
    val checkForUpdatesOnOpen: Boolean = false
)
