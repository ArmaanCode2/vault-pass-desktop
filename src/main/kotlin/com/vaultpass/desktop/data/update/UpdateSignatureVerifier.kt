package com.vaultpass.desktop.data.update

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies that a downloaded installer was signed by the VaultPass release key.
 *
 * Release format: the installer `VaultPass-Setup-vX.Y.Z.exe` is published next to
 * `VaultPass-Setup-vX.Y.Z.exe.sig`, a single line holding the base64 of a DER-encoded
 * ECDSA P-256 signature (SHA256withECDSA) over the UTF-8 message
 *
 *     VaultPass-Desktop-Update-v1\n<installer file name>\n<lower-case hex SHA-256 of the installer>
 *
 * The app rebuilds that message from the file name the release tag implies and the hash of the
 * downloaded bytes, so an older signed installer republished under a newer tag does not verify.
 *
 * The trust anchor is the public key in the classpath resource [PUBLIC_KEY_RESOURCE]
 * (PEM "PUBLIC KEY", X.509 SubjectPublicKeyInfo). The release signer `tools/SignInstaller.java` writes it
 * from the release key once; a build without it refuses every update. The signer checks each new
 * signature by calling this object inside the built app image, so both sides use [signedMessage].
 */
object UpdateSignatureVerifier {
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    const val PUBLIC_KEY_RESOURCE = "update-signing-key.pem"
    const val SIGNATURE_SUFFIX = ".sig"
    const val MESSAGE_HEADER = "VaultPass-Desktop-Update-v1"

    private const val PEM_BEGIN = "-----BEGIN PUBLIC KEY-----"
    private const val PEM_END = "-----END PUBLIC KEY-----"

    private val p256Params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC")
            .apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * Loads the embedded update signing key, or returns null when the build has none or it is invalid.
     */
    fun loadEmbeddedPublicKey(
        classLoader: ClassLoader? = UpdateSignatureVerifier::class.java.classLoader
    ): PublicKey? {
        return try {
            val stream = classLoader?.getResourceAsStream(PUBLIC_KEY_RESOURCE) ?: return null
            val pem = stream.use { it.readBytes().toString(Charsets.US_ASCII) }
            parsePublicKeyPem(pem)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a PEM "PUBLIC KEY" block and accepts only EC keys on the P-256 curve.
     */
    fun parsePublicKeyPem(pem: String): PublicKey {
        val begin = pem.indexOf(PEM_BEGIN)
        val end = pem.indexOf(PEM_END)
        require(begin >= 0 && end > begin) { "Not a PEM public key" }
        val body = pem.substring(begin + PEM_BEGIN.length, end).filterNot { it.isWhitespace() }
        val der = Base64.getDecoder().decode(body)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        require(key is ECPublicKey && isP256(key.params)) { "Update signing key must be an EC P-256 key" }
        return key
    }

    /**
     * The exact bytes a release signature covers. `tools/SignInstaller.java` builds the same message
     * and then checks its signature through [verify] in the built app image.
     */
    fun signedMessage(installerFileName: String, sha256Hex: String): ByteArray =
        "$MESSAGE_HEADER\n$installerFileName\n${sha256Hex.lowercase()}".toByteArray(Charsets.UTF_8)

    /**
     * Returns true only when [signatureBase64] is a valid signature by [publicKey] over
     * [signedMessage] for [installerFileName] and [sha256Hex].
     * Malformed base64 or DER returns false instead of throwing.
     */
    fun verify(publicKey: PublicKey, installerFileName: String, sha256Hex: String, signatureBase64: String): Boolean {
        return try {
            val signatureBytes = Base64.getDecoder().decode(signatureBase64.trim())
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(signedMessage(installerFileName, sha256Hex))
            verifier.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Hashes [file] and verifies [signatureBase64] for it under the name [installerFileName].
     */
    fun verifyFile(publicKey: PublicKey, file: File, installerFileName: String, signatureBase64: String): Boolean {
        return try {
            verify(publicKey, installerFileName, sha256Hex(file), signatureBase64)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Lower-case hex SHA-256 of the file's bytes.
     */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Lower-case hex SHA-256 of everything left in [channel]. The channel is read to its end and left open,
     * so a caller holding a locked handle can hash through it and keep the lock.
     */
    fun sha256Hex(channel: ReadableByteChannel): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        while (channel.read(buffer) != -1) {
            buffer.flip()
            digest.update(buffer)
            buffer.clear()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isP256(params: ECParameterSpec): Boolean {
        val expected = p256Params
        return params.curve == expected.curve &&
            params.generator == expected.generator &&
            params.order == expected.order &&
            params.cofactor == expected.cofactor
    }
}
