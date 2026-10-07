package com.vaultpass.synccore

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * An authenticated, encrypted connection of sync protocol v2. Created by [initiate] or [accept],
 * which run the handshake; afterwards every message is an encrypted record. The caller owns the
 * socket and closes it.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
class SecureChannel private constructor(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val cipher: RecordCipher,
    val mode: HandshakeMode,
    /** The new long-term pair key after a pairing handshake; null for sync connections. */
    val pairKey: ByteArray?
) {
    private val sendLock = Any()

    /** Encrypts and sends one record. Thread-safe: records go out in counter order. */
    fun send(plaintext: ByteArray) {
        synchronized(sendLock) {
            WireV2.write(output, WireV2.TYPE_RECORD, cipher.seal(plaintext))
        }
    }

    /** Blocks until the next record arrives. Throws on any protocol or authentication failure. */
    fun receive(): ByteArray {
        val frame = WireV2.read(input)
        if (frame.type != WireV2.TYPE_RECORD) throw SyncProtocolException("Unexpected handshake frame")
        return cipher.open(frame.payload)
    }

    /** Wipes the record keys. The channel can't be used afterwards. */
    fun destroy() {
        cipher.destroy()
    }

    class Accepted<T>(val channel: SecureChannel, val peer: T)

    companion object {
        /** Initiator side: sends HELLO and waits for an authentic REPLY. Blocks. */
        fun initiate(
            input: DataInputStream,
            output: DataOutputStream,
            mode: HandshakeMode,
            secret: ByteArray
        ): SecureChannel {
            val handshake = HandshakeInitiator(mode, secret)
            WireV2.write(output, WireV2.TYPE_HELLO, handshake.helloBytes)
            val reply = WireV2.read(input)
            if (reply.type != WireV2.TYPE_REPLY) throw SyncProtocolException("Expected a handshake reply")
            val keys = handshake.finish(reply.payload)
            return SecureChannel(input, output, RecordCipher(keys.sendKey, keys.receiveKey), mode, keys.pairKey)
        }

        /**
         * Responder side: reads HELLO, finds the secret the initiator used among [candidates]
         * (chosen by the requested mode), and replies. Returns null, without replying, when no
         * candidate matches. Blocks.
         */
        fun <T> accept(
            input: DataInputStream,
            output: DataOutputStream,
            candidates: (HandshakeMode) -> List<Pair<T, ByteArray>>
        ): Accepted<T>? {
            val hello = WireV2.read(input)
            if (hello.type != WireV2.TYPE_HELLO) throw SyncProtocolException("Expected a handshake hello")
            val parsed = HandshakeResponder.parseHello(hello.payload)
            val match = HandshakeResponder.findSecret(parsed, candidates(parsed.mode)) ?: return null
            val (replyBytes, keys) = HandshakeResponder.respond(parsed, match.second)
            WireV2.write(output, WireV2.TYPE_REPLY, replyBytes)
            val channel = SecureChannel(input, output, RecordCipher(keys.sendKey, keys.receiveKey), parsed.mode, keys.pairKey)
            return Accepted(channel, match.first)
        }
    }
}
