package com.vaultpass.desktop.data.platform

import com.vaultpass.desktop.domain.platform.FileDialogProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AwtFileDialogProvider : FileDialogProvider {

    override suspend fun showOpenFileDialog(title: String, allowedExtensions: List<String>): File? =
        withContext(Dispatchers.IO) {
            suspendCoroutine { cont ->
                SwingUtilities.invokeLater {
                    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
                    if (allowedExtensions.isNotEmpty()) {
                        dialog.file = allowedExtensions.joinToString(";") { "*.$it" }
                    }
                    dialog.isVisible = true
                    val file = dialog.file
                    val dir = dialog.directory
                    if (file != null && dir != null) {
                        cont.resume(File(dir, file))
                    } else {
                        cont.resume(null)
                    }
                }
            }
        }

    override suspend fun showSaveFileDialog(title: String, defaultFileName: String): File? =
        withContext(Dispatchers.IO) {
            suspendCoroutine { cont ->
                SwingUtilities.invokeLater {
                    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
                    dialog.file = defaultFileName
                    dialog.isVisible = true
                    val file = dialog.file
                    val dir = dialog.directory
                    if (file != null && dir != null) {
                        cont.resume(File(dir, file))
                    } else {
                        cont.resume(null)
                    }
                }
            }
        }
}
