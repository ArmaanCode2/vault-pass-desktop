package com.vaultpass.synccore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyPair
import java.security.interfaces.ECPublicKey

/**
 * Handshake of sync protocol v2: an ephemeral ECDH (P-256) exchange authenticated by a 32-byte
 * secret both sides already hold. For pairing that secret is the one in the desktop's QR code;
 * for syncing it is the pair key agreed during pairing. The secret itself never goes on the wire.
 *
 *  1. Initiator -> HELLO  {v, mode, e: ephemeral public key, n: nonce, hint}
 *  2. Responder -> REPLY  {e: ephemeral public key, n: nonce, mac}
 *
 * Both derive per-direction record keys from HKDF(salt = secret, ikm = ECDH result) bound to a
 * hash of the exchange. The responder's `mac` proves it holds the secret; the initiator proves it
 * with its first encrypted record, which only decrypts under the right keys. A pairing handshake
 * also yields the long-term pair key.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
enum class HandshakeMode(val wireName: String) {
    PAIR("pair"),
    SYNC("sync");

    companion object {
        fun fromWireName(name: String): HandshakeMode? = values().firstOrNull { it.wireName == name }
    }
}

@Serializable
data class HelloMessage(val v: Int, val mode: String, val e: String, val n: String, val hint: String)

@Serializable
data class ReplyMessage(val e: String, val n: String, val mac: String)

/** Record keys for one connection, plus the new pair key after a pairing handshake. */
class SessionKeys(val sendKey: ByteArray, val receiveKey: ByteArray, val pairKey: ByteArray?)

object Handshake {
    const val PROTOCOL_VERSION = 2
    const val HINT_BYTES = 16
    private const val MAX_HANDSHAKE_BYTES = 4096

    private val LABEL_HINT = "VaultPass-v2 hint".toByteArray(Charsets.UTF_8)
    private val LABEL_TRANSCRIPT = "VaultPass-v2 transcript".toByteArray(Charsets.UTF_8)
    private val LABEL_KEYS = "VaultPass-v2 keys".toByteArray(Charsets.UTF_8)
    private val LABEL_RESPONDER = "VaultPass-v2 responder".toByteArray(Charsets.UTF_8)
    private val SEPARATOR = byteArrayOf(0)

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Lets the responder find which secret the initiator uses, without revealing it. */
    fun hint(secret: ByteArray, mode: HandshakeMode, initiatorNonce: ByteArray): ByteArray =
        SyncCrypto.hmacSha256(
            secret,
            LABEL_HINT, SEPARATOR, mode.wireName.toByteArray(Charsets.UTF_8), SEPARATOR, initiatorNonce
        ).copyOf(HINT_BYTES)

    fun transcriptHash(helloBytes: ByteArray, responderPublicKey: ByteArray, responderNonce: ByteArray): ByteArray =
        SyncCrypto.sha256(
            LABEL_TRANSCRIPT,
            SyncCrypto.u32(helloBytes.size), helloBytes,
            SyncCrypto.u32(responderPublicKey.size), responderPublicKey,
            SyncCrypto.u32(responderNonce.size), responderNonce
        )

    internal class DerivedKeys(
        val initiatorToResponder: ByteArray,
        val responderToInitiator: ByteArray,
        val responderMacKey: ByteArray,
        val pairKey: ByteArray?
    )

    internal fun deriveKeys(mode: HandshakeMode, secret: ByteArray, ecdhResult: ByteArray, transcript: ByteArray): DerivedKeys {
        val prk = SyncCrypto.hkdfExtract(secret, ecdhResult)
        val length = if (mode == HandshakeMode.PAIR) 4 * SyncCrypto.KEY_BYTES else 3 * SyncCrypto.KEY_BYTES
        val okm = SyncCrypto.hkdfExpand(prk, LABEL_KEYS + transcript, length)
        fun slice(index: Int) = okm.copyOfRange(index * SyncCrypto.KEY_BYTES, (index + 1) * SyncCrypto.KEY_BYTES)
        return DerivedKeys(
            initiatorToResponder = slice(0),
            responderToInitiator = slice(1),
            responderMacKey = slice(2),
            pairKey = if (mode == HandshakeMode.PAIR) slice(3) else null
        )
    }

    internal fun responderMac(macKey: ByteArray, transcript: ByteArray): ByteArray =
        SyncCrypto.hmacSha256(macKey, LABEL_RESPONDER, transcript)

    internal fun checkSize(bytes: ByteArray) {
        if (bytes.size > MAX_HANDSHAKE_BYTES) throw SyncProtocolException("Handshake message too large")
    }

    internal fun decodeHex(value: String, expectedSize: Int? = null): ByteArray {
        val bytes = try {
            Hex.decode(value)
        } catch (e: IllegalArgumentException) {
            throw SyncProtocolException("Malformed handshake field")
        }
        if (expectedSize != null && bytes.size != expectedSize) throw SyncProtocolException("Handshake field has the wrong size")
        return bytes
    }
}

