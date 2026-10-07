package com.vaultpass.desktop.data.sync

import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.desktop.domain.crypto.CryptoResult
import com.vaultpass.desktop.domain.models.EncryptionConfig
import com.vaultpass.desktop.domain.models.KdfConfig
import com.vaultpass.desktop.domain.security.SecureCharArray
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import com.vaultpass.desktop.domain.sync.models.SyncFrame
import com.vaultpass.synccore.HandshakeMode
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SecureChannel
import com.vaultpass.synccore.SyncCrypto
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

/** Encrypts with a fixed in-memory key, like the real vault key (DEK); fails while "locked". */
class FakeCryptoManager : CryptoManager {
    var locked = false
    private val dek = SyncCrypto.randomBytes(32)

    override fun encryptData(plaintext: ByteArray): ByteArray {
        check(!locked) { "Vault is locked" }
        val iv = SyncCrypto.randomBytes(12)
        return iv + SyncCrypto.aesGcmSeal(dek, iv, ByteArray(0), plaintext)
    }

    override fun decryptData(ciphertext: ByteArray): ByteArray {
        check(!locked) { "Vault is locked" }
        return SyncCrypto.aesGcmOpen(dek, ciphertext.copyOfRange(0, 12), ByteArray(0), ciphertext.copyOfRange(12, ciphertext.size))
    }

    override fun setupVault(password: SecureCharArray): Pair<KdfConfig, EncryptionConfig> = throw UnsupportedOperationException()

    override fun unlockVault(password: SecureCharArray, kdfConfig: KdfConfig, encryptionConfig: EncryptionConfig): CryptoResult<Unit> =
        throw UnsupportedOperationException()

    override fun lockVault() {
        locked = true
    }

    override fun decryptExternalPayload(
        ciphertext: ByteArray,
        password: SecureCharArray,
        kdfConfig: KdfConfig,
        encryptionConfig: EncryptionConfig
    ): CryptoResult<ByteArray> = throw UnsupportedOperationException()
}

/** Stores a paired phone the way pairing does (key sealed with the vault key) and returns its pair key. */
suspend fun pairTestDevice(
    repository: PairedDeviceRepository,
    protector: DesktopPairKeyProtector,
    deviceId: String,
    deviceName: String,
    ipAddress: String? = null,
    port: Int = 53853
): ByteArray {
    val pairKey = SyncCrypto.randomBytes(32)
    repository.savePairedDevice(
        PairedDevice(
            deviceId = deviceId,
            deviceName = deviceName,
            sharedSecret = protector.seal(pairKey)!!,
            ipAddress = ipAddress,
            port = port,
            pairedAt = 1000L
        )
    )
    return pairKey
}

/**
 * What a phone does with an agreed plan, on a plain list of records: the same rules the desktop
 * applies to its database (see SyncMergeExecutor.applyPlan).
 */
fun applyPlanToRecords(records: List<SyncRecord>, items: List<PlanItem>, now: Long = 1L): List<SyncRecord> {
    val byId = LinkedHashMap<String, SyncRecord>()
    records.forEach { byId[it.syncId] = it }
    for (item in items) {
        val oldId = item.renameFrom?.takeIf { it != item.syncId }
        if (oldId != null) byId.remove(oldId)?.let { byId[item.syncId] = it.copy(syncId = item.syncId) }
        when (item.action) {
            PlanAction.UPSERT -> byId[item.syncId] = item.record!!
            PlanAction.RECYCLE -> if (byId[item.syncId]?.deleted == false) {
                byId[item.syncId] = SyncRecords.tombstone(item.syncId, item.deletedAt ?: now)
            }
            PlanAction.RENAME_ONLY -> Unit
        }
    }
    return byId.values.toList()
}

/** A scripted phone speaking sync protocol v2 (session v3 frames), used to drive the desktop over real sockets. */
class ScriptedPhone(val socket: Socket, val channel: SecureChannel) : AutoCloseable {

    fun send(frame: SyncFrame) {
        channel.send(json.encodeToString(SyncFrame.serializer(), frame).toByteArray(Charsets.UTF_8))
    }

    /** How many KeepAlive frames [receive] has skipped. */
    @Volatile
    var keepAlivesReceived = 0

