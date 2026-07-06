package com.vaultpass.desktop.domain.security

/**
 * Extension function to explicitly zero out a ByteArray.
 * Highly recommended for clearing cryptographic keys (like DEKs) from memory
 * as soon as they are no longer actively needed.
 */
fun ByteArray?.wipe() {
    this?.fill(0.toByte())
}

/**
 * Extension function to explicitly zero out a CharArray.
 */
fun CharArray?.wipe() {
    this?.fill('\u0000')
}
