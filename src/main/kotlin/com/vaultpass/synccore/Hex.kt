package com.vaultpass.synccore

/**
 * Lowercase hex encoding. Sync protocol v2 uses hex rather than Base64 so this code also runs on
 * Android API 24, where java.util.Base64 is unavailable.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(DIGITS[v ushr 4])
            out.append(DIGITS[v and 0x0f])
        }
        return out.toString()
    }

    /** Decodes ASCII hex (either case). Throws [IllegalArgumentException] on anything else. */
    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "Hex string has an odd length" }
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = nibble(text[2 * i])
            val lo = nibble(text[2 * i + 1])
            require(hi >= 0 && lo >= 0) { "Invalid hex character" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun nibble(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
