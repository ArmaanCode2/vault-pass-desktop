package com.vaultpass.desktop.data

import com.vaultpass.desktop.domain.security.PasswordVerifier

/**
 * Temporary mock implementation of PasswordVerifier for Milestone 2.4.
 * Uses plain text matching to verify the architecture without implementing hashing or cryptography.
 */
class TempPasswordVerifier : PasswordVerifier {
    override fun verifyPassword(password: String, expectedData: String): Boolean {
        // Plain text match for testing ONLY
        return password == expectedData
    }

    override fun deriveVerificationData(password: String): String {
        // Just return the plain text for testing ONLY
        return password
    }
}
