package com.vaultpass.desktop.domain.backup.models

import com.vaultpass.desktop.domain.backup.errors.BackupException

/**
 * Result of a backup operation.
 */
sealed class BackupResult<out T> {
    data class Success<out T>(val data: T) : BackupResult<T>()
    data class Failure(val error: BackupException) : BackupResult<Nothing>()
}
