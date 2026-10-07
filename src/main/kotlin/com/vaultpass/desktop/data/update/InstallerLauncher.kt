package com.vaultpass.desktop.data.update

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32
import java.io.File
import kotlin.system.exitProcess

/**
 * Starts a verified installer and ends this process. Injectable so tests never launch anything.
 */
fun interface InstallerLauncher {
    /**
     * Starts [installerFile] with [arguments] (unquoted, one entry per argument) and exits.
     * Returns or throws only when the installer couldn't be started.
     */
    fun launchAndExit(installerFile: File, arguments: List<String>)
}

/** How an update is installed. */
enum class LaunchMode {
    /** Now, reopening VaultPass when Setup has finished. */
    RestartNow,

    /** While VaultPass closes; it isn't reopened. */
    OnExit
}

/** Inno Setup command line for an in-app update. */
object InstallerArguments {
    private val PLAIN_VALUE = Regex("^[A-Za-z0-9]+$")

    /**
     * Unquoted arguments, as ProcessBuilder takes them. No /SUPPRESSMSGBOXES: a file in use shows
     * Setup's Retry/Cancel instead of silently aborting.
     */
    fun build(mode: LaunchMode, installDirectory: File, logFile: File): List<String> {
        val arguments = mutableListOf(
            "/SILENT",
            "/NORESTART",
            "/LOG=${logFile.absolutePath}",
            "/DIR=${installDirectory.absolutePath}"
        )
        if (mode == LaunchMode.RestartNow) arguments += "/RELAUNCH=1"
        return arguments
    }

    /**
     * Joins [arguments] into one ShellExecute parameter string. A `/NAME=value` whose value isn't
     * plain letters and digits is written `/NAME="value"`, which Inno Setup reads with spaces intact.
     */
    fun toCommandLine(arguments: List<String>): String = arguments.joinToString(" ") { argument ->
        require('"' !in argument) { "Installer arguments can't contain quotes: $argument" }
        val separator = argument.indexOf('=')
        if (argument.startsWith("/") && separator > 0) {
            val value = argument.substring(separator + 1)
            if (PLAIN_VALUE.matches(value)) argument else argument.substring(0, separator + 1) + "\"" + value + "\""
        } else {
            require(argument.none { it.isWhitespace() }) { "Installer switches can't contain spaces: $argument" }
            argument
        }
    }
}

/**
 * Launches the installer through ShellExecute (falling back to ProcessBuilder) and exits
 * so Inno Setup can replace the application files.
 */
object WindowsInstallerLauncher : InstallerLauncher {
    override fun launchAndExit(installerFile: File, arguments: List<String>) {
        val parameters = InstallerArguments.toCommandLine(arguments)
        // ShellExecute may use COM; this thread is VaultPass's own, so it can enter a single-threaded apartment.
        val comInitialized = try {
            COMUtils.SUCCEEDED(Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED))
        } catch (_: Throwable) {
            false
        }
        try {
            var launched = false
            // Tier 1: Windows Native ShellExecute via JNA (native UAC handling, no black console window, zero URI issues)
            try {
                val result = Shell32.INSTANCE.ShellExecute(
                    null,
                    "open",
                    installerFile.absolutePath,
                    parameters,
                    installerFile.parentFile?.absolutePath,
                    1 // SW_SHOWNORMAL
                )
                val code = result?.let { Pointer.nativeValue(it.toPointer()) } ?: 0L
                // In Win32 API, a return value > 32 indicates success
                if (code > 32) {
                    launched = true
                }
            } catch (_: Throwable) {
                // Fall through to ProcessBuilder if JNA is unavailable
            }
            // Tier 2: Direct Native ProcessBuilder fallback
            if (!launched) {
                val pb = ProcessBuilder(listOf(installerFile.absolutePath) + arguments)
                installerFile.parentFile?.let { pb.directory(it) }
                pb.start()
            }
            // Allow the operating system 500ms to spawn the installer process before terminating VaultPass
            Thread.sleep(500)
            exitProcess(0)
        } finally {
            // Reached only when the launch failed.
            if (comInitialized) {
                try { Ole32.INSTANCE.CoUninitialize() } catch (_: Throwable) {}
            }
        }
    }
}