    /** The next frame that isn't a KeepAlive (those are counted in [keepAlivesReceived]). */
    fun receive(): SyncFrame {
        while (true) {
            val frame = json.decodeFromString(SyncFrame.serializer(), String(channel.receive(), Charsets.UTF_8))
            if (frame !is SyncFrame.KeepAlive) return frame
            keepAlivesReceived++
        }
    }

    /** Sends a KeepAlive every [intervalMs] from a background thread until the result is closed. */
    fun keepAlive(intervalMs: Long): AutoCloseable {
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val sender = Thread {
            while (running.get()) {
                try {
                    Thread.sleep(intervalMs)
                    if (running.get()) send(SyncFrame.KeepAlive(System.currentTimeMillis()))
                } catch (e: Exception) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; start() }
        return AutoCloseable {
            running.set(false)
            sender.join(2_000)
        }
    }

    /** Receives the next frame, which must be a [T]. */
    inline fun <reified T : SyncFrame> expect(): T {
        val frame = receive()
        check(frame is T) { "Expected ${T::class.simpleName}, got $frame" }
        return frame
    }

    /** Sends [records] the way the app does: numbered PayloadBatch parts. */
    fun sendRecords(records: List<SyncRecord>, maxBytes: Int = SyncRecords.DEFAULT_CHUNK_BYTES) {
        val chunks = SyncRecords.encodeChunks(records, maxBytes)
        chunks.forEachIndexed { i, chunk -> send(SyncFrame.PayloadBatch(chunk, part = i, last = i == chunks.lastIndex)) }
    }

    /** Receives the other device's whole vault, part by part. */
    fun receiveRecords(): List<SyncRecord> {
        val records = mutableListOf<SyncRecord>()
        var part = 0
        while (true) {
            val batch = expect<SyncFrame.PayloadBatch>()
            check(batch.part == part++) { "Vault part out of order" }
            records += SyncRecords.decodeChunk(batch.encryptedBatchJson)
            if (batch.last) return records
        }
    }

    /** Sends a merge plan as the reviewer does: numbered parts, the fingerprint in the last one. */
    fun sendPlan(items: List<PlanItem>, expectedFingerprint: String, maxBytes: Int = SyncRecords.DEFAULT_CHUNK_BYTES) {
        val chunks = MergePlans.encodeChunks(items, maxBytes)
        chunks.forEachIndexed { i, chunk ->
            val last = i == chunks.lastIndex
            send(SyncFrame.MergePlanBatch(chunk, part = i, last = last, expectedFingerprint = if (last) expectedFingerprint else ""))
        }
    }

    /** Receives the reviewer's whole plan and the fingerprint it promises. */
    fun receivePlan(): Pair<List<PlanItem>, String> {
        val items = mutableListOf<PlanItem>()
        var part = 0
        while (true) {
            val batch = expect<SyncFrame.MergePlanBatch>()
            check(batch.part == part++) { "Plan part out of order" }
            items += MergePlans.decodeChunk(batch.planJson)
            if (batch.last) return items to batch.expectedFingerprint
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Connects to the desktop and runs the handshake as the initiator. */
        fun connect(port: Int, mode: HandshakeMode, secret: ByteArray): ScriptedPhone {
            val socket = connectWithRetry(port)
            return try {
                ScriptedPhone(
                    socket,
                    SecureChannel.initiate(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream()), mode, secret)
                )
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        /** Accepts the desktop's connection on [server] and answers its sync handshake with [pairKey]. */
        fun acceptSync(server: ServerSocket, pairKey: ByteArray): ScriptedPhone? {
            val socket = server.accept().apply { soTimeout = 5000 }
            val accepted = SecureChannel.accept(DataInputStream(socket.getInputStream()), DataOutputStream(socket.getOutputStream())) { mode ->
                if (mode == HandshakeMode.SYNC) listOf("desktop" to pairKey) else emptyList()
            }
            if (accepted == null) {
                socket.close()
                return null
            }
            return ScriptedPhone(socket, accepted.channel)
        }

        fun connectWithRetry(port: Int): Socket {
            val deadline = System.currentTimeMillis() + 5000
            while (true) {
                try {
                    return Socket("127.0.0.1", port).apply { soTimeout = 5000 }
                } catch (e: Exception) {
                    if (System.currentTimeMillis() > deadline) throw e
                    Thread.sleep(50)
                }
            }
        }
    }
}
