package com.vaultpass.desktop.ui.screens

import com.vaultpass.desktop.data.platform.VaultCandidate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** What the "Vault found in another folder" window says. */
class VaultFoundElsewhereTextTest {

    private val found = VaultCandidate(File("D:\\Old\\data"), 2_000L)
    private val resolved = "C:\\Users\\me\\AppData\\Local\\Programs\\VaultPass\\data"
    private val time: (Long) -> String = { "time-$it" }

    @Test
    fun emptyTargetOffersCopyAndStartNew() {
        val lines = vaultFoundElsewhereLines(found, emptyList(), resolved, resolvedHasDatabase = false, formatTime = time)

        assertEquals(
            listOf(
                "A VaultPass vault was found in ${found.folder.absolutePath} (last changed time-2000), not in $resolved.",
                "Copy it here copies the vault files into $resolved and leaves the original where it is.",
                "The original stays in ${found.folder.absolutePath}. Delete it yourself once you've checked the copy, " +
                    "especially if other Windows accounts can open that folder.",
                "Start a new vault creates an empty vault in $resolved. The vault in ${found.folder.absolutePath} is left untouched."
            ),
            lines
        )
    }

    @Test
    fun targetWithADatabaseSaysItIsKeptAndThatStartNewIsNotPossible() {
        val others = listOf(VaultCandidate(File("C:\\VaultPass\\data"), 1_000L), VaultCandidate(File("C:\\Temp\\.vaultpass"), 500L))

        val lines = vaultFoundElsewhereLines(found, others, resolved, resolvedHasDatabase = true, formatTime = time)

        assertEquals(
            "Other vaults were also found: ${File("C:\\VaultPass\\data").absolutePath} (last changed time-1000); " +
                "${File("C:\\Temp\\.vaultpass").absolutePath} (last changed time-500).",
            lines[1]
        )
        assertEquals(
            "Copy it here copies the vault files into $resolved and leaves the original where it is. " +
                "The vault database already in $resolved isn't overwritten; it is kept as vaultpass.db.replaced-<date and time>.",
            lines[2]
        )
        assertEquals("Start a new vault isn't possible here: $resolved already has a vault database.", lines.last())
    }

    @Test
    fun lastChangedUsesALocalDateAndTime() {
        val text = lastChangedText(1_700_000_000_000L)
        assertTrue(text.isNotBlank())
        assertTrue(text.any { it.isDigit() })
    }
}
