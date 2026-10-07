package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeePassImportTest {

    @Test
    fun testKeePassXmlImportWithHierarchyAndCustomFields() {
        val keepassXml = """
            <?xml version="1.0" encoding="utf-8" standalone="yes"?>
            <KeePassFile>
                <Meta>
                    <Generator>KeePass</Generator>
                    <DatabaseName>TestVault</DatabaseName>
                    <RecycleBinUUID>AAAAAAAAAAAAAAAAAAAAAA==</RecycleBinUUID>
                </Meta>
                <Root>
                    <Group>
                        <UUID>BBBBBBBBBBBBBBBBBBBBBB==</UUID>
                        <Name>Root</Name>
                        <Group>
                            <UUID>CCCCCCCCCCCCCCCCCCCCCC==</UUID>
                            <Name>Work</Name>
                            <Group>
                                <UUID>DDDDDDDDDDDDDDDDDDDDDD==</UUID>
                                <Name>Email</Name>
                                <Entry>
                                    <UUID>EEEEEEEEEEEEEEEEEEEEEE==</UUID>
                                    <String>
                                        <Key>Title</Key>
                                        <Value>Work Outlook</Value>
                                    </String>
                                    <String>
                                        <Key>UserName</Key>
                                        <Value>alice@work.corp</Value>
                                    </String>
                                    <String>
                                        <Key>Password</Key>
                                        <Value>CorpP@ssw0rd!</Value>
                                    </String>
                                    <String>
                                        <Key>URL</Key>
                                        <Value>https://outlook.office.com</Value>
                                    </String>
                                    <String>
                                        <Key>Notes</Key>
                                        <Value>Corporate mail access</Value>
                                    </String>
                                    <String>
                                        <Key>Department</Key>
                                        <Value>Engineering</Value>
                                    </String>
                                    <Tags>work, corporate; email</Tags>
                                    <Times>
                                        <CreationTime>2024-01-10T08:00:00Z</CreationTime>
                                        <LastModificationTime>2024-02-15T12:30:00Z</LastModificationTime>
                                    </Times>
                                </Entry>
                            </Group>
                        </Group>
                        <Group>
                            <UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID>
                            <Name>Recycle Bin</Name>
                            <Entry>
                                <UUID>FFFFFFFFFFFFFFFFFFFFFF==</UUID>
                                <String>
                                    <Key>Title</Key>
                                    <Value>Deleted Old Entry</Value>
                                </String>
                                <String>
                                    <Key>UserName</Key>
                                    <Value>trash_user</Value>
                                </String>
                                <String>
                                    <Key>Password</Key>
                                    <Value>trash_pass</Value>
                                </String>
                            </Entry>
                        </Group>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()

        val result = KeePassImporter.parseXml(keepassXml)

        // Recycle Bin entry should be excluded! Only the 1 active entry should be imported.
        assertEquals(1, result.validEntries.size)
        assertEquals(0, result.invalidCount)

        val entry = result.validEntries[0]
        assertEquals("Work Outlook", entry.title)
        assertEquals("alice@work.corp", entry.username)
        assertEquals("CorpP@ssw0rd!", entry.secret)
        assertEquals("https://outlook.office.com", entry.url)
        assertEquals("Work/Email", entry.category)
        assertTrue(entry.notes.contains("Corporate mail access"))
        assertTrue(entry.notes.contains("[Custom Fields]"))
        assertTrue(entry.notes.contains("Department: Engineering"))
        assertEquals(listOf("work", "corporate", "email"), entry.tags)
        assertFalse(entry.isDeleted)
    }

    @Test
    fun testKeePassXmlXxeRejection() {
        val maliciousXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE foo [
                <!ELEMENT foo ANY >
                <!ENTITY xxe SYSTEM "file:///nonexistent/sensitive/system/file" >
            ]>
            <KeePassFile>
                <Root>
                    <Group>
                        <Name>General</Name>
                        <Entry>
                            <String>
                                <Key>Title</Key>
                                <Value>&xxe;</Value>
                            </String>
                        </Entry>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()

        val result = KeePassImporter.parseXml(maliciousXml)
        // XXE DTD is disallowed, parser rejects without resolving external entity
        assertEquals(0, result.validEntries.size)
        assertTrue(result.invalidCount > 0)
    }

    @Test
    fun testKeePassCsvImportLayout1() {
        // KeePass 1.x standard layout: "Account","Login Name","Password","Web Site","Comments"
        val csvLayout1 = """
            "Account","Login Name","Password","Web Site","Comments"
            "Server Control","admin","RootAdminPass!","https://server.local","Main console"
        """.trimIndent()

        val result = KeePassImporter.parseCsv(csvLayout1)
        assertEquals(1, result.validEntries.size)
        val entry = result.validEntries[0]
        assertEquals("Server Control", entry.title)
        assertEquals("admin", entry.username)
        assertEquals("RootAdminPass!", entry.secret)
        assertEquals("https://server.local", entry.url)
        assertEquals("Main console", entry.notes)
    }

    @Test
    fun testKeePassCsvImportLayout2WithRecycleBinExclusion() {
        // KeePass 2.x standard layout: "Group","Title","Username","Password","URL","Notes"
        val csvLayout2 = """
            "Group","Title","Username","Password","URL","Notes"
            "Finance/Banking","Chase Bank","chase_user","ChasePassword123","https://chase.com","Checking account"
            "Recycle Bin","Old Dead Account","dead_user","dead_pass","https://dead.com","Old notes"
        """.trimIndent()

        val result = KeePassImporter.parseCsv(csvLayout2)
        assertEquals(1, result.validEntries.size)
        val entry = result.validEntries[0]
        assertEquals("Chase Bank", entry.title)
        assertEquals("chase_user", entry.username)
        assertEquals("Finance/Banking", entry.category)
    }

    @Test
    fun testKeePassDuplicateDetection() {
        val existing = listOf(
            VaultEntry(
                id = "1",
                title = "Work Outlook",
                username = "alice@work.corp",
                secret = "old_pass",
                createdAt = 1000L,
                updatedAt = 2000L
            )
        )

        val csv = """
            "Account","Login Name","Password","Web Site","Comments"
            "Work Outlook","alice@work.corp","new_pass","https://outlook.com",""
            "GitHub","alice","gh_pass","https://github.com",""
        """.trimIndent()

        val result = KeePassImporter.parseCsv(csv, existing)
        assertEquals(2, result.validEntries.size)
        assertEquals(1, result.duplicateCount)
        assertEquals(setOf("work outlook|alice@work.corp"), result.duplicateKeys)
    }
}
