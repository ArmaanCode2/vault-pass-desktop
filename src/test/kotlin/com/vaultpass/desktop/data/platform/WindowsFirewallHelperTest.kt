package com.vaultpass.desktop.data.platform

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WindowsFirewallHelperTest {

    private fun String.occurrences(part: String): Int = split(part).size - 1

    @Test
    fun rulesArePrivateOnlyAndBoundToTheApp() {
        val exe = "C:\\Program Files\\VaultPass\\VaultPass.exe"
        val commands = WindowsFirewallHelper.buildFirewallCommands(exe)

        assertFalse(commands.contains("profile=any"))
        assertFalse(commands.contains("profile=public"))
        assertEquals(2, commands.occurrences("add rule"))
        assertEquals(2, commands.occurrences("profile=private program=\"$exe\""))
        assertTrue(commands.contains("localport=53853"))
        assertTrue(commands.contains("localport=53852"))
    }

    @Test
    fun legacyAllProfileRulesAreRemovedBeforeAdding() {
        val commands = WindowsFirewallHelper.buildFirewallCommands("C:\\VaultPass\\VaultPass.exe")

        assertTrue(commands.contains("delete rule name=\"VaultPass LAN Sync\""))
        assertTrue(commands.contains("delete rule name=\"VaultPass LAN Discovery\""))
        // Deleting first means a repeated grant replaces the rules instead of duplicating them.
        assertTrue(commands.lastIndexOf("delete rule") < commands.indexOf("add rule"))
    }

    @Test
    fun withoutAKnownExecutable_rulesAreStillPrivateOnly() {
        val commands = WindowsFirewallHelper.buildFirewallCommands(null)

        assertFalse(commands.contains("program="))
        assertEquals(2, commands.occurrences("profile=private"))
        assertFalse(commands.contains("profile=any"))
    }
}
