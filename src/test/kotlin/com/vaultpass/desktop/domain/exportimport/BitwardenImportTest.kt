package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitwardenImportTest {

    @Test
    fun testBitwardenJsonImportComprehensive() {
        val bitwardenJson = """
        {
          "encrypted": false,
          "folders": [
            { "id": "fld-work-1", "name": "Work Accounts" },
            { "id": "fld-fin-2", "name": "Banking" }
          ],
          "items": [
            {
              "id": "item-1",
              "folderId": "fld-work-1",
              "type": 1,
              "name": "Corporate Slack",
              "notes": "Company workspace",
              "favorite": true,
              "login": {
                "username": "employee@corp.com",
                "password": "SlackSecretPass99!",
                "totp": "JBSWY3DPEHPK3PXP",
                "uris": [
                  { "uri": "https://corp.slack.com" },
                  { "uri": "https://slack.com" }
                ]
              },
              "fields": [
                { "name": "EmployeeID", "value": "EMP-421" },
                { "name": "Desk", "value": "Building 3" }
              ]
            },
            {
              "id": "item-2",
              "type": 2,
              "name": "Server Recovery Key",
              "notes": "Recovery master key: 9876-5432-1000",
              "favorite": false
            },
            {
              "id": "item-3",
              "folderId": "fld-fin-2",
              "type": 3,
              "name": "Corporate Credit Card",
              "notes": "Emergency card",
              "card": {
                "cardholderName": "Jane Doe",
                "brand": "Visa",
                "number": "4111111111111111",
                "expMonth": "12",
                "expYear": "2028",
                "code": "123"
              }
            },
            {
              "id": "item-4",
              "type": 4,
              "name": "John Passport",
              "identity": {
                "title": "Mr",
                "firstName": "John",
                "lastName": "Doe",
                "ssn": "000-00-0000"
              }
            }
          ]
        }
        """.trimIndent()

        val result = BitwardenImporter.parseJson(bitwardenJson)
        assertEquals(4, result.validEntries.size)
        assertEquals(0, result.invalidCount)
        assertEquals(4, result.totalCount)

        // Verify Login entry
        val loginItem = result.validEntries.first { it.title == "Corporate Slack" }
        assertEquals("employee@corp.com", loginItem.username)
        assertEquals("SlackSecretPass99!", loginItem.secret)
        assertEquals("https://corp.slack.com", loginItem.url)
        assertEquals("Work Accounts", loginItem.category)
        assertTrue(loginItem.isFavorite)
        assertTrue(loginItem.notes.contains("Company workspace"))
        assertTrue(loginItem.notes.contains("[Additional URIs]"))
        assertTrue(loginItem.notes.contains("https://slack.com"))
        assertTrue(loginItem.notes.contains("[Custom Fields]"))
        assertTrue(loginItem.notes.contains("EmployeeID: EMP-421"))
        assertTrue(loginItem.notes.contains("Desk: Building 3"))

        // Verify Secure Note entry
        val noteItem = result.validEntries.first { it.title == "Server Recovery Key" }
        assertEquals("", noteItem.secret)
        assertEquals("Recovery master key: 9876-5432-1000", noteItem.notes)
        assertEquals("Personal", noteItem.category) // default category

        // Verify Card entry
        val cardItem = result.validEntries.first { it.title == "Corporate Credit Card" }
        assertEquals("4111111111111111", cardItem.secret)
        assertEquals("Banking", cardItem.category)
        assertTrue(cardItem.notes.contains("[Card Details]"))
        assertTrue(cardItem.notes.contains("Cardholder: Jane Doe"))
        assertTrue(cardItem.notes.contains("Brand: Visa"))
        assertTrue(cardItem.notes.contains("Expires: 12/2028"))
        assertTrue(cardItem.notes.contains("CVV: 123"))

        // Verify Identity entry
        val idItem = result.validEntries.first { it.title == "John Passport" }
        assertTrue(idItem.notes.contains("[Identity Details]"))
        assertTrue(idItem.notes.contains("FirstName: John") || idItem.notes.contains("Firstname: John"))
        assertTrue(idItem.notes.contains("LastName: Doe") || idItem.notes.contains("Lastname: Doe"))
    }

    @Test
    fun testBitwardenCsvImport() {
        val bwCsv = """
            "folder","favorite","type","name","notes","fields","reprompt","login_uri","login_username","login_password","login_totp"
            "Cloud","1","login","AWS Admin","Production root account","Env: Prod","0","https://aws.amazon.com","cloud_admin","AwsSecretPassword#1","TOTPSECRETKEY"
        """.trimIndent()

        val result = BitwardenImporter.parseCsv(bwCsv)
        assertEquals(1, result.validEntries.size)
        assertEquals(0, result.invalidCount)

        val entry = result.validEntries[0]
        assertEquals("AWS Admin", entry.title)
        assertEquals("cloud_admin", entry.username)
        assertEquals("AwsSecretPassword#1", entry.secret)
        assertEquals("https://aws.amazon.com", entry.url)
        assertEquals("Cloud", entry.category)
        assertTrue(entry.isFavorite)
        assertTrue(entry.notes.contains("Production root account"))
        assertTrue(entry.notes.contains("[2FA TOTP Seed]: TOTPSECRETKEY"))
        assertTrue(entry.notes.contains("[Custom Fields]\nEnv: Prod"))
    }

    @Test
    fun testBitwardenDuplicateDetection() {
        val existing = listOf(
            VaultEntry(
                id = "1",
                title = "GitHub",
                username = "octocat",
                secret = "pwd",
                createdAt = 1000L,
                updatedAt = 2000L
            )
        )

        val bwJson = """
        {
          "items": [
            {
              "type": 1,
              "name": "GitHub",
              "login": {
                "username": "octocat",
                "password": "pwd2"
              }
            }
          ]
        }
        """.trimIndent()

        val result = BitwardenImporter.parseJson(bwJson, existing)
        assertEquals(1, result.validEntries.size)
        assertEquals(1, result.duplicateCount)
        assertEquals(setOf("github|octocat"), result.duplicateKeys)
    }

    @Test
    fun testBitwardenMalformedJsonHandledGracefully() {
        val brokenJson = "{ this is not valid json! }"
        val result = BitwardenImporter.parseJson(brokenJson)
        assertEquals(0, result.validEntries.size)
        assertEquals(1, result.invalidCount)
        assertEquals(1, result.totalCount)
    }
}
