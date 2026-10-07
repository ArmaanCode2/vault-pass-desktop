package com.vaultpass.synccore

import kotlinx.serialization.Serializable

/**
 * Discovery beacon of sync protocol v2. It carries no device ID or name: only a random nonce, a
 * timestamp, the listening port, and one short tag per pairing, computed with that pairing's key.
 * Only a paired device can recognise a beacon, and only a fresh, authentic beacon may update a
 * paired device's address.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
@Serializable
data class BeaconV2(
    val type: String = TYPE,
    val v: Int = Handshake.PROTOCOL_VERSION,
    val n: String,
    val ts: Long,
    val port: Int,
    val tags: List<String>
) {
    companion object {
        const val TYPE = "BEACON"
    }
}

object Beacons {
    private const val NONCE_BYTES = 16
    private const val TAG_BYTES = 8
    private const val MAX_TAGS = 64

    private val LABEL = "VaultPass-v2 beacon".toByteArray(Charsets.UTF_8)

    fun tag(pairKey: ByteArray, nonce: ByteArray, timestampMs: Long, port: Int): ByteArray =
        SyncCrypto.hmacSha256(pairKey, LABEL, nonce, SyncCrypto.u64(timestampMs), SyncCrypto.u32(port))
            .copyOf(TAG_BYTES)

    fun create(
        pairKeys: List<ByteArray>,
        port: Int,
        nowMs: Long,
        nonce: ByteArray = SyncCrypto.randomBytes(NONCE_BYTES)
    ): BeaconV2 = BeaconV2(
        n = Hex.encode(nonce),
        ts = nowMs,
        port = port,
        tags = pairKeys.take(MAX_TAGS).map { Hex.encode(tag(it, nonce, nowMs, port)) }
    )

    fun encode(beacon: BeaconV2): String = Handshake.json.encodeToString(BeaconV2.serializer(), beacon)

    /** Returns the beacon if [text] is a well-formed v2 beacon, otherwise null. */
    fun parse(text: String): BeaconV2? {
        val beacon = try {
            Handshake.json.decodeFromString(BeaconV2.serializer(), text)
        } catch (e: Exception) {
            return null
        }
        if (beacon.type != BeaconV2.TYPE || beacon.v != Handshake.PROTOCOL_VERSION) return null
        if (beacon.port !in 1..65535 || beacon.tags.size > MAX_TAGS) return null
        val nonceOk = try { Hex.decode(beacon.n).size == NONCE_BYTES } catch (e: IllegalArgumentException) { false }
        if (!nonceOk) return null
        return beacon
    }

    /**
     * Returns the candidates whose pair key produced one of the beacon's tags. Whether the beacon
     * is fresh is decided separately, by [BeaconTracker].
     */
    fun <T> identify(beacon: BeaconV2, candidates: List<Pair<T, ByteArray>>): List<T> {
        val nonce = try { Hex.decode(beacon.n) } catch (e: IllegalArgumentException) { return emptyList() }
        val received = beacon.tags.mapNotNull { tag ->
            try { Hex.decode(tag) } catch (e: IllegalArgumentException) { null }
        }
        return candidates.filter { (_, key) ->
            val expected = tag(key, nonce, beacon.ts, beacon.port)
            received.any { SyncCrypto.constantTimeEquals(it, expected) }
        }.map { it.first }
    }
}

/**
 * Decides which authentic beacons to believe, without relying on the devices' clocks agreeing:
 * our own broadcasts are ignored, and for each paired device only a beacon newer than the last
 * one seen from it is accepted, so a recorded beacon can't be replayed to redirect the device.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
class BeaconTracker {
    private val ownNonces = ArrayDeque<String>()
    private val lastTimestamps = HashMap<String, Long>()

    /** Call for every beacon we send, so we don't mistake it for a peer's. */
    @Synchronized
    fun recordOwn(beacon: BeaconV2) {
        ownNonces.addLast(beacon.n)
        while (ownNonces.size > MAX_OWN_NONCES) ownNonces.removeFirst()
    }

    @Synchronized
    fun isOwn(beacon: BeaconV2): Boolean = ownNonces.contains(beacon.n)

    /** True if [beacon] is newer than anything seen from [deviceId]; remembers it if so. */
    @Synchronized
    fun acceptFrom(deviceId: String, beacon: BeaconV2): Boolean {
        val last = lastTimestamps[deviceId]
        if (last != null && beacon.ts <= last) return false
        lastTimestamps[deviceId] = beacon.ts
        return true
    }

    @Synchronized
    fun reset() {
        ownNonces.clear()
        lastTimestamps.clear()
    }

    private companion object {
        const val MAX_OWN_NONCES = 64
    }
}
