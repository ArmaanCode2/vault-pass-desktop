package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VpexExportImportTest {

    @Test
    fun testVpexEncryptionAndDecryption() {
        val payload = """{"TestKey":["user","secret",{"isFavorite":true}]}"""
        val password = "ExportPassword123!"

        val encryptedBase64 = VpexCryptoManager.encrypt(payload, password)
        assertTrue(encryptedBase64.isNotEmpty())

        val decryptedText = VpexCryptoManager.decrypt(encryptedBase64, password)
        assertEquals(payload, decryptedText)
    }

    @Test
    fun testVpexDecryptionFailsWithWrongPassword() {
        val payload = """{"TestKey":["user","secret"]}"""
        val correctPassword = "CorrectPassword123!"
        val wrongPassword = "WrongPassword123!"

        val encryptedBase64 = VpexCryptoManager.encrypt(payload, correctPassword)

        val exception = assertThrows<IllegalArgumentException> {
            VpexCryptoManager.decrypt(encryptedBase64, wrongPassword)
        }
        assertTrue(exception.message?.contains("Decryption failed") == true)
    }

    @Test
    fun testVpexCorruptedPayloadRejection() {
        val shortContent = "SGVsbG8=" // Base64 for "Hello" (too short)
        assertThrows<IllegalArgumentException> {
            VpexCryptoManager.decrypt(shortContent, "SomePassword")
        }

        val invalidBase64 = "NotValidBase64!@#"
        assertThrows<IllegalArgumentException> {
            VpexCryptoManager.decrypt(invalidBase64, "SomePassword")
        }
    }

    @Test
    fun testVpexRoundTripWithVaultEntries() {
        val original = VaultEntry(
            id = "vpex-id",
            title = "Secret Vault Item",
            username = "vpex_user",
            secret = "vpex_pass_999",
            url = "https://vpex.org",
            notes = "Encrypted backup item",
            category = "Work",
            tags = listOf("crypto", "vpex"),
            isFavorite = true,
            createdAt = 5000L,
            updatedAt = 6000L
        )

        val password = "StrongExportPassword2026!"

        val jsonStr = JsonExporter.format(listOf(original))
        val encryptedBase64 = VpexCryptoManager.encrypt(jsonStr, password)
        val decryptedJson = VpexCryptoManager.decrypt(encryptedBase64, password)

        val parseResult = JsonImporter.parse(decryptedJson)

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
