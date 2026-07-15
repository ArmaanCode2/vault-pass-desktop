package com.vaultpass.desktop.domain.backup.errors

/**
 * Domain errors specifically related to backup architecture.
 */
sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InvalidFormat(message: String = "The backup format is invalid or unsupported.") : BackupException(message)
    class UnsupportedVersion(version: String) : BackupException("The backup version '$version' is not supported.")
    class CorruptedData(message: String = "The backup data is corrupted and cannot be read.") : BackupException(message)
    class DecryptionFailed(message: String = "Failed to decrypt the backup.", cause: Throwable? = null) : BackupException(message, cause)
    class StorageError(message: String, cause: Throwable? = null) : BackupException(message, cause)
    class ValidationError(message: String) : BackupException(message)
}
