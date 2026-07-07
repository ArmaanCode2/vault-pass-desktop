package com.vaultpass.desktop.domain.models

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val requireMasterPasswordOnStartup: Boolean = true,
    val lockWhenMinimized: Boolean = false,
    val autoLockTimeoutMinutes: Int = 15,
    val clearClipboardTimeoutSeconds: Int = 30,
    val hidePasswordsByDefault: Boolean = true,
    val theme: String = "System Default",
    val developerMode: Boolean = false
)
