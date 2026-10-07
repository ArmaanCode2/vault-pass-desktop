package com.vaultpass.desktop.data.sync

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.desktop.domain.sync.models.SyncFrame
import com.vaultpass.synccore.Beacons
import com.vaultpass.synccore.PairKeyFormat
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncTimeouts
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class LanSyncNetworkingTest {

    private lateinit var tempDb: File
    private lateinit var connectionManager: SQLiteConnectionManager
    private lateinit var repository: PairedDeviceRepository

    @BeforeEach
    fun setup() {
        tempDb = File.createTempFile("test_sync_db_", ".db")
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
    fun testPairedDevicePersistenceAndUnpairing() = runTest {
        val device = PairedDevice(
            deviceId = "android-pixel-7-id",
            deviceName = "Pixel 7",
            sharedSecret = sealedKey(),
            ipAddress = "192.168.1.105",
            port = 53853,
            pairedAt = System.currentTimeMillis(),
            lastSyncAt = 0L,
            isOnline = false
        )

        // 1. Save paired device
        repository.savePairedDevice(device)

        // 2. Verify retrieval
        val devices = repository.getPairedDevices()
        assertEquals(1, devices.size)
        val retrieved = devices[0]
        assertEquals("android-pixel-7-id", retrieved.deviceId)
        assertEquals("Pixel 7", retrieved.deviceName)
        assertEquals(device.sharedSecret, retrieved.sharedSecret)
        assertEquals("192.168.1.105", retrieved.ipAddress)
        assertEquals(53853, retrieved.port)

        // 3. Update last sync
        val syncTime = System.currentTimeMillis()
        repository.updateLastSync("android-pixel-7-id", syncTime)
        val updated = repository.getPairedDevice("android-pixel-7-id")
        assertNotNull(updated)
        assertEquals(syncTime, updated?.lastSyncAt)

        // 4. Unpair device
        repository.unpairDevice("android-pixel-7-id")
        val remaining = repository.getPairedDevices()
        assertTrue(remaining.isEmpty(), "Expected empty list after unpairing")
    }

    @Test
    fun testRePairingSameDeviceOverwritesCleanly() = runTest {
        val secret1 = sealedKey()
        val deviceV1 = PairedDevice(
            deviceId = "device-alpha",
            deviceName = "Tablet Alpha",
            sharedSecret = secret1,
            ipAddress = "192.168.1.50",
            port = 53853,
            pairedAt = 1000L
        )

        repository.savePairedDevice(deviceV1)
        assertEquals(secret1, repository.getPairedDevice("device-alpha")?.sharedSecret)

        // Unpair
        repository.unpairDevice("device-alpha")
        assertNull(repository.getPairedDevice("device-alpha"))

        // Re-pair with new secret and updated IP
        val secret2 = sealedKey()
        val deviceV2 = PairedDevice(
            deviceId = "device-alpha",
            deviceName = "Tablet Alpha Renamed",
            sharedSecret = secret2,
            ipAddress = "192.168.1.99",
            port = 53853,
            pairedAt = 2000L
        )

        repository.savePairedDevice(deviceV2)
        val finalRetrieved = repository.getPairedDevice("device-alpha")
        assertNotNull(finalRetrieved)
        assertEquals(secret2, finalRetrieved?.sharedSecret)
        assertEquals("Tablet Alpha Renamed", finalRetrieved?.deviceName)
        assertEquals("192.168.1.99", finalRetrieved?.ipAddress)
        assertEquals(1, repository.getPairedDevices().size)
    }

    @Test
    fun testQrCodeBitmapGeneration() {
        val payload = "vaultpass://pair?v=2&deviceId=pc-123&secret=${"ab".repeat(32)}&ip=192.168.1.10&port=53853"
        val bitmap = QrCodeGenerator.generateQrBitmap(payload, size = 280)

        assertNotNull(bitmap)
        assertEquals(280, bitmap.width)
        assertEquals(280, bitmap.height)
    }

    @Test
    fun testMultiIpAddressResolution() {
        val discoveryManager = LanDiscoveryManager(
            deviceId = "test-device",
            deviceName = "Test PC",
            pairedDeviceRepository = repository
        )

        val allIps = discoveryManager.getAllLocalIpAddresses()
        assertNotNull(allIps)
        assertTrue(allIps.isNotEmpty())

        for (ip in allIps) {
            assertFalse(ip.startsWith("169.254."), "Link-local APIPA IP must be filtered out: $ip")
            if (allIps.size > 1) {
                assertNotEquals("127.0.0.1", ip, "Loopback must not be included when real IPs exist")
            }
        }

        val localIp = discoveryManager.getLocalIpAddress()
        assertNotNull(localIp)
        assertEquals(allIps.first(), localIp)
    }

    @Test
    fun testSyncFramePolymorphicSerializationSerialName() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        val frame: SyncFrame = SyncFrame.PairingInfo(
            deviceId = "dev-1",
            deviceName = "Phone"
        )
        val encoded = json.encodeToString(SyncFrame.serializer(), frame)
        assertTrue(encoded.contains("\"type\":\"PairingInfo\""), "Serialized frame should contain short SerialName: $encoded")

        val decoded = json.decodeFromString(SyncFrame.serializer(), encoded)
        assertTrue(decoded is SyncFrame.PairingInfo)
        assertEquals("dev-1", (decoded as SyncFrame.PairingInfo).deviceId)

        // Verify mobile-originated frame cross-compatibility
        val mobileSentJson = """{"type":"PairingAcceptance","deviceId":"dev-2","isAccepted":true}"""
        val decodedMobile = json.decodeFromString(SyncFrame.serializer(), mobileSentJson)
        assertTrue(decodedMobile is SyncFrame.PairingAcceptance)
        val acceptance = decodedMobile as SyncFrame.PairingAcceptance
        assertEquals("dev-2", acceptance.deviceId)
        assertTrue(acceptance.isAccepted)
    }

    @Test
    fun testSessionV3FramesUseTheSharedWireNames() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val frames = listOf<SyncFrame>(
            SyncFrame.MergePlanBatch(planJson = "[]", part = 2, last = true, expectedFingerprint = "abc"),
            SyncFrame.MergePlanDecision(accepted = false, code = SyncError.PLAN_REJECTED.code),
            SyncFrame.SyncFinished(code = "", fingerprint = "f".repeat(32), applied = 3),
            SyncFrame.CancelSync(reason = "Timed out", code = SyncError.TIMED_OUT.code),
            SyncFrame.KeepAlive(sentAt = 1_700_000_000_000L)
        )
        for (frame in frames) {
            val encoded = json.encodeToString(SyncFrame.serializer(), frame)
            assertTrue(encoded.contains("\"type\":\"${frame::class.simpleName}\""), encoded)
            assertEquals(frame, json.decodeFromString(SyncFrame.serializer(), encoded))
        }

        // What the other app sends, field for field; a cancel without a code still decodes.
        val fromPhone = """{"type":"MergePlanBatch","planJson":"[]","part":0,"last":false}"""
        assertEquals(SyncFrame.MergePlanBatch("[]", 0, false, ""), json.decodeFromString(SyncFrame.serializer(), fromPhone))
        val oldCancel = """{"type":"CancelSync","reason":"User cancelled"}"""
        assertEquals(SyncFrame.CancelSync("User cancelled", ""), json.decodeFromString(SyncFrame.serializer(), oldCancel))

        // The retired frame is gone for good.
        assertThrows(Exception::class.java) {
            json.decodeFromString(SyncFrame.serializer(), """{"type":"ManifestExchange","entries":[]}""")
        }
    }

    @Test
    fun testTransportTimeLimitsComeFromTheSharedCore() {
        assertEquals(SyncTimeouts.HANDSHAKE_MS, LanSocketTransport.FIRST_FRAME_TIMEOUT_MS)
        assertEquals(SyncTimeouts.IDLE_MS, LanSocketTransport.IDLE_READ_TIMEOUT_MS)
        assertTrue(LanSocketTransport.KEEP_ALIVE_MS * 3 < SyncTimeouts.IDLE_MS, "Several keep-alives fit in one idle limit")
    }

    @Test
    fun testOnlyPairedDevicesBeaconsRegisterPresence() = runTest {
        val protector = DesktopPairKeyProtector(FakeCryptoManager())
        val key = pairTestDevice(repository, protector, "mobile-phone-123", "Pixel 8")
        val discoveryManager = LanDiscoveryManager(
            deviceId = "test-device",
            deviceName = "Test PC",
            pairedDeviceRepository = repository,
            pairKeyProtector = protector
        )
        discoveryManager.refreshPairedKeysForTesting()
        val handleMethod = LanDiscoveryManager::class.java.getDeclaredMethod(
            "handleIncomingPacket",
            String::class.java,
            String::class.java
        )
        handleMethod.isAccessible = true

        // Reverse-connect requests, v1 beacons and beacons of unpaired devices are ignored.
        val reversePair = """{"type":"REVERSE_PAIR_REQUEST","deviceId":"mobile-phone-123","deviceName":"Pixel 8","token":"ABCD1234","mobileIp":"192.168.1.120","mobilePort":53853}"""
        val v1Beacon = """{"deviceId":"mobile-phone-123","deviceName":"Pixel 8","port":53853,"type":"BEACON"}"""
        val strangerBeacon = Beacons.encode(Beacons.create(listOf(SyncCrypto.randomBytes(32)), 53853, 1_000L))
        handleMethod.invoke(discoveryManager, reversePair, "192.168.1.120")
        handleMethod.invoke(discoveryManager, v1Beacon, "192.168.1.121")
        handleMethod.invoke(discoveryManager, strangerBeacon, "192.168.1.122")
        assertTrue(discoveryManager.discoveredDevices.value.isEmpty())

        // The paired phone's beacon is recognised, and its address is remembered.
        val beacon = Beacons.encode(Beacons.create(listOf(key), 53853, 1_000L))
        handleMethod.invoke(discoveryManager, beacon, "192.168.1.120")
        assertEquals("192.168.1.120", discoveryManager.discoveredDevices.value["mobile-phone-123"]?.ipAddress)
        assertEquals("Pixel 8", discoveryManager.discoveredDevices.value["mobile-phone-123"]?.deviceName)
        assertTrue(waitUntil { runBlocking { repository.getPairedDevice("mobile-phone-123")?.ipAddress } == "192.168.1.120" })

        // Replaying that beacon from another address changes nothing.
        handleMethod.invoke(discoveryManager, beacon, "10.0.0.66")
        assertEquals("192.168.1.120", discoveryManager.discoveredDevices.value["mobile-phone-123"]?.ipAddress)
    }

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** A stored pair key in the v2 format (the content is opaque to the repository). */
    private fun sealedKey(): String =
        PairKeyFormat.PREFIX + com.vaultpass.synccore.Hex.encode(com.vaultpass.synccore.SyncCrypto.randomBytes(60))
}
