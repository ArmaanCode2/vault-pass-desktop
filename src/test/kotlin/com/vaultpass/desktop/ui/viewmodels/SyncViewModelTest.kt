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
import com.vaultpass.synccore.HandshakeMode
import com.vaultpass.synccore.CustomFieldRecord
import com.vaultpass.synccore.EntryLimits
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import com.vaultpass.synccore.SyncSessionState
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.errors.DatabaseError
import com.vaultpass.desktop.domain.models.CustomField
import com.vaultpass.desktop.domain.models.SyncTombstone
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.sync.SyncState
import com.vaultpass.desktop.domain.sync.diff.*
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.desktop.domain.sync.models.SyncFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class SyncViewModelTest {

    private val port = 54853 // Ephemeral port to avoid conflicts

    private lateinit var tempDb: File
    private lateinit var connectionManager: SQLiteConnectionManager
    private lateinit var pairedDeviceRepo: PairedDeviceRepository
    private lateinit var lanDiscoveryManager: LanDiscoveryManager
    private lateinit var lanSocketTransport: LanSocketTransport
    private lateinit var protector: DesktopPairKeyProtector
    private lateinit var fakeVaultRepo: FakeTestVaultRepository
    private lateinit var viewModel: SyncViewModel

    @BeforeEach
    fun setup() {
        tempDb = File.createTempFile("test_sync_vm_", ".db")
        connectionManager = SQLiteConnectionManager(tempDb.absolutePath)
        connectionManager.openConnection()
        pairedDeviceRepo = PairedDeviceRepository(connectionManager)

        lanDiscoveryManager = LanDiscoveryManager(
            deviceId = "test-desktop-id",
            deviceName = "Test Desktop",
            pairedDeviceRepository = pairedDeviceRepo
        )

        protector = DesktopPairKeyProtector(FakeCryptoManager())
        fakeVaultRepo = FakeTestVaultRepository()
        buildViewModel()
    }

    /**
     * A fresh transport and view model; [timeoutMsFor] shortens the session's time limits,
     * [idleMs] and [keepAliveMs] the socket's.
     */
    private fun buildViewModel(
        idleMs: Int = LanSocketTransport.IDLE_READ_TIMEOUT_MS,
        keepAliveMs: Long = LanSocketTransport.KEEP_ALIVE_MS,
        timeoutMsFor: (SyncSessionState) -> Long? = { it.timeoutMs },
        vaultRepository: VaultRepository = fakeVaultRepo
    ) {
        lanSocketTransport = LanSocketTransport(
            localDeviceId = "test-desktop-id",
            localDeviceName = "Test Desktop",
            pairedDeviceRepository = pairedDeviceRepo,
            pairKeyProtector = protector,
            port = port,
            idleReadTimeoutMs = idleMs,
            keepAliveMs = keepAliveMs
        )
        viewModel = SyncViewModel(
            pairedDeviceRepository = pairedDeviceRepo,
            lanDiscoveryManager = lanDiscoveryManager,
            lanSocketTransport = lanSocketTransport,
            vaultRepository = vaultRepository,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            timeoutMsFor = timeoutMsFor
        )
    }

    @AfterEach
    fun tearDown() {
        lanDiscoveryManager.stop()
        lanSocketTransport.close()
        connectionManager.closeConnection()
        tempDb.delete()
    }

    @Test
    fun testListenerRunsOnlyWhileSyncPageIsOpen() {
        assertFalse(canConnect(), "Nothing may listen before the Sync page opens")

        viewModel.startNetworking()
        assertTrue(canConnect())

        viewModel.stopNetworking()
        assertFalse(canConnect())

        // Quick open/close cycles must not leave a listener behind.
        repeat(5) {
            viewModel.startNetworking()
            viewModel.stopNetworking()
        }
        assertFalse(canConnect())
    }

    @Test
    fun testStopNetworkingClosesQrPairing() {
        viewModel.startNetworking()
        viewModel.startQrPairing()
        assertTrue(lanSocketTransport.isExpectingPairing)

        viewModel.stopNetworking()
        assertFalse(viewModel.isQrDialogOpen.value)
        assertNull(viewModel.qrCodeBitmap.value)
        assertFalse(lanSocketTransport.isExpectingPairing)
    }

    @Test
    fun testVaultLockDropsTheReview() {
        val device = PairedDevice(deviceId = "lock-test", deviceName = "Phone", sharedSecret = "sec", pairedAt = 1000L)
        viewModel.setSimulatedDiffForTesting(SyncDiffEngine.computeDiff(emptyList(), emptyList()), device)
        assertTrue(viewModel.isSyncReviewOpen.value)

        viewModel.onVaultLocked()
        assertFalse(viewModel.isSyncReviewOpen.value)
        assertNull(viewModel.activeDiffResult.value)
        assertNull(viewModel.activeSyncingDevice.value)
        assertEquals(SyncError.VAULT_LOCKED.message, viewModel.activeError.value?.first)
    }

    private fun canConnect(): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
        true
    } catch (e: Exception) {
        false
    }

    @Test
    fun testQrPairingLifecycle() {
        assertFalse(viewModel.isQrDialogOpen.value)
        assertNull(viewModel.qrCodeBitmap.value)

        // 1. Start QR pairing
        viewModel.startQrPairing()
        assertTrue(viewModel.isQrDialogOpen.value)
        assertNotNull(viewModel.qrCodeBitmap.value)

        // 2. Dismiss QR pairing
        viewModel.dismissQrPairing()
        assertFalse(viewModel.isQrDialogOpen.value)
        assertNull(viewModel.qrCodeBitmap.value)
    }

    @Test
    fun testPairedDevicesAndUnpair() = runBlocking {
        pairTestDevice(pairedDeviceRepo, protector, "pixel-8-test", "Pixel 8", "192.168.1.150")
        viewModel.refreshDevices()

        // Verify device is retrieved in repository
        val list = pairedDeviceRepo.getPairedDevices()
        assertEquals(1, list.size)
        assertEquals("Pixel 8", list[0].deviceName)

        // Unpair device
        viewModel.unpairDevice("pixel-8-test").join()
        val remaining = pairedDeviceRepo.getPairedDevices()
        assertTrue(remaining.isEmpty())
        assertEquals("Device unpaired", viewModel.statusMessage.value)

        viewModel.clearStatusMessage()
        assertNull(viewModel.statusMessage.value)
    }

    @Test
    fun testUnpairWhileLockedSaysSoInsteadOfCrashing() = runBlocking {
        pairTestDevice(pairedDeviceRepo, protector, "pixel-8-test", "Pixel 8", "192.168.1.150")
        connectionManager.closeConnection()

        viewModel.unpairDevice("pixel-8-test").join()
        assertEquals("The vault is locked. Unlock it, then unpair the device again.", viewModel.statusMessage.value)

        connectionManager.openConnection()
        assertEquals(listOf("pixel-8-test"), pairedDeviceRepo.getPairedDevices().map { it.deviceId })
    }

    /**
     * The real SQLite vault on this test's connection; [onRead] runs before each entry list is
     * read, [onCreate] before each entry is created.
     */
    private inner class LockableVault(
        val crypto: FakeCryptoManager = FakeCryptoManager(),
        private val real: VaultRepository = com.vaultpass.desktop.data.repository.VaultRepositoryImpl(
            com.vaultpass.desktop.data.database.SQLiteVaultDataSource(connectionManager),
            crypto
        )
    ) : VaultRepository by real {
        @Volatile var onRead: (() -> Unit)? = null
        @Volatile var onCreate: (() -> Unit)? = null

        /** What SessionManagerImpl.lock() does, in its order: close the database, then wipe the key. */
        fun lock() {
            connectionManager.closeConnection()
            crypto.locked = true
        }

        fun unlock() {
            connectionManager.openConnection()
            crypto.locked = false
        }

        override suspend fun getAllEntries(isDeleted: Boolean): RepositoryResult<List<VaultEntry>> {
            onRead?.invoke()
            return real.getAllEntries(isDeleted)
        }

        override suspend fun createEntry(entry: VaultEntry): RepositoryResult<Unit> {
            onCreate?.invoke()
            return real.createEntry(entry)
        }

        // The block's calls must go through this wrapper to reach the hooks.
        override suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T> =
            real.transaction { this@LockableVault.block() }
    }

    /** Swaps in a view model whose vault is the real SQLite one. */
    private fun useLockableVault(): LockableVault {
        lanSocketTransport.close()
        val vault = LockableVault()
        buildViewModel(vaultRepository = vault)
        return vault
    }

    @Test
    fun testALockWhileTheReviewReadsTheVaultFailsTheSync_neverAnEmptyReview() {
        val vault = useLockableVault()
        runBlocking {
            vault.createEntry(VaultEntry(id = "pc-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L))
        }

        desktopStartedSync("lock-review-peer") { phone, desktopRecords ->
            assertEquals(setOf("pc-1"), desktopRecords.map { it.syncId }.toSet())
            // The vault locks just as the review starts reading it: a closed database reads as
            // empty, which must never become a review (or a plan) of an empty vault.
            vault.onRead = {
                vault.onRead = null
                vault.lock()
            }
            phone.sendRecords(listOf(SyncRecord(syncId = "phone-1", title = "AWS", username = "root", password = "x", updatedAt = 2L)))
            assertEquals(SyncError.VAULT_LOCKED.code, phone.expect<SyncFrame.CancelSync>().code)
        }
        assertEquals(SyncSessionState.FAILED, viewModel.sessionState.value)
        assertEquals(SyncError.VAULT_LOCKED.message, viewModel.activeError.value?.first)
        assertNull(viewModel.activeDiffResult.value)
        assertFalse(viewModel.isSyncReviewOpen.value)
    }

    @Test
    fun testALockWhileTheApproverReadsItsVaultSendsNoRecords() = runBlocking {
        val vault = useLockableVault()
        vault.createEntry(VaultEntry(id = "pc-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L))
        val key = pairTestDevice(pairedDeviceRepo, protector, "lock-send-peer", "Phone")
        viewModel.startNetworking()

        withApprovedSync(key, "lock-send-peer") { phone ->
            vault.onRead = { vault.lock() }
            phone.sendRecords(emptyList())
            // A CancelSync, never this PC's vault read as empty.
            assertEquals(SyncError.VAULT_LOCKED.code, phone.expect<SyncFrame.CancelSync>().code)
            // Wait for the desktop to hang up: failSync() does that from its own coroutine after the
            // CancelSync and a pause, while the rest of the PayloadBatch handling (where the session
            // used to start up again) runs straight on in the frame handler once failSync() returns.
            phone.socket.soTimeout = 5_000
            val hangUp = runCatching { while (true) phone.receive() }.exceptionOrNull()
            assertFalse(hangUp is java.net.SocketTimeoutException, "The desktop must hang up after its CancelSync")
        }
        assertEquals(SyncSessionState.FAILED, viewModel.sessionState.value, "The failed session must not start up again")
        assertEquals(SyncError.VAULT_LOCKED.message, viewModel.activeError.value?.first)
        assertFalse(viewModel.isAwaitingPeerReview.value)
    }

    @Test
    fun testALockDuringTheMergeRollsItBack_andReportsVaultLocked() = runBlocking {
        val vault = useLockableVault()
        vault.createEntry(VaultEntry(id = "pc-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L))
        val key = pairTestDevice(pairedDeviceRepo, protector, "lock-merge-peer", "Phone")
        viewModel.startNetworking()
        val phoneVault = listOf(SyncRecord(syncId = "phone-1", title = "AWS", password = "aws", updatedAt = 2L))
        val plan = listOf(
            PlanItem("phone-1", PlanAction.UPSERT, phoneVault.single()),
            PlanItem("pc-1", PlanAction.RECYCLE, deletedAt = 900L)
        )

        withApprovedSync(key, "lock-merge-peer") { phone ->
            phone.sendRecords(phoneVault)
            phone.receiveRecords()
            phone.sendPlan(plan, MergePlans.vaultFingerprint(applyPlanToRecords(phoneVault, plan)))
            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })

            // The vault locks in the middle of the merge, as its first write starts: that write's
            // encryption then fails on the wiped key, which must still read as "locked".
            vault.onCreate = {
                vault.onCreate = null
                vault.lock()
            }
            runBlocking { viewModel.approvePlan()!!.join() }
            assertEquals(SyncFrame.MergePlanDecision(accepted = true), phone.receive())
            assertEquals(SyncError.VAULT_LOCKED.code, phone.expect<SyncFrame.SyncFinished>().code)
        }
        assertEquals(SyncSessionState.FAILED, viewModel.sessionState.value)
        assertEquals(SyncError.VAULT_LOCKED.message, viewModel.activeError.value?.first)

        // Nothing of the merge was kept.
        vault.unlock()
        val active = (vault.getAllEntries(false) as RepositoryResult.Success).data
        assertEquals(listOf("pc-1"), active.map { it.id })
        assertFalse(active.single().isDeleted)
        assertTrue((vault.getAllEntries(true) as RepositoryResult.Success).data.isEmpty())
    }

    @Test
    fun testIncomingSyncRequestDecline() = runBlocking {
        assertFalse(viewModel.isIncomingSyncDialogOpen.value)
        val key = pairTestDevice(pairedDeviceRepo, protector, "peer-phone-1", "Galaxy S24")
        viewModel.startNetworking()

        ScriptedPhone.connect(port, HandshakeMode.SYNC, key).use { phone ->
            phone.send(SyncFrame.SyncRequest(deviceId = "peer-phone-1", deviceName = "Galaxy S24"))
            assertTrue(waitUntil { viewModel.isIncomingSyncDialogOpen.value })
            assertEquals("Galaxy S24", viewModel.activeSyncingDevice.value?.deviceName)

            // Decline request
            viewModel.declineIncomingRequest().join()
            assertEquals(SyncFrame.SyncAcceptance(deviceId = "test-desktop-id", isAccepted = false), phone.receive())
            assertFalse(viewModel.isIncomingSyncDialogOpen.value)
            assertNull(viewModel.activeSyncingDevice.value)
        }
    }

    @Test
    fun testIncomingSyncRequestFromUnpairedDeviceShowsNoDialog() {
        viewModel.startNetworking()
        // A device without a pair key can't even complete the handshake.
        assertThrows(Exception::class.java) {
            ScriptedPhone.connect(port, HandshakeMode.SYNC, SyncCrypto.randomBytes(32))
        }
        assertFalse(viewModel.isIncomingSyncDialogOpen.value)
        assertNull(lanSocketTransport.pendingSyncRequest.value)
        assertEquals(LanSocketTransport.SyncRole.NONE, lanSocketTransport.syncRole)
    }

    @Test
    fun testPayloadBatchOutsideSyncSessionIsIgnored() {
        lanSocketTransport.emitFrameForTesting(SyncFrame.PayloadBatch(encryptedBatchJson = "[]"))
        assertFalse(viewModel.isSyncReviewOpen.value)
        assertNull(viewModel.activeDiffResult.value)
    }

    // --- The review ---

    @Test
    fun testReviewOutcomesAndFieldOverrides() {
        val localEntry = VaultEntry(
            id = "1",
            title = "GitHub",
            username = "octocat",
            secret = "old-local-pass",
            url = "https://github.com",
            notes = "Local note",
            category = "Dev",
            tags = listOf("git", "work"),
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val remoteEntry = VaultEntry(
            id = "1",
            title = "GitHub",
            username = "octocat",
            secret = "new-phone-pass",
            url = "https://github.company.com",
            notes = "Updated on phone",
            category = "Work",
            tags = listOf("git", "enterprise"),
            createdAt = 1000L,
            updatedAt = 2000L
        )

        val diffResult = SyncDiffEngine.computeDiff(listOf(localEntry), listOf(SyncRecordMapper.toRecord(remoteEntry)))
        val testDevice = PairedDevice(
            deviceId = "test-device-id",
            deviceName = "Test Phone",
            sharedSecret = "sec",
            ipAddress = "192.168.1.10",
            pairedAt = 1000L
        )

        viewModel.setSimulatedDiffForTesting(diffResult, testDevice)
        assertTrue(viewModel.isSyncReviewOpen.value)
        val syncKey = diffResult.diffItems[0].syncKey
        fun item() = viewModel.activeDiffResult.value!!.diffItems[0]

        // 1. The newer phone version is the default, and only the offered choices are accepted.
        assertEquals(ItemOutcome.USE_REMOTE, item().outcome)
        assertEquals(listOf(ItemOutcome.USE_REMOTE, ItemOutcome.USE_LOCAL, ItemOutcome.SKIP), item().choices)
        viewModel.setOutcome(syncKey, ItemOutcome.DELETE_BOTH)
        assertEquals(ItemOutcome.USE_REMOTE, item().outcome, "Not offered for this change")

        // 2. Field overrides start from the chosen version.
        viewModel.updateEntryFieldOverride(syncKey, "password", "custom-overridden-pass")
        assertEquals("custom-overridden-pass", item().editedEntry?.secret)
        assertEquals("https://github.company.com", item().editedEntry?.url, "Based on the phone's version")

        viewModel.updateEntryFieldOverride(syncKey, "title", "Custom Title")
        assertEquals("Custom Title", item().editedEntry?.title)

        viewModel.updateEntryFieldOverride(syncKey, "username", "custom-user")
        assertEquals("custom-user", item().editedEntry?.username)

        viewModel.updateEntryFieldOverride(syncKey, "url", "https://custom.url")
        assertEquals("https://custom.url", item().editedEntry?.url)

        viewModel.updateEntryFieldOverride(syncKey, "notes", "Custom notes")
        assertEquals("Custom notes", item().editedEntry?.notes)

        viewModel.updateEntryFieldOverride(syncKey, "category", "Personal")
        assertEquals("Personal", item().editedEntry?.category)

        viewModel.updateEntryFieldOverride(syncKey, "tags", "tag1, tag2, tag3")
        assertEquals(listOf("tag1", "tag2", "tag3"), item().editedEntry?.tags)

        // 3. Choosing the other version drops the edits made to the first one.
        viewModel.setOutcome(syncKey, ItemOutcome.USE_LOCAL)
        assertNull(item().editedEntry)
        assertEquals("old-local-pass", item().chosenEntry?.secret)
        viewModel.updateEntryFieldOverride(syncKey, "notes", "Edited PC note")
        assertEquals("old-local-pass", item().editedEntry?.secret, "Based on this PC's version")

        // 4. Discarding edits, and no edits while nothing is stored.
        viewModel.discardEdits(syncKey)
        assertNull(item().editedEntry)
        viewModel.setOutcome(syncKey, ItemOutcome.SKIP)
        viewModel.updateEntryFieldOverride(syncKey, "title", "Ignored")
        assertNull(item().editedEntry, "Leaving an entry unchanged leaves nothing to edit")
        assertEquals(0, viewModel.activeDiffResult.value!!.plannedCount)
    }

    @Test
    fun testConflictBlocksTheMergeUntilAnswered() = runBlocking {
        val local = VaultEntry(id = "e-1", title = "Bank", username = "me", secret = "pc", createdAt = 1L, updatedAt = 6_000L)
        val remote = SyncRecord(syncId = "e-1", title = "Bank", username = "me", password = "phone", updatedAt = 7_000L)
        val diff = SyncDiffEngine.computeDiff(listOf(local), listOf(remote), lastSyncAt = 5_000L)
        val device = PairedDevice(deviceId = "conflict-test", deviceName = "Phone", sharedSecret = "sec", pairedAt = 1L)
        viewModel.setSimulatedDiffForTesting(diff, device)
        assertEquals(1, viewModel.activeDiffResult.value!!.unansweredCount)

        assertNull(viewModel.confirmAndMerge { }, "A conflict must be answered first")
        assertTrue(viewModel.isSyncReviewOpen.value)
        assertEquals(SyncSessionState.REVIEWING, viewModel.sessionState.value)

        viewModel.setOutcome("e-1", ItemOutcome.USE_LOCAL)
        assertEquals(0, viewModel.activeDiffResult.value!!.unansweredCount)
        // Answered: the merge goes ahead (and, with no phone connected here, ends visibly).
        val job = viewModel.confirmAndMerge { }
        assertNotNull(job)
        job!!.join()
        assertFalse(viewModel.isSyncReviewOpen.value)
        assertTrue(waitUntil { viewModel.activeError.value?.first == SyncError.CONNECTION_LOST.message })
    }

    @Test
    fun testAnEditPastAFieldLimitBlocksTheMerge() {
        val remote = SyncRecord(syncId = "r-1", title = "AWS", password = "pw", updatedAt = 1L)
        val device = PairedDevice(deviceId = "limit-test", deviceName = "Phone", sharedSecret = "sec", pairedAt = 1L)
        viewModel.setSimulatedDiffForTesting(SyncDiffEngine.computeDiff(emptyList(), listOf(remote)), device)

        viewModel.updateEntryFieldOverride("r-1", "title", "t".repeat(EntryLimits.TITLE + 1))
        assertNull(viewModel.confirmAndMerge { })
        assertTrue(viewModel.isSyncReviewOpen.value)
        assertTrue(viewModel.statusMessage.value!!.contains("longer than an entry may be"))

        viewModel.updateEntryFieldOverride("r-1", "title", "t".repeat(EntryLimits.TITLE))
        assertTrue(viewModel.activeDiffResult.value!!.diffItems.single().editsFit, "The limit itself is fine")
    }

    @Test
    fun testCancelSync() {
        val testDevice = PairedDevice(
            deviceId = "cancel-test",
            deviceName = "Cancel Phone",
            sharedSecret = "sec",
            pairedAt = 1000L
        )
        val diffResult = SyncDiffEngine.computeDiff(emptyList(), emptyList())
        viewModel.setSimulatedDiffForTesting(diffResult, testDevice)

        assertTrue(viewModel.isSyncReviewOpen.value)
        assertNotNull(viewModel.activeDiffResult.value)
        assertNotNull(viewModel.activeSyncingDevice.value)

        viewModel.cancelSync()

        assertFalse(viewModel.isSyncReviewOpen.value)
        assertNull(viewModel.activeDiffResult.value)
        assertNull(viewModel.activeSyncingDevice.value)
        assertNull(viewModel.activeError.value, "The user's own cancel is not an error")
    }

    @Test
    fun testActiveErrorStateFlow() {
        assertNull(viewModel.activeError.value)

        viewModel.showError("Failed to pair", "Connection timed out after 10000ms")
        val error = viewModel.activeError.value
        assertNotNull(error)
        assertEquals("Failed to pair", error?.first)
        assertEquals("Connection timed out after 10000ms", error?.second)

        viewModel.dismissError()
        assertNull(viewModel.activeError.value)
    }

    @Test
    fun testEverySyncErrorFromThePeerReachesTheUser() {
        val device = PairedDevice(deviceId = "err-peer", deviceName = "Error Phone", sharedSecret = "sec", ipAddress = "10.0.0.7", pairedAt = 1L)
        for (error in SyncError.values()) {
            viewModel.setSimulatedDiffForTesting(SyncDiffEngine.computeDiff(emptyList(), emptyList()), device)
            lanSocketTransport.emitFrameForTesting(SyncFrame.CancelSync(reason = "whatever the peer says", code = error.code))

            assertTrue(waitUntil { viewModel.activeError.value != null }, "$error never reached the UI")
            val (message, details) = viewModel.activeError.value!!
            assertEquals(error.message, message)
            assertTrue(details!!.contains("Error Phone"), "Details name the peer")
            assertTrue(details.contains("10.0.0.7"), "Details give the peer's address")
            assertTrue(details.contains("Error code: ${error.code}"))
            assertTrue(details.contains("Session state: REVIEWING"), "Details say where the session was")
            assertEquals(SyncState.ERROR, viewModel.syncState.value)
            assertFalse(viewModel.isSyncReviewOpen.value)
            viewModel.dismissError()
        }
        // A cancel without a code is a plain cancel.
        viewModel.setSimulatedDiffForTesting(SyncDiffEngine.computeDiff(emptyList(), emptyList()), device)
        lanSocketTransport.emitFrameForTesting(SyncFrame.CancelSync(reason = "old peer"))
        assertTrue(waitUntil { viewModel.activeError.value?.first == SyncError.CANCELLED.message })
    }

    @Test
    fun testInitiateSyncAndWaitingForApproval() = runBlocking {
        // A local port with nothing listening: the connection is refused right away.
        val closedPort = ServerSocket(0).use { it.localPort }
        pairTestDevice(pairedDeviceRepo, protector, "phone-sync-test", "Pixel 8", "127.0.0.1", closedPort)

        assertFalse(viewModel.isWaitingForRemoteApproval.value)

        viewModel.initiateSyncWith(pairedDeviceRepo.getPairedDevice("phone-sync-test")!!).join()

        assertFalse(viewModel.isWaitingForRemoteApproval.value)
        assertTrue(viewModel.statusMessage.value?.contains("Could not connect to Pixel 8") == true)
    }

    @Test
    fun testCancelSyncRequest() = runBlocking {
        SilentPhone().use { silent ->
            val device = silent.pairedDevice("cancel-req-test", "Pixel Phone")
            viewModel.initiateSyncWith(device)
            assertTrue(viewModel.isWaitingForRemoteApproval.value)
            viewModel.cancelSyncRequest()

            assertFalse(viewModel.isWaitingForRemoteApproval.value)
            assertNull(viewModel.activeSyncingDevice.value)
            assertEquals("Sync cancelled", viewModel.statusMessage.value)

            // The interrupted connection attempt must not overwrite the user's cancel.
            Thread.sleep(600)
            assertEquals("Sync cancelled", viewModel.statusMessage.value)
        }
    }

    @Test
    fun testRemoteDeclineSyncRequest() = runBlocking {
        SilentPhone().use { silent ->
            val device = silent.pairedDevice("decline-test", "Tablet")
            viewModel.initiateSyncWith(device)
            // Emit remote decline frame
            lanSocketTransport.emitFrameForTesting(SyncFrame.SyncAcceptance(deviceId = "decline-test", isAccepted = false))

            assertTrue(waitUntil { viewModel.activeError.value != null })
            assertFalse(viewModel.isWaitingForRemoteApproval.value)
            assertNull(viewModel.activeSyncingDevice.value)
            assertEquals(SyncError.DECLINED.message, viewModel.activeError.value?.first)
            assertEquals(SyncState.ERROR, viewModel.syncState.value)
        }
    }

    @Test
    fun testSyncRecordMapping() {
        val entry = VaultEntry(
            id = "test-uuid-123",
            title = "Personal Google",
            username = "user@gmail.com",
            secret = "SuperSecret123!",
            url = "https://google.com",
            notes = "Work account",
            category = null,
            tags = listOf("mail", "google"),
            customFields = listOf(CustomField("PIN", "1234"), CustomField("Recovery", "blue")),
            isFavorite = true,
            createdAt = 5000L,
            updatedAt = 6000L
        )

        val record = SyncRecordMapper.toRecord(entry)
        assertEquals("test-uuid-123", record.syncId)
        assertEquals("SuperSecret123!", record.password)
        assertEquals("https://google.com", record.url)
        assertEquals("", record.category, "No category travels as an empty string")
        assertEquals(listOf(CustomFieldRecord("PIN", "1234"), CustomFieldRecord("Recovery", "blue")), record.customFields)
        assertTrue(record.favorite)
        assertEquals(6000L, record.updatedAt)
        assertFalse(record.deleted)

        val domain = SyncRecordMapper.toDomain(record)
        assertEquals(entry.copy(createdAt = 6000L), domain)
        assertNull(domain.category)
        assertEquals("Work", SyncRecordMapper.toDomain(record.copy(category = "Work")).category)
    }

    // --- This PC started the sync: it reviews ---

    @Test
    fun testTombstoneAwareDiffComputation() {
        fakeVaultRepo.entries.add(
            VaultEntry(
                id = "del-1",
                title = "OldSite",
                username = "admin",
                secret = "pass",
                isDeleted = true,
                deletedAt = 1000L,
                createdAt = 1000L,
                updatedAt = 1000L
            )
        )
        val remote = SyncRecord(syncId = "del-1", title = "OldSite", username = "admin", password = "newPass", updatedAt = 2000L)

        desktopStartedSync("tombstone-peer") { phone, _ ->
            phone.sendRecords(listOf(remote))
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
        }

        val diffResult = viewModel.activeDiffResult.value
        assertNotNull(diffResult)
        assertEquals(1, diffResult?.deletedLocalCount)
        assertEquals(0, diffResult?.newRemoteCount)

        val item = diffResult?.diffItems?.first()
        assertEquals(EntrySyncCategory.DELETED_LOCAL, item?.category)
        assertEquals(ItemOutcome.DELETE_BOTH, item?.outcome, "A tombstone is not brought back by default")
    }

    @Test
    fun testChunkedPayloadIsAssembledBeforeTheReview() {
        val remote = (1..30).map { i ->
            SyncRecord(syncId = "r-$i", title = "Site $i", username = "u$i", password = "p".repeat(200), updatedAt = 2000L)
        }
        val chunks = SyncRecords.encodeChunks(remote, maxBytes = 2_000)
        assertTrue(chunks.size > 2, "The test needs several parts")

        desktopStartedSync("chunk-peer") { phone, _ ->
            chunks.forEachIndexed { i, chunk ->
                phone.send(SyncFrame.PayloadBatch(chunk, part = i, last = i == chunks.lastIndex))
                if (i < chunks.lastIndex) {
                    Thread.sleep(50)
                    assertFalse(viewModel.isSyncReviewOpen.value, "No review before the last part")
                }
            }
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
            // The desktop sent its vault once, before the phone's parts, and nothing since.
            phone.socket.soTimeout = 500
            assertThrows(java.net.SocketTimeoutException::class.java) { phone.receive() }
        }
        val diff = viewModel.activeDiffResult.value!!
        assertEquals(30, diff.newRemoteCount)
        assertEquals((1..30).map { "r-$it" }.toSet(), diff.diffItems.map { it.syncKey }.toSet())
    }

    @Test
    fun testCorruptedEntriesAreNeverSentAndTombstonesAre() {
        fakeVaultRepo.entries += VaultEntry(id = "ok-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L)
        fakeVaultRepo.entries += VaultEntry(
            id = "bad-1", title = "Corrupted Entry", username = "", secret = "", createdAt = 1L, updatedAt = 1L, isCorrupted = true
        )
        fakeVaultRepo.entries += VaultEntry(
            id = "bin-1", title = "Old", username = "me", secret = "old-pw", isDeleted = true, deletedAt = 50L, createdAt = 1L, updatedAt = 40L
        )
        fakeVaultRepo.tombstones += SyncTombstone("gone-1", 70L)

        // The phone also has the entry this PC can't read: it must not show up in the review.
        val remote = listOf(SyncRecord(syncId = "bad-1", title = "Readable there", password = "x", updatedAt = 9L))

        desktopStartedSync("send-peer") { phone, desktopRecords ->
            val sent = desktopRecords.associateBy { it.syncId }
            assertEquals(setOf("ok-1", "bin-1", "gone-1"), sent.keys)
            assertEquals(SyncRecords.tombstone("bin-1", 50L), sent["bin-1"])
            assertEquals(SyncRecords.tombstone("gone-1", 70L), sent["gone-1"])
            assertFalse(sent.values.any { it.password == "old-pw" }, "Recycle-bin content must not be sent")
            phone.sendRecords(remote)
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
        }
        val keys = viewModel.activeDiffResult.value!!.diffItems.map { it.syncKey }
        assertFalse("bad-1" in keys)
    }

    @Test
    fun testReviewUsesThePairedDevicesLastSyncTime() {
        // Both sides changed the entry after the last sync: a conflict, not "newest wins".
        fakeVaultRepo.entries += VaultEntry(id = "e-1", title = "Bank", username = "me", secret = "pc", createdAt = 1L, updatedAt = 6_000L)
        val remote = SyncRecord(syncId = "e-1", title = "Bank", username = "me", password = "phone", updatedAt = 7_000L)

        desktopStartedSync("last-sync-peer", prepare = { pairedDeviceRepo.updateLastSync("last-sync-peer", 5_000L) }) { phone, _ ->
            phone.sendRecords(listOf(remote))
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
        }
        val item = viewModel.activeDiffResult.value!!.diffItems.single()
        assertEquals(EntrySyncCategory.CONFLICT, item.category)
        assertTrue(item.isUnanswered)
        assertEquals(1, viewModel.activeDiffResult.value!!.conflictCount)
    }

    @Test
    fun testReviewedPlanIsAppliedOnBothDevices_andMatchingFingerprintsFinish() = runBlocking {
        fakeVaultRepo.entries += VaultEntry(id = "pc-1", title = "Mail", username = "me", secret = "pc-pw", createdAt = 1L, updatedAt = 1L)
        val phoneVault = listOf(SyncRecord(syncId = "phone-1", title = "AWS", username = "root", password = "aws-pw", updatedAt = 2L))
        var mergedCount = -1

        desktopStartedSync("happy-peer") { phone, _ ->
            phone.sendRecords(phoneVault)
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
            assertEquals(2, viewModel.activeDiffResult.value!!.plannedCount)

            runBlocking { viewModel.confirmAndMerge { mergedCount = it }!!.join() }
            // One Finish: the review closes at once and no cancel is sent.
            assertFalse(viewModel.isSyncReviewOpen.value)
            val (plan, expected) = phone.receivePlan()
            assertEquals(setOf("pc-1", "phone-1"), plan.map { it.syncId }.toSet())
            assertTrue(plan.all { it.action == PlanAction.UPSERT })
            assertTrue(fakeVaultRepo.entries.none { it.id == "phone-1" }, "Nothing changes before the phone accepts")

            phone.send(SyncFrame.MergePlanDecision(accepted = true))
            val phoneAfter = applyPlanToRecords(phoneVault, plan)
            val desktopFinished = phone.expect<SyncFrame.SyncFinished>()
            assertEquals("", desktopFinished.code)
            assertEquals(expected, desktopFinished.fingerprint, "The desktop ended where its plan said it would")
            assertEquals(MergePlans.vaultFingerprint(phoneAfter), desktopFinished.fingerprint)
            phone.send(SyncFrame.SyncFinished(fingerprint = MergePlans.vaultFingerprint(phoneAfter), applied = plan.size))

            assertTrue(waitUntil { viewModel.statusMessage.value?.startsWith("Devices are in sync") == true })
        }
        assertEquals("Devices are in sync (2 entries updated)", viewModel.statusMessage.value)
        assertEquals(2, mergedCount)
        assertNull(viewModel.activeError.value)
        assertEquals("aws-pw", fakeVaultRepo.entries.single { it.id == "phone-1" }.secret)
        assertTrue(pairedDeviceRepo.getPairedDevice("happy-peer")!!.lastSyncAt > 0L)
    }

    @Test
    fun testAnEmptyPlanFinishesAndUpdatesTheLastSyncTime() = runBlocking {
        val shared = VaultEntry(id = "same-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L)
        fakeVaultRepo.entries += shared

        desktopStartedSync("empty-peer") { phone, desktopRecords ->
            phone.sendRecords(desktopRecords)
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
            assertEquals(0, viewModel.activeDiffResult.value!!.plannedCount, "\"Devices are up to date (Finish)\"")

            runBlocking { viewModel.confirmAndMerge { }!!.join() }
            val (plan, expected) = phone.receivePlan()
            assertTrue(plan.isEmpty())
            assertEquals(MergePlans.vaultFingerprint(desktopRecords), expected)
            phone.send(SyncFrame.MergePlanDecision(accepted = true))
            val desktopFinished = phone.expect<SyncFrame.SyncFinished>()
            assertEquals(0, desktopFinished.applied)
            phone.send(SyncFrame.SyncFinished(fingerprint = MergePlans.vaultFingerprint(desktopRecords)))
            assertTrue(waitUntil { viewModel.statusMessage.value?.startsWith("Devices are in sync") == true })
        }
        assertEquals("Devices are in sync (0 entries updated)", viewModel.statusMessage.value)
        assertTrue(pairedDeviceRepo.getPairedDevice("empty-peer")!!.lastSyncAt > 0L)
        assertEquals(listOf(shared), fakeVaultRepo.entries.toList())
    }

    @Test
    fun testADeclinedPlanChangesNothing() = runBlocking {
        fakeVaultRepo.entries += VaultEntry(id = "pc-1", title = "Mail", username = "me", secret = "pc-pw", createdAt = 1L, updatedAt = 1L)
        val before = fakeVaultRepo.entries.toList()

        desktopStartedSync("declining-peer") { phone, _ ->
            phone.sendRecords(listOf(SyncRecord(syncId = "phone-1", title = "AWS", password = "aws", updatedAt = 2L)))
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
            runBlocking { viewModel.confirmAndMerge { }!!.join() }
            phone.receivePlan()
            phone.send(SyncFrame.MergePlanDecision(accepted = false, code = SyncError.PLAN_REJECTED.code))
            assertTrue(waitUntil { viewModel.activeError.value != null })
        }
        assertEquals(SyncError.PLAN_REJECTED.message, viewModel.activeError.value?.first)
        assertEquals(SyncState.ERROR, viewModel.syncState.value)
        assertEquals(before, fakeVaultRepo.entries.toList())
        assertEquals(0L, pairedDeviceRepo.getPairedDevice("declining-peer")!!.lastSyncAt)
    }

    @Test
    fun testDifferentFingerprintsReportNotIdentical_andOfferSyncAgain() = runBlocking {
        val phoneVault = listOf(SyncRecord(syncId = "phone-1", title = "AWS", password = "aws", updatedAt = 2L))

        desktopStartedSync("mismatch-peer") { phone, _ ->
            phone.sendRecords(phoneVault)
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
            runBlocking { viewModel.confirmAndMerge { }!!.join() }
            phone.receivePlan()
            phone.send(SyncFrame.MergePlanDecision(accepted = true))
            phone.expect<SyncFrame.SyncFinished>()
            phone.send(SyncFrame.SyncFinished(fingerprint = "0".repeat(32), applied = 1))
            assertTrue(waitUntil { viewModel.activeError.value != null })
        }
        assertEquals(SyncError.NOT_IDENTICAL.message, viewModel.activeError.value?.first)
        assertEquals(0L, pairedDeviceRepo.getPairedDevice("mismatch-peer")!!.lastSyncAt, "No success, no new sync time")
        assertTrue(fakeVaultRepo.entries.any { it.id == "phone-1" }, "Nothing is undone")
        assertEquals("mismatch-peer", viewModel.retryDevice.value?.deviceId, "\"Sync again\" is offered")
    }

    @Test
    fun testSyncAgainRestartsTheSyncWithThatDevice() = runBlocking {
        ServerSocket(0).use { server ->
            val key = pairTestDevice(pairedDeviceRepo, protector, "again-peer", "Phone", "127.0.0.1", server.localPort)
            runDesktopStarted(server, key, "again-peer") { phone, _ ->
                phone.sendRecords(emptyList())
                assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
                runBlocking { viewModel.confirmAndMerge { }!!.join() }
                phone.receivePlan()
                phone.send(SyncFrame.MergePlanDecision(accepted = true))
                phone.expect<SyncFrame.SyncFinished>()
                phone.send(SyncFrame.SyncFinished(fingerprint = "different"))
                assertTrue(waitUntil { viewModel.retryDevice.value != null })
            }

            assertNotNull(viewModel.syncAgain())
            assertNull(viewModel.activeError.value)
            ScriptedPhone.acceptSync(server, key)!!.use { phone ->
                assertEquals(SyncFrame.SyncRequest("test-desktop-id", "Test Desktop"), phone.receive())
            }
        }
    }

    @Test
    fun testAMergeFailureOnThePhoneIsReported() {
        desktopStartedSync("failing-peer") { phone, _ ->
            phone.sendRecords(listOf(SyncRecord(syncId = "phone-1", title = "AWS", password = "aws", updatedAt = 2L)))
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
            runBlocking { viewModel.confirmAndMerge { }!!.join() }
            phone.receivePlan()
            phone.send(SyncFrame.MergePlanDecision(accepted = true))
            phone.expect<SyncFrame.SyncFinished>()
            phone.send(SyncFrame.SyncFinished(code = SyncError.MERGE_FAILED.code))
            assertTrue(waitUntil { viewModel.activeError.value != null })
        }
        assertEquals(SyncError.MERGE_FAILED.message, viewModel.activeError.value?.first)
    }

    @Test
    fun testAConnectionDroppedMidPlanReportsConnectionLost() = runBlocking {
        desktopStartedSync("dropping-peer") { phone, _ ->
            phone.sendRecords(listOf(SyncRecord(syncId = "phone-1", title = "AWS", password = "aws", updatedAt = 2L)))
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
            runBlocking { viewModel.confirmAndMerge { }!!.join() }
            phone.receivePlan()
            phone.close() // gone before answering
            assertTrue(waitUntil { viewModel.activeError.value != null })
        }
        assertEquals(SyncError.CONNECTION_LOST.message, viewModel.activeError.value?.first)
        assertTrue(viewModel.activeError.value!!.second!!.contains("Session state: AWAITING_PLAN_APPROVAL"))
        assertTrue(fakeVaultRepo.entries.none { it.id == "phone-1" })
        assertEquals(0L, pairedDeviceRepo.getPairedDevice("dropping-peer")!!.lastSyncAt)
    }

    // --- The other device started the sync: this PC approves ---

    @Test
    fun testTheApproverNeverReviews_andAcceptsAnEmptyPlanByItself() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "approve-empty", "Phone")
        viewModel.startNetworking()
        fakeVaultRepo.entries += VaultEntry(id = "same-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L)

        withApprovedSync(key, "approve-empty") { phone ->
            phone.sendRecords(emptyList())
            val desktopRecords = phone.receiveRecords()
            assertTrue(waitUntil { viewModel.sessionState.value == SyncSessionState.REVIEWING })
            assertFalse(viewModel.isSyncReviewOpen.value, "Only the device that started the sync reviews")
            assertNull(viewModel.activeDiffResult.value)

            phone.sendPlan(emptyList(), MergePlans.vaultFingerprint(desktopRecords))
            assertEquals(SyncFrame.MergePlanDecision(accepted = true), phone.receive())
            assertNull(viewModel.pendingPlanApproval.value, "Nothing to approve: no dialog")
            val desktopFinished = phone.expect<SyncFrame.SyncFinished>()
            assertEquals(MergePlans.vaultFingerprint(desktopRecords), desktopFinished.fingerprint)
            phone.send(SyncFrame.SyncFinished(fingerprint = desktopFinished.fingerprint))
            assertTrue(waitUntil { viewModel.statusMessage.value?.startsWith("Devices are in sync") == true })
        }
        assertTrue(pairedDeviceRepo.getPairedDevice("approve-empty")!!.lastSyncAt > 0L)
    }

    @Test
    fun testTheApproverAppliesThePlanAfterTheUserAccepts() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "approve-apply", "Phone")
        viewModel.startNetworking()
        fakeVaultRepo.entries += VaultEntry(id = "pc-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L)
        val phoneVault = listOf(SyncRecord(syncId = "phone-1", title = "AWS", password = "aws", updatedAt = 2L))
        val plan = listOf(
            PlanItem("phone-1", PlanAction.UPSERT, phoneVault.single()),
            PlanItem("pc-1", PlanAction.RECYCLE, deletedAt = 900L)
        )

        withApprovedSync(key, "approve-apply") { phone ->
            phone.sendRecords(phoneVault)
            phone.receiveRecords()
            val phoneAfter = applyPlanToRecords(phoneVault, plan)
            phone.sendPlan(plan, MergePlans.vaultFingerprint(phoneAfter))

            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })
            val request = viewModel.pendingPlanApproval.value!!
            assertEquals("Phone", request.peerName)
            assertEquals(1, request.summary.added)
            assertEquals(0, request.summary.updated)
            assertEquals(1, request.summary.deleted)
            assertEquals(1, fakeVaultRepo.entries.size, "Nothing changes before Apply")

            runBlocking { viewModel.approvePlan()!!.join() }
            assertEquals(SyncFrame.MergePlanDecision(accepted = true), phone.receive())
            val desktopFinished = phone.expect<SyncFrame.SyncFinished>()
            assertEquals(MergePlans.vaultFingerprint(phoneAfter), desktopFinished.fingerprint)
            assertEquals(2, desktopFinished.applied)
            phone.send(SyncFrame.SyncFinished(fingerprint = MergePlans.vaultFingerprint(phoneAfter)))
            assertTrue(waitUntil { viewModel.statusMessage.value?.startsWith("Devices are in sync") == true })
        }
        assertEquals("aws", fakeVaultRepo.entries.single { it.id == "phone-1" }.secret)
        val binned = fakeVaultRepo.entries.single { it.id == "pc-1" }
        assertTrue(binned.isDeleted)
        assertEquals(900L, binned.deletedAt)
        assertTrue(pairedDeviceRepo.getPairedDevice("approve-apply")!!.lastSyncAt > 0L)
    }

    @Test
    fun testTheApproverDecliningChangesNothing() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "approve-decline", "Phone")
        viewModel.startNetworking()
        fakeVaultRepo.entries += VaultEntry(id = "pc-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L)
        val before = fakeVaultRepo.entries.toList()

        withApprovedSync(key, "approve-decline") { phone ->
            phone.sendRecords(emptyList())
            phone.receiveRecords()
            phone.sendPlan(listOf(PlanItem("pc-1", PlanAction.RECYCLE, deletedAt = 5L)), "fp")
            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })

            runBlocking { viewModel.declinePlan().join() }
            assertEquals(SyncFrame.MergePlanDecision(accepted = false, code = SyncError.PLAN_REJECTED.code), phone.receive())
        }
        assertEquals(before, fakeVaultRepo.entries.toList())
        assertNull(viewModel.pendingPlanApproval.value)
        assertEquals(0L, pairedDeviceRepo.getPairedDevice("approve-decline")!!.lastSyncAt)
    }

    @Test
    fun testABrokenPlanIsRefusedByTheApprover() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "approve-broken", "Phone")
        viewModel.startNetworking()
        fakeVaultRepo.entries += VaultEntry(id = "pc-1", title = "Mail", username = "me", secret = "pw", createdAt = 1L, updatedAt = 1L)
        val before = fakeVaultRepo.entries.toList()
        val broken = listOf(
            PlanItem("pc-1", PlanAction.UPSERT, SyncRecord(syncId = "pc-1", title = "Evil", password = "evil")),
            PlanItem("pc-1", PlanAction.RECYCLE)
        )

        withApprovedSync(key, "approve-broken") { phone ->
            phone.sendRecords(emptyList())
            phone.receiveRecords()
            phone.sendPlan(broken, "fp")
            val cancel = phone.expect<SyncFrame.CancelSync>()
            assertEquals(SyncError.PROTOCOL.code, cancel.code)
        }
        assertTrue(waitUntil { viewModel.activeError.value != null })
        assertEquals(SyncError.PROTOCOL.message, viewModel.activeError.value?.first)
        assertTrue(viewModel.activeError.value!!.second!!.contains("refused"))
        assertNull(viewModel.pendingPlanApproval.value)
        assertEquals(before, fakeVaultRepo.entries.toList())
    }

    @Test
    fun testAChunkedPlanIsAssembledBeforeTheApproval() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "approve-chunks", "Phone")
        viewModel.startNetworking()
        val records = (1..30).map { SyncRecord(syncId = "r-$it", title = "Site $it", password = "p".repeat(200), updatedAt = 2L) }
        val plan = records.map { PlanItem(it.syncId, PlanAction.UPSERT, it) }
        assertTrue(MergePlans.encodeChunks(plan, maxBytes = 2_000).size > 2, "The test needs several parts")

        withApprovedSync(key, "approve-chunks") { phone ->
            phone.sendRecords(emptyList())
            phone.receiveRecords()
            phone.sendPlan(plan, MergePlans.vaultFingerprint(records), maxBytes = 2_000)
            assertTrue(waitUntil { viewModel.pendingPlanApproval.value != null })
            assertEquals(30, viewModel.pendingPlanApproval.value!!.summary.added)

            runBlocking { viewModel.approvePlan()!!.join() }
            assertTrue(phone.receive() is SyncFrame.MergePlanDecision)
            val desktopFinished = phone.expect<SyncFrame.SyncFinished>()
            phone.send(SyncFrame.SyncFinished(fingerprint = desktopFinished.fingerprint))
            assertTrue(waitUntil { viewModel.statusMessage.value?.startsWith("Devices are in sync") == true })
        }
        assertEquals(records.map { it.syncId }.toSet(), fakeVaultRepo.entries.map { it.id }.toSet())
    }

    @Test
    fun testAnOutOfOrderPlanPartIsRefused() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "approve-order", "Phone")
        viewModel.startNetworking()
        val chunk = MergePlans.encodeChunks(listOf(PlanItem("r-1", PlanAction.RECYCLE))).single()

        withApprovedSync(key, "approve-order") { phone ->
            phone.sendRecords(emptyList())
            phone.receiveRecords()
            phone.send(SyncFrame.MergePlanBatch(chunk, part = 1, last = true))
            assertEquals(SyncError.PROTOCOL.code, phone.expect<SyncFrame.CancelSync>().code)
        }
        assertTrue(waitUntil { viewModel.activeError.value != null })
        assertTrue(viewModel.activeError.value!!.second!!.contains("out of order"))
        assertNull(viewModel.pendingPlanApproval.value)
    }

    @Test
    fun testOutOfOrderPayloadPartCancelsTheSync() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "order-peer", "Phone")
        viewModel.startNetworking()
        val chunk = SyncRecords.encodeChunks(listOf(SyncRecord(syncId = "r-1", title = "A", updatedAt = 1L))).single()

        withApprovedSync(key, "order-peer") { phone ->
            phone.send(SyncFrame.PayloadBatch(chunk, part = 1, last = true))
            assertTrue(phone.receive() is SyncFrame.PayloadBatch) // our own records, sent first
            val cancel = phone.receive() as SyncFrame.CancelSync
            assertEquals(SyncError.PROTOCOL.code, cancel.code)
            assertEquals(SyncError.PROTOCOL.message, cancel.reason)
        }
        assertTrue(waitUntil { viewModel.activeError.value != null })
        assertEquals(SyncError.PROTOCOL.message, viewModel.activeError.value?.first)
        assertTrue(viewModel.activeError.value!!.second!!.contains("out of order"))
        assertFalse(viewModel.isSyncReviewOpen.value)
        assertNull(viewModel.activeDiffResult.value)
    }

    // --- Time limits ---

    @Test
    fun testSyncTimeoutWatchdog() = runBlocking {
        SilentPhone().use { silent ->
            val device = silent.pairedDevice("watchdog-peer", "Watchdog Phone")
            viewModel.initiateSyncWith(device)
            viewModel.cancelSyncRequest()
            assertFalse(viewModel.isWaitingForRemoteApproval.value)
            assertEquals("Sync cancelled", viewModel.statusMessage.value)
        }
    }

    @Test
    fun testAPeerThatStopsSendingTimesOut() {
        lanSocketTransport.close()
        buildViewModel(timeoutMsFor = { state -> state.timeoutMs?.let { 400L } })

        desktopStartedSync("slow-peer") { phone, _ ->
            // Accepted, then silence while the desktop waits for the phone's vault.
            val cancel = phone.expect<SyncFrame.CancelSync>()
            assertEquals(SyncError.TIMED_OUT.code, cancel.code)
        }
        assertEquals(SyncError.TIMED_OUT.message, viewModel.activeError.value?.first)
        assertTrue(viewModel.activeError.value!!.second!!.contains("Session state: EXCHANGING"))
    }

    @Test
    fun testAPeerThatNeverAnswersThePlanTimesOut() {
        lanSocketTransport.close()
        buildViewModel(timeoutMsFor = { state -> state.timeoutMs?.let { 400L } })

        desktopStartedSync("silent-approver") { phone, _ ->
            phone.sendRecords(emptyList())
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
            Thread.sleep(600) // the review itself has no time limit
            assertTrue(viewModel.isSyncReviewOpen.value)
            runBlocking { viewModel.confirmAndMerge { }!!.join() }
            phone.receivePlan()
            assertEquals(SyncError.TIMED_OUT.code, phone.expect<SyncFrame.CancelSync>().code)
        }
        assertTrue(viewModel.activeError.value!!.second!!.contains("Session state: AWAITING_PLAN_APPROVAL"))
    }

    @Test
    fun testEveryPartRestartsTheWatchdog() {
        lanSocketTransport.close()
        buildViewModel(timeoutMsFor = { state -> state.timeoutMs?.let { 500L } })
        val remote = (1..30).map { SyncRecord(syncId = "r-$it", title = "Site $it", password = "p".repeat(200), updatedAt = 2L) }
        val chunks = SyncRecords.encodeChunks(remote, maxBytes = 2_000)
        assertTrue(chunks.size >= 4)

        desktopStartedSync("steady-peer") { phone, _ ->
            // Longer than one time limit in all, but never silent for that long.
            chunks.forEachIndexed { i, chunk ->
                Thread.sleep(250)
                phone.send(SyncFrame.PayloadBatch(chunk, part = i, last = i == chunks.lastIndex))
            }
            assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
        }
        assertNull(viewModel.activeError.value)
    }

    // --- Keep-alive ---

    @Test
    fun testAKeepAliveHoldsALongReviewOpen() {
        lanSocketTransport.close()
        val idleMs = 1_500
        buildViewModel(idleMs = idleMs, keepAliveMs = 300L)
        val framesSeen = CopyOnWriteArrayList<SyncFrame>()
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            lanSocketTransport.receiveFrames().collect { framesSeen += it }
        }
        val phoneVault = listOf(SyncRecord(syncId = "phone-1", title = "AWS", password = "aws", updatedAt = 2L))

        try {
            desktopStartedSync("patient-peer") { phone, _ ->
                phone.keepAlive(300L).use {
                    phone.sendRecords(phoneVault)
                    assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
                    // The user takes their time: three idle limits without a single sync message.
                    Thread.sleep(3L * idleMs + 500L)
                    assertTrue(lanSocketTransport.isConnected, "The review outlived the idle limit")
                    assertEquals(SyncSessionState.REVIEWING, viewModel.sessionState.value)
                    assertNull(viewModel.activeError.value)

                    runBlocking { viewModel.confirmAndMerge { }!!.join() }
                    val (plan, _) = phone.receivePlan()
                    assertTrue(phone.keepAlivesReceived >= 3, "The desktop kept its side alive too")
                    phone.send(SyncFrame.MergePlanDecision(accepted = true))
                    val desktopFinished = phone.expect<SyncFrame.SyncFinished>()
                    val phoneAfter = applyPlanToRecords(phoneVault, plan)
                    assertEquals(MergePlans.vaultFingerprint(phoneAfter), desktopFinished.fingerprint)
                    phone.send(SyncFrame.SyncFinished(fingerprint = desktopFinished.fingerprint))
                    assertTrue(waitUntil { viewModel.statusMessage.value?.startsWith("Devices are in sync") == true })
                }
            }
        } finally {
            watcher.cancel()
        }
        assertTrue(framesSeen.isNotEmpty(), "The watcher saw the session's real frames")
        assertTrue(framesSeen.none { it is SyncFrame.KeepAlive }, "A keep-alive never reaches the view model")
        assertTrue(fakeVaultRepo.entries.any { it.id == "phone-1" })
    }

    @Test
    fun testKeepAlivesDontHoldOffASessionTimeLimit() {
        lanSocketTransport.close()
        buildViewModel(idleMs = 1_500, keepAliveMs = 100L, timeoutMsFor = { state -> state.timeoutMs?.let { 600L } })

        desktopStartedSync("stuck-peer") { phone, _ ->
            phone.keepAlive(100L).use {
                phone.sendRecords(emptyList())
                assertTrue(waitUntil { viewModel.isSyncReviewOpen.value })
                runBlocking { viewModel.confirmAndMerge { }!!.join() }
                phone.receivePlan()
                // Alive, but never answers the plan: the plan's time limit still ends the sync.
                assertEquals(SyncError.TIMED_OUT.code, phone.expect<SyncFrame.CancelSync>().code)
            }
        }
        assertEquals(SyncError.TIMED_OUT.message, viewModel.activeError.value?.first)
    }

    @Test
    fun testTheApproverCanCancelWhileThePeerReviews() = runBlocking {
        val key = pairTestDevice(pairedDeviceRepo, protector, "approve-cancel", "Phone")
        viewModel.startNetworking()

        withApprovedSync(key, "approve-cancel") { phone ->
            phone.sendRecords(emptyList())
            phone.receiveRecords()
            assertTrue(waitUntil { viewModel.isAwaitingPeerReview.value }, "The waiting state is shown")

            viewModel.cancelSyncRequest()
            assertEquals(SyncError.CANCELLED.code, phone.expect<SyncFrame.CancelSync>().code)
        }
        assertFalse(viewModel.isAwaitingPeerReview.value)
        assertEquals("Sync cancelled", viewModel.statusMessage.value)
    }

    // --- Helpers ---

    private fun waitUntil(timeoutMs: Long = 5000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** Runs [block] with a scripted phone whose sync request this PC has approved. */
    private fun withApprovedSync(key: ByteArray, deviceId: String, block: (ScriptedPhone) -> Unit) {
        ScriptedPhone.connect(port, HandshakeMode.SYNC, key).use { phone ->
            phone.send(SyncFrame.SyncRequest(deviceId = deviceId, deviceName = "Phone"))
            assertTrue(waitUntil { viewModel.isIncomingSyncDialogOpen.value })
            runBlocking { viewModel.acceptIncomingRequest().join() }
            assertTrue(phone.receive() is SyncFrame.SyncAcceptance)
            block(phone)
        }
    }

    /**
     * Pairs a scripted phone, lets this PC start a sync with it, and runs [block] once the phone
     * accepted and received this PC's vault. [prepare] runs after pairing, before the sync starts.
     */
    private fun desktopStartedSync(
        deviceId: String,
        prepare: suspend () -> Unit = {},
        block: (ScriptedPhone, List<SyncRecord>) -> Unit
    ) {
        ServerSocket(0).use { server ->
            val key = runBlocking {
                pairTestDevice(pairedDeviceRepo, protector, deviceId, "Phone", "127.0.0.1", server.localPort).also { prepare() }
            }
            runDesktopStarted(server, key, deviceId, block)
        }
    }

    private fun runDesktopStarted(
        server: ServerSocket,
        key: ByteArray,
        deviceId: String,
        block: (ScriptedPhone, List<SyncRecord>) -> Unit
    ) {
        runBlocking { viewModel.initiateSyncWith(pairedDeviceRepo.getPairedDevice(deviceId)!!) }
        ScriptedPhone.acceptSync(server, key)!!.use { phone ->
            assertTrue(phone.receive() is SyncFrame.SyncRequest)
            phone.send(SyncFrame.SyncAcceptance(deviceId = deviceId, isAccepted = true))
            block(phone, phone.receiveRecords())
        }
    }

    /**
     * A paired phone that accepts the TCP connection but never answers the handshake, so a sync
     * started with it stays "connecting" until cancelled.
     */
    private inner class SilentPhone : AutoCloseable {
        private val server = ServerSocket(0)

        suspend fun pairedDevice(deviceId: String, name: String): PairedDevice {
            pairTestDevice(pairedDeviceRepo, protector, deviceId, name, "127.0.0.1", server.localPort)
            return pairedDeviceRepo.getPairedDevice(deviceId)!!
        }

        override fun close() {
            server.close()
        }
    }

    private class FakeTestVaultRepository : VaultRepository {
        // Written by the sync's coroutines and read by the test thread.
        val entries = CopyOnWriteArrayList<VaultEntry>()
        val tombstones = CopyOnWriteArrayList<SyncTombstone>()

        override fun observeAllEntries(isDeleted: Boolean): Flow<RepositoryResult<List<VaultEntry>>> {
            val list = if (isDeleted) entries.filter { it.isDeleted } else entries.filter { !it.isDeleted }
            return flowOf(RepositoryResult.Success(list))
        }

        override suspend fun getAllEntries(isDeleted: Boolean): RepositoryResult<List<VaultEntry>> {
            val list = if (isDeleted) entries.filter { it.isDeleted } else entries.filter { !it.isDeleted }
            return RepositoryResult.Success(list)
        }

        override fun observeQuery(query: com.vaultpass.desktop.domain.models.VaultQuery): Flow<RepositoryResult<com.vaultpass.desktop.domain.models.PagedVaultResult>> {
            return flowOf(RepositoryResult.Success(com.vaultpass.desktop.domain.models.PagedVaultResult(entries, entries.size, 1, 1)))
        }

        override suspend fun getEntryById(id: String): RepositoryResult<VaultEntry?> {
            return RepositoryResult.Success(entries.find { it.id == id })
        }

        override suspend fun getEntryHistory(id: String): RepositoryResult<List<com.vaultpass.desktop.domain.models.VaultEntryHistory>> {
            return RepositoryResult.Success(emptyList())
        }

        override suspend fun createEntry(entry: VaultEntry): RepositoryResult<Unit> {
            entries.add(entry)
            tombstones.removeIf { it.syncId == entry.id }
            return RepositoryResult.Success(Unit)
        }

        override suspend fun updateEntry(entry: VaultEntry): RepositoryResult<Unit> {
            val idx = entries.indexOfFirst { it.id == entry.id }
            if (idx >= 0) {
                entries[idx] = entry
            } else {
                entries.add(entry)
            }
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

        override suspend fun getTombstones(): RepositoryResult<List<SyncTombstone>> = RepositoryResult.Success(tombstones.toList())

        override suspend fun changeEntryId(oldId: String, newId: String): RepositoryResult<Unit> {
            val idx = entries.indexOfFirst { it.id == oldId }
            if (idx >= 0) entries[idx] = entries[idx].copy(id = newId)
            return RepositoryResult.Success(Unit)
        }

        override suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T> {
            val snapshot = ArrayList(entries)
            return try {
                val res = block()
                RepositoryResult.Success(res)
            } catch (e: Exception) {
                entries.clear()
                entries.addAll(snapshot)
                RepositoryResult.Error(DatabaseError.CorruptionDetected(e.message ?: "Transaction failed"))
            }
        }

        override fun clearCache() {}
    }
}
