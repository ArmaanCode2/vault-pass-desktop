package com.vaultpass.desktop.ui.viewmodels

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.sync.DesktopPairKeyProtector
import com.vaultpass.desktop.data.sync.FakeCryptoManager
import com.vaultpass.desktop.data.sync.LanDiscoveryManager
import com.vaultpass.desktop.data.sync.LanSocketTransport
import com.vaultpass.desktop.data.sync.PairedDeviceRepository
import com.vaultpass.desktop.data.sync.ScriptedPhone
import com.vaultpass.desktop.data.sync.applyPlanToRecords
import com.vaultpass.desktop.data.sync.pairTestDevice
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.errors.DatabaseError
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.sync.SyncState
import com.vaultpass.desktop.domain.sync.models.SyncFrame
import com.vaultpass.synccore.HandshakeInitiator
import com.vaultpass.synccore.HandshakeMode
import com.vaultpass.synccore.Hex
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.RecordCipher
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import com.vaultpass.synccore.SyncSessionState
import com.vaultpass.synccore.WireV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * End-to-end checks of LAN sync protocol v2 on the desktop, over real sockets, against a scripted
 * phone built from the shared protocol core.
 */
class SyncSessionSecurityTest {

    private val port = 54871
    private val vaultSecret = "TopSecret-Password-123"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var tempDb: File
    private lateinit var connectionManager: SQLiteConnectionManager
    private lateinit var pairedDeviceRepo: PairedDeviceRepository
    private lateinit var crypto: FakeCryptoManager
    private lateinit var protector: DesktopPairKeyProtector
    private lateinit var discovery: LanDiscoveryManager
    private lateinit var transport: LanSocketTransport
    private lateinit var vmScope: CoroutineScope
    private lateinit var repo: FakeVaultRepository
    private lateinit var viewModel: SyncViewModel

    @BeforeEach
    fun setup() {
        tempDb = File.createTempFile("test_sync_security_", ".db")
        connectionManager = SQLiteConnectionManager(tempDb.absolutePath)
        connectionManager.openConnection()
        pairedDeviceRepo = PairedDeviceRepository(connectionManager)
        crypto = FakeCryptoManager()
        protector = DesktopPairKeyProtector(crypto)
        discovery = LanDiscoveryManager(
            deviceId = "desktop-under-test",
            deviceName = "Desktop Under Test",
            pairedDeviceRepository = pairedDeviceRepo
        )
        transport = LanSocketTransport(
            localDeviceId = "desktop-under-test",
            localDeviceName = "Desktop Under Test",
            pairedDeviceRepository = pairedDeviceRepo,
            pairKeyProtector = protector,
            port = port
        )
        repo = FakeVaultRepository().apply {
            entries.add(
                VaultEntry(
                    id = "local-1",
                    title = "Bank",
                    username = "alice",
                    secret = vaultSecret,
                    createdAt = 1000L,
                    updatedAt = 1000L
                )
            )
        }
        vmScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        viewModel = SyncViewModel(
            pairedDeviceRepository = pairedDeviceRepo,
            lanDiscoveryManager = discovery,
            lanSocketTransport = transport,
            vaultRepository = repo,
            coroutineScope = vmScope,
            ioDispatcher = Dispatchers.IO
        )
        // The listener only runs while the Sync page is open.
        viewModel.startNetworking()
    }

    @AfterEach
    fun tearDown() {
        vmScope.cancel()
        discovery.stop()
        transport.close()
        connectionManager.closeConnection()
        tempDb.delete()
    }

    // --- Devices that must get nothing ---

    @Test
    fun v1Peer_isDropped_andTheUserIsToldToUpdate() {
        connectRaw().use { socket ->
            val bytes = """{"type":"SyncRequest","deviceId":"old-phone","deviceName":"Old"}""".toByteArray()
            DataOutputStream(socket.getOutputStream()).apply {
                writeByte(0)
                writeInt(bytes.size)
                write(bytes)
                flush()
            }
            assertEquals(0, drainUntilClosed(socket).size, "Desktop must not answer")
        }
        assertTrue(waitUntil { viewModel.activeError.value?.first == "Update Needed" })
        assertFalse(viewModel.isIncomingSyncDialogOpen.value)
    }

