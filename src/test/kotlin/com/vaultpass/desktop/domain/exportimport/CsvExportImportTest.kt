package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CsvExportImportTest {

    @Test
    fun testCsvExportAndImportRoundTrip() {
        val entry = VaultEntry(
            id = "1",
            title = "ProtonMail",
            username = "user@proton.me",
            secret = "SuperSecret123!",
            url = "https://mail.proton.me",
            notes = "Encrypted email account",
            category = "Personal",
            tags = listOf("email", "privacy"),
            isFavorite = true,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val csvString = CsvExporter.format(listOf(entry))
        assertTrue(csvString.startsWith("Title,Username,Password,URL,Notes,Category,Tags,Favorite"))

        val result = CsvImporter.parse(csvString)
        assertEquals(1, result.validEntries.size)
        assertEquals(0, result.invalidCount)
        assertEquals(0, result.duplicateCount)

        val imported = result.validEntries[0]
        assertEquals("ProtonMail", imported.title)
        assertEquals("user@proton.me", imported.username)
        assertEquals("SuperSecret123!", imported.secret)
        assertEquals("https://mail.proton.me", imported.url)
        assertEquals("Encrypted email account", imported.notes)
        assertEquals("Personal", imported.category)
        assertEquals(listOf("email", "privacy"), imported.tags)
        assertTrue(imported.isFavorite)
    }

    @Test
    fun testCsvEscapingQuotesCommasAndNewlines() {
        val entry = VaultEntry(
            id = "2",
            title = "Acme, Inc.",
            username = "john\"doe\"",
            secret = "p@ss,w\"ord",
            url = "https://acme.com",
            notes = "Line 1\nLine 2, with comma and \"quotes\"",
            category = "Work, HQ",
            tags = listOf("finance", "tax"),
            isFavorite = false,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val csvString = CsvExporter.format(listOf(entry))
        val result = CsvImporter.parse(csvString)

        assertEquals(1, result.validEntries.size)
        val imported = result.validEntries[0]
        assertEquals("Acme, Inc.", imported.title)
        assertEquals("john\"doe\"", imported.username)
        assertEquals("p@ss,w\"ord", imported.secret)
        assertEquals("Line 1\nLine 2, with comma and \"quotes\"", imported.notes)
        assertEquals("Work, HQ", imported.category)
        assertFalse(imported.isFavorite)
    }

    @Test
    fun testCsvFormulaInjectionSanitization() {
        val maliciousEntry = VaultEntry(
            id = "3",
            title = "=cmd|' /C calc'!A0",
            username = "+123456789",
            secret = "-passwordSecret",
            url = "https://example.com",
            notes = "@SUM(1+1)",
            category = "Security",
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val exportedCsv = CsvExporter.format(listOf(maliciousEntry))
        // Verify exported cells starting with formula triggers are prefixed with single quote
        assertTrue(exportedCsv.contains("'=cmd|' /C calc'!A0") || exportedCsv.contains("\"'=cmd|' /C calc'!A0\""))
        assertTrue(exportedCsv.contains("'+123456789"))
        assertTrue(exportedCsv.contains("'-passwordSecret"))
        assertTrue(exportedCsv.contains("'@SUM(1+1)"))

        // Verify importing cleanly strips leading quote
        val result = CsvImporter.parse(exportedCsv)
        assertEquals(1, result.validEntries.size)
        val imported = result.validEntries[0]
        assertEquals("=cmd|' /C calc'!A0", imported.title)
        assertEquals("+123456789", imported.username)
        assertEquals("-passwordSecret", imported.secret)
        assertEquals("@SUM(1+1)", imported.notes)
    }

    @Test
    fun testCsvHeaderAliasing() {
        val customCsv = """
            "Item","Login Name","Pass","Website","Comment","Folder","Labels","Starred"
            "Bitwarden Vault","bw_user","bw_pass123","https://bitwarden.com","Vault notes","Security","sec,cloud","1"
            "Server SSH","root","ssh_key_pass","ssh://192.168.1.1","Admin access","Servers","infra","yes"
        """.trimIndent()

        val result = CsvImporter.parse(customCsv)
        assertEquals(2, result.validEntries.size)
        assertEquals(0, result.invalidCount)

        val first = result.validEntries[0]
        assertEquals("Bitwarden Vault", first.title)
        assertEquals("bw_user", first.username)
        assertEquals("bw_pass123", first.secret)
        assertEquals("https://bitwarden.com", first.url)
        assertEquals("Vault notes", first.notes)
        assertEquals("Security", first.category)
        assertEquals(listOf("sec", "cloud"), first.tags)
        assertTrue(first.isFavorite)

        val second = result.validEntries[1]
        assertEquals("Server SSH", second.title)
        assertEquals("root", second.username)
        assertTrue(second.isFavorite)
    }

    @Test
    fun testCsvDuplicateDetection() {
        val existing = listOf(
            VaultEntry(
                id = "10",
                title = "GitHub",
                username = "octocat",
                secret = "existingSecret",
                createdAt = 1000L,
                updatedAt = 2000L
            )
        )

        val csvText = """
            Title,Username,Password
            GitHub,octocat,newSecret
            GitLab,octocat,anotherSecret
        """.trimIndent()

        val result = CsvImporter.parse(csvText, existing)
        assertEquals(2, result.validEntries.size)
        assertEquals(1, result.duplicateCount)
        assertEquals(setOf("github|octocat"), result.duplicateKeys)
    }

    @Test
    fun testCsvInvalidAndEmptyRows() {
        val csvText = """
            Title,Username,Password
            GitHub,octocat,pass
            ,,
            , , 
            ValidSite,user,pass
        """.trimIndent()

        val result = CsvImporter.parse(csvText)
        assertEquals(2, result.validEntries.size)
        assertEquals(2, result.invalidCount)
        assertEquals(4, result.totalCount)
    }
}
