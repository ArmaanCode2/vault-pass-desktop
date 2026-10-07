package com.vaultpass.desktop.domain.exportimport

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ImportFormatDetectorTest {

    @Test
    fun testDetectVaultPassVpex() {
        // Via file extension
        assertEquals(
            DetectedFormat.VAULTPASS_VPEX,
            ImportFormatDetector.detect("some-payload", "my_backup.vpex")
        )

        // Via raw encrypted base64 payload without filename
        val vpexPayload = VpexCryptoManager.encrypt("""{"test": 123}""", "TestPassword123")
        assertEquals(
            DetectedFormat.VAULTPASS_VPEX,
            ImportFormatDetector.detect(vpexPayload, "")
        )
    }

    @Test
    fun testDetectVaultPassJson() {
        val currentJson = """
            {
                "Google": ["user@gmail.com", "pass123", {"isFavorite": true}]
            }
        """.trimIndent()
        assertEquals(
            DetectedFormat.VAULTPASS_JSON,
            ImportFormatDetector.detect(currentJson, "export.json")
        )

        val legacyJson = """
            [
                {"title": "Google", "username": "user@gmail.com", "password": "123"}
            ]
        """.trimIndent()
        assertEquals(
            DetectedFormat.VAULTPASS_JSON,
            ImportFormatDetector.detect(legacyJson, "")
        )
    }

    @Test
    fun testDetectVaultPassTxt() {
        val txtContent = """
            Title: ProtonMail
            
            Username:
            user@proton.me
            
            Password:
            Secret123
            
            ---
        """.trimIndent()
        assertEquals(
            DetectedFormat.VAULTPASS_TXT,
            ImportFormatDetector.detect(txtContent, "vault.txt")
        )
        assertEquals(
            DetectedFormat.VAULTPASS_TXT,
            ImportFormatDetector.detect(txtContent, "")
        )
    }

    @Test
    fun testDetectBitwardenJson() {
        val bwJson = """
            {
                "encrypted": false,
                "folders": [],
                "items": [
                    {"name": "Slack", "type": 1}
                ]
            }
        """.trimIndent()
        assertEquals(
            DetectedFormat.BITWARDEN_JSON,
            ImportFormatDetector.detect(bwJson, "bitwarden_export.json")
        )
        assertEquals(
            DetectedFormat.BITWARDEN_JSON,
            ImportFormatDetector.detect(bwJson, "")
        )
    }

    @Test
    fun testDetectBitwardenCsv() {
        val bwCsv = """
            "folder","favorite","type","name","notes","fields","reprompt","login_uri","login_username","login_password","login_totp"
            "Cloud","0","login","Site","notes","","0","https://example.com","user","pass",""
        """.trimIndent()
        assertEquals(
            DetectedFormat.BITWARDEN_CSV,
            ImportFormatDetector.detect(bwCsv, "bw.csv")
        )
        assertEquals(
            DetectedFormat.BITWARDEN_CSV,
            ImportFormatDetector.detect(bwCsv, "")
        )
    }

    @Test
    fun testDetectKeePassXml() {
        val kpXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <KeePassFile>
                <Root>
                    <Group>
                        <Name>Root</Name>
                    </Group>
                </Root>
            </KeePassFile>
        """.trimIndent()
        assertEquals(
            DetectedFormat.KEEPASS_XML,
            ImportFormatDetector.detect(kpXml, "KeePass.xml")
        )
        assertEquals(
            DetectedFormat.KEEPASS_XML,
            ImportFormatDetector.detect(kpXml, "")
        )
    }

    @Test
    fun testDetectKeePassCsv() {
        // KeePass 1.x
        val kp1Csv = """
            "Account","Login Name","Password","Web Site","Comments"
            "Server","admin","pass","https://site.com","notes"
        """.trimIndent()
        assertEquals(
            DetectedFormat.KEEPASS_CSV,
            ImportFormatDetector.detect(kp1Csv, "keepass1.csv")
        )
        assertEquals(
            DetectedFormat.KEEPASS_CSV,
            ImportFormatDetector.detect(kp1Csv, "")
        )

        // KeePass 2.x
        val kp2Csv = """
            "Group","Title","Username","Password","URL","Notes"
            "General","Server","admin","pass","https://site.com","notes"
        """.trimIndent()
        assertEquals(
            DetectedFormat.KEEPASS_CSV,
            ImportFormatDetector.detect(kp2Csv, "keepass2.csv")
        )
        assertEquals(
            DetectedFormat.KEEPASS_CSV,
            ImportFormatDetector.detect(kp2Csv, "")
        )
    }

    @Test
    fun testDetectGenericCsv() {
        val genericCsv = """
            Title,Username,Password,URL,Notes,Category,Tags,Favorite
            GitHub,octocat,pass,https://github.com,Dev,Work,git,true
        """.trimIndent()
        assertEquals(
            DetectedFormat.GENERIC_CSV,
            ImportFormatDetector.detect(genericCsv, "passwords.csv")
        )
        assertEquals(
            DetectedFormat.GENERIC_CSV,
            ImportFormatDetector.detect(genericCsv, "")
        )
    }

    @Test
    fun testDetectUnknownFormat() {
        assertEquals(
            DetectedFormat.UNKNOWN,
            ImportFormatDetector.detect("Just some plain random text without headers or structure.")
        )
        assertEquals(
            DetectedFormat.UNKNOWN,
            ImportFormatDetector.detect("")
        )
    }
}
