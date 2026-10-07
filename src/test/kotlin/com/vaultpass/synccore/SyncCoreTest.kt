package com.vaultpass.synccore

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.interfaces.ECPublicKey
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Behaviour of the shared sync protocol v2 core: what it must accept and what it must refuse. */
class SyncCoreTest {

    private val secret = SyncCrypto.randomBytes(32)

    // --- Hex ---

    @Test
    fun hex_roundTripsAndRejectsInvalidInput() {
        val bytes = byteArrayOf(0, 1, 0x7f, -128, -1)
        assertEquals("00017f80ff", Hex.encode(bytes))
        assertArrayEquals(bytes, Hex.decode("00017F80FF"))
        assertThrows { Hex.decode("abc") }
        assertThrows { Hex.decode("zz") }
        assertThrows { Hex.decode("٣٣") } // Arabic-Indic digits are not hex
    }

    // --- Handshake ---

    @Test
    fun handshake_withSameSecret_givesMatchingKeys() {
        val initiator = HandshakeInitiator(HandshakeMode.SYNC, secret)
        val (reply, responderKeys) = HandshakeResponder.respond(HandshakeResponder.parseHello(initiator.helloBytes), secret)
        val initiatorKeys = initiator.finish(reply)
        assertArrayEquals(initiatorKeys.sendKey, responderKeys.receiveKey)
        assertArrayEquals(initiatorKeys.receiveKey, responderKeys.sendKey)
        assertFalse(initiatorKeys.sendKey.contentEquals(initiatorKeys.receiveKey))
    }

    @Test
    fun handshake_eachSessionGetsFreshKeys() {
        val first = runHandshake(HandshakeMode.SYNC)
        val second = runHandshake(HandshakeMode.SYNC)
        assertFalse(first.sendKey.contentEquals(second.sendKey))
    }

    @Test
    fun handshake_responderWithWrongSecret_isRejectedByInitiator() {
        val initiator = HandshakeInitiator(HandshakeMode.SYNC, secret)
        val (reply, _) = HandshakeResponder.respond(HandshakeResponder.parseHello(initiator.helloBytes), SyncCrypto.randomBytes(32))
        assertThrows { initiator.finish(reply) }
    }

    @Test
    fun handshake_tamperedReply_isRejected() {
        val initiator = HandshakeInitiator(HandshakeMode.SYNC, secret)
        val (reply, _) = HandshakeResponder.respond(HandshakeResponder.parseHello(initiator.helloBytes), secret)
        val text = String(reply, Charsets.UTF_8)
        val macStart = text.indexOf("\"mac\":\"") + 7
        val flipped = if (text[macStart] == '0') '1' else '0'
        val tampered = text.substring(0, macStart) + flipped + text.substring(macStart + 1)
        assertThrows { initiator.finish(tampered.toByteArray(Charsets.UTF_8)) }
    }

    @Test
    fun findSecret_withoutTheSecret_matchesNothing() {
        val hello = HandshakeResponder.parseHello(HandshakeInitiator(HandshakeMode.SYNC, secret).helloBytes)
        assertNull(HandshakeResponder.findSecret(hello, listOf("a" to SyncCrypto.randomBytes(32))))
        assertNull(HandshakeResponder.findSecret(hello, emptyList<Pair<String, ByteArray>>()))
    }

    @Test
    fun hint_differsPerMode_soAPairingHelloCantBeReusedForSync() {
        val nonce = SyncCrypto.randomBytes(32)
        assertFalse(Handshake.hint(secret, HandshakeMode.PAIR, nonce).contentEquals(Handshake.hint(secret, HandshakeMode.SYNC, nonce)))
    }

