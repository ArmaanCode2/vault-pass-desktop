package com.vaultpass.desktop.ui.viewmodels

import androidx.compose.ui.graphics.ImageBitmap
import com.vaultpass.desktop.data.sync.LanDiscoveryManager
import com.vaultpass.desktop.data.sync.LanSocketTransport
import com.vaultpass.desktop.data.sync.PairedDeviceRepository
import com.vaultpass.desktop.data.sync.QrCodeGenerator
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.sync.PairingProtocol
import com.vaultpass.desktop.domain.sync.SyncState
import com.vaultpass.desktop.domain.sync.diff.*
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.desktop.domain.sync.models.SyncFrame
import com.vaultpass.desktop.domain.sync.models.SyncPayloadEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.UUID

class SyncViewModel(
    val pairedDeviceRepository: PairedDeviceRepository,
    val lanDiscoveryManager: LanDiscoveryManager,
    val lanSocketTransport: LanSocketTransport,
    val vaultRepository: VaultRepository,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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

    val pendingPairingRequest: StateFlow<SyncFrame.PairingRequest?> = lanSocketTransport.pendingPairingRequest

    val incomingRequest: StateFlow<SyncFrame.SyncRequest?> = lanSocketTransport.pendingSyncRequest

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private var syncTimeoutJob: Job? = null

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

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _activeError = MutableStateFlow<Pair<String, String?>?>(null)
    val activeError: StateFlow<Pair<String, String?>?> = _activeError.asStateFlow()

    fun showError(msg: String, details: String? = null) {
        _activeError.value = Pair(msg, details)
    }

    fun dismissError() {
        _activeError.value = null
    }

    init {
        // Automatically start discovery and TCP listener
        lanDiscoveryManager.start()
        lanSocketTransport.startServer()

        lanSocketTransport.onTransportError = { title, details ->
            showError(title, details)
        }

        lanDiscoveryManager.onReverseConnectRequested = { signal ->
            coroutineScope.launch(ioDispatcher) {
                // Automatically connect outward to phone
                val connected = lanSocketTransport.connectToPeer(signal.mobileIp, signal.mobilePort)
                if (connected) {
                    lanSocketTransport.initiatePairing(signal.token, signal.deviceId, signal.deviceName)
                }
            }
        }

        lanDiscoveryManager.onReverseSyncRequested = { signal ->
            coroutineScope.launch(ioDispatcher) {
                val device = pairedDevices.value.find { it.deviceId == signal.deviceId }
                    ?: pairedDeviceRepository.getPairedDevice(signal.deviceId)
                if (device != null) {
                    val targetDevice = device.copy(ipAddress = signal.mobileIp, port = signal.mobilePort)
                    initiateSyncWith(targetDevice)
                }
            }
        }

        // React to mutual pairing completion
        coroutineScope.launch {
            syncState.collect { state ->
                if (state == SyncState.PAIRED) {
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
                } else {
                    _isIncomingSyncDialogOpen.value = false
                }
            }
        }

        // Listen to incoming sync frames (SyncAcceptance, PayloadBatch, CancelSync)
        coroutineScope.launch {
            lanSocketTransport.receiveFrames().collect { frame ->
                when (frame) {
                    is SyncFrame.SyncAcceptance -> {
                        syncTimeoutJob?.cancel()
                        if (frame.isAccepted) {
                            _statusMessage.value = "Sync accepted. Exchanging vault records..."
                            val localResult = vaultRepository.observeAllEntries(false).first()
                            val localEntries = if (localResult is RepositoryResult.Success) localResult.data else emptyList()
                            lanSocketTransport.sendPayloadBatch(localEntries)
                        } else {
                            _isWaitingForRemoteApproval.value = false
                            _statusMessage.value = "Sync request was declined by ${_activeSyncingDevice.value?.deviceName ?: "peer"}."
                            _activeSyncingDevice.value = null
                        }
                    }
                    is SyncFrame.PayloadBatch -> {
                        syncTimeoutJob?.cancel()
                        try {
                            val dtoList = json.decodeFromString(
                                kotlinx.serialization.builtins.ListSerializer(SyncPayloadEntry.serializer()),
                                frame.encryptedBatchJson
                            )
                            val remoteEntries = dtoList.map { dto -> dto.toDomainVaultEntry() }
                            val localResult = vaultRepository.observeAllEntries(false).first()
                            val localEntries = if (localResult is RepositoryResult.Success) localResult.data else emptyList()
                            val deletedResult = vaultRepository.observeAllEntries(true).first()
                            val deletedEntries = if (deletedResult is RepositoryResult.Success) deletedResult.data else emptyList()
                            val diffResult = SyncDiffEngine.computeDiff(localEntries, remoteEntries, deletedEntries)
                            _activeDiffResult.value = diffResult
                            _isWaitingForRemoteApproval.value = false
                            _isSyncReviewOpen.value = true
                        } catch (e: Exception) {
                            _statusMessage.value = "Failed to process vault records: ${e.message}"
                            _isWaitingForRemoteApproval.value = false
                        }
                    }
                    is SyncFrame.CancelSync -> {
                        syncTimeoutJob?.cancel()
                        _isWaitingForRemoteApproval.value = false
                        _isSyncReviewOpen.value = false
                        _isIncomingSyncDialogOpen.value = false
                        _activeDiffResult.value = null
                        _activeSyncingDevice.value = null
                        _statusMessage.value = "Sync was cancelled by peer."
                    }
                    else -> {}
                }
            }
        }
    }

    fun startQrPairing(selectedIp: String? = null) {
        val pairingToken = UUID.randomUUID().toString().take(8).uppercase()
        val allIps = lanDiscoveryManager.getAllLocalIpAddresses()
        val primaryIp = selectedIp ?: allIps.firstOrNull() ?: "127.0.0.1"
        val ipsParam = allIps.joinToString(",")
        val payloadUri = "vaultpass://pair?deviceId=${lanDiscoveryManager.deviceId}" +
                "&name=${URLEncoder.encode(lanDiscoveryManager.deviceName, "UTF-8")}" +
                "&token=$pairingToken" +
                "&ip=$primaryIp" +
                "&ips=$ipsParam" +
                "&port=53853"
        val bitmap = QrCodeGenerator.generateQrBitmap(payloadUri, size = 300)
        _qrCodeBitmap.value = bitmap
        _isQrDialogOpen.value = true
        lanSocketTransport.startServer()
    }

    fun dismissQrPairing() {
        _isQrDialogOpen.value = false
        _qrCodeBitmap.value = null
        if (syncState.value == SyncState.AWAITING_LOCAL_APPROVAL || syncState.value == SyncState.WAITING_FOR_REMOTE_APPROVAL) {
            lanSocketTransport.disconnect()
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
            pairedDeviceRepository.unpairDevice(deviceId)
            _statusMessage.value = "Device unpaired"
        }
    }

    fun initiateSyncWith(device: PairedDevice): Job {
        _activeSyncingDevice.value = device
        return coroutineScope.launch(ioDispatcher) {
            val ip = device.ipAddress
            if (ip.isNullOrBlank()) {
                _statusMessage.value = "Cannot connect: Device IP is unknown"
                return@launch
            }
            _isWaitingForRemoteApproval.value = true
            _statusMessage.value = "Connecting to ${device.deviceName}..."
            val connected = lanSocketTransport.connectToPeer(ip, device.port, sharedSecret = device.sharedSecret)
            if (!connected) {
                _isWaitingForRemoteApproval.value = false
                _statusMessage.value = "Could not connect to ${device.deviceName} on port ${device.port}"
                return@launch
            }
            lanSocketTransport.activePeerDeviceId = device.deviceId
            lanSocketTransport.activePeerDeviceName = device.deviceName

            // Start 45-second timeout watchdog
            syncTimeoutJob?.cancel()
            syncTimeoutJob = coroutineScope.launch {
                kotlinx.coroutines.delay(45_000L)
                if (_isWaitingForRemoteApproval.value) {
                    _isWaitingForRemoteApproval.value = false
                    _statusMessage.value = "Sync timed out: No response from ${device.deviceName}"
                    try {
                        lanSocketTransport.sendFrame(SyncFrame.CancelSync(reason = "Sync request timed out after 45 seconds"))
                        kotlinx.coroutines.delay(200L)
                    } catch (_: Exception) {}
                    lanSocketTransport.disconnect()
                }
            }

            _statusMessage.value = "Waiting for ${device.deviceName} to accept sync..."
            val sent = lanSocketTransport.sendFrame(
                SyncFrame.SyncRequest(deviceId = lanSocketTransport.localDeviceId, deviceName = lanSocketTransport.localDeviceName),
                sharedSecret = null
            )
            if (!sent) {
                syncTimeoutJob?.cancel()
                _isWaitingForRemoteApproval.value = false
                _statusMessage.value = "Failed to send sync request."
                lanSocketTransport.disconnect()
            }
        }
    }

    fun setSimulatedDiffForTesting(diffResult: SyncDiffResult, device: PairedDevice) {
        _activeSyncingDevice.value = device
        _activeDiffResult.value = diffResult
        _isSyncReviewOpen.value = true
    }

    fun acceptIncomingRequest(): Job {
        lanSocketTransport.acceptSync()
        _isIncomingSyncDialogOpen.value = false

        return coroutineScope.launch(ioDispatcher) {
            _statusMessage.value = "Sync accepted. Exchanging vault records..."
            val localResult = vaultRepository.observeAllEntries(false).first()
            val localEntries = if (localResult is RepositoryResult.Success) localResult.data else emptyList()
            lanSocketTransport.sendPayloadBatch(localEntries)
        }
    }

    fun declineIncomingRequest(): Job {
        val job = lanSocketTransport.declineSync()
        _isIncomingSyncDialogOpen.value = false
        _activeSyncingDevice.value = null
        return job
    }

    fun toggleEntrySelection(syncKey: String, isSelected: Boolean) {
        val current = _activeDiffResult.value ?: return
        val updatedItems = current.diffItems.map { item ->
            if (item.syncKey == syncKey) {
                item.copy(isSelectedForSync = isSelected)
            } else {
                item
            }
        }
        _activeDiffResult.value = current.copy(diffItems = updatedItems)
    }

    fun toggleSelectAll(isSelected: Boolean) {
        val current = _activeDiffResult.value ?: return
        val updatedItems = current.diffItems.map { item ->
            item.copy(isSelectedForSync = isSelected)
        }
        _activeDiffResult.value = current.copy(diffItems = updatedItems)
    }

    fun updateEntryFieldOverride(syncKey: String, fieldName: String, newValue: String) {
        val current = _activeDiffResult.value ?: return
        val updatedItems = current.diffItems.map { item ->
            if (item.syncKey == syncKey) {
                val base = item.editedEntry ?: item.remoteEntry ?: item.localEntry
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

    fun applyRemoteValue(syncKey: String) {
        val current = _activeDiffResult.value ?: return
        val updatedItems = current.diffItems.map { item ->
            if (item.syncKey == syncKey && item.remoteEntry != null) {
                item.copy(editedEntry = item.remoteEntry.copy())
            } else {
                item
            }
        }
        _activeDiffResult.value = current.copy(diffItems = updatedItems)
    }

    fun applyLocalValue(syncKey: String) {
        val current = _activeDiffResult.value ?: return
        val updatedItems = current.diffItems.map { item ->
            if (item.syncKey == syncKey && item.localEntry != null) {
                item.copy(editedEntry = item.localEntry.copy())
            } else {
                item
            }
        }
        _activeDiffResult.value = current.copy(diffItems = updatedItems)
    }

    fun confirmAndMerge(onSuccess: (Int) -> Unit): Job? {
        val diffResult = _activeDiffResult.value ?: return null
        val device = _activeSyncingDevice.value

        return coroutineScope.launch(ioDispatcher) {
            val mergeResult = SyncMergeExecutor.executeMerge(diffResult.diffItems, vaultRepository)
            if (mergeResult.isSuccess) {
                val count = mergeResult.getOrThrow()
                device?.let { dev ->
                    pairedDeviceRepository.updateLastSync(dev.deviceId, System.currentTimeMillis())
                }
                _isSyncReviewOpen.value = false
                _activeDiffResult.value = null
                _activeSyncingDevice.value = null
                lanSocketTransport.disconnect()
                _statusMessage.value = "Successfully merged $count items"
                onSuccess(count)
            } else {
                _statusMessage.value = "Merge error: ${mergeResult.exceptionOrNull()?.message}"
            }
        }
    }

    fun cancelSyncRequest() {
        syncTimeoutJob?.cancel()
        _isWaitingForRemoteApproval.value = false
        _activeSyncingDevice.value = null
        coroutineScope.launch(ioDispatcher) {
            try {
                lanSocketTransport.sendFrame(SyncFrame.CancelSync(reason = "User cancelled sync request"))
                kotlinx.coroutines.delay(200L)
            } catch (_: Exception) {}
            lanSocketTransport.disconnect()
        }
        _statusMessage.value = "Sync cancelled"
    }

    fun cancelSync() {
        syncTimeoutJob?.cancel()
        _isWaitingForRemoteApproval.value = false
        _isSyncReviewOpen.value = false
        _activeDiffResult.value = null
        _activeSyncingDevice.value = null
        coroutineScope.launch(ioDispatcher) {
            try {
                lanSocketTransport.sendFrame(SyncFrame.CancelSync(reason = "User cancelled sync review"))
                kotlinx.coroutines.delay(200L)
            } catch (_: Exception) {}
            lanSocketTransport.disconnect()
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
