package com.vaultpass.desktop.data.sync

import com.vaultpass.desktop.domain.db.DatabaseClosedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.synccore.BeaconTracker
import com.vaultpass.synccore.BeaconV2
import com.vaultpass.synccore.Beacons
import com.vaultpass.synccore.PairKeyProtector
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class DevicePresence(
    val deviceId: String,
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val lastSeen: Long
)

/**
 * Finds paired devices on the local network over UDP broadcast, with v2 beacons (see
 * com.vaultpass.synccore.Beacons): a beacon names no device, and only a paired device can
 * recognise it by its pair key. Beacons go out every 3s; a device counts as online for 10s.
 */
class LanDiscoveryManager(
    val deviceId: String,
    val deviceName: String,
    private val pairedDeviceRepository: PairedDeviceRepository? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val beaconPort: Int = 53852,
    private val tcpPort: Int = 53853,
    private val pairKeyProtector: PairKeyProtector? = null
) {
    /** Paired devices with their pair keys, reloaded every beacon cycle (needs an unlocked vault). */
    @Volatile
    private var pairedKeys: List<Pair<PairedDevice, ByteArray>> = emptyList()

    private val tracker = BeaconTracker()

    private val _discoveredDevices = MutableStateFlow<Map<String, DevicePresence>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DevicePresence>> = _discoveredDevices.asStateFlow()

    @Volatile
    private var broadcastJob: Job? = null

    @Volatile
    private var listenerJob: Job? = null

    @Volatile
    private var socket: DatagramSocket? = null

    val isRunning: Boolean
        get() = broadcastJob?.isActive == true || listenerJob?.isActive == true

    fun start() {
        if (isRunning) return

        try {
            socket = DatagramSocket(beaconPort).apply {
                broadcast = true
                reuseAddress = true
            }
        } catch (e: Exception) {
            // If port 53852 is temporarily bound, create an ephemeral socket for sending
            try {
                socket = DatagramSocket().apply { broadcast = true }
            } catch (_: Exception) {}
        }

        // 1. Start Listener Loop
        listenerJob = scope.launch {
            val listenSocket = socket ?: return@launch
            val buffer = ByteArray(2048)

            while (isActive && !listenSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    listenSocket.receive(packet)

                    val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    handleIncomingPacket(message, packet.address.hostAddress)
                } catch (e: Exception) {
                    if (!isActive || listenSocket.isClosed) break
                }
            }
        }

        // 2. Start Periodic Broadcast & Prune Loop (every 3 seconds)
        broadcastJob = scope.launch {
            while (isActive) {
                refreshPairedKeys()
                broadcastBeacon()
                pruneStaleDevices()
                delay(3000L)
            }
        }
    }

    fun stop() {
        broadcastJob?.cancel()
        listenerJob?.cancel()
        broadcastJob = null
        listenerJob = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        pairedKeys = emptyList()
        tracker.reset()
        storedLocations.clear()
        seenLocations.clear()
        _discoveredDevices.value = emptyMap()
    }

    fun refreshNow() {
        scope.launch {
            broadcastPing()
        }
    }

    private suspend fun refreshPairedKeys() {
        val repository = pairedDeviceRepository ?: return
        val protector = pairKeyProtector ?: return
        pairedKeys = try {
            repository.getPairedDevices().mapNotNull { device ->
                protector.open(device.sharedSecret)?.let { device to it }
            }
        } catch (e: Exception) {
            emptyList()
        }
        // What the database holds is what a beacon is compared with (a re-pairing may have moved it).
        for ((device, _) in pairedKeys) {
            val ip = device.ipAddress
            if (ip != null) storedLocations[device.deviceId] = ip to device.port else storedLocations.remove(device.deviceId)
        }
    }

    /** Loads the pair keys now instead of waiting for the next beacon cycle. */
    internal suspend fun refreshPairedKeysForTesting() = refreshPairedKeys()

    private fun handleIncomingPacket(message: String, senderIp: String) {
        try {
            if (message == "PING") {
                // Another device requested immediate discovery; reply with our beacon
                broadcastBeacon()
                return
            }

            val beacon = Beacons.parse(message) ?: return
            if (tracker.isOwn(beacon)) return

            val now = System.currentTimeMillis()
            for (device in Beacons.identify(beacon, pairedKeys)) {
                // Only a beacon newer than the last one from this device counts, so a recorded
                // beacon can't be replayed to redirect it.
                if (!tracker.acceptFrom(device.deviceId, beacon)) continue
                _discoveredDevices.value = _discoveredDevices.value + (device.deviceId to DevicePresence(
                    deviceId = device.deviceId,
                    deviceName = device.deviceName,
                    ipAddress = senderIp,
                    port = beacon.port,
                    lastSeen = now
                ))
                // An authentic beacon: remember where the paired device is.
                onAuthenticBeacon(device.deviceId, senderIp, beacon.port)
            }
        } catch (_: Exception) {
            // Ignore malformed packets
        }
    }

    /** Where each paired device is, as last stored (or read back from the database). */
    private val storedLocations = ConcurrentHashMap<String, Pair<String, Int>>()

    /** Where each paired device was last seen, waiting to be stored. */
    private val seenLocations = ConcurrentHashMap<String, Pair<String, Int>>()

    /** Devices whose location is being written; at most one write per device at a time. */
    private val writingLocations: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Stores where an authentic beacon says [deviceId] is. Nothing is written when that is where
     * it is already stored, and only one write per device runs at a time (it stores the newest
     * location seen). Returns the write it started, if any. A lock meanwhile, or any other
     * failure, is never a crash: a later beacon tries again.
     */
    internal fun onAuthenticBeacon(deviceId: String, ipAddress: String, port: Int): Job? {
        val repository = pairedDeviceRepository ?: return null
        val location = ipAddress to port
        seenLocations[deviceId] = location
        if (storedLocations[deviceId] == location) return null
        if (!writingLocations.add(deviceId)) return null // The running write picks it up.
        return scope.launch {
            var caughtUp = false
            try {
                while (true) {
                    val next = seenLocations[deviceId] ?: break
                    if (storedLocations[deviceId] == next) break
                    repository.updateNetworkLocation(deviceId, next.first, next.second)
                    storedLocations[deviceId] = next
                }
                caughtUp = true
            } catch (_: DatabaseClosedException) {
                // Locked meanwhile; a beacon after unlocking records it.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("[WARN] LanDiscoveryManager: couldn't save a paired device's address (${e.javaClass.simpleName})")
            } finally {
                beforeLocationWriteEndsForTesting?.invoke()
                writingLocations.remove(deviceId)
            }
            // A location seen after the loop's last check found this write still running and left
            // it to this one: store it too. Not after a failure, which would only fail again.
            if (caughtUp) {
                seenLocations[deviceId]?.let { (ip, port) -> onAuthenticBeacon(deviceId, ip, port)?.join() }
            }
        }
    }

    /** Test-only: runs between a location write's last check and its letting go of the device. */
    @Volatile
    internal var beforeLocationWriteEndsForTesting: (() -> Unit)? = null

    /** Our beacon, or null when there is no paired device to be found by. */
    private fun createBeacon(): BeaconV2? {
        val keys = pairedKeys
        if (keys.isEmpty()) return null
        return Beacons.create(keys.map { it.second }, tcpPort, System.currentTimeMillis())
            .also { tracker.recordOwn(it) }
    }

    private fun broadcastBeacon() {
        val currentSocket = socket ?: return
        if (currentSocket.isClosed) return

        try {
            val beacon = createBeacon() ?: return
            sendToAllBroadcastTargets(currentSocket, Beacons.encode(beacon).toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    private fun broadcastPing() {
        val currentSocket = socket ?: return
        if (currentSocket.isClosed) return

        try {
            val bytes = "PING".toByteArray(Charsets.UTF_8)
            sendToAllBroadcastTargets(currentSocket, bytes)
        } catch (_: Exception) {}
    }

    private fun sendToAllBroadcastTargets(s: DatagramSocket, bytes: ByteArray) {
        // 1. General 255.255.255.255 broadcast
        try {
            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), beaconPort)
            s.send(packet)
        } catch (_: Exception) {}

        // 2. Subnet-directed broadcasts across all active interfaces (Wi-Fi, hotspot, etc.)
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (ifaceAddr in intf.interfaceAddresses) {
                    val broadcast = ifaceAddr.broadcast
                    if (broadcast != null) {
                        try {
                            s.send(DatagramPacket(bytes, bytes.size, broadcast, beaconPort))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun pruneStaleDevices() {
        val now = System.currentTimeMillis()
        val current = _discoveredDevices.value
        val pruned = current.filterValues { now - it.lastSeen < 10_000L }
        if (pruned.size != current.size) {
            _discoveredDevices.value = pruned
        }
    }

    /**
     * Returns all active, reachable IPv4 addresses for this PC.
     * Filters out loopback (127.0.0.1), link-local APIPA (169.254.x.x), and virtual network bridges.
     */
    fun getAllLocalIpAddresses(): List<String> {
        val results = mutableListOf<String>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                val displayName = intf.displayName.lowercase()
                // Ignore virtual/container adapters
                if (name.contains("veth") || name.contains("wsl") || name.contains("docker") ||
                    displayName.contains("virtual") || displayName.contains("vmware") || displayName.contains("hyper-v")
                ) {
                    continue
                }
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress
                        if (!host.startsWith("169.254.")) {
                            results.add(host)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        // Prioritize Wi-Fi/Home router subnets (192.168.1.x, 192.168.0.x), Hotspot (192.168.43.x), then others
        fun ipPriority(ip: String): Int = when {
            ip.startsWith("192.168.1.") -> 1
            ip.startsWith("192.168.0.") -> 2
            ip.startsWith("192.168.43.") -> 3
            ip.startsWith("192.168.") -> 4
            ip.startsWith("10.") -> 5
            ip.startsWith("172.") -> 6
            else -> 7
        }
        return results.distinct().sortedWith(Comparator { a, b ->
            val pA = ipPriority(a)
            val pB = ipPriority(b)
            if (pA != pB) pA.compareTo(pB) else a.compareTo(b)
        }).ifEmpty { listOf("127.0.0.1") }
    }

    fun getLocalIpAddress(): String {
        return getAllLocalIpAddresses().firstOrNull() ?: "127.0.0.1"
    }
}
