package com.vaultpass.desktop.domain.crypto

/**
 * Encapsulates the result of a cryptographic operation.
 * Prevents throwing raw exceptions to the presentation layer.
 */
sealed class CryptoResult<out T> {
    data class Success<out T>(val data: T) : CryptoResult<T>()
    data class Failure(val error: CryptoException) : CryptoResult<Nothing>()
}
