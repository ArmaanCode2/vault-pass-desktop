package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlainTextExportImportTest {

    @Test
    fun testExportFormattingMatchesAndroidSpec() {
        val entry1 = VaultEntry(
            id = "1",
            title = "GitHub",
            username = "octocat",
            secret = "SuperSecret123!",
            url = "https://github.com",
            notes = "Developer account",
            category = "Personal",
            tags = listOf("dev", "git"),
            isFavorite = true,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val formatted = PlainTextExporter.format(listOf(entry1))

        assertTrue(formatted.contains("Title: GitHub"))
        assertTrue(formatted.contains("Username:"))
        assertTrue(formatted.contains("octocat"))
        assertTrue(formatted.contains("Password:"))
        assertTrue(formatted.contains("SuperSecret123!"))
        assertTrue(formatted.contains("Favorite:"))
        assertTrue(formatted.contains("Yes"))
        assertTrue(formatted.contains("Website:"))
        assertTrue(formatted.contains("https://github.com"))
        assertTrue(formatted.contains("Notes:"))
        assertTrue(formatted.contains("Developer account"))
        assertTrue(formatted.contains("Tags:"))
        assertTrue(formatted.contains("dev, git"))
        assertTrue(formatted.contains("---"))
    }

    @Test
    fun testImportParsingMatchesAndroidSpec() {
        val rawText = """
            Title: Google Account

            Username:
            user@gmail.com

            Password:
            Password456!

            Favorite:
            Yes

            Website:
            https://google.com

            Notes:
            Primary email

            Category:
            Personal

            Tags:
            google, email

            ---

            Title: AWS Console

            Username:
            admin

            Password:
            AwsPass789!

            Category:
            Work

            ---
        """.trimIndent()

        val result = PlainTextImporter.parse(rawText)

        assertEquals(2, result.validEntries.size)
        assertEquals(0, result.invalidCount)
        assertEquals(0, result.duplicateCount)
        assertEquals(2, result.totalCount)

        val first = result.validEntries[0]
        assertEquals("Google Account", first.title)
        assertEquals("user@gmail.com", first.username)
        assertEquals("Password456!", first.secret)
        assertTrue(first.isFavorite)
        assertEquals("https://google.com", first.url)
        assertEquals("Primary email", first.notes)
        assertEquals(listOf("google", "email"), first.tags)

        val second = result.validEntries[1]
        assertEquals("AWS Console", second.title)
        assertEquals("admin", second.username)
        assertEquals("AwsPass789!", second.secret)
        assertEquals("Work", second.category)
    }

    @Test
    fun testRoundTripExportAndImport() {
        val original = VaultEntry(
            id = "test-id",
            title = "Bitbucket",
            username = "dev_user",
            secret = "BitbucketPass123",
            url = "https://bitbucket.org",
            notes = "Work repo",
            category = "Work",
            tags = listOf("code", "work"),
            isFavorite = true,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val exportedText = PlainTextExporter.format(listOf(original))
        val parseResult = PlainTextImporter.parse(exportedText)

        assertEquals(1, parseResult.validEntries.size)
        val imported = parseResult.validEntries[0]

        assertEquals(original.title, imported.title)
        assertEquals(original.username, imported.username)
        assertEquals(original.secret, imported.secret)
        assertEquals(original.url, imported.url)
        assertEquals(original.notes, imported.notes)
        assertEquals(original.category, imported.category)
        assertEquals(original.tags, imported.tags)
        assertEquals(original.isFavorite, imported.isFavorite)
    }

    @Test
    fun testInvalidEntriesCounting() {
        val rawText = """
            Username:
            no_title_user

            Password:
            pass123

            ---

            Title: Valid Entry

            Username:
            user

            Password:
            pass

            ---
        """.trimIndent()

        val result = PlainTextImporter.parse(rawText)

        assertEquals(1, result.validEntries.size)
        assertEquals(1, result.invalidCount)
        assertEquals(2, result.totalCount)
        assertEquals("Valid Entry", result.validEntries[0].title)
    }

    @Test
    fun testDuplicateEntriesDetection() {
        val existing = listOf(
            VaultEntry(
                id = "1",
                title = "GitHub",
                username = "octocat",
                secret = "Pass1",
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )

        val rawText = """
            Title: GitHub

            Username:
            octocat

            Password:
            NewPass!

            ---

            Title: GitLab

            Username:
            octocat

            Password:
            GitLabPass!

            ---
        """.trimIndent()

        val result = PlainTextImporter.parse(rawText, existing)

        assertEquals(2, result.validEntries.size)
        assertEquals(1, result.duplicateCount)
        assertEquals(0, result.invalidCount)
        assertEquals(2, result.totalCount)
    }
}
