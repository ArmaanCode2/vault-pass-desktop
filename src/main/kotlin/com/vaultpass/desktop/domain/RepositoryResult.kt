package com.vaultpass.desktop.domain

import com.vaultpass.desktop.domain.errors.VaultError

/**
 * A sealed class representing the outcome of a repository operation.
 * This guarantees that ViewModels and UI layers never handle raw data-source exceptions
 * (e.g., SQLiteExceptions or Network IOExceptions).
 */
sealed class RepositoryResult<out T> {
    data class Success<out T>(val data: T) : RepositoryResult<T>()
    data class Error(val error: VaultError) : RepositoryResult<Nothing>()
    object Loading : RepositoryResult<Nothing>()
}