    @Test
    fun pairHandshake_givesBothSidesTheSamePairKey_andSyncGivesNone() {
        val pairing = HandshakeInitiator(HandshakeMode.PAIR, secret)
        val (reply, responderKeys) = HandshakeResponder.respond(HandshakeResponder.parseHello(pairing.helloBytes), secret)
        val initiatorKeys = pairing.finish(reply)
        assertNotNull(initiatorKeys.pairKey)
        assertArrayEquals(initiatorKeys.pairKey, responderKeys.pairKey)
        assertFalse(initiatorKeys.pairKey!!.contentEquals(secret), "The pair key must not be the QR secret")

        assertNull(runHandshake(HandshakeMode.SYNC).pairKey)
    }

    @Test
    fun parseHello_rejectsMalformedMessages() {
        assertThrows { HandshakeResponder.parseHello("not json".toByteArray()) }
        assertThrows { HandshakeResponder.parseHello("""{"v":1,"mode":"sync","e":"00","n":"00","hint":"00"}""".toByteArray()) }
        val valid = String(HandshakeInitiator(HandshakeMode.SYNC, secret).helloBytes, Charsets.UTF_8)
        assertThrows { HandshakeResponder.parseHello(valid.replace("\"sync\"", "\"other\"").toByteArray()) }
        assertThrows { HandshakeResponder.parseHello(ByteArray(5000) { 'a'.code.toByte() }) }
    }

    // --- Public keys ---

    @Test
    fun decodePublicKey_rejectsOffCurvePointsAndGarbage() {
        val valid = SyncCrypto.encodePublicKey(SyncCrypto.generateKeyPair().public as ECPublicKey)
        assertNotNull(SyncCrypto.decodePublicKey(valid))

        val offCurve = valid.copyOf()
        offCurve[offCurve.size - 1] = (offCurve[offCurve.size - 1] + 1).toByte()
        assertThrows { SyncCrypto.decodePublicKey(offCurve) }
        assertThrows { SyncCrypto.decodePublicKey(ByteArray(91)) }
        assertThrows { SyncCrypto.decodePublicKey(byteArrayOf(1, 2, 3)) }
    }

    // --- Records ---

    @Test
    fun records_replayReorderAndTamperingAreRejected() {
        val keys = runHandshakePair()
        val sender = RecordCipher(keys.first.sendKey, keys.first.receiveKey)

        val first = sender.seal("one".toByteArray())
        val second = sender.seal("two".toByteArray())

        // Replay: the same record twice.
        RecordCipher(keys.second.sendKey, keys.second.receiveKey).let { receiver ->
            assertArrayEquals("one".toByteArray(), receiver.open(first))
            assertThrows { receiver.open(first) }
        }
        // Reorder: the second record first.
        RecordCipher(keys.second.sendKey, keys.second.receiveKey).let { receiver ->
            assertThrows { receiver.open(second) }
        }
        // Tampering.
        RecordCipher(keys.second.sendKey, keys.second.receiveKey).let { receiver ->
            val tampered = first.copyOf().also { it[0] = (it[0] + 1).toByte() }
            assertThrows { receiver.open(tampered) }
        }
        // Reflection: a record can't be fed back to its own sender.
        assertThrows { sender.open(first) }
    }

    // --- Framing ---

    @Test
    fun wire_detectsLegacyPeersAndRejectsBadFrames() {
        assertThrowsType<LegacyPeerException> { WireV2.read(input(byteArrayOf(0, 0, 0, 0, 2, 0x7b, 0x7d))) }
        assertThrowsType<LegacyPeerException> { WireV2.read(input(byteArrayOf(1, 0, 0, 0, 0))) }
        assertThrowsType<SyncProtocolException> { WireV2.read(input(byteArrayOf(0x41, 2, 3, 0, 0, 0, 0))) }
        assertThrowsType<SyncProtocolException> { WireV2.read(input(byteArrayOf(0x56, 3, 3, 0, 0, 0, 0))) }
        assertThrowsType<SyncProtocolException> { WireV2.read(input(byteArrayOf(0x56, 2, 9, 0, 0, 0, 0))) }
        assertThrowsType<SyncProtocolException> { WireV2.read(input(byteArrayOf(0x56, 2, 3, 0x7f, 0, 0, 0))) }

        val buffer = ByteArrayOutputStream()
        WireV2.write(DataOutputStream(buffer), WireV2.TYPE_RECORD, byteArrayOf(9, 8, 7))
        val frame = WireV2.read(input(buffer.toByteArray()))
        assertEquals(WireV2.TYPE_RECORD, frame.type)
        assertArrayEquals(byteArrayOf(9, 8, 7), frame.payload)
    }

