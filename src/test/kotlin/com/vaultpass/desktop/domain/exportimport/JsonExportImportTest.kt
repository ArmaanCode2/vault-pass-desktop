package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonExportImportTest {

    @Test
    fun testExportSchemaMatchesAndroidSpec() {
        val entry = VaultEntry(
            id = "1",
            title = "GitHub",
            username = "octocat",
            secret = "SuperSecret123!",
            url = "https://github.com",
            notes = "Developer account",
            category = "Work",
            tags = listOf("dev", "git"),
            isFavorite = true,
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val jsonStr = JsonExporter.format(listOf(entry))

        assertTrue(jsonStr.contains("GitHub"))
        assertTrue(jsonStr.contains("octocat"))
        assertTrue(jsonStr.contains("SuperSecret123!"))
        assertTrue(jsonStr.contains("https://github.com"))
        assertTrue(jsonStr.contains("Developer account"))
        assertTrue(jsonStr.contains("Work"))
        assertTrue(jsonStr.contains("dev"))
        assertTrue(jsonStr.contains("git"))
    }

    @Test
    fun testImportCurrentJsonSchema() {
        val rawJson = """
            {
                "Google Account": [
                    "user@gmail.com",
                    "Password456!",
                    {
                        "isFavorite": true,
                        "website": "https://google.com",
                        "notes": "Primary email",
                        "category": "Personal",
                        "tags": ["google", "email"]
                    }
                ],
                "AWS Console": [
                    "admin",
                    "AwsPass789!",
                    {
                        "category": "Work"
                    }
                ]
            }
        """.trimIndent()

        val result = JsonImporter.parse(rawJson)

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
    fun testImportLegacyJsonDtoArraySchema() {
        val legacyJson = """
            [
                {
                    "title": "Legacy Entry",
                    "username": "legacy_user",
                    "password": "legacy_pass_123",
                    "customFields": ["val1", "val2"],
                    "isFavorite": true
                }
            ]
        """.trimIndent()

        val result = JsonImporter.parse(legacyJson)

        assertEquals(1, result.validEntries.size)
        assertEquals(0, result.invalidCount)
        assertEquals(1, result.totalCount)

        val entry = result.validEntries[0]
        assertEquals("Legacy Entry", entry.title)
        assertEquals("legacy_user", entry.username)
        assertEquals("legacy_pass_123", entry.secret)
        assertTrue(entry.isFavorite)
        assertTrue(entry.notes.contains("Field 1: val1"))
        assertTrue(entry.notes.contains("Field 2: val2"))
    }

    @Test
    fun testIgnoreUnknownProperties() {
        val futureJson = """
            {
                "Future Service": [
                    "future_user",
                    "future_pass",
                    {
                        "isFavorite": true,
                        "website": "https://future.io",
                        "futureNewField": "some_value",
                        "nestedNewObject": { "key": "val" }
                    }
                ]
            }
        """.trimIndent()

        val result = JsonImporter.parse(futureJson)

        assertEquals(1, result.validEntries.size)
        assertEquals(0, result.invalidCount)
        val entry = result.validEntries[0]
        assertEquals("Future Service", entry.title)
        assertEquals("future_user", entry.username)
        assertEquals("future_pass", entry.secret)
        assertTrue(entry.isFavorite)
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

        val rawJson = """
            {
                "GitHub": [
                    "octocat",
                    "NewPass123!"
                ],
                "GitLab": [
                    "octocat",
                    "GitLabPass!"
                ]
            }
        """.trimIndent()

        val result = JsonImporter.parse(rawJson, existing)

        assertEquals(2, result.validEntries.size)
        assertEquals(1, result.duplicateCount)
        assertEquals(0, result.invalidCount)
        assertEquals(2, result.totalCount)
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

        val exportedJson = JsonExporter.format(listOf(original))
        val parseResult = JsonImporter.parse(exportedJson)

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
}
