package com.vaultpass.desktop.data.sync

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.synccore.PairKeyFormat
import com.vaultpass.synccore.SyncCrypto
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

/** Pair keys are stored sealed with the vault key; v1 pairings are dropped; the device ID is random. */
class SyncStorageTest {

    private lateinit var tempDb: File
    private lateinit var connectionManager: SQLiteConnectionManager
    private lateinit var repository: PairedDeviceRepository

    @BeforeEach
    fun setup() {
        tempDb = File.createTempFile("test_sync_storage_", ".db")
        connectionManager = SQLiteConnectionManager(tempDb.absolutePath)
        connectionManager.openConnection()
        repository = PairedDeviceRepository(connectionManager)
    }

    @AfterEach
    fun tearDown() {
        connectionManager.closeConnection()
        tempDb.delete()
    }

    @Test
    fun protector_sealsWithTheVaultKey_andNeedsAnUnlockedVault() {
        val crypto = FakeCryptoManager()
        val protector = DesktopPairKeyProtector(crypto)
        val pairKey = SyncCrypto.randomBytes(32)

        val sealed = protector.seal(pairKey)!!
        assertTrue(PairKeyFormat.isCurrent(sealed))
        assertFalse(sealed.contains(com.vaultpass.synccore.Hex.encode(pairKey)), "The key must not be stored in plaintext")
        assertArrayEquals(pairKey, protector.open(sealed))

        crypto.locked = true
        assertNull(protector.seal(pairKey))
        assertNull(protector.open(sealed))
    }

    @Test
    fun protector_rejectsV1SecretsAndGarbage() {
        val protector = DesktopPairKeyProtector(FakeCryptoManager())
        assertNull(protector.open("dGVzdC1zZWNyZXQ=")) // a v1 Base64 secret
        assertNull(protector.open("v2:zz"))
        assertNull(protector.open("v2:" + "00".repeat(40)))
        assertNull(protector.seal(ByteArray(16)))
    }

    @Test
    fun repository_hidesAndPurgesV1Pairings() = runBlocking {
        repository.savePairedDevice(PairedDevice(deviceId = "old-phone", deviceName = "Old", sharedSecret = "dGVzdC1zZWNyZXQ=", pairedAt = 1L))
        repository.savePairedDevice(PairedDevice(deviceId = "new-phone", deviceName = "New", sharedSecret = "v2:abcd", pairedAt = 2L))

        assertEquals(listOf("new-phone"), repository.getPairedDevices().map { it.deviceId })
        assertNull(repository.getPairedDevice("old-phone"))
        assertNotNull(repository.getPairedDevice("new-phone"))

        assertEquals(1, repository.purgeLegacyPairings())
        assertEquals(0, repository.purgeLegacyPairings())
        assertEquals(listOf("new-phone"), repository.getPairedDevices().map { it.deviceId })
    }

    @Test
    fun deviceIdentity_isARandomUuidKeptAcrossRuns() {
        val file = File.createTempFile("sync-device-id", ".txt").apply { delete() }
        try {
            val first = DeviceIdentity.loadOrCreate(file)
            assertEquals(first, UUID.fromString(first).toString())
            assertEquals(first, DeviceIdentity.loadOrCreate(file))

            file.writeText("not-a-uuid")
            val replaced = DeviceIdentity.loadOrCreate(file)
            assertNotEquals("not-a-uuid", replaced)
            assertEquals(replaced, UUID.fromString(replaced).toString())
        } finally {
            file.delete()
        }
    }
}
