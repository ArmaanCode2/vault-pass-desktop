package com.vaultpass.synccore

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic building blocks of sync protocol v2. Uses only the platform JCA, so it runs on
 * Android (API 24+) and on the desktop JVM without extra libraries.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
object SyncCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val CURVE = "secp256r1"

    private val secureRandom = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    /** HKDF-Extract (RFC 5869) with SHA-256. An empty salt means 32 zero bytes, as in the RFC. */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)

    /** HKDF-Expand (RFC 5869) with SHA-256. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Invalid HKDF output length $length" }
        val out = ByteArray(length)
        var block = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            block = hmacSha256(prk, block, info, byteArrayOf(counter.toByte()))
            val count = minOf(block.size, length - offset)
            System.arraycopy(block, 0, out, offset, count)
            offset += count
            counter++
        }
        return out
    }

    /** Compares without an early exit, so the time taken doesn't reveal where values differ. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    // --- ECDH on P-256 ---

    private val p256: ECParameterSpec by lazy {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE))
        (generator.generateKeyPair().public as ECPublicKey).params
    }

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), secureRandom)
        return generator.generateKeyPair()
    }

    /** X.509 SubjectPublicKeyInfo encoding of a public key. */
    fun encodePublicKey(key: ECPublicKey): ByteArray = key.encoded

    /**
     * Decodes a peer's P-256 public key and checks it is a valid point on the curve, so a
     * malformed or off-curve key can't be used to probe our private key.
     */
    fun decodePublicKey(encoded: ByteArray): ECPublicKey {
        val key = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        } catch (e: Exception) {
            throw SyncProtocolException("Invalid public key encoding")
        }
        if (key !is ECPublicKey) throw SyncProtocolException("Public key is not an EC key")
        val params = key.params
        val expected = p256
        if (params.curve != expected.curve ||
            params.generator != expected.generator ||
            params.order != expected.order ||
            params.cofactor != expected.cofactor
        ) {
            throw SyncProtocolException("Public key is not on the P-256 curve")
        }
        if (!isOnCurve(key.w, expected)) throw SyncProtocolException("Public key point is not on the curve")
        return key
    }

    private fun isOnCurve(point: ECPoint, params: ECParameterSpec): Boolean {
        if (point == ECPoint.POINT_INFINITY) return false
        val p: BigInteger = (params.curve.field as ECFieldFp).p
        val x = point.affineX
        val y = point.affineY
        if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
        val left = y.multiply(y).mod(p)
        val right = x.multiply(x).multiply(x)
            .add(params.curve.a.multiply(x))
            .add(params.curve.b)
            .mod(p)
        return left == right
    }

    fun ecdh(privateKey: PrivateKey, peerPublicKey: ECPublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    // --- AES-256-GCM ---

    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** Throws if the ciphertext, nonce or associated data was tampered with. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    // --- Fixed-width encodings used in transcripts and tags ---

    fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()
    )

    fun u64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }
}
