package com.vaultpass.desktop.data.platform

import com.sun.jna.platform.win32.Shell32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object WindowsFirewallHelper {
    // Rules are limited to Private networks and to this app's executable.
    private const val RULE_TCP = "VaultPass LAN Sync (Private)"
    private const val RULE_UDP = "VaultPass LAN Discovery (Private)"

    // Older versions added these rules for every network profile (Public included).
    private const val LEGACY_RULE_TCP = "VaultPass LAN Sync"
    private const val LEGACY_RULE_UDP = "VaultPass LAN Discovery"

    suspend fun isFirewallRuleConfigured(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                "netsh", "advfirewall", "firewall", "show", "rule", "name=$RULE_TCP"
            ).redirectErrorStream(true).start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val exitCode = process.waitFor()
            exitCode == 0 && output.contains(RULE_TCP, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the elevated command: removes the legacy all-profile rules and any earlier copy of
     * the current rules (so repeated grants don't pile up duplicates), then adds the current
     * rules for Private networks only, bound to [programPath] when it is known.
     */
    internal fun buildFirewallCommands(programPath: String?): String {
        val program = programPath?.takeIf { it.isNotBlank() }?.let { " program=\"$it\"" } ?: ""
        return listOf(
            "netsh advfirewall firewall delete rule name=\"$LEGACY_RULE_TCP\"",
            "netsh advfirewall firewall delete rule name=\"$LEGACY_RULE_UDP\"",
            "netsh advfirewall firewall delete rule name=\"$RULE_TCP\"",
            "netsh advfirewall firewall delete rule name=\"$RULE_UDP\"",
            "netsh advfirewall firewall add rule name=\"$RULE_TCP\" dir=in action=allow protocol=TCP localport=53853 profile=private$program",
            "netsh advfirewall firewall add rule name=\"$RULE_UDP\" dir=in action=allow protocol=UDP localport=53852 profile=private$program"
        ).joinToString(" & ")
    }

    suspend fun requestFirewallPermission(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val programPath = try {
                ProcessHandle.current().info().command().orElse(null)
            } catch (_: Exception) {
                null
            }
            val commands = buildFirewallCommands(programPath)
            // ShellExecute with "runas" triggers the genuine Windows UAC prompt
            val result = Shell32.INSTANCE.ShellExecute(
                null,
                "runas",
                "cmd.exe",
                "/c $commands",
                null,
                0 // SW_HIDE
            )
            // Win32 convention: HINSTANCE > 32 means success
            val hInstance = try {
                result?.toPointer()?.let { com.sun.jna.Pointer.nativeValue(it) } ?: 0L
            } catch (_: Throwable) {
                try {
                    result?.toLong() ?: 0L
                } catch (_: Throwable) {
                    0L
                }
            }
            if (hInstance <= 32) {
                return@withContext Result.failure(Exception("UAC elevation cancelled or failed (Code: $hInstance)"))
            }
            // Wait briefly for netsh to finish and verify
            delay(1500L)
            val configured = isFirewallRuleConfigured()
            if (configured) {
                Result.success(true)
            } else {
                Result.failure(Exception("Firewall rule was not applied by Windows."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
