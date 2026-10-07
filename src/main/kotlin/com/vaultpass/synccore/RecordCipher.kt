package com.vaultpass.synccore

/**
 * Encrypts the records of one connection with AES-256-GCM. Each direction has its own key and
 * the nonce is that direction's record counter, so a replayed, reordered or dropped record
 * fails authentication instead of being accepted.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
class RecordCipher(sendKey: ByteArray, receiveKey: ByteArray) {
    private val sendKey = sendKey.copyOf()
    private val receiveKey = receiveKey.copyOf()
    private var sendSequence = 0L
    private var receiveSequence = 0L

    init {
        require(this.sendKey.size == SyncCrypto.KEY_BYTES && this.receiveKey.size == SyncCrypto.KEY_BYTES) {
            "Record keys must be 32 bytes"
        }
    }

    /** Callers that write the result must keep sealing and writing in the same order. */
    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val nonce = nonceFor(sendSequence)
        sendSequence++
        return SyncCrypto.aesGcmSeal(sendKey, nonce, AAD, plaintext)
    }

    @Synchronized
    fun open(ciphertext: ByteArray): ByteArray {
        val plaintext = try {
            SyncCrypto.aesGcmOpen(receiveKey, nonceFor(receiveSequence), AAD, ciphertext)
        } catch (e: Exception) {
            throw SyncProtocolException("Record authentication failed")
        }
        receiveSequence++
        return plaintext
    }

    @Synchronized
    fun destroy() {
        sendKey.fill(0)
        receiveKey.fill(0)
    }

    companion object {
        private val AAD = byteArrayOf(WireV2.MAGIC, WireV2.VERSION, WireV2.TYPE_RECORD)

        /** 4 zero bytes followed by the 64-bit big-endian record counter. */
        fun nonceFor(sequence: Long): ByteArray =
            ByteArray(4) + SyncCrypto.u64(sequence)
    }
}
