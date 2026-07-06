package com.vaultpass.desktop.domain.security

/**
 * Interface responsible for persisting and verifying authentication materials.
 */
interface AuthenticationRepository {
    suspend fun saveMasterPassword(password: String): Boolean
    suspend fun verifyMasterPassword(password: String): Boolean
}
