package com.vaultpass.synccore

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Fixed test vectors for sync protocol v2. The expected values were computed by an independent
 * Python implementation (pyca/cryptography), not by this code. The desktop app checks the same
 * vectors, so both copies of the shared core must produce these exact bytes.
 */
class SyncCoreVectorsTest {

    @Test
    fun hkdf_matchesRfc5869TestCase1() {
        val prk = SyncCrypto.hkdfExtract(Hex.decode("000102030405060708090a0b0c"), ByteArray(22) { 0x0b })
        assertEquals(V.RFC5869_PRK, Hex.encode(prk))
        val okm = SyncCrypto.hkdfExpand(prk, Hex.decode("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals(V.RFC5869_OKM, Hex.encode(okm))
    }

    @Test
    fun ecdh_matchesReference() {
        val initiator = keyPair(V.PUB_I, V.PKCS8_I)
        val responderPublic = SyncCrypto.decodePublicKey(Hex.decode(V.PUB_R))
        assertEquals(V.DH, Hex.encode(SyncCrypto.ecdh(initiator.private, responderPublic)))
    }

    @Test
    fun syncHandshake_matchesReferenceBytes() = checkHandshake(
        HandshakeMode.SYNC, V.SYNC_HELLO, V.SYNC_REPLY, V.SYNC_I2R, V.SYNC_R2I, null,
        V.SYNC_REC_I0, V.SYNC_REC_I1, V.SYNC_REC_R0
    )

    @Test
    fun pairHandshake_matchesReferenceBytes() = checkHandshake(
        HandshakeMode.PAIR, V.PAIR_HELLO, V.PAIR_REPLY, V.PAIR_I2R, V.PAIR_R2I, V.PAIR_PAIRKEY,
        V.PAIR_REC_I0, V.PAIR_REC_I1, V.PAIR_REC_R0
    )

    @Test
    fun beaconTag_matchesReference() {
        val tag = Beacons.tag(Hex.decode(V.SECRET), Hex.decode("000102030405060708090a0b0c0d0e0f"), 1700000000000L, 53853)
        assertEquals(V.BEACON_TAG, Hex.encode(tag))
    }

    private fun checkHandshake(
        mode: HandshakeMode,
        hello: String,
        reply: String,
        i2r: String,
        r2i: String,
        pairKey: String?,
        recordI0: String,
        recordI1: String,
        recordR0: String
    ) {
        val secret = Hex.decode(V.SECRET)
        val initiator = HandshakeInitiator(mode, secret, keyPair(V.PUB_I, V.PKCS8_I), Hex.decode(V.NONCE_I))
        assertEquals(hello, String(initiator.helloBytes, Charsets.UTF_8))

        val parsed = HandshakeResponder.parseHello(initiator.helloBytes)
        assertEquals(mode, parsed.mode)
        val match = HandshakeResponder.findSecret(parsed, listOf("other" to ByteArray(32) { 7 }, "right" to secret))
        assertEquals("right", match?.first)

        val (replyBytes, responderKeys) =
            HandshakeResponder.respond(parsed, secret, keyPair(V.PUB_R, V.PKCS8_R), Hex.decode(V.NONCE_R))
        assertEquals(reply, String(replyBytes, Charsets.UTF_8))

        val initiatorKeys = initiator.finish(replyBytes)
        assertEquals(i2r, Hex.encode(initiatorKeys.sendKey))
        assertEquals(r2i, Hex.encode(initiatorKeys.receiveKey))
        assertEquals(r2i, Hex.encode(responderKeys.sendKey))
        assertEquals(i2r, Hex.encode(responderKeys.receiveKey))
        if (pairKey == null) {
            assertNull(initiatorKeys.pairKey)
            assertNull(responderKeys.pairKey)
        } else {
            assertEquals(pairKey, Hex.encode(initiatorKeys.pairKey!!))
            assertEquals(pairKey, Hex.encode(responderKeys.pairKey!!))
        }

        val initiatorRecords = RecordCipher(initiatorKeys.sendKey, initiatorKeys.receiveKey)
        val responderRecords = RecordCipher(responderKeys.sendKey, responderKeys.receiveKey)
        val first = initiatorRecords.seal("""{"msg":"first"}""".toByteArray(Charsets.UTF_8))
        val second = initiatorRecords.seal("""{"msg":"second"}""".toByteArray(Charsets.UTF_8))
        assertEquals(recordI0, Hex.encode(first))
        assertEquals(recordI1, Hex.encode(second))
        assertArrayEquals("""{"msg":"first"}""".toByteArray(Charsets.UTF_8), responderRecords.open(first))
        assertArrayEquals("""{"msg":"second"}""".toByteArray(Charsets.UTF_8), responderRecords.open(second))

        val answer = responderRecords.seal("""{"msg":"reply"}""".toByteArray(Charsets.UTF_8))
        assertEquals(recordR0, Hex.encode(answer))
        assertArrayEquals("""{"msg":"reply"}""".toByteArray(Charsets.UTF_8), initiatorRecords.open(answer))
    }

    private fun keyPair(publicHex: String, pkcs8Hex: String): KeyPair {
        val factory = KeyFactory.getInstance("EC")
        return KeyPair(
            factory.generatePublic(X509EncodedKeySpec(Hex.decode(publicHex))),
            factory.generatePrivate(PKCS8EncodedKeySpec(Hex.decode(pkcs8Hex)))
        )
    }

    /** Reference values (independent Python implementation). Keep identical in both apps. */
    private object V {
        const val PUB_I = "3059301306072a8648ce3d020106082a8648ce3d03010703420004a0dfb2e44c00135c98628d069f7420095319f3b40af39dc7cd20b937510f7a747941883484f721c9300221cbb38c47030eab64d85c0eeece58a145bfc9963215"
        const val PKCS8_I = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420bb48766ff2ba44321e3701f61d12ba4ee71c3eaa6003b4208151a7fc7fe9a327a14403420004a0dfb2e44c00135c98628d069f7420095319f3b40af39dc7cd20b937510f7a747941883484f721c9300221cbb38c47030eab64d85c0eeece58a145bfc9963215"
        const val PUB_R = "3059301306072a8648ce3d020106082a8648ce3d0301070342000470b5ba4836dceab51a3715ae6ba145890290c6d7e2ce0989985bac98914b240d42a180b966decf08c14ff96fa264a5865735de743c697f5fab8fa42721781bdb"
        const val PKCS8_R = "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b0201010420ca0647931f1c7620390e6dfec5867e43d8678eeeecd6275e5a3a5d140d0d084ea1440342000470b5ba4836dceab51a3715ae6ba145890290c6d7e2ce0989985bac98914b240d42a180b966decf08c14ff96fa264a5865735de743c697f5fab8fa42721781bdb"
        const val SECRET = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val NONCE_I = "995e228539e59b32cb16a80da62f56c96cf4e1930e28f548d8763773bcac1ae1"
        const val NONCE_R = "8d2286988dff8c7f623db7f6760e4ea64c4fe8e23c5f80386c3d41795297c325"
        const val DH = "c94dc5aebbc9ccc6f9a767350764eaf8e82d8545396a4100d32bf201ab239d7f"

        const val SYNC_HELLO = """{"v":2,"mode":"sync","e":"3059301306072a8648ce3d020106082a8648ce3d03010703420004a0dfb2e44c00135c98628d069f7420095319f3b40af39dc7cd20b937510f7a747941883484f721c9300221cbb38c47030eab64d85c0eeece58a145bfc9963215","n":"995e228539e59b32cb16a80da62f56c96cf4e1930e28f548d8763773bcac1ae1","hint":"be1d22c977151c609f722f634ef55694"}"""
        const val SYNC_REPLY = """{"e":"3059301306072a8648ce3d020106082a8648ce3d0301070342000470b5ba4836dceab51a3715ae6ba145890290c6d7e2ce0989985bac98914b240d42a180b966decf08c14ff96fa264a5865735de743c697f5fab8fa42721781bdb","n":"8d2286988dff8c7f623db7f6760e4ea64c4fe8e23c5f80386c3d41795297c325","mac":"3e9b455739540686563876f217e9c731c177151fef305fdfc8fa2f2199091428"}"""
        const val SYNC_I2R = "cd10cc641c71eac34ce96590a77510d1492d0c86a2ec0cf551c79a782c2f1b78"
        const val SYNC_R2I = "c97642222c2b9d64c20fef8ec2fa631d7d508c835acc50aa9e06839e82711bcc"
        const val SYNC_REC_I0 = "93bfa86a121fcbd4acff5d5c2820d639007f372a5872962083c26dfadcb6b6"
        const val SYNC_REC_I1 = "1db9499277712b79f032e7ed971a8d76dbffa2c2e45198a01d5a07f68440b05a"
        const val SYNC_REC_R0 = "541c77e20ea4e55eecee4ceef9e019ff92a262c5b525b9c4bb745b8bb6c784"

        const val PAIR_HELLO = """{"v":2,"mode":"pair","e":"3059301306072a8648ce3d020106082a8648ce3d03010703420004a0dfb2e44c00135c98628d069f7420095319f3b40af39dc7cd20b937510f7a747941883484f721c9300221cbb38c47030eab64d85c0eeece58a145bfc9963215","n":"995e228539e59b32cb16a80da62f56c96cf4e1930e28f548d8763773bcac1ae1","hint":"f03ce770fa1c87dec704683e53b4b069"}"""
        const val PAIR_REPLY = """{"e":"3059301306072a8648ce3d020106082a8648ce3d0301070342000470b5ba4836dceab51a3715ae6ba145890290c6d7e2ce0989985bac98914b240d42a180b966decf08c14ff96fa264a5865735de743c697f5fab8fa42721781bdb","n":"8d2286988dff8c7f623db7f6760e4ea64c4fe8e23c5f80386c3d41795297c325","mac":"9d47d283b0a673b45665972eacc14d54576d1c5f7cdc2a35ffb7252d2a831a94"}"""
        const val PAIR_I2R = "b0d3afd661f78b90236790096f83ddb8a33f4a0e0d0b124ce2298b7213eec3fb"
        const val PAIR_R2I = "ceff84fdc801f633ff894b7c526a34d2a1a5b44e6bb276f516633ce970314d27"
        const val PAIR_PAIRKEY = "7a9bdc88e58abc65c062faca7a42e2eb9ec5c20243501eba6ae6e524120aaab7"
        const val PAIR_REC_I0 = "33abd99d0b8b047684aefc8a5425302dff6d51178dd10946cd8fb738e7d9c7"
        const val PAIR_REC_I1 = "6a17d2131f564d47e21a567aa73595e3c9965114ab8bff7d1b4a633d69ce3d03"
        const val PAIR_REC_R0 = "cef3195734f551e7a943aecf0d17f116b6f2acbeee141c93151e5f7d3e21b7"

        const val BEACON_TAG = "1dfffced4e7cecbf"
        const val RFC5869_PRK = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
        const val RFC5869_OKM = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
    }
}

