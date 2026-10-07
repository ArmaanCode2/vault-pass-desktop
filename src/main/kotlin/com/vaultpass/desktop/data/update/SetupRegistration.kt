package com.vaultpass.desktop.data.update

import java.io.File

/**
 * Where VaultPass's Setup program says it installed VaultPass. Only a copy in that folder updates
 * itself; a copy unpacked or built elsewhere would have Setup install over another folder.
 * Replaced in tests.
 */
fun interface SetupRegistration {
    /** The registered install folder, or null when Setup has never installed VaultPass for this account. */
    fun installedAppPath(): String?

    companion object {
        /** True when [registeredPath] names [installDirectory] (case-insensitive, after canonicalising both). */
        fun isSetupInstall(registeredPath: String?, installDirectory: File): Boolean {
            if (registeredPath.isNullOrBlank()) return false
            return canonical(File(registeredPath.trim())).equals(canonical(installDirectory), ignoreCase = true)
        }

        private fun canonical(file: File): String =
            try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
    }
}

/** Inno Setup's uninstall entry for VaultPass in HKCU (the installer runs without admin rights). */
object WindowsSetupRegistration : SetupRegistration {
    private const val UNINSTALL_KEY =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{D37E84B2-9D12-4B7C-A58F-810B8D9A1C4E}_is1"
    private const val APP_PATH_VALUE = "Inno Setup: App Path"

    override fun installedAppPath(): String? {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return null
        return try {
            val root = com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
            if (com.sun.jna.platform.win32.Advapi32Util.registryValueExists(root, UNINSTALL_KEY, APP_PATH_VALUE)) {
                com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(root, UNINSTALL_KEY, APP_PATH_VALUE)
            } else null
        } catch (_: Throwable) {
            null
        }
    }
}
