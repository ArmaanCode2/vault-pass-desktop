package com.vaultpass.desktop.ui.viewmodels

import androidx.compose.ui.graphics.ImageBitmap
import com.vaultpass.desktop.data.sync.LanDiscoveryManager
import com.vaultpass.desktop.data.sync.LanSocketTransport
import com.vaultpass.desktop.data.sync.PairedDeviceRepository
import com.vaultpass.desktop.data.sync.QrCodeGenerator
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.errors.AuthError
import com.vaultpass.desktop.domain.models.SyncTombstone
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.sync.SyncState
import com.vaultpass.desktop.domain.sync.diff.*
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.desktop.domain.sync.models.SyncFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.vaultpass.synccore.Hex
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.PlanSummary
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import com.vaultpass.synccore.SyncSessionState
import java.net.URLEncoder

/** What the other device wants to change here, waiting for this user's Apply or Decline. */
class PlanApprovalRequest(val peerName: String, val summary: PlanSummary)

class SyncViewModel(
    val pairedDeviceRepository: PairedDeviceRepository,
    val lanDiscoveryManager: LanDiscoveryManager,
    val lanSocketTransport: LanSocketTransport,
    val vaultRepository: VaultRepository,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Every session limit comes from the shared core; tests shorten them. */
    private val timeoutMsFor: (SyncSessionState) -> Long? = { it.timeoutMs }
) {

    val pairedDevices: StateFlow<List<PairedDevice>> = combine(
        pairedDeviceRepository.observePairedDevices(),
        lanDiscoveryManager.discoveredDevices
    ) { devices, discovered ->
        devices.map { device ->
            val presence = discovered[device.deviceId]
            val isOnline = presence != null && (System.currentTimeMillis() - presence.lastSeen < 10_000L)
            device.copy(
                isOnline = isOnline,
                ipAddress = presence?.ipAddress ?: device.ipAddress
            )
        }
    }.stateIn(coroutineScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val syncState: StateFlow<SyncState> = lanSocketTransport.syncState

    val pendingPairingInfo: StateFlow<SyncFrame.PairingInfo?> = lanSocketTransport.pendingPairingInfo

    val incomingRequest: StateFlow<SyncFrame.SyncRequest?> = lanSocketTransport.pendingSyncRequest

    companion object {
        /** Limits on what a peer may send in one sync. */
        const val MAX_PAYLOAD_PARTS = 5_000
        const val MAX_SYNC_RECORDS = 200_000
    }

    /** One watchdog for the whole session; it restarts on every state change. */
    private var watchdogJob: Job? = null

    // The connect-and-handshake of a sync this PC starts. Cancelled when the user cancels, so a
    // late result can't overwrite their "Sync cancelled".
    private var outgoingSyncJob: Job? = null

    // Whether this PC already sent its records in the current sync session.
    @Volatile
    private var localPayloadSent = false

    // The peer's records received so far in this session, and how many PayloadBatch parts
    // brought them. Only touched by the frame collector and the session resets.
    private val inboundRecords = mutableListOf<SyncRecord>()
    private var inboundParts = 0
    private var inboundComplete = false

    // The merge plan: what this PC built as the reviewer, or what arrived as the approver.
    private val inboundPlan = mutableListOf<PlanItem>()
    private var inboundPlanParts = 0
    private var inboundPlanComplete = false
    private var peerExpectedFingerprint = ""
    private var agreedPlan: List<PlanItem>? = null

    // The two halves of the confirmation: what this device ended with, and what the peer sent.
    // They are set from different coroutines, so only under finishMutex.
    private val finishMutex = Mutex()
    private var localFinished: SyncFrame.SyncFinished? = null
    private var peerFinished: SyncFrame.SyncFinished? = null
    private var mergeSuccessCallback: ((Int) -> Unit)? = null

    private val _sessionState = MutableStateFlow(SyncSessionState.IDLE)
    val sessionState: StateFlow<SyncSessionState> = _sessionState.asStateFlow()

    private val _isWaitingForRemoteApproval = MutableStateFlow(false)
    val isWaitingForRemoteApproval: StateFlow<Boolean> = _isWaitingForRemoteApproval.asStateFlow()

    private val _activeDiffResult = MutableStateFlow<SyncDiffResult?>(null)
    val activeDiffResult: StateFlow<SyncDiffResult?> = _activeDiffResult.asStateFlow()

    private val _activeSyncingDevice = MutableStateFlow<PairedDevice?>(null)
    val activeSyncingDevice: StateFlow<PairedDevice?> = _activeSyncingDevice.asStateFlow()

    private val _isQrDialogOpen = MutableStateFlow(false)
    val isQrDialogOpen: StateFlow<Boolean> = _isQrDialogOpen.asStateFlow()

    private val _qrCodeBitmap = MutableStateFlow<ImageBitmap?>(null)
    val qrCodeBitmap: StateFlow<ImageBitmap?> = _qrCodeBitmap.asStateFlow()

    private val _isSyncReviewOpen = MutableStateFlow(false)
    val isSyncReviewOpen: StateFlow<Boolean> = _isSyncReviewOpen.asStateFlow()

    private val _isIncomingSyncDialogOpen = MutableStateFlow(false)
    val isIncomingSyncDialogOpen: StateFlow<Boolean> = _isIncomingSyncDialogOpen.asStateFlow()

    private val _pendingPlanApproval = MutableStateFlow<PlanApprovalRequest?>(null)
    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = _pendingPlanApproval.asStateFlow()

    /** True while this PC (the approver) waits for the other device's user to finish reviewing. */
    private val _isAwaitingPeerReview = MutableStateFlow(false)
    val isAwaitingPeerReview: StateFlow<Boolean> = _isAwaitingPeerReview.asStateFlow()

    /** The device to offer "Sync again" for, after the two vaults ended up different. */
    private val _retryDevice = MutableStateFlow<PairedDevice?>(null)
    val retryDevice: StateFlow<PairedDevice?> = _retryDevice.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _activeError = MutableStateFlow<Pair<String, String?>?>(null)
    val activeError: StateFlow<Pair<String, String?>?> = _activeError.asStateFlow()

    fun showError(msg: String, details: String? = null) {
        _activeError.value = Pair(msg, details)
    }

    fun dismissError() {
        _activeError.value = null
        _retryDevice.value = null
    }

    init {
        // Discovery and the TCP listener are not started here: they run only while the Sync
        // page is open (see startNetworking / stopNetworking).
        lanSocketTransport.onTransportError = { title, details ->
            showError(title, details)
        }

        // React to mutual pairing completion
        coroutineScope.launch {
            syncState.collect { state ->
                if (state == SyncState.PAIRED) {
                    // A QR code pairs one device.
                    lanSocketTransport.setPairingSecret(null)
                    _statusMessage.value = "Device paired successfully"
                    _isQrDialogOpen.value = false
                    _qrCodeBitmap.value = null
                }
            }
        }

        // React to incoming sync requests
        coroutineScope.launch {
            incomingRequest.collect { req ->
                if (req != null) {
                    val peer = pairedDevices.value.find { it.deviceId == req.deviceId }
                        ?: PairedDevice(
                            deviceId = req.deviceId,
                            deviceName = req.deviceName,
                            sharedSecret = "",
                            ipAddress = null,
                            pairedAt = System.currentTimeMillis(),
                            isOnline = true
                        )
                    _activeSyncingDevice.value = peer
                    _isIncomingSyncDialogOpen.value = true
                    setSessionState(SyncSessionState.AWAITING_APPROVAL)
                } else {
                    _isIncomingSyncDialogOpen.value = false
                }
            }
        }

        // Frames and the end of the connection, in order: a peer that confirms and hangs up is
        // seen confirming first.
        coroutineScope.launch {
            lanSocketTransport.sessionEvents.collect { event ->
                when (event) {
                    is LanSocketTransport.SessionEvent.Received -> try {
                        onFrame(event.frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Never let one bad frame stop this listener; the session ends visibly.
                        failSync(SyncError.UNKNOWN, details = "${e::class.simpleName}: ${e.message}")
                    }
                    is LanSocketTransport.SessionEvent.Ended ->
                        // A connection that ends while a sync is running is a failure with a reason.
                        if (isSessionLive()) failSync(event.reason ?: SyncError.CONNECTION_LOST, tellPeer = false)
                }
            }
        }
    }

    /** Handles one frame of the live session. Runs in order, one frame at a time. */
    private suspend fun onFrame(frame: SyncFrame) {
        when (frame) {
            is SyncFrame.SyncAcceptance -> {
                if (frame.isAccepted) {
                    if (lanSocketTransport.syncRole != LanSocketTransport.SyncRole.INITIATOR) {
                        // We did not request this sync; ignore unsolicited acceptances.
                        return
                    }
                    setSessionState(SyncSessionState.EXCHANGING)
                    _statusMessage.value = "Sync accepted. Exchanging vault records..."
                    sendLocalPayloadIfAuthorized()
                } else {
                    failSync(SyncError.DECLINED, tellPeer = false)
                }
            }
            is SyncFrame.PayloadBatch -> {
                if (!lanSocketTransport.isSyncSessionActive()) {
                    // Records outside a sync the user started or approved are ignored.
                    return
                }
                // Every part restarts the watchdog: a big vault may take a while.
                setSessionState(SyncSessionState.EXCHANGING)
                // As responder, reply with our records now that the peer has proven the secret.
                sendLocalPayloadIfAuthorized()
                // Reading this vault may have failed the session; it must not start up again.
                if (!isSessionLive()) return
                receivePayloadPart(frame)
            }
            is SyncFrame.MergePlanBatch -> receivePlanPart(frame)
            is SyncFrame.MergePlanDecision -> onPlanDecision(frame)
            is SyncFrame.SyncFinished -> onPeerFinished(frame)
            is SyncFrame.CancelSync -> {
                // The reason is read before anything on screen is cleared.
                val error = if (frame.code.isBlank()) SyncError.CANCELLED else SyncError.fromCode(frame.code)
                failSync(error, tellPeer = false)
            }
            else -> {}
        }
    }

    // --- Session state machine ---

    /** True while a sync is running, so a failure still has something to report. */
    private fun isSessionLive(): Boolean = when (_sessionState.value) {
        SyncSessionState.IDLE, SyncSessionState.DONE, SyncSessionState.FAILED -> false
        else -> true
    }

    /**
     * Moves the session on and restarts the single watchdog. Passing the state it is already in
     * (each payload or plan part) re-arms it.
     */
    private fun setSessionState(state: SyncSessionState) {
        _sessionState.value = state
        watchdogJob?.cancel()
        val timeout = timeoutMsFor(state) ?: return
        watchdogJob = coroutineScope.launch {
            kotlinx.coroutines.delay(timeout)
            if (_sessionState.value == state) failSync(SyncError.TIMED_OUT)
        }
    }

    /** Ends the session, tells the peer when it can still hear us, and shows the user why. */
    private fun failSync(error: SyncError, tellPeer: Boolean = true, details: String? = null): Job? {
        if (!isSessionLive()) return null
        // Where the session was when it failed, then what went wrong if there is more to say.
        val technical = errorDetails(error) + (details?.let { "\n$it" } ?: "")
        watchdogJob?.cancel()
        outgoingSyncJob?.cancel()
        _sessionState.value = SyncSessionState.FAILED
        showError(error.message, technical)
        lanSocketTransport.markSyncFailed()
        _statusMessage.value = error.message
        clearSessionUi()
        _activeSyncingDevice.value = null
        return coroutineScope.launch(ioDispatcher) {
            if (tellPeer) {
                try {
                    lanSocketTransport.sendCancel(error)
                    kotlinx.coroutines.delay(200L)
                } catch (_: Exception) {
                    // The peer is already gone; the local error is what matters.
                }
            }
            lanSocketTransport.disconnect()
        }
    }

    /** Peer name, address, where the session had got to, the code and this PC's address. */
    private fun errorDetails(error: SyncError): String {
        val device = _activeSyncingDevice.value
        return buildString {
            appendLine("Device: ${device?.deviceName ?: "unknown"}")
            appendLine("Device IP: ${device?.ipAddress ?: "unknown"}")
            appendLine("Session state: ${_sessionState.value}")
            appendLine("Error code: ${error.code}")
            append("This PC: ${lanDiscoveryManager.getLocalIpAddress()}")
        }
    }

    /** Everything a finished or failed session leaves behind on screen. */
    private fun clearSessionUi() {
        _isAwaitingPeerReview.value = false
        resetInbound()
        _isWaitingForRemoteApproval.value = false
        _isSyncReviewOpen.value = false
        _isIncomingSyncDialogOpen.value = false
        _pendingPlanApproval.value = null
        _activeDiffResult.value = null
    }

    /** Starts discovery and the sync listener. Called when the Sync page opens. */
    fun startNetworking() {
        // Pairings from sync protocol v1 sent their key in plaintext; they must be redone.
        coroutineScope.launch(ioDispatcher) {
            try {
                pairedDeviceRepository.purgeLegacyPairings()
            } catch (_: Exception) {
                // Nothing to clean up, or the vault is locked: the next sync reports it.
            }
        }
        lanDiscoveryManager.start()
        lanSocketTransport.startServer()
    }

    /** Stops discovery and the sync listener and ends any connection. */
    fun stopNetworking() {
        watchdogJob?.cancel()
        outgoingSyncJob?.cancel()
        _isWaitingForRemoteApproval.value = false
        _isIncomingSyncDialogOpen.value = false
        lanSocketTransport.setPairingSecret(null)
        _isQrDialogOpen.value = false
        _qrCodeBitmap.value = null
        lanSocketTransport.stopServer()
        lanDiscoveryManager.stop()
    }

    /** Called when the vault locks: stop networking and drop any review of decrypted entries. */
    fun onVaultLocked() {
        // Best effort: the peer hears why, unless the connection closes first.
        if (isSessionLive()) failSync(SyncError.VAULT_LOCKED)
        stopNetworking()
        resetInbound()
        _sessionState.value = SyncSessionState.IDLE
        _isSyncReviewOpen.value = false
        _activeDiffResult.value = null
        _activeSyncingDevice.value = null
        _pendingPlanApproval.value = null
    }

    fun startQrPairing(selectedIp: String? = null) {
        // The QR code carries a one-time 32-byte secret. It is only ever seen by the phone's
        // camera: the pairing handshake proves knowledge of it without sending it.
        val pairingSecret = SyncCrypto.randomBytes(SyncCrypto.KEY_BYTES)
        val allIps = lanDiscoveryManager.getAllLocalIpAddresses()
        val primaryIp = selectedIp ?: allIps.firstOrNull() ?: "127.0.0.1"
        val ipsParam = allIps.joinToString(",")
        val payloadUri = "vaultpass://pair?v=2&deviceId=${lanDiscoveryManager.deviceId}" +
                "&name=${URLEncoder.encode(lanDiscoveryManager.deviceName, "UTF-8")}" +
                "&secret=${Hex.encode(pairingSecret)}" +
                "&ip=$primaryIp" +
                "&ips=$ipsParam" +
                "&port=53853"
        val bitmap = QrCodeGenerator.generateQrBitmap(payloadUri, size = 300)
        lanSocketTransport.setPairingSecret(pairingSecret)
        pairingSecret.fill(0)
        _qrCodeBitmap.value = bitmap
        _isQrDialogOpen.value = true
        lanSocketTransport.startServer()
    }

    fun dismissQrPairing() {
        lanSocketTransport.setPairingSecret(null)
        _isQrDialogOpen.value = false
        _qrCodeBitmap.value = null
        if (syncState.value == SyncState.AWAITING_LOCAL_APPROVAL || syncState.value == SyncState.WAITING_FOR_REMOTE_APPROVAL) {
            coroutineScope.launch(ioDispatcher) {
                try {
                    lanSocketTransport.sendCancel(SyncError.CANCELLED, "User dismissed pairing dialog")
                    kotlinx.coroutines.delay(200L)
                } catch (_: Exception) {
                    // The phone is already gone; closing below is enough.
                }
                lanSocketTransport.disconnect()
            }
        }
    }

    fun acceptPairing() {
        lanSocketTransport.acceptPairing()
    }

    fun declinePairing() {
        lanSocketTransport.declinePairing()
        dismissQrPairing()
    }

    fun refreshDevices() {
        lanDiscoveryManager.refreshNow()
    }

    fun unpairDevice(deviceId: String): Job {
        return coroutineScope.launch(ioDispatcher) {
            _statusMessage.value = try {
                pairedDeviceRepository.unpairDevice(deviceId)
                "Device unpaired"
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseClosedException) {
                "The vault is locked. Unlock it, then unpair the device again."
            } catch (e: Exception) {
                "Could not unpair the device: ${e.message}"
            }
        }
    }

    // --- Starting a sync (this PC reviews) ---

    fun initiateSyncWith(device: PairedDevice): Job {
        _activeSyncingDevice.value = device
        _retryDevice.value = null
        outgoingSyncJob?.cancel()
        val job = coroutineScope.launch(ioDispatcher) {
            // Reset before the request goes out: the reply can arrive right after it.
            localPayloadSent = false
            resetInbound()
            setSessionState(SyncSessionState.CONNECTING)
            val ip = device.ipAddress
            if (ip.isNullOrBlank()) {
                failToStart(SyncError.CONNECTION_LOST, "Cannot connect: Device IP is unknown")
                return@launch
            }
            _isWaitingForRemoteApproval.value = true
            _statusMessage.value = "Connecting to ${device.deviceName}..."
            when (lanSocketTransport.startOutgoingSync(device)) {
                // The answer may already be in: never move the session back.
                LanSocketTransport.OutgoingSyncResult.STARTED -> if (_sessionState.value == SyncSessionState.CONNECTING) {
                    setSessionState(SyncSessionState.AWAITING_APPROVAL)
                    _statusMessage.value = "Waiting for ${device.deviceName} to accept sync..."
                }
                LanSocketTransport.OutgoingSyncResult.NOT_PAIRED -> failToStart(
                    SyncError.VAULT_UNREADABLE,
                    "${device.deviceName} is no longer paired with this PC. Pair it again."
                )
                LanSocketTransport.OutgoingSyncResult.UNREACHABLE -> failToStart(
                    SyncError.CONNECTION_LOST,
                    "Could not connect to ${device.deviceName} on port ${device.port}"
                )
                LanSocketTransport.OutgoingSyncResult.FAILED -> failToStart(
                    SyncError.PROTOCOL,
                    "Could not start a secure sync with ${device.deviceName}. " +
                        "Make sure VaultPass is up to date on both devices, or pair them again."
                )
            }
        }
        outgoingSyncJob = job
        return job
    }

    /** Restarts the sync with the device the last one left different. */
    fun syncAgain(): Job? {
        val device = _retryDevice.value ?: return null
        dismissError()
        return initiateSyncWith(device)
    }

    /** A sync that never got started: the error dialog, with [message] saying what exactly failed. */
    private fun failToStart(error: SyncError, message: String) {
        failSync(error, tellPeer = false, details = message)
        _statusMessage.value = message
    }

    fun setSimulatedDiffForTesting(diffResult: SyncDiffResult, device: PairedDevice) {
        _sessionState.value = SyncSessionState.REVIEWING
        _activeSyncingDevice.value = device
        _activeDiffResult.value = diffResult
        _isSyncReviewOpen.value = true
    }

    // --- Answering a sync (this PC approves) ---

    fun acceptIncomingRequest(): Job {
        _isIncomingSyncDialogOpen.value = false
        val peerName = peerName()

        return coroutineScope.launch(ioDispatcher) {
            // Reset before the answer goes out: the peer's records can arrive right after it.
            localPayloadSent = false
            resetInbound()
            setSessionState(SyncSessionState.EXCHANGING)
            if (!lanSocketTransport.acceptSync()) {
                watchdogJob?.cancel()
                _statusMessage.value = "This sync request is no longer valid. Ask $peerName to try again."
                _activeSyncingDevice.value = null
                _sessionState.value = SyncSessionState.IDLE
                lanSocketTransport.disconnect()
                return@launch
            }
            // Our vault is sent only after the peer proves it holds the paired secret by
            // sending its own encrypted records (see PayloadBatch handling).
            if (!inboundComplete) {
                _isWaitingForRemoteApproval.value = true
                _statusMessage.value = "Sync accepted. Waiting for $peerName to send vault records..."
            }
        }
    }

    fun declineIncomingRequest(): Job {
        val job = lanSocketTransport.declineSync()
        _isIncomingSyncDialogOpen.value = false
        _activeSyncingDevice.value = null
        watchdogJob?.cancel()
        _sessionState.value = SyncSessionState.IDLE
        return job
    }

    /**
     * Sends this PC's records once per session, and only when the transport confirms the peer
     * is authenticated and the local user started or approved the sync.
     */
    private suspend fun sendLocalPayloadIfAuthorized() {
        if (localPayloadSent || !lanSocketTransport.canSendVaultData()) return
        localPayloadSent = true
        val local = try {
            loadLocalVault()
        } catch (e: Exception) {
            failSync(readFailure(e))
            return
        }
        lanSocketTransport.sendPayloadBatch(SyncRecordMapper.localRecords(local.active, local.deleted, local.tombstones))
    }

    private class LocalVault(
        val active: List<VaultEntry>,
        val deleted: List<VaultEntry>,
        val tombstones: List<SyncTombstone>
    )

    /**
     * This vault as a sync sees it. Throws rather than treat an unreadable vault as empty.
     * The three lists are read in one transaction, so they are one snapshot of the vault. A lock
     * before or during the reads (where a closed database reads as empty) fails that
     * transaction, and this throws [DatabaseClosedException] instead of returning a vault.
     */
    private suspend fun loadLocalVault(): LocalVault {
        fun <T> RepositoryResult<T>.orThrow(): T = when (this) {
            is RepositoryResult.Success -> data
            is RepositoryResult.Error ->
                if (error is AuthError.VaultLocked) throw DatabaseClosedException() else throw IllegalStateException(error.message)
            is RepositoryResult.Loading -> throw IllegalStateException("Vault not ready")
        }
        return vaultRepository.transaction {
            LocalVault(
                active = getAllEntries(false).orThrow(),
                deleted = getAllEntries(true).orThrow(),
                tombstones = getTombstones().orThrow()
            )
        }.orThrow()
    }

    /** Why reading this vault failed: the vault locked meanwhile, or it can't be read. */
    private fun readFailure(e: Exception): SyncError =
        if (e is DatabaseClosedException) SyncError.VAULT_LOCKED else SyncError.VAULT_UNREADABLE

    private suspend fun localRecords(): List<SyncRecord> = loadLocalVault().let {
        SyncRecordMapper.localRecords(it.active, it.deleted, it.tombstones)
    }

    /**
     * Collects one PayloadBatch part. Parts must arrive in order and within the limits; once the
     * last one is in, the device that started the sync builds the review.
     */
    private suspend fun receivePayloadPart(frame: SyncFrame.PayloadBatch) {
        if (inboundComplete || frame.part != inboundParts) {
            failSync(SyncError.PROTOCOL, details = "Vault records arrived out of order.")
            return
        }
        if (inboundParts >= MAX_PAYLOAD_PARTS) {
            failSync(SyncError.TOO_MUCH_DATA)
            return
        }
        val records = try {
            SyncRecords.decodeChunk(frame.encryptedBatchJson)
        } catch (e: Exception) {
            failSync(SyncError.PROTOCOL, details = "The other device sent unreadable vault records.")
            return
        }
        if (inboundRecords.size + records.size > MAX_SYNC_RECORDS) {
            failSync(SyncError.TOO_MUCH_DATA)
            return
        }
        inboundRecords += records
        inboundParts++
        if (!frame.last) return
        inboundComplete = true

        val isReviewer = lanSocketTransport.syncRole == LanSocketTransport.SyncRole.INITIATOR
        if (!isReviewer) {
            // The approver never computes a diff: it waits for the plan the reviewer agreed.
            inboundRecords.clear()
            _isWaitingForRemoteApproval.value = false
            _statusMessage.value = "Waiting for ${peerName()} to choose what to sync..."
            _isAwaitingPeerReview.value = true
            setSessionState(SyncSessionState.REVIEWING)
            return
        }

        try {
            val local = loadLocalVault()
            val lastSyncAt = peerDevice()?.let { pairedDeviceRepository.getPairedDevice(it.deviceId)?.lastSyncAt } ?: 0L
            val diffResult = SyncDiffEngine.computeDiff(
                localEntries = local.active,
                remoteRecords = inboundRecords.toList(),
                lastSyncAt = lastSyncAt,
                deletedLocalEntries = local.deleted,
                tombstones = local.tombstones
            )
            inboundRecords.clear()
            _activeDiffResult.value = diffResult
            _isWaitingForRemoteApproval.value = false
            _isSyncReviewOpen.value = true
            setSessionState(SyncSessionState.REVIEWING)
        } catch (e: Exception) {
            val error = readFailure(e)
            failSync(error, details = if (error == SyncError.VAULT_LOCKED) null else "Failed to process vault records: ${e.message}")
        }
    }

    private fun resetInbound() {
        inboundRecords.clear()
        inboundParts = 0
        inboundComplete = false
        inboundPlan.clear()
        inboundPlanParts = 0
        inboundPlanComplete = false
        peerExpectedFingerprint = ""
        agreedPlan = null
        localFinished = null
        peerFinished = null
    }

    private fun peerDevice(): PairedDevice? = _activeSyncingDevice.value

    private fun peerName(): String = _activeSyncingDevice.value?.deviceName ?: "the other device"

    // --- The review (reviewer side) ---

    /**
     * Records the user's choice for one entry. Conflicts count as answered once chosen. Choosing
     * another version drops hand edits, which were made to the previous one.
     */
    fun setOutcome(syncKey: String, outcome: ItemOutcome) {
        val current = _activeDiffResult.value ?: return
        val updatedItems = current.diffItems.map { item ->
            if (item.syncKey == syncKey && outcome in item.choices) {
                item.copy(
                    outcome = outcome,
                    isAnswered = true,
                    editedEntry = item.editedEntry.takeIf { outcome == item.outcome }
                )
            } else {
                item
            }
        }
        _activeDiffResult.value = current.copy(diffItems = updatedItems)
    }

    /** Hand edits apply to the version the entry keeps; they need USE_REMOTE or USE_LOCAL. */
    fun updateEntryFieldOverride(syncKey: String, fieldName: String, newValue: String) {
        val current = _activeDiffResult.value ?: return
        val updatedItems = current.diffItems.map { item ->
            if (item.syncKey == syncKey && item.isEditable) {
                val base = item.editedEntry ?: item.chosenEntry
                if (base != null) {
                    val updatedEntry = when (fieldName.lowercase()) {
                        "title" -> base.copy(title = newValue)
                        "username" -> base.copy(username = newValue)
                        "password" -> base.copy(secret = newValue)
                        "url" -> base.copy(url = newValue)
                        "notes" -> base.copy(notes = newValue)
                        "category" -> base.copy(category = newValue.ifBlank { null })
                        "tags" -> base.copy(tags = newValue.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                        else -> base
                    }
                    item.copy(editedEntry = updatedEntry)
                } else item
            } else {
                item
            }
        }
        _activeDiffResult.value = current.copy(diffItems = updatedItems)
    }

    /** Drops the hand edits of one entry: it goes back to the chosen version. */
    fun discardEdits(syncKey: String) {
        val current = _activeDiffResult.value ?: return
        _activeDiffResult.value = current.copy(
            diffItems = current.diffItems.map { if (it.syncKey == syncKey) it.copy(editedEntry = null) else it }
        )
    }

    /**
     * Turns the reviewed entries into the plan both devices apply and sends it. The vault is only
     * changed once the other device has accepted it (see [onPlanDecision]).
     */
    fun confirmAndMerge(onSuccess: (Int) -> Unit): Job? {
        val diffResult = _activeDiffResult.value ?: return null
        if (_sessionState.value != SyncSessionState.REVIEWING) return null
        if (diffResult.unansweredCount > 0) {
            _statusMessage.value = "Choose what to do with the entries changed on both devices."
            return null
        }
        if (diffResult.diffItems.any { !it.editsFit }) {
            _statusMessage.value = "An edited entry is longer than an entry may be. Shorten it first."
            return null
        }
        // One Finish per review: the dialog closes and the session moves on at once.
        _isSyncReviewOpen.value = false
        setSessionState(SyncSessionState.AWAITING_PLAN_APPROVAL)
        mergeSuccessCallback = onSuccess

        return coroutineScope.launch(ioDispatcher) {
            val items = try {
                MergePlans.build(diffResult.diffItems.map { it.toPlanInput() }, System.currentTimeMillis())
            } catch (e: Exception) {
                failSync(SyncError.MERGE_FAILED, details = "The chosen changes could not be turned into a plan: ${e.message}")
                return@launch
            }
            val fingerprint = try {
                SyncMergeExecutor.fingerprintAfter(localRecords(), items)
            } catch (e: Exception) {
                failSync(readFailure(e))
                return@launch
            }
            agreedPlan = items
            _statusMessage.value = "Waiting for ${peerName()} to accept the changes..."
            if (!lanSocketTransport.sendMergePlan(items, fingerprint)) {
                failSync(lanSocketTransport.lastFailure() ?: SyncError.CONNECTION_LOST, tellPeer = false)
            }
        }
    }

    // --- The plan (approver side) ---

    /** Collects one MergePlanBatch part, then validates and summarises the whole plan. */
    private suspend fun receivePlanPart(frame: SyncFrame.MergePlanBatch) {
        if (inboundPlanComplete || frame.part != inboundPlanParts) {
            failSync(SyncError.PROTOCOL, details = "The merge plan arrived out of order.")
            return
        }
        if (inboundPlanParts >= MAX_PAYLOAD_PARTS) {
            failSync(SyncError.TOO_MUCH_DATA)
            return
        }
        val items = try {
            MergePlans.decodeChunk(frame.planJson)
        } catch (e: Exception) {
            failSync(SyncError.PROTOCOL, details = "The other device sent an unreadable merge plan.")
            return
        }
        if (inboundPlan.size + items.size > MergePlans.MAX_ITEMS) {
            failSync(SyncError.TOO_MUCH_DATA)
            return
        }
        inboundPlan += items
        _isAwaitingPeerReview.value = false
        inboundPlanParts++
        // Every part restarts the watchdog: a big plan arrives in many of them.
        setSessionState(SyncSessionState.AWAITING_PLAN_APPROVAL)
        if (!frame.last) return
        inboundPlanComplete = true
        peerExpectedFingerprint = frame.expectedFingerprint

        val plan = inboundPlan.toList()
        try {
            MergePlans.validate(plan)
        } catch (e: Exception) {
            failSync(SyncError.PROTOCOL, details = "The merge plan was refused: ${e.message}")
            return
        }
        agreedPlan = plan

        val localIds = try {
            localRecords().map { it.syncId }.toSet()
        } catch (e: Exception) {
            failSync(readFailure(e))
            return
        }
        val summary = MergePlans.summarize(plan, localIds)
        if (summary.isEmpty) {
            // Nothing to decide: the devices already agree.
            if (lanSocketTransport.sendFrame(SyncFrame.MergePlanDecision(accepted = true))) {
                applyAgreedPlan(plan)
            } else {
                failSync(lanSocketTransport.lastFailure() ?: SyncError.CONNECTION_LOST, tellPeer = false)
            }
            return
        }
        _isWaitingForRemoteApproval.value = false
        _pendingPlanApproval.value = PlanApprovalRequest(peerName(), summary)
    }

    /** "Apply" in the plan approval dialog. */
    fun approvePlan(): Job? {
        val plan = agreedPlan ?: return null
        _pendingPlanApproval.value = null
        return coroutineScope.launch(ioDispatcher) {
            if (!lanSocketTransport.sendFrame(SyncFrame.MergePlanDecision(accepted = true))) {
                failSync(lanSocketTransport.lastFailure() ?: SyncError.CONNECTION_LOST, tellPeer = false)
                return@launch
            }
            applyAgreedPlan(plan)
        }
    }

    /** "Decline" in the plan approval dialog: nothing changes on either device. */
    fun declinePlan(): Job {
        _pendingPlanApproval.value = null
        return coroutineScope.launch(ioDispatcher) {
            try {
                lanSocketTransport.sendFrame(
                    SyncFrame.MergePlanDecision(accepted = false, code = SyncError.PLAN_REJECTED.code)
                )
                kotlinx.coroutines.delay(200L) // Let the answer reach the reviewer before closing.
            } catch (_: Exception) {
                // The reviewer is already gone; ending the session below is enough.
            }
            watchdogJob?.cancel()
            _sessionState.value = SyncSessionState.FAILED
            _statusMessage.value = "The changes were declined. Nothing was changed."
            clearSessionUi()
            _activeSyncingDevice.value = null
            lanSocketTransport.disconnect()
        }
    }

    // --- Applying and confirming (both devices) ---

    private suspend fun onPlanDecision(frame: SyncFrame.MergePlanDecision) {
        val plan = agreedPlan
        if (!frame.accepted) {
            val error = if (frame.code.isBlank()) SyncError.PLAN_REJECTED else SyncError.fromCode(frame.code)
            failSync(error, tellPeer = false)
            return
        }
        if (plan == null) {
            failSync(SyncError.PROTOCOL, details = "An answer arrived for a plan this device never sent.")
            return
        }
        applyAgreedPlan(plan)
    }

    /** Applies the agreed plan here and tells the peer what this vault ended up with. */
    private suspend fun applyAgreedPlan(plan: List<PlanItem>) {
        setSessionState(SyncSessionState.APPLYING)
        _statusMessage.value = "Applying the agreed changes..."
        val result = SyncMergeExecutor.applyPlan(plan, vaultRepository)
        // The session may have failed meanwhile (timeout, cancel); its error is already shown.
        if (_sessionState.value != SyncSessionState.APPLYING) return
        if (result.isFailure) {
            // A lock during the merge rolls it back like any failure, but the peer and the user
            // are told the vault locked.
            val error = if (result.exceptionOrNull() is DatabaseClosedException) SyncError.VAULT_LOCKED else SyncError.MERGE_FAILED
            try {
                lanSocketTransport.sendFrame(SyncFrame.SyncFinished(code = error.code))
            } catch (_: Exception) {
                // Nothing was changed here either way; the local error is what matters.
            }
            failSync(
                error,
                details = if (error == SyncError.VAULT_LOCKED) null else "Merge error: ${result.exceptionOrNull()?.message}"
            )
            return
        }
        val applied = result.getOrThrow()
        val fingerprint = try {
            MergePlans.vaultFingerprint(localRecords())
        } catch (e: Exception) {
            failSync(readFailure(e))
            return
        }
        val finished = SyncFrame.SyncFinished(code = "", fingerprint = fingerprint, applied = applied)
        setSessionState(SyncSessionState.CONFIRMING)
        if (!lanSocketTransport.sendFrame(finished)) {
            failSync(lanSocketTransport.lastFailure() ?: SyncError.CONNECTION_LOST, tellPeer = false)
            return
        }
        finishMutex.withLock {
            localFinished = finished
            resolveFinish()
        }
    }

    private suspend fun onPeerFinished(frame: SyncFrame.SyncFinished) {
        if (frame.code.isNotBlank()) {
            failSync(SyncError.fromCode(frame.code), tellPeer = false)
            return
        }
        finishMutex.withLock {
            peerFinished = frame
            resolveFinish()
        }
    }

    /**
     * Both devices have applied the plan: the vaults must now hold exactly the same entries.
     * Called under [finishMutex] by whichever half of the confirmation arrives last.
     */
    private suspend fun resolveFinish() {
        val mine = localFinished ?: return
        val theirs = peerFinished ?: return
        if (!isSessionLive()) return
        watchdogJob?.cancel()

        if (mine.fingerprint == theirs.fingerprint) {
            // DONE first: from here on a peer hanging up is no longer a failure.
            _sessionState.value = SyncSessionState.DONE
            try {
                peerDevice()?.let { pairedDeviceRepository.updateLastSync(it.deviceId, System.currentTimeMillis()) }
            } catch (e: Exception) {
                // Harmless: the vaults match; the next review may only show more entries as changed.
            }
            _retryDevice.value = null
            clearSessionUi()
            _activeSyncingDevice.value = null
            _statusMessage.value = "Devices are in sync (${mine.applied} entries updated)"
            lanSocketTransport.disconnect()
            mergeSuccessCallback?.invoke(mine.applied)
            mergeSuccessCallback = null
        } else {
            // Nothing is undone: the user syncs again and sees what is still different.
            val technical = errorDetails(SyncError.NOT_IDENTICAL) +
                "\nThis device: ${mine.fingerprint}\n${peerName()}: ${theirs.fingerprint}"
            _sessionState.value = SyncSessionState.FAILED
            _retryDevice.value = peerDevice()
            showError(SyncError.NOT_IDENTICAL.message, technical)
            lanSocketTransport.markSyncFailed()
            _statusMessage.value = SyncError.NOT_IDENTICAL.message
            clearSessionUi()
            _activeSyncingDevice.value = null
            lanSocketTransport.disconnect()
        }
    }

    // --- Cancelling ---

    /** The user's own cancel, before the records are in: a status line, not an error. */
    fun cancelSyncRequest() {
        watchdogJob?.cancel()
        outgoingSyncJob?.cancel()
        _sessionState.value = SyncSessionState.IDLE
        clearSessionUi()
        _activeSyncingDevice.value = null
        coroutineScope.launch(ioDispatcher) {
            try {
                lanSocketTransport.sendCancel(SyncError.CANCELLED, "User cancelled sync request")
                kotlinx.coroutines.delay(200L)
            } catch (_: Exception) {
                // Already disconnected; closing below is enough.
            }
            lanSocketTransport.disconnect()
        }
        _statusMessage.value = "Sync cancelled"
    }

    /** The user's own cancel from the review screen. */
    fun cancelSync() {
        watchdogJob?.cancel()
        outgoingSyncJob?.cancel()
        _sessionState.value = SyncSessionState.IDLE
        clearSessionUi()
        _activeSyncingDevice.value = null
        coroutineScope.launch(ioDispatcher) {
            try {
                lanSocketTransport.sendCancel(SyncError.CANCELLED, "User cancelled sync review")
                kotlinx.coroutines.delay(200L)
            } catch (_: Exception) {
                // Already disconnected; closing below is enough.
            }
            lanSocketTransport.disconnect()
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
