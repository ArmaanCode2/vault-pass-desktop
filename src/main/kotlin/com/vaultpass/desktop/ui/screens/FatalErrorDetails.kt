package com.vaultpass.desktop.ui.screens

import com.vaultpass.desktop.domain.ErrorLog
import java.awt.Desktop
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.temporal.ChronoUnit

/** How much of crash.log "Copy details" takes: its last lines, at most this many characters. */
internal const val CRASH_LOG_TAIL_LINES = 40
internal const val CRASH_LOG_TAIL_CHARS = 4_000

/**
 * D23: what "Copy details" on the fatal error screen puts on the clipboard for a bug report: the
 * reason, versions, and the data folder's file names, sizes and dates. No file's contents except
 * the end of crash.log; metadata.json (the vault's key file) is only listed.
 */
internal fun fatalErrorDetails(
    reason: String,
    appVersion: String,
    dataFolder: File?,
    osName: String = System.getProperty("os.name").orEmpty(),
    osVersion: String = System.getProperty("os.version").orEmpty(),
    javaVersion: String = System.getProperty("java.version").orEmpty()
): String = buildString {
    appendLine("VaultPass can't open the vault")
    appendLine("Reason: $reason")
    appendLine("VaultPass: $appVersion")
    appendLine("System: $osName $osVersion, Java $javaVersion")
    if (dataFolder == null) {
        appendLine("Data folder: not chosen yet")
        return@buildString
    }
    appendLine("Data folder: ${dataFolder.absolutePath}")
    val files = try {
        dataFolder.listFiles()?.sortedBy { it.name.lowercase() }
    } catch (e: SecurityException) {
        null
    }
    when {
        files == null -> appendLine("  (can't be listed)")
        files.isEmpty() -> appendLine("  (empty)")
        else -> for (file in files) {
            val modified = Instant.ofEpochMilli(file.lastModified()).truncatedTo(ChronoUnit.SECONDS)
            if (file.isDirectory) appendLine("  ${file.name}/  folder  $modified")
            else appendLine("  ${file.name}  ${file.length()} bytes  $modified")
        }
    }
    val crashLog = File(dataFolder, "crash.log")
    if (crashLog.isFile) {
        appendLine()
        appendLine("End of crash.log:")
        append(
            try {
                crashLogTail(crashLog)
            } catch (e: Exception) {
                "(can't be read)"
            }
        )
    }
}.trimEnd()

/** The last [CRASH_LOG_TAIL_LINES] lines of [file], read from its end so a large log isn't loaded whole. */
internal fun crashLogTail(file: File): String {
    val bytes = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        // Four bytes per character at most in UTF-8.
        val start = maxOf(0L, length - CRASH_LOG_TAIL_CHARS * 4L)
        raf.seek(start)
        ByteArray((length - start).toInt()).also { raf.readFully(it) }
    }
    return String(bytes, Charsets.UTF_8).trimEnd().lines().takeLast(CRASH_LOG_TAIL_LINES).joinToString("\n").takeLast(CRASH_LOG_TAIL_CHARS)
}

/** Opens [folder] in Explorer; a failure is only logged. Call it off the UI thread. */
internal fun openFolderInExplorer(folder: File) {
    try {
        if (folder.isDirectory && Desktop.isDesktopSupported()) Desktop.getDesktop().open(folder)
    } catch (e: Exception) {
        ErrorLog.warn("Opening the data folder", e)
    }
}
