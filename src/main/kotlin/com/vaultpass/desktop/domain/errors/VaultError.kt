package com.vaultpass.desktop.domain.errors

/**
 * The root interface for all domain-level errors in VaultPass.
 * Ensures the UI never handles raw, cryptic exceptions from the framework.
 */
sealed interface VaultError {
    val message: String
}

/**
 * Errors related to authentication and session state.
 */
sealed class AuthError(override val message: String) : VaultError {
    class IncorrectPassword(message: String = "Incorrect Master Password.") : AuthError(message)
    class VaultLocked(message: String = "The vault is currently locked.") : AuthError(message)
    class MaxAttemptsReached(message: String = "Too many failed attempts. Try again later.") : AuthError(message)
}

/**
 * Errors related to the local SQLite/SQLDelight database.
 */
sealed class DatabaseError(override val message: String) : VaultError {
    class DiskFull(message: String = "Not enough space to save vault data.") : DatabaseError(message)
    class CorruptionDetected(message: String = "Vault database appears to be corrupted.") : DatabaseError(message)
    class NotFound(message: String = "Requested record was not found.") : DatabaseError(message)
}

/**
 * Errors related to encryption/decryption operations.
 */
sealed class CryptoError(override val message: String) : VaultError {
    class DecryptionFailed(message: String = "Failed to decrypt the payload. Invalid key.") : CryptoError(message)
    class MacMismatch(message: String = "Message authentication code mismatch. Data may be tampered.") : CryptoError(message)
    class UnsupportedAlgorithm(message: String = "This cipher or algorithm is not supported.") : CryptoError(message)
}

/**
 * Errors related to background synchronization tasks.
 */
sealed class SyncError(override val message: String) : VaultError {
    class ConnectionTimeout(message: String = "Connection to peer timed out.") : SyncError(message)
    class PairingFailed(message: String = "Cryptographic handshake with peer failed.") : SyncError(message)
    class PayloadRejected(message: String = "Peer rejected the synchronization payload.") : SyncError(message)
}
