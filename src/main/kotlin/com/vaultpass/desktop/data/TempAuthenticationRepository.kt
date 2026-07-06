package com.vaultpass.desktop.data

import com.vaultpass.desktop.domain.security.AuthenticationRepository
import com.vaultpass.desktop.domain.security.PasswordVerifier
import java.util.prefs.Preferences

/**
 * Temporary mock implementation of AuthenticationRepository for Milestone 2.4.
 * Uses Java Preferences to store temporary mock credentials for testing the architecture.
 */
class TempAuthenticationRepository(
    private val passwordVerifier: PasswordVerifier
) : AuthenticationRepository {
    
    private val prefs = Preferences.userNodeForPackage(TempAuthenticationRepository::class.java)
    
    companion object {
        private const val KEY_MOCK_VERIFICATION_DATA = "mock_verification_data"
    }

    override suspend fun saveMasterPassword(password: String): Boolean {
        val verificationData = passwordVerifier.deriveVerificationData(password)
        prefs.put(KEY_MOCK_VERIFICATION_DATA, verificationData)
        prefs.flush()
        return true
    }

    override suspend fun verifyMasterPassword(password: String): Boolean {
        val storedData = prefs.get(KEY_MOCK_VERIFICATION_DATA, null) ?: return false
        return passwordVerifier.verifyPassword(password, storedData)
    }
}
