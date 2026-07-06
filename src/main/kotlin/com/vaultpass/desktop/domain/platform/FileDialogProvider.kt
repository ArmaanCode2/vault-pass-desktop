package com.vaultpass.desktop.domain.platform

import java.io.File

/**
 * A generic contract for invoking the OS's native file picker dialogues.
 * Used during Import/Export operations without coupling the domain to AWT/Swing or Mac native APIs.
 */
interface FileDialogProvider {
    
    /**
     * Prompts the user to select a file for opening (e.g., during Vault Import).
     *
     * @param title The title of the dialogue window.
     * @param allowedExtensions An optional list of allowed file extensions (e.g. listOf("vpex", "json")).
     * @return The selected File, or null if the user canceled the dialogue.
     */
    suspend fun showOpenFileDialog(title: String, allowedExtensions: List<String> = emptyList()): File?

    /**
     * Prompts the user to select a destination for saving a file (e.g., during Vault Export).
     *
     * @param title The title of the dialogue window.
     * @param defaultFileName The suggested name for the file.
     * @return The selected destination File, or null if the user canceled.
     */
    suspend fun showSaveFileDialog(title: String, defaultFileName: String): File?
}
