package com.vaultpass.synccore

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Thrown when a peer breaks sync protocol v2: bad frame, failed authentication, wrong order. */
open class SyncProtocolException(message: String) : IOException(message)

/** Thrown when the peer speaks the old v1 sync protocol, so the other device needs updating. */
class LegacyPeerException : SyncProtocolException(
    "The other device runs an older version of VaultPass sync. Update VaultPass on both devices."
)

/**
 * Framing of sync protocol v2: `[magic 'V'][version 2][type][int32 length][payload]`.
 *
 * v1 frames started with a flag byte of 0 or 1, so a v1 peer is recognised by its first byte,
 * and a v1 peer reading a v2 frame rejects it as an unknown flag.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
object WireV2 {
    const val MAGIC: Byte = 0x56
    const val VERSION: Byte = 2

    const val TYPE_HELLO: Byte = 1
    const val TYPE_REPLY: Byte = 2
    const val TYPE_RECORD: Byte = 3

    const val MAX_PAYLOAD_BYTES = 10 * 1024 * 1024

    class Frame(val type: Byte, val payload: ByteArray)

    fun write(output: DataOutputStream, type: Byte, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Frame payload too large: ${payload.size} bytes" }
        output.writeByte(MAGIC.toInt())
        output.writeByte(VERSION.toInt())
        output.writeByte(type.toInt())
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    fun read(input: DataInputStream): Frame {
        val magic = input.readByte()
        if (magic == 0.toByte() || magic == 1.toByte()) throw LegacyPeerException()
        if (magic != MAGIC) throw SyncProtocolException("Not a VaultPass sync frame")
        val version = input.readByte()
        if (version != VERSION) throw SyncProtocolException("Unsupported sync protocol version $version")
        val type = input.readByte()
        if (type != TYPE_HELLO && type != TYPE_REPLY && type != TYPE_RECORD) {
            throw SyncProtocolException("Unknown frame type $type")
        }
        val length = input.readInt()
        if (length < 0 || length > MAX_PAYLOAD_BYTES) throw SyncProtocolException("Invalid frame length $length")
        val payload = ByteArray(length)
        input.readFully(payload)
        return Frame(type, payload)
    }
}