    @Test
    fun unknownDevice_getsNoHandshakeReply() = runBlocking {
        pairTestDevice(pairedDeviceRepo, protector, "phone-1", "Pixel 8")
        connectRaw().use { socket ->
            sendHello(socket, HandshakeMode.SYNC, SyncCrypto.randomBytes(32))
            assertEquals(0, drainUntilClosed(socket).size, "Desktop must not answer a device it isn't paired with")
        }
        assertFalse(viewModel.isIncomingSyncDialogOpen.value)
    }

    @Test
    fun lockedVault_refusesSyncHandshakes() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "phone-1", "Pixel 8")
        crypto.locked = true
        connectRaw().use { socket ->
            sendHello(socket, HandshakeMode.SYNC, key)
            assertEquals(0, drainUntilClosed(socket).size)
        }
    }

    @Test
    fun pairingWithoutAnOpenQrCode_isRefused() {
        assertFalse(transport.isExpectingPairing)
        connectRaw().use { socket ->
            sendHello(socket, HandshakeMode.PAIR, SyncCrypto.randomBytes(32))
            assertEquals(0, drainUntilClosed(socket).size)
        }
        assertNull(viewModel.pendingPairingInfo.value)
    }

    @Test
    fun pairingWithTheWrongSecret_isRefusedWhileTheQrCodeIsOpen() {
        viewModel.startQrPairing()
        assertTrue(transport.isExpectingPairing)
        connectRaw().use { socket ->
            sendHello(socket, HandshakeMode.PAIR, SyncCrypto.randomBytes(32))
            assertEquals(0, drainUntilClosed(socket).size)
        }
        assertNull(viewModel.pendingPairingInfo.value)
        assertTrue(viewModel.isQrDialogOpen.value)

        viewModel.dismissQrPairing()
        assertFalse(transport.isExpectingPairing)
    }

    // --- Keep-alive ---

    @Test
    fun keepAliveOnAPairingConnection_isRefused() {
        val qrSecret = SyncCrypto.randomBytes(32)
        transport.setPairingSecret(qrSecret)
        ScriptedPhone.connect(port, HandshakeMode.PAIR, qrSecret).use { phone ->
            phone.send(SyncFrame.KeepAlive(System.currentTimeMillis()))
            assertEquals(0, drainUntilClosed(phone.socket).size)
        }
        assertNull(viewModel.pendingPairingInfo.value)
    }

    @Test
    fun keepAliveBeforeTheHandshake_isRefused() {
        connectRaw().use { socket ->
            val frame = json.encodeToString(SyncFrame.serializer(), SyncFrame.KeepAlive(1L)).toByteArray()
            WireV2.write(DataOutputStream(socket.getOutputStream()), WireV2.TYPE_RECORD, frame)
            assertEquals(0, drainUntilClosed(socket).size, "Desktop must not answer")
        }
        assertFalse(viewModel.isIncomingSyncDialogOpen.value)
        assertFalse(transport.isConnected)
    }

    // --- Pairing ---

    @Test
    fun pairingWithTheQrSecret_storesTheSealedPairKey() = runBlocking {
        val qrSecret = SyncCrypto.randomBytes(32)
        transport.setPairingSecret(qrSecret) // what startQrPairing() does with its random secret

        ScriptedPhone.connect(port, HandshakeMode.PAIR, qrSecret).use { phone ->
            phone.send(SyncFrame.PairingInfo(deviceId = "phone-new", deviceName = "Pixel 9"))
            assertTrue(waitUntil { viewModel.pendingPairingInfo.value != null }, "Pairing prompt never appeared")
            assertEquals("Pixel 9", viewModel.pendingPairingInfo.value?.deviceName)

            viewModel.acceptPairing()
            assertEquals(SyncFrame.PairingAcceptance(deviceId = "desktop-under-test", isAccepted = true), phone.receive())
            assertTrue(waitUntil { viewModel.syncState.value == SyncState.PAIRED }, "Desktop never finished pairing")
            assertTrue(waitUntil { transport.pairingSaveJob?.isCompleted == true })

            val saved = pairedDeviceRepo.getPairedDevice("phone-new")!!
            val pairKey = phone.channel.pairKey!!
            assertArrayEquals(pairKey, protector.open(saved.sharedSecret))
            assertFalse(saved.sharedSecret.contains(Hex.encode(pairKey)), "The pair key must be stored sealed")
            assertFalse(pairKey.contentEquals(qrSecret), "The pair key must not be the QR secret")
        }
        // A QR code pairs one device.
        assertTrue(waitUntil { !transport.isExpectingPairing })
    }

    @Test
    fun declinedPairing_storesNothing() = runBlocking {
        val qrSecret = SyncCrypto.randomBytes(32)
        transport.setPairingSecret(qrSecret)
        ScriptedPhone.connect(port, HandshakeMode.PAIR, qrSecret).use { phone ->
            phone.send(SyncFrame.PairingInfo(deviceId = "phone-new", deviceName = "Pixel 9"))
            assertTrue(waitUntil { viewModel.pendingPairingInfo.value != null })
            viewModel.declinePairing()
            assertEquals(SyncFrame.PairingAcceptance(deviceId = "desktop-under-test", isAccepted = false), phone.receive())
        }
        assertTrue(pairedDeviceRepo.getPairedDevices().isEmpty())
    }

    @Test
    fun pairingWhileTheKeyCantBeStored_isRefusedAndReported() = runBlocking {
        val qrSecret = SyncCrypto.randomBytes(32)
        transport.setPairingSecret(qrSecret)
        ScriptedPhone.connect(port, HandshakeMode.PAIR, qrSecret).use { phone ->
            phone.send(SyncFrame.PairingInfo(deviceId = "phone-new", deviceName = "Pixel 9"))
            assertTrue(waitUntil { viewModel.pendingPairingInfo.value != null })
            crypto.locked = true
            viewModel.acceptPairing()
            assertEquals(SyncFrame.PairingAcceptance(deviceId = "desktop-under-test", isAccepted = false), phone.receive())
        }
        assertTrue(waitUntil { viewModel.activeError.value?.first == "Pairing Failed" })
        assertTrue(pairedDeviceRepo.getPairedDevices().isEmpty())
    }

    // --- Syncing with a paired phone ---

    @Test
    fun pairedPhone_getsTheVaultOnlyAfterTheUserApproves() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "phone-1", "Pixel 8")
        ScriptedPhone.connect(port, HandshakeMode.SYNC, key).use { phone ->
            phone.send(SyncFrame.SyncRequest(deviceId = "phone-1", deviceName = "Pixel 8"))
            assertTrue(waitUntil { viewModel.isIncomingSyncDialogOpen.value }, "Incoming sync dialog never opened")

            // Nothing arrives until the user approves.
            phone.socket.soTimeout = 700
            assertThrows(SocketTimeoutException::class.java) { phone.receive() }
            phone.socket.soTimeout = 5000

            viewModel.acceptIncomingRequest().join()
            assertEquals(SyncFrame.SyncAcceptance(deviceId = "desktop-under-test", isAccepted = true), phone.receive())

            phone.send(SyncFrame.PayloadBatch(encryptedBatchJson = batchJson("Phone Mail", "phone-pass")))
            val desktopBatch = phone.receive() as SyncFrame.PayloadBatch
            assertTrue(desktopBatch.encryptedBatchJson.contains(vaultSecret), "Desktop records missing from reply")
            // The phone started the sync, so it reviews; the desktop waits for its plan.
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })
            assertFalse(viewModel.isSyncReviewOpen.value)
            assertNull(viewModel.activeDiffResult.value)
        }
    }

    @Test
    fun mergePlanBeforeApproval_endsTheSession() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "phone-1", "Pixel 8")
        ScriptedPhone.connect(port, HandshakeMode.SYNC, key).use { phone ->
            phone.send(SyncFrame.SyncRequest(deviceId = "phone-1", deviceName = "Pixel 8"))
            assertTrue(waitUntil { viewModel.isIncomingSyncDialogOpen.value })
            phone.sendPlan(listOf(PlanItem("local-1", PlanAction.RECYCLE)), "fp")
            assertEquals(0, drainUntilClosed(phone.socket).size)
        }
        assertTrue(waitUntil { !viewModel.isIncomingSyncDialogOpen.value })
        assertTrue(repo.entries.none { it.isDeleted }, "An unapproved plan must never be applied")
    }

    @Test
    fun vaultDataBeforeApproval_endsTheSession() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "phone-1", "Pixel 8")
        ScriptedPhone.connect(port, HandshakeMode.SYNC, key).use { phone ->
            phone.send(SyncFrame.SyncRequest(deviceId = "phone-1", deviceName = "Pixel 8"))
            assertTrue(waitUntil { viewModel.isIncomingSyncDialogOpen.value })
            phone.send(SyncFrame.PayloadBatch(encryptedBatchJson = batchJson("Injected", "evil")))
            assertEquals(0, drainUntilClosed(phone.socket).size)
        }
        assertTrue(waitUntil { !viewModel.isIncomingSyncDialogOpen.value })
        assertFalse(viewModel.isSyncReviewOpen.value)
    }

    @Test
    fun syncRequestClaimingAnotherDevice_isRejected() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "phone-1", "Pixel 8")
        pairTestDevice(pairedDeviceRepo, protector, "phone-2", "Galaxy")
        ScriptedPhone.connect(port, HandshakeMode.SYNC, key).use { phone ->
            phone.send(SyncFrame.SyncRequest(deviceId = "phone-2", deviceName = "Galaxy"))
            assertEquals(0, drainUntilClosed(phone.socket).size)
        }
        assertFalse(viewModel.isIncomingSyncDialogOpen.value)
    }

    @Test
    fun replayedRecord_endsTheSession() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "phone-1", "Pixel 8")
        connectRaw().use { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val handshake = HandshakeInitiator(HandshakeMode.SYNC, key)
            WireV2.write(output, WireV2.TYPE_HELLO, handshake.helloBytes)
            val keys = handshake.finish(WireV2.read(input).payload)
            val record = RecordCipher(keys.sendKey, keys.receiveKey).seal(
                json.encodeToString(SyncFrame.serializer(), SyncFrame.SyncRequest("phone-1", "Pixel 8")).toByteArray()
            )
            WireV2.write(output, WireV2.TYPE_RECORD, record)
            assertTrue(waitUntil { viewModel.isIncomingSyncDialogOpen.value }, "The first copy is genuine")

            WireV2.write(output, WireV2.TYPE_RECORD, record) // replay
            assertEquals(0, drainUntilClosed(socket).size)
        }
        assertTrue(waitUntil { !viewModel.isIncomingSyncDialogOpen.value })
    }

    @Test
    fun secondDevice_cantTakeOverAnActiveSession() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "phone-1", "Pixel 8")
        ScriptedPhone.connect(port, HandshakeMode.SYNC, key).use { phone ->
            phone.send(SyncFrame.SyncRequest(deviceId = "phone-1", deviceName = "Pixel 8"))
            assertTrue(waitUntil { viewModel.isIncomingSyncDialogOpen.value })

            connectRaw().use { intruder ->
                assertEquals(0, drainUntilClosed(intruder).size, "The intruder is turned away")
            }
            assertTrue(viewModel.isIncomingSyncDialogOpen.value, "The real session is untouched")
            viewModel.acceptIncomingRequest().join()
            assertEquals(SyncFrame.SyncAcceptance(deviceId = "desktop-under-test", isAccepted = true), phone.receive())
        }
    }

    // --- Syncs started on the desktop ---

    @Test
    fun desktopStartedSync_completesWithThePairedPhone() = runBlocking {
        ServerSocket(0).use { fakePhone ->
            val key = pairTestDevice(pairedDeviceRepo, protector, "phone-2", "Galaxy", "127.0.0.1", fakePhone.localPort)
            viewModel.initiateSyncWith(pairedDeviceRepo.getPairedDevice("phone-2")!!)

            ScriptedPhone.acceptSync(fakePhone, key)!!.use { phone ->
                assertEquals(SyncFrame.SyncRequest("desktop-under-test", "Desktop Under Test"), phone.receive())
                phone.send(SyncFrame.SyncAcceptance(deviceId = "phone-2", isAccepted = true))

                val desktopBatch = phone.receive() as SyncFrame.PayloadBatch
                assertTrue(desktopBatch.encryptedBatchJson.contains(vaultSecret))

                val phoneVault = SyncRecords.decodeChunk(batchJson("Phone Notes", "pw"))
                phone.sendRecords(phoneVault)
                assertTrue(waitUntil { viewModel.isSyncReviewOpen.value }, "Review never opened")

                viewModel.confirmAndMerge { }!!.join()
                val (plan, expected) = phone.receivePlan()
                phone.send(SyncFrame.MergePlanDecision(accepted = true))
                val phoneAfter = applyPlanToRecords(phoneVault, plan)
                assertEquals(expected, phone.expect<SyncFrame.SyncFinished>().fingerprint)
                phone.send(SyncFrame.SyncFinished(fingerprint = MergePlans.vaultFingerprint(phoneAfter), applied = plan.size))
                assertTrue(waitUntil { viewModel.statusMessage.value?.startsWith("Devices are in sync") == true })
            }
        }
        assertTrue(repo.entries.any { it.title == "Phone Notes" })
        assertTrue(pairedDeviceRepo.getPairedDevice("phone-2")!!.lastSyncAt > 0L)
    }

    @Test
    fun desktopStartedSync_refusesAPlanFromThePhone() = runBlocking {
        ServerSocket(0).use { fakePhone ->
            val key = pairTestDevice(pairedDeviceRepo, protector, "phone-2", "Galaxy", "127.0.0.1", fakePhone.localPort)
            viewModel.initiateSyncWith(pairedDeviceRepo.getPairedDevice("phone-2")!!)

            ScriptedPhone.acceptSync(fakePhone, key)!!.use { phone ->
                phone.expect<SyncFrame.SyncRequest>()
                phone.send(SyncFrame.SyncAcceptance(deviceId = "phone-2", isAccepted = true))
                phone.receiveRecords()
                // Only the device that started the sync may send a plan.
                phone.sendPlan(listOf(PlanItem("local-1", PlanAction.RECYCLE)), "fp")
                assertEquals(0, drainUntilClosed(phone.socket).size)
            }
        }
        assertTrue(waitUntil { viewModel.activeError.value != null })
        assertEquals(SyncError.PROTOCOL.message, viewModel.activeError.value?.first)
        assertEquals(SyncError.PROTOCOL, transport.lastFailure())
        assertEquals(SyncState.ERROR, viewModel.syncState.value)
        assertTrue(repo.entries.none { it.isDeleted })
    }

    @Test
    fun desktopStartedSync_refusesADecisionBeforeItsPlan() = runBlocking {
        ServerSocket(0).use { fakePhone ->
            val key = pairTestDevice(pairedDeviceRepo, protector, "phone-2", "Galaxy", "127.0.0.1", fakePhone.localPort)
            viewModel.initiateSyncWith(pairedDeviceRepo.getPairedDevice("phone-2")!!)

            ScriptedPhone.acceptSync(fakePhone, key)!!.use { phone ->
                phone.expect<SyncFrame.SyncRequest>()
                phone.send(SyncFrame.SyncAcceptance(deviceId = "phone-2", isAccepted = true))
                phone.receiveRecords()
                phone.send(SyncFrame.MergePlanDecision(accepted = true))
                assertEquals(0, drainUntilClosed(phone.socket).size)
            }
        }
        assertTrue(waitUntil { viewModel.activeError.value != null })
        assertEquals(SyncError.PROTOCOL.message, viewModel.activeError.value?.first)
    }

    @Test
    fun desktopStartedSync_toAnImpostor_sendsNothing() = runBlocking {
        ServerSocket(0).use { impostor ->
            impostor.soTimeout = 5000
            pairTestDevice(pairedDeviceRepo, protector, "phone-2", "Galaxy", "127.0.0.1", impostor.localPort)
            viewModel.initiateSyncWith(pairedDeviceRepo.getPairedDevice("phone-2")!!)

            // The impostor doesn't hold the pair key, so it can't complete the handshake.
            assertNull(ScriptedPhone.acceptSync(impostor, SyncCrypto.randomBytes(32)))
        }
        assertTrue(waitUntil { viewModel.statusMessage.value?.startsWith("Could not start a secure sync") == true })
        assertFalse(viewModel.isWaitingForRemoteApproval.value)
    }

    // --- Audit Step 1.8: nothing secret on the wire ---

    @Test
    fun captureOfAFullPairingAndSync_containsNoSecrets() = runBlocking {
        val qrSecret = SyncCrypto.randomBytes(32)
        val phonePassword = "Phone-Only-Password-987"
        RecordingProxy(port).use { proxy ->
            // Pairing, through the proxy.
            transport.setPairingSecret(qrSecret)
            val pairKey = ScriptedPhone.connect(proxy.port, HandshakeMode.PAIR, qrSecret).use { phone ->
                phone.send(SyncFrame.PairingInfo(deviceId = "phone-new", deviceName = "Pixel 9"))
                assertTrue(waitUntil { viewModel.pendingPairingInfo.value != null })
                viewModel.acceptPairing()
                assertEquals(SyncFrame.PairingAcceptance("desktop-under-test", true), phone.receive())
                phone.channel.pairKey!!
            }
            assertTrue(waitUntil { transport.pairingSaveJob?.isCompleted == true })
            assertTrue(waitUntil { !transport.isConnected })

            // A full sync, through the proxy.
            ScriptedPhone.connect(proxy.port, HandshakeMode.SYNC, pairKey).use { phone ->
                phone.send(SyncFrame.SyncRequest(deviceId = "phone-new", deviceName = "Pixel 9"))
                assertTrue(waitUntil { viewModel.isIncomingSyncDialogOpen.value })
                viewModel.acceptIncomingRequest().join()
                assertTrue(phone.receive() is SyncFrame.SyncAcceptance)
                phone.send(SyncFrame.PayloadBatch(encryptedBatchJson = batchJson("Phone Mail", phonePassword)))
                val desktopBatch = phone.receive() as SyncFrame.PayloadBatch
                assertTrue(desktopBatch.encryptedBatchJson.contains(vaultSecret), "The sync really carried the vault")
            }

            val captured = proxy.captured()
            assertTrue(captured.size > 500, "The capture should contain the whole exchange")
            // Positive control: the (public) handshake hellos of both connections are in the capture.
            assertTrue(captured.containsSequence("\"mode\":\"pair\"".toByteArray()))
            assertTrue(captured.containsSequence("\"mode\":\"sync\"".toByteArray()))
            val forbidden = mapOf(
                "QR secret" to qrSecret,
                "QR secret (hex)" to Hex.encode(qrSecret).toByteArray(),
                "pair key" to pairKey,
                "pair key (hex)" to Hex.encode(pairKey).toByteArray(),
                "desktop password" to vaultSecret.toByteArray(),
                "phone password" to phonePassword.toByteArray(),
                "entry title" to "Phone Mail".toByteArray(),
                "phone device id" to "phone-new".toByteArray(),
                "device name" to "Pixel 9".toByteArray()
            )
            for ((what, bytes) in forbidden) {
                assertFalse(captured.containsSequence(bytes), "The capture contains the $what")
            }
        }
    }

    // --- Helpers ---

    private fun connectRaw(): Socket = ScriptedPhone.connectWithRetry(port)

    private fun sendHello(socket: Socket, mode: HandshakeMode, secret: ByteArray) {
        WireV2.write(DataOutputStream(socket.getOutputStream()), WireV2.TYPE_HELLO, HandshakeInitiator(mode, secret).helloBytes)
    }

    /** Reads until the peer closes the connection and returns everything received. */
    private fun drainUntilClosed(socket: Socket): ByteArray {
        val buffer = ByteArrayOutputStream()
        val input = socket.getInputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val read = try {
                input.read(chunk)
            } catch (e: SocketTimeoutException) {
                fail<Unit>("Connection was not closed by the desktop")
                -1
            } catch (e: java.io.IOException) {
                -1 // connection reset also counts as closed
            }
            if (read < 0) break
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun batchJson(title: String, password: String): String =
        SyncRecords.encodeChunks(
            listOf(SyncRecord(syncId = "phone-entry-1", title = title, username = "user", password = password, updatedAt = 2000L))
        ).single()

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun ByteArray.containsSequence(part: ByteArray): Boolean {
        if (part.isEmpty() || part.size > size) return false
        outer@ for (i in 0..(size - part.size)) {
            for (j in part.indices) {
                if (this[i + j] != part[j]) continue@outer
            }
            return true
        }
        return false
    }

    /** Forwards connections to the desktop and records every byte in both directions. */
    private class RecordingProxy(targetPort: Int) : AutoCloseable {
        private val server = ServerSocket(0)
        private val recorded = ByteArrayOutputStream()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    val upstream = Socket("127.0.0.1", targetPort)
                    pump(client, upstream)
                    pump(upstream, client)
                }
            }
        }

        private fun pump(from: Socket, to: Socket) {
            thread(isDaemon = true) {
                val buffer = ByteArray(8192)
                try {
                    val input = from.getInputStream()
                    val output = to.getOutputStream()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        synchronized(recorded) { recorded.write(buffer, 0, read) }
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    try { to.shutdownOutput() } catch (_: Exception) {}
                    try { from.close() } catch (_: Exception) {}
                }
            }
        }

        fun captured(): ByteArray = synchronized(recorded) { recorded.toByteArray() }

        override fun close() {
            server.close()
        }
    }

    private class FakeVaultRepository : VaultRepository {
        val entries = mutableListOf<VaultEntry>()

        override fun observeAllEntries(isDeleted: Boolean): Flow<RepositoryResult<List<VaultEntry>>> =
            flowOf(RepositoryResult.Success(entries.filter { it.isDeleted == isDeleted }))

        override suspend fun getAllEntries(isDeleted: Boolean): RepositoryResult<List<VaultEntry>> =
            RepositoryResult.Success(entries.filter { it.isDeleted == isDeleted })

        override fun observeQuery(query: com.vaultpass.desktop.domain.models.VaultQuery): Flow<RepositoryResult<com.vaultpass.desktop.domain.models.PagedVaultResult>> =
            flowOf(RepositoryResult.Success(com.vaultpass.desktop.domain.models.PagedVaultResult(entries, entries.size, 1, 1)))

        override suspend fun getEntryById(id: String): RepositoryResult<VaultEntry?> =
            RepositoryResult.Success(entries.find { it.id == id })

        override suspend fun getEntryHistory(id: String): RepositoryResult<List<com.vaultpass.desktop.domain.models.VaultEntryHistory>> =
            RepositoryResult.Success(emptyList())

        override suspend fun createEntry(entry: VaultEntry): RepositoryResult<Unit> {
            entries.add(entry)
            return RepositoryResult.Success(Unit)
        }

        override suspend fun updateEntry(entry: VaultEntry): RepositoryResult<Unit> {
            val idx = entries.indexOfFirst { it.id == entry.id }
            if (idx >= 0) entries[idx] = entry else entries.add(entry)
            return RepositoryResult.Success(Unit)
        }

        override suspend fun softDeleteEntry(id: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun hardDeleteEntry(id: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun archiveEntry(id: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun restoreEntry(id: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun emptyRecycleBin(): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun purgeRecycleBinOlderThan(thresholdMillis: Long): RepositoryResult<Int> = RepositoryResult.Success(0)
        override suspend fun restoreEntries(ids: List<String>): RepositoryResult<Unit> = RepositoryResult.Success(Unit)
        override suspend fun hardDeleteEntries(ids: List<String>): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

        override suspend fun getTombstones(): RepositoryResult<List<com.vaultpass.desktop.domain.models.SyncTombstone>> =
            RepositoryResult.Success(emptyList())

        override suspend fun changeEntryId(oldId: String, newId: String): RepositoryResult<Unit> = RepositoryResult.Success(Unit)

        override suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T> =
            try {
                RepositoryResult.Success(block())
            } catch (e: Exception) {
                RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Transaction failed"))
            }

        override fun clearCache() {}
    }
}