    // --- SecureChannel over real sockets ---

    @Test
    fun secureChannel_syncRoundTrip_identifiesThePeerBySecret() {
        val otherKey = SyncCrypto.randomBytes(32)
        val result = runChannels(
            initiatorMode = HandshakeMode.SYNC,
            initiatorSecret = secret,
            candidates = { mode -> if (mode == HandshakeMode.SYNC) listOf("laptop" to otherKey, "phone" to secret) else emptyList() }
        ) { initiator, accepted ->
            assertEquals("phone", accepted!!.peer)
            initiator.send("ping".toByteArray())
            assertArrayEquals("ping".toByteArray(), accepted.channel.receive())
            accepted.channel.send("pong".toByteArray())
            assertArrayEquals("pong".toByteArray(), initiator.receive())
        }
        assertTrue(result)
    }

    @Test
    fun secureChannel_pairing_bothSidesDeriveTheSamePairKey() {
        runChannels(
            initiatorMode = HandshakeMode.PAIR,
            initiatorSecret = secret,
            candidates = { mode -> if (mode == HandshakeMode.PAIR) listOf("qr" to secret) else emptyList() }
        ) { initiator, accepted ->
            assertNotNull(initiator.pairKey)
            assertArrayEquals(initiator.pairKey, accepted!!.channel.pairKey)
        }
    }

    @Test
    fun secureChannel_unknownSecret_isRefusedWithoutAReply() {
        assertNull(acceptOneHello(HandshakeMode.SYNC) { listOf("phone" to SyncCrypto.randomBytes(32)) })
    }

    @Test
    fun secureChannel_pairingHelloIsNotAcceptedAsSync() {
        // The responder offers the secret only for sync; a pairing hello with it must not match.
        assertNull(acceptOneHello(HandshakeMode.PAIR) { mode ->
            if (mode == HandshakeMode.SYNC) listOf("phone" to secret) else emptyList()
        })
    }

    // --- Beacons ---

    @Test
    fun beacons_identifyOnlyPairedDevices() {
        val now = 1_700_000_000_000L
        val beacon = Beacons.parse(Beacons.encode(Beacons.create(listOf(secret), 53853, now)))!!
        assertFalse(Beacons.encode(beacon).contains("deviceId"), "Beacons carry no device id")

        assertEquals(listOf("desktop"), Beacons.identify(beacon, listOf("desktop" to secret)))
        assertEquals(emptyList<String>(), Beacons.identify(beacon, listOf("desktop" to SyncCrypto.randomBytes(32))))

        // A beacon whose port or time was changed no longer matches.
        assertEquals(emptyList<String>(), Beacons.identify(beacon.copy(port = 1234), listOf("desktop" to secret)))
        assertEquals(emptyList<String>(), Beacons.identify(beacon.copy(ts = now + 1), listOf("desktop" to secret)))
    }

    @Test
    fun beacons_parseRejectsV1BeaconsAndGarbage() {
        assertNull(Beacons.parse("""{"deviceId":"x","deviceName":"PC","port":53853,"type":"BEACON"}"""))
        assertNull(Beacons.parse("PING"))
        assertNull(Beacons.parse("""{"type":"BEACON","v":2,"n":"zz","ts":1,"port":53853,"tags":[]}"""))
    }

