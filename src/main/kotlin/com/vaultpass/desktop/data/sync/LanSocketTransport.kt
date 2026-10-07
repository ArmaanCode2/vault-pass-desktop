package com.vaultpass.desktop.data.sync

import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.sync.SyncState
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.desktop.domain.sync.models.SyncFrame
import com.vaultpass.synccore.HandshakeMode
import com.vaultpass.synccore.LegacyPeerException
import com.vaultpass.synccore.PairKeyProtector
import com.vaultpass.synccore.SecureChannel
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncError
import com.vaultpass.synccore.SyncProtocolException
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import com.vaultpass.synccore.SyncTimeouts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * LAN sync transport on sync protocol v2 (see com.vaultpass.synccore). Every connection starts
 * with an authenticated handshake, and every message after it is an encrypted record.
 *
 * The desktop answers pairing requests while its QR code is shown, and both starts and answers
 * syncs with paired devices. Only one connection (or handshake) is served at a time, so another
 * device can't take over a pairing or sync in progress.
 */
class LanSocketTransport(
    val localDeviceId: String,
    val localDeviceName: String,
    private val pairedDeviceRepository: PairedDeviceRepository? = null,
    private val pairKeyProtector: PairKeyProtector? = null,
    private val port: Int = 53853,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val firstFrameTimeoutMs: Int = FIRST_FRAME_TIMEOUT_MS,
    private val idleReadTimeoutMs: Int = IDLE_READ_TIMEOUT_MS,
    private val keepAliveMs: Long = KEEP_ALIVE_MS
) {

    companion object {
        /** Every time limit of a session comes from the shared core (see SyncTimeouts). */
        const val FIRST_FRAME_TIMEOUT_MS = SyncTimeouts.HANDSHAKE_MS
        const val IDLE_READ_TIMEOUT_MS = SyncTimeouts.IDLE_MS

        /** How often an open sync connection proves it is alive; well under the idle limit. */
        const val KEEP_ALIVE_MS = 30_000L

        private const val LEGACY_NOTICE_INTERVAL_MS = 60_000L

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
    }

    /** Which side of the current connection this device is on. */
    enum class SyncRole { NONE, INITIATOR, RESPONDER }

    enum class OutgoingSyncResult { STARTED, NOT_PAIRED, UNREACHABLE, FAILED }

    private class Connection(
        val socket: Socket,
        val channel: SecureChannel,
        val mode: HandshakeMode,
        val role: SyncRole,
        /** The paired device on a sync connection; null on a pairing connection. */
        val peer: PairedDevice?
    )

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _pendingPairingInfo = MutableStateFlow<SyncFrame.PairingInfo?>(null)
    val pendingPairingInfo: StateFlow<SyncFrame.PairingInfo?> = _pendingPairingInfo.asStateFlow()

    private val _pendingSyncRequest = MutableStateFlow<SyncFrame.SyncRequest?>(null)
    val pendingSyncRequest: StateFlow<SyncFrame.SyncRequest?> = _pendingSyncRequest.asStateFlow()

    /** What happened on the live connection, in the order it happened. */
    sealed class SessionEvent {
        class Received(val frame: SyncFrame) : SessionEvent()

        /** The connection's read loop ended; [reason] is null when nothing went wrong. */
        class Ended(val reason: SyncError?) : SessionEvent()
    }

    // Frames and the end of the connection travel in one flow, so a listener never sees the
    // connection end before the last frames that arrived on it.
    private val eventFlow = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 64)

    /** Every received frame, and the end of each connection, in order. */
    val sessionEvents: SharedFlow<SessionEvent> = eventFlow.asSharedFlow()

    var onTransportError: ((title: String, details: String) -> Unit)? = null

    internal var pairingSaveJob: Job? = null

    // --- Connection slot ---

    private val connectionLock = Any()

    @Volatile
    private var connection: Connection? = null

    /** A socket still in its handshake; it occupies the single connection slot. */
    @Volatile
    private var handshakeSocket: Socket? = null

    // --- Session state of the current connection (reset for every connection) ---

    @Volatile
    private var receivedPairingInfo: SyncFrame.PairingInfo? = null

    @Volatile
    private var pairingDecided = false

    @Volatile
    private var receivedSyncRequest = false

    @Volatile
    private var receivedSyncAcceptance = false

    @Volatile
    private var remoteAcceptedSync = false

    @Volatile
    private var incomingSyncApprovedLocally = false

    @Volatile
    private var planSent = false

    @Volatile
    private var planComplete = false

    @Volatile
    private var planReceived = false

    @Volatile
    private var decisionReceived = false

    @Volatile
    private var finishedReceived = false

    /** Why the current (or last) session ended; cleared when a new one starts. */
    @Volatile
    private var lastFailure: SyncError? = null

    /** Sends KeepAlive frames while a sync connection is open (never on a pairing one). */
    @Volatile
    private var keepAliveJob: Job? = null

    // --- Pairing secret of the QR code on screen ---

    @Volatile
    private var pairingSecret: ByteArray? = null

    @Volatile
    private var lastLegacyNoticeAt = 0L

    val syncRole: SyncRole get() = connection?.role ?: SyncRole.NONE

    val isConnected: Boolean get() = connection != null

    fun receiveFrames(): Flow<SyncFrame> = eventFlow.filterIsInstance<SessionEvent.Received>().map { it.frame }

    /** Why the current (or last) session ended, or null when nothing went wrong. */
    fun lastFailure(): SyncError? = lastFailure

    /** Shows a sync failure in the UI state; starting a new session clears it again. */
    fun markSyncFailed() {
        _syncState.value = SyncState.ERROR
    }

    /**
     * Sets the secret of the pairing QR code now on screen, or null when it closes. Pairing
     * handshakes succeed only while a QR code is shown, and only with its secret.
     */
    fun setPairingSecret(secret: ByteArray?) {
        pairingSecret = secret?.copyOf()
    }

    internal val isExpectingPairing: Boolean get() = pairingSecret != null

    // --- Server ---

    private val serverLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var serverJob: Job? = null

    /**
     * Binds the listener synchronously, so a [stopServer] right after this always finds the
     * socket to close; accepting runs on [scope].
     */
    fun startServer() {
        synchronized(serverLock) {
            if (serverSocket?.isClosed == false) return

            val server = try {
                ServerSocket(port).apply { reuseAddress = true }
            } catch (e: Exception) {
                _syncState.value = SyncState.ERROR
                return
            }
            serverSocket = server
            serverJob = scope.launch {
                try {
                    while (isActive && !server.isClosed) {
                        val client = server.accept()
                        // A failure on this one socket must not end the accept loop.
                        try {
                            client.soTimeout = firstFrameTimeoutMs
                        } catch (e: Exception) {
                            closeQuietly(client)
                            continue
                        }
                        launch { handleIncomingConnection(client) }
                    }
                } catch (e: Exception) {
                    // Closing the socket in stopServer() ends accept() with an exception.
                    if (isActive && !server.isClosed) {
                        _syncState.value = SyncState.ERROR
                    }
                }
            }
        }
    }

    fun stopServer() {
        synchronized(serverLock) {
            serverJob?.cancel()
            serverJob = null
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            serverSocket = null
        }
        disconnect()
    }

    fun close() {
        stopServer()
        scope.coroutineContext.cancelChildren()
    }

    private suspend fun handleIncomingConnection(client: Socket) {
        synchronized(connectionLock) {
            if (connection != null || handshakeSocket != null) {
                closeQuietly(client)
                return
            }
            handshakeSocket = client
        }

        val accepted = try {
            val syncCandidates = loadSyncCandidates()
            SecureChannel.accept(
                DataInputStream(client.getInputStream()),
                DataOutputStream(client.getOutputStream())
            ) { mode ->
                when (mode) {
                    HandshakeMode.PAIR -> pairingSecret?.let { listOf<Pair<PairedDevice?, ByteArray>>(null to it) } ?: emptyList()
                    HandshakeMode.SYNC -> syncCandidates.map { (device, key) -> Pair<PairedDevice?, ByteArray>(device, key) }
                }
            }
        } catch (e: LegacyPeerException) {
            notifyLegacyPeer()
            null
        } catch (e: CancellationException) {
            releaseHandshakeSlot(client)
            closeQuietly(client)
            throw e
        } catch (e: Exception) {
            null
        }

        if (accepted == null) {
            // Not one of our devices (or an old app): close without answering.
            releaseHandshakeSlot(client)
            closeQuietly(client)
            return
        }

        val conn = Connection(client, accepted.channel, accepted.channel.mode, SyncRole.RESPONDER, accepted.peer)
        val installed = synchronized(connectionLock) {
            if (handshakeSocket === client) {
                handshakeSocket = null
                resetSession()
                connection = conn
                true
            } else {
                false // disconnect() ran during the handshake
            }
        }
        if (!installed) {
            conn.channel.destroy()
            closeQuietly(client)
            return
        }
        try {
            client.soTimeout = idleReadTimeoutMs
        } catch (_: Exception) {
            // Harmless: a socket that can't take the idle limit keeps the handshake one.
        }
        _syncState.value = if (conn.mode == HandshakeMode.PAIR) SyncState.PAIRING else SyncState.IDLE
        startKeepAlive(conn)
        readLoop(conn)
    }

    private fun releaseHandshakeSlot(socket: Socket) {
        synchronized(connectionLock) {
            if (handshakeSocket === socket) handshakeSocket = null
        }
    }

    /** Paired devices with their pair keys; needs an unlocked vault. */
    private suspend fun loadSyncCandidates(): List<Pair<PairedDevice, ByteArray>> {
        val repository = pairedDeviceRepository ?: return emptyList()
        val protector = pairKeyProtector ?: return emptyList()
        return repository.getPairedDevices().mapNotNull { device ->
            protector.open(device.sharedSecret)?.let { device to it }
        }
    }

    // --- Starting a sync ---

    /**
     * Connects to a paired device, runs the handshake with its pair key and sends a sync
     * request. The caller then waits for the peer's SyncAcceptance on [receiveFrames].
     */
    suspend fun startOutgoingSync(device: PairedDevice): OutgoingSyncResult = withContext(Dispatchers.IO) {
        val ip = device.ipAddress?.takeIf { it.isNotBlank() } ?: return@withContext OutgoingSyncResult.UNREACHABLE
        val pairKey = pairKeyProtector?.open(device.sharedSecret) ?: return@withContext OutgoingSyncResult.NOT_PAIRED

        disconnect()
        val socket = Socket()
        synchronized(connectionLock) { handshakeSocket = socket }
        try {
            socket.connect(InetSocketAddress(ip, device.port), SyncTimeouts.HANDSHAKE_MS)
            socket.soTimeout = firstFrameTimeoutMs
        } catch (e: Exception) {
            releaseHandshakeSlot(socket)
            closeQuietly(socket)
            pairKey.fill(0)
            return@withContext OutgoingSyncResult.UNREACHABLE
        }

        val channel = try {
            SecureChannel.initiate(
                DataInputStream(socket.getInputStream()),
                DataOutputStream(socket.getOutputStream()),
                HandshakeMode.SYNC,
                pairKey
            )
        } catch (e: Exception) {
            releaseHandshakeSlot(socket)
            closeQuietly(socket)
            return@withContext OutgoingSyncResult.FAILED
        } finally {
            pairKey.fill(0)
        }

        val conn = Connection(socket, channel, HandshakeMode.SYNC, SyncRole.INITIATOR, device)
        val installed = synchronized(connectionLock) {
            if (handshakeSocket === socket) {
                handshakeSocket = null
                resetSession()
                connection = conn
                true
            } else {
                false
            }
        }
        if (!installed) {
            channel.destroy()
            closeQuietly(socket)
            return@withContext OutgoingSyncResult.FAILED
        }
        try {
            socket.soTimeout = idleReadTimeoutMs
        } catch (_: Exception) {
            // Harmless: a socket that can't take the idle limit keeps the handshake one.
        }
        _syncState.value = SyncState.WAITING_FOR_REMOTE_APPROVAL
        startKeepAlive(conn)
        scope.launch(Dispatchers.IO) { readLoop(conn) }

        if (!sendFrame(SyncFrame.SyncRequest(deviceId = localDeviceId, deviceName = localDeviceName))) {
            closeConnection(conn)
            return@withContext OutgoingSyncResult.FAILED
        }
        OutgoingSyncResult.STARTED
    }

    // --- Receiving ---

    private fun readLoop(conn: Connection) {
        try {
            while (true) {
                val bytes = conn.channel.receive()
                val frame = try {
                    json.decodeFromString(SyncFrame.serializer(), String(bytes, Charsets.UTF_8))
                } catch (e: Exception) {
                    throw SyncProtocolException("Malformed sync message")
                }
                if (!acceptInboundFrame(conn, frame)) {
                    lastFailure = SyncError.PROTOCOL
                    throw SyncProtocolException("${frame::class.simpleName} is not allowed in this session")
                }
                // A keep-alive only proves the peer is alive (the socket read did that). It never
                // reaches the view model, so it can't hold off a session time limit.
                if (frame is SyncFrame.KeepAlive) continue
                // Update the session first: listeners react to the frame by checking it
                // (e.g. canSendVaultData() right after a SyncAcceptance).
                handleFrame(conn, frame)
                if (!eventFlow.tryEmit(SessionEvent.Received(frame)) &&
                    (frame is SyncFrame.PayloadBatch || frame is SyncFrame.MergePlanBatch)
                ) {
                    // A dropped part would corrupt the transfer: end the session instead.
                    throw SyncProtocolException("Sync data arrived faster than it could be processed")
                }
            }
        } catch (e: Exception) {
            // Closed, timed out, or broke the protocol: keep the reason so the user is told.
            // A connection this device already closed on purpose has nothing to report.
            if (lastFailure == null && connection === conn) lastFailure = failureFor(e)
        } finally {
            endConnection(conn)
        }
    }

    private fun failureFor(e: Exception): SyncError = when (e) {
        is LegacyPeerException -> SyncError.PEER_OUTDATED
        is SyncProtocolException -> SyncError.PROTOCOL
        is SocketTimeoutException -> SyncError.TIMED_OUT
        else -> SyncError.CONNECTION_LOST
    }

    /** What the peer may send, by connection mode, role and progress. Anything else ends the connection. */
    private fun acceptInboundFrame(conn: Connection, frame: SyncFrame): Boolean = when (conn.mode) {
        HandshakeMode.PAIR -> when (frame) {
            is SyncFrame.PairingInfo -> conn.role == SyncRole.RESPONDER && receivedPairingInfo == null
            is SyncFrame.CancelSync -> true
            else -> false
        }
        HandshakeMode.SYNC -> when (frame) {
            is SyncFrame.SyncRequest ->
                conn.role == SyncRole.RESPONDER && !receivedSyncRequest && frame.deviceId == conn.peer?.deviceId
            is SyncFrame.SyncAcceptance -> conn.role == SyncRole.INITIATOR && !receivedSyncAcceptance
            is SyncFrame.PayloadBatch -> when (conn.role) {
                SyncRole.INITIATOR -> remoteAcceptedSync
                SyncRole.RESPONDER -> receivedSyncRequest && incomingSyncApprovedLocally
                SyncRole.NONE -> false
            }
            is SyncFrame.MergePlanBatch ->
                conn.role == SyncRole.RESPONDER && incomingSyncApprovedLocally && !planComplete
            is SyncFrame.MergePlanDecision ->
                conn.role == SyncRole.INITIATOR && planSent && !decisionReceived
            is SyncFrame.SyncFinished -> conn.role != SyncRole.NONE && !finishedReceived
            is SyncFrame.KeepAlive -> conn.role != SyncRole.NONE
            is SyncFrame.CancelSync -> true
            else -> false
        }
    }

    private fun handleFrame(conn: Connection, frame: SyncFrame) {
        when (frame) {
            is SyncFrame.PairingInfo -> {
                receivedPairingInfo = frame
                _pendingPairingInfo.value = frame
                _syncState.value = SyncState.AWAITING_LOCAL_APPROVAL
            }
            is SyncFrame.SyncRequest -> {
                receivedSyncRequest = true
                _pendingSyncRequest.value = frame
                _syncState.value = SyncState.AWAITING_LOCAL_APPROVAL
            }
            is SyncFrame.SyncAcceptance -> {
                receivedSyncAcceptance = true
                if (frame.isAccepted) {
                    remoteAcceptedSync = true
                    _syncState.value = SyncState.SYNCING
                } else {
                    if (lastFailure == null) lastFailure = SyncError.DECLINED
                    closeConnection(conn)
                }
            }
            is SyncFrame.MergePlanBatch -> if (frame.last) {
                planComplete = true
                planReceived = true
            }
            is SyncFrame.MergePlanDecision -> decisionReceived = true
            is SyncFrame.SyncFinished -> finishedReceived = true
            is SyncFrame.CancelSync -> {
                // The reason is read here, before the socket goes, and kept in lastFailure.
                if (lastFailure == null) {
                    lastFailure = if (frame.code.isBlank()) SyncError.CANCELLED else SyncError.fromCode(frame.code)
                }
                closeConnection(conn)
            }
            else -> {}
        }
    }

    // --- Sending ---

    /** Encrypts and sends [frame] if the current session allows it. */
    suspend fun sendFrame(frame: SyncFrame): Boolean = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext false
        if (!mayOutbound(conn, frame)) return@withContext false
        try {
            conn.channel.send(json.encodeToString(SyncFrame.serializer(), frame).toByteArray(Charsets.UTF_8))
            if (frame is SyncFrame.MergePlanBatch && frame.last) planSent = true
            true
        } catch (e: Exception) {
            // The caller ends the session; this keeps the reason for it.
            if (lastFailure == null) lastFailure = failureFor(e)
            false
        }
    }

    private fun mayOutbound(conn: Connection, frame: SyncFrame): Boolean = when (frame) {
        is SyncFrame.PairingAcceptance -> conn.mode == HandshakeMode.PAIR && conn.role == SyncRole.RESPONDER
        is SyncFrame.SyncRequest -> conn.mode == HandshakeMode.SYNC && conn.role == SyncRole.INITIATOR
        is SyncFrame.SyncAcceptance ->
            conn.mode == HandshakeMode.SYNC && conn.role == SyncRole.RESPONDER && receivedSyncRequest
        is SyncFrame.PayloadBatch -> canSendVaultData()
        is SyncFrame.MergePlanBatch -> conn.role == SyncRole.INITIATOR && remoteAcceptedSync
        is SyncFrame.MergePlanDecision -> conn.role == SyncRole.RESPONDER && planReceived
        is SyncFrame.SyncFinished -> conn.role != SyncRole.NONE
        is SyncFrame.KeepAlive -> conn.mode == HandshakeMode.SYNC && conn.role != SyncRole.NONE
        is SyncFrame.CancelSync -> true
        is SyncFrame.PairingInfo -> false
    }

    /**
     * Keeps [conn] from reaching its idle limit while nobody has anything to send (a long review).
     * A keep-alive that can't be sent ends the connection like any failed send.
     */
    private fun startKeepAlive(conn: Connection) {
        if (conn.mode != HandshakeMode.SYNC || conn.role == SyncRole.NONE) return
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(keepAliveMs)
                if (connection !== conn) return@launch
                if (!sendFrame(SyncFrame.KeepAlive(System.currentTimeMillis()))) {
                    if (connection === conn) closeConnection(conn)
                    return@launch
                }
            }
        }
    }

    /** Sends [records] as numbered PayloadBatch parts, each under the frame size limit. */
    suspend fun sendPayloadBatch(records: List<SyncRecord>): Boolean {
        val chunks = SyncRecords.encodeChunks(records)
        chunks.forEachIndexed { i, chunk ->
            if (!sendFrame(SyncFrame.PayloadBatch(chunk, part = i, last = i == chunks.lastIndex))) return false
        }
        return true
    }

    /** Sends the reviewed plan as numbered parts; the fingerprint travels in the last one. */
    suspend fun sendMergePlan(items: List<PlanItem>, expectedFingerprint: String): Boolean {
        val chunks = MergePlans.encodeChunks(items)
        chunks.forEachIndexed { i, chunk ->
            val last = i == chunks.lastIndex
            val sent = sendFrame(
                SyncFrame.MergePlanBatch(
                    planJson = chunk,
                    part = i,
                    last = last,
                    expectedFingerprint = if (last) expectedFingerprint else ""
                )
            )
            if (!sent) return false
        }
        return true
    }

    /** Best effort: tells the peer why the sync ended, and remembers it for the UI. */
    suspend fun sendCancel(error: SyncError, reason: String = error.message): Boolean {
        if (lastFailure == null) lastFailure = error
        return sendFrame(SyncFrame.CancelSync(reason = reason, code = error.code))
    }

    // --- Sync session decisions ---

    /** True when this device may send its vault: a sync with a paired device that both sides agreed to. */
    fun canSendVaultData(): Boolean {
        val conn = connection ?: return false
        if (conn.mode != HandshakeMode.SYNC) return false
        return when (conn.role) {
            SyncRole.INITIATOR -> remoteAcceptedSync
            SyncRole.RESPONDER -> receivedSyncRequest && incomingSyncApprovedLocally
            SyncRole.NONE -> false
        }
    }

    /** True while a sync with a paired device has been agreed by the local user and the peer. */
    fun isSyncSessionActive(): Boolean = canSendVaultData()

    /** Records the local user's approval of the pending incoming sync request. */
    fun approveIncomingSync(): Boolean {
        val conn = connection ?: return false
        if (conn.mode != HandshakeMode.SYNC || conn.role != SyncRole.RESPONDER) return false
        if (!receivedSyncRequest || _pendingSyncRequest.value == null) return false
        incomingSyncApprovedLocally = true
        _pendingSyncRequest.value = null
        return true
    }

    /** Called by the local user clicking "Accept & Compare" in the incoming sync dialog. */
    suspend fun acceptSync(): Boolean {
        if (!approveIncomingSync()) return false
        _syncState.value = SyncState.SYNCING
        return sendFrame(SyncFrame.SyncAcceptance(deviceId = localDeviceId, isAccepted = true))
    }

    /** Called by the local user clicking "Decline" in the incoming sync dialog. */
    fun declineSync(): Job {
        _pendingSyncRequest.value = null
        return scope.launch {
            try {
                sendFrame(SyncFrame.SyncAcceptance(deviceId = localDeviceId, isAccepted = false))
                delay(200L) // Let the answer reach the peer before closing.
            } catch (_: Exception) {
                // Harmless: the peer is gone either way; the user declined.
            }
            disconnect()
        }
    }

    // --- Pairing decisions ---

    /**
     * Called by the local user clicking "Accept" in the pairing dialog: stores the phone with the
     * pair key from the handshake (sealed with the vault key) and tells the phone.
     */
    fun acceptPairing(): Job? {
        val conn = connection?.takeIf { it.mode == HandshakeMode.PAIR } ?: return null
        val info = receivedPairingInfo ?: return null
        if (pairingDecided) return null
        pairingDecided = true
        _pendingPairingInfo.value = null

        val job = scope.launch {
            val repository = pairedDeviceRepository
            val notStored = "Could not store the pairing key. Make sure the vault is unlocked, then pair again."
            if (repository == null) {
                failPairing(conn, notStored)
                return@launch
            }
            val saved = try {
                // Sealed inside the transaction: a key sealed while the vault locks is never kept.
                repository.transaction {
                    val sealed = conn.channel.pairKey?.let { pairKeyProtector?.seal(it) } ?: return@transaction false
                    savePairedDevice(
                        PairedDevice(
                            deviceId = info.deviceId,
                            deviceName = info.deviceName,
                            sharedSecret = sealed,
                            ipAddress = conn.socket.inetAddress?.hostAddress,
                            port = port,
                            pairedAt = System.currentTimeMillis(),
                            isOnline = true
                        )
                    )
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DatabaseClosedException) {
                failPairing(conn, notStored)
                return@launch
            } catch (e: Exception) {
                failPairing(conn, "Could not save the paired device: ${e.message}")
                return@launch
            }
            if (!saved) {
                failPairing(conn, notStored)
                return@launch
            }
            if (sendFrame(SyncFrame.PairingAcceptance(deviceId = localDeviceId, isAccepted = true))) {
                pairingSecret = null // A QR code pairs one device.
                _syncState.value = SyncState.PAIRED
            } else {
                // The phone never learned it was accepted; don't keep a one-sided pairing.
                try {
                    repository.unpairDevice(info.deviceId)
                } catch (_: Exception) {}
                closeConnection(conn)
            }
        }
        pairingSaveJob = job
        return job
    }

    private suspend fun failPairing(conn: Connection, details: String) {
        sendFrame(SyncFrame.PairingAcceptance(deviceId = localDeviceId, isAccepted = false))
        onTransportError?.invoke("Pairing Failed", details)
        delay(200L)
        closeConnection(conn)
    }

    /** Called by the local user clicking "Decline" in the pairing dialog. */
    fun declinePairing(): Job {
        val conn = connection?.takeIf { it.mode == HandshakeMode.PAIR }
        pairingDecided = true
        _pendingPairingInfo.value = null
        return scope.launch {
            if (conn != null) {
                try {
                    sendFrame(SyncFrame.PairingAcceptance(deviceId = localDeviceId, isAccepted = false))
                    delay(200L) // Let the answer reach the phone before closing.
                } catch (_: Exception) {}
                closeConnection(conn)
            }
        }
    }

    // --- Closing ---

    /** Closes the connection's socket; its read loop then ends and cleans up. */
    private fun closeConnection(conn: Connection) {
        closeQuietly(conn.socket)
    }

    /** Called when a connection's read loop ends for any reason. */
    private fun endConnection(conn: Connection) {
        if (connection === conn) keepAliveJob?.cancel()
        conn.channel.destroy()
        closeQuietly(conn.socket)
        val wasCurrent = synchronized(connectionLock) {
            if (connection === conn) {
                connection = null
                true
            } else {
                false
            }
        }
        if (wasCurrent) {
            val reason = lastFailure
            resetSession()
            lastFailure = reason // stays readable until the next session starts
            val ended = SessionEvent.Ended(reason)
            // Never lose the end of a session: wait for buffer space if the listener is busy.
            if (!eventFlow.tryEmit(ended)) scope.launch { eventFlow.emit(ended) }
            _pendingPairingInfo.value = null
            _pendingSyncRequest.value = null
            // PAIRED stays visible after the phone hangs up, so the UI can react to it.
            if (_syncState.value != SyncState.PAIRED && _syncState.value != SyncState.ERROR) {
                _syncState.value = SyncState.IDLE
            }
        }
    }

    fun disconnect() {
        val conn: Connection?
        val pending: Socket?
        synchronized(connectionLock) {
            conn = connection
            pending = handshakeSocket
            connection = null
            handshakeSocket = null
            val reason = lastFailure
            resetSession()
            lastFailure = reason // cleared only when the next session starts
        }
        pending?.let { closeQuietly(it) }
        conn?.let {
            it.channel.destroy()
            closeQuietly(it.socket)
        }
        _pendingPairingInfo.value = null
        _pendingSyncRequest.value = null
        if (_syncState.value != SyncState.ERROR) {
            _syncState.value = SyncState.IDLE
        }
    }

    private fun resetSession() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        receivedPairingInfo = null
        pairingDecided = false
        receivedSyncRequest = false
        receivedSyncAcceptance = false
        remoteAcceptedSync = false
        incomingSyncApprovedLocally = false
        planSent = false
        planComplete = false
        planReceived = false
        decisionReceived = false
        finishedReceived = false
        lastFailure = null
    }

    private fun notifyLegacyPeer() {
        val now = System.currentTimeMillis()
        if (now - lastLegacyNoticeAt < LEGACY_NOTICE_INTERVAL_MS) return
        lastLegacyNoticeAt = now
        onTransportError?.invoke(
            "Update Needed",
            "A device running an older version of VaultPass tried to connect. " +
                "Update VaultPass on your phone, then pair it again."
        )
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {}
    }

    internal fun emitFrameForTesting(frame: SyncFrame) {
        eventFlow.tryEmit(SessionEvent.Received(frame))
    }
}