/** Initiator side of the handshake. The key pair and nonce can be fixed for test vectors only. */
class HandshakeInitiator(
    private val mode: HandshakeMode,
    private val secret: ByteArray,
    private val ephemeral: KeyPair = SyncCrypto.generateKeyPair(),
    nonce: ByteArray = SyncCrypto.randomBytes(SyncCrypto.NONCE_BYTES)
) {
    val helloBytes: ByteArray

    init {
        require(secret.size == SyncCrypto.KEY_BYTES) { "Handshake secret must be 32 bytes" }
        require(nonce.size == SyncCrypto.NONCE_BYTES) { "Handshake nonce must be 32 bytes" }
        val hello = HelloMessage(
            v = Handshake.PROTOCOL_VERSION,
            mode = mode.wireName,
            e = Hex.encode(SyncCrypto.encodePublicKey(ephemeral.public as ECPublicKey)),
            n = Hex.encode(nonce),
            hint = Hex.encode(Handshake.hint(secret, mode, nonce))
        )
        helloBytes = Handshake.json.encodeToString(HelloMessage.serializer(), hello).toByteArray(Charsets.UTF_8)
    }

    /** Verifies the responder's REPLY and returns the record keys; throws if it is not authentic. */
    fun finish(replyBytes: ByteArray): SessionKeys {
        Handshake.checkSize(replyBytes)
        val reply = try {
            Handshake.json.decodeFromString(ReplyMessage.serializer(), String(replyBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SyncProtocolException("Malformed handshake reply")
        }
        val responderKeyBytes = Handshake.decodeHex(reply.e)
        val responderNonce = Handshake.decodeHex(reply.n, SyncCrypto.NONCE_BYTES)
        val mac = Handshake.decodeHex(reply.mac, SyncCrypto.KEY_BYTES)
        val responderKey = SyncCrypto.decodePublicKey(responderKeyBytes)

        val ecdhResult = SyncCrypto.ecdh(ephemeral.private, responderKey)
        val transcript = Handshake.transcriptHash(helloBytes, responderKeyBytes, responderNonce)
        val keys = Handshake.deriveKeys(mode, secret, ecdhResult, transcript)
        if (!SyncCrypto.constantTimeEquals(mac, Handshake.responderMac(keys.responderMacKey, transcript))) {
            throw SyncProtocolException("Handshake authentication failed")
        }
        return SessionKeys(
            sendKey = keys.initiatorToResponder,
            receiveKey = keys.responderToInitiator,
            pairKey = keys.pairKey
        )
    }
}

/** A validated HELLO, as received by the responder. */
class ParsedHello internal constructor(
    val bytes: ByteArray,
    val mode: HandshakeMode,
    internal val initiatorKey: ECPublicKey,
    internal val initiatorNonce: ByteArray,
    internal val hint: ByteArray
)

/** Responder side of the handshake. */
object HandshakeResponder {

    fun parseHello(bytes: ByteArray): ParsedHello {
        Handshake.checkSize(bytes)
        val hello = try {
            Handshake.json.decodeFromString(HelloMessage.serializer(), String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SyncProtocolException("Malformed handshake hello")
        }
        if (hello.v != Handshake.PROTOCOL_VERSION) throw SyncProtocolException("Unsupported handshake version ${hello.v}")
        val mode = HandshakeMode.fromWireName(hello.mode) ?: throw SyncProtocolException("Unknown handshake mode")
        return ParsedHello(
            bytes = bytes,
            mode = mode,
            initiatorKey = SyncCrypto.decodePublicKey(Handshake.decodeHex(hello.e)),
            initiatorNonce = Handshake.decodeHex(hello.n, SyncCrypto.NONCE_BYTES),
            hint = Handshake.decodeHex(hello.hint, Handshake.HINT_BYTES)
        )
    }

    /** Returns the candidate whose secret the initiator used, or null if none matches. */
    fun <T> findSecret(hello: ParsedHello, candidates: List<Pair<T, ByteArray>>): Pair<T, ByteArray>? {
        var match: Pair<T, ByteArray>? = null
        for (candidate in candidates) {
            val expected = Handshake.hint(candidate.second, hello.mode, hello.initiatorNonce)
            if (SyncCrypto.constantTimeEquals(expected, hello.hint) && match == null) {
                match = candidate
            }
        }
        return match
    }

    /** Builds the REPLY for [hello] and returns it with the record keys. */
    fun respond(
        hello: ParsedHello,
        secret: ByteArray,
        ephemeral: KeyPair = SyncCrypto.generateKeyPair(),
        nonce: ByteArray = SyncCrypto.randomBytes(SyncCrypto.NONCE_BYTES)
    ): Pair<ByteArray, SessionKeys> {
        require(secret.size == SyncCrypto.KEY_BYTES) { "Handshake secret must be 32 bytes" }
        require(nonce.size == SyncCrypto.NONCE_BYTES) { "Handshake nonce must be 32 bytes" }
        val publicKeyBytes = SyncCrypto.encodePublicKey(ephemeral.public as ECPublicKey)
        val ecdhResult = SyncCrypto.ecdh(ephemeral.private, hello.initiatorKey)
        val transcript = Handshake.transcriptHash(hello.bytes, publicKeyBytes, nonce)
        val keys = Handshake.deriveKeys(hello.mode, secret, ecdhResult, transcript)
        val reply = ReplyMessage(
            e = Hex.encode(publicKeyBytes),
            n = Hex.encode(nonce),
            mac = Hex.encode(Handshake.responderMac(keys.responderMacKey, transcript))
        )
        val replyBytes = Handshake.json.encodeToString(ReplyMessage.serializer(), reply).toByteArray(Charsets.UTF_8)
        return replyBytes to SessionKeys(
            sendKey = keys.responderToInitiator,
            receiveKey = keys.initiatorToResponder,
            pairKey = keys.pairKey
        )
    }
}