    @Test
    fun beaconTracker_rejectsReplaysAndOwnBeacons_withoutComparingClocks() {
        val tracker = BeaconTracker()
        // The sender's clock can be far off ours: only its own sequence of timestamps matters.
        val first = Beacons.create(listOf(secret), 53853, nowMs = 5_000L)
        val second = Beacons.create(listOf(secret), 53853, nowMs = 8_000L)

        assertTrue(tracker.acceptFrom("desktop", first))
        assertFalse(tracker.acceptFrom("desktop", first), "A replay of the same beacon")
        assertTrue(tracker.acceptFrom("desktop", second))
        assertFalse(tracker.acceptFrom("desktop", first), "An older recorded beacon")
        assertTrue(tracker.acceptFrom("laptop", first), "Other devices are tracked separately")

        val own = Beacons.create(listOf(secret), 53853, nowMs = 9_000L)
        tracker.recordOwn(own)
        assertTrue(tracker.isOwn(own))
        assertFalse(tracker.isOwn(second))

        tracker.reset()
        assertFalse(tracker.isOwn(own))
        assertTrue(tracker.acceptFrom("desktop", first), "Forgotten after a reset")
    }

    // --- Helpers ---

    private fun runHandshake(mode: HandshakeMode): SessionKeys = runHandshakePair(mode).first

    private fun runHandshakePair(mode: HandshakeMode = HandshakeMode.SYNC): Pair<SessionKeys, SessionKeys> {
        val initiator = HandshakeInitiator(mode, secret)
        val (reply, responderKeys) = HandshakeResponder.respond(HandshakeResponder.parseHello(initiator.helloBytes), secret)
        return initiator.finish(reply) to responderKeys
    }

    /** Runs a full handshake over a local socket, then [body] with both ends. */
    private fun runChannels(
        initiatorMode: HandshakeMode,
        initiatorSecret: ByteArray,
        candidates: (HandshakeMode) -> List<Pair<String, ByteArray>>,
        body: (SecureChannel, SecureChannel.Accepted<String>?) -> Unit
    ): Boolean {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val serverSide = executor.submit<Pair<Socket, SecureChannel.Accepted<String>?>> {
                    val socket = server.accept()
                    socket.soTimeout = 5000
                    socket to SecureChannel.accept(
                        DataInputStream(socket.getInputStream()),
                        DataOutputStream(socket.getOutputStream()),
                        candidates
                    )
                }
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    val initiator = SecureChannel.initiate(
                        DataInputStream(socket.getInputStream()),
                        DataOutputStream(socket.getOutputStream()),
                        initiatorMode,
                        initiatorSecret
                    )
                    val (serverSocket, accepted) = serverSide.get(5, TimeUnit.SECONDS)
                    serverSocket.use { body(initiator, accepted) }
                }
            } finally {
                executor.shutdownNow()
            }
        }
        return true
    }

    /**
     * Sends one HELLO (using [secret]) to a responder offering [candidates]. Returns what the
     * responder accepted; when it accepts nothing, also checks it sent no reply at all.
     */
    private fun acceptOneHello(
        mode: HandshakeMode,
        candidates: (HandshakeMode) -> List<Pair<String, ByteArray>>
    ): SecureChannel.Accepted<String>? {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val serverSide = executor.submit<SecureChannel.Accepted<String>?> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        SecureChannel.accept(
                            DataInputStream(socket.getInputStream()),
                            DataOutputStream(socket.getOutputStream()),
                            candidates
                        )
                    }
                }
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 5000
                    val initiator = HandshakeInitiator(mode, secret)
                    WireV2.write(DataOutputStream(socket.getOutputStream()), WireV2.TYPE_HELLO, initiator.helloBytes)
                    val accepted = serverSide.get(5, TimeUnit.SECONDS)
                    if (accepted == null) {
                        // The responder closed the connection without sending anything.
                        assertEquals(-1, socket.getInputStream().read())
                    }
                    return accepted
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    private fun input(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes))

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            return
        }
        fail<Unit>("Expected an exception")
    }

    private inline fun <reified T : Throwable> assertThrowsType(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            fail<Unit>("Expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
        }
        fail<Unit>("Expected ${T::class.simpleName}")
    }
}
