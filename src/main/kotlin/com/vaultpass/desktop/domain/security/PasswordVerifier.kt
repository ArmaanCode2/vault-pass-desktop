package com.vaultpass.desktop.domain.security

/**
 * Interface responsible strictly for comparing a provided password against a stored verification token.
 * Future implementations will utilize cryptographic key derivation functions (like PBKDF2) here.
 */
interface PasswordVerifier {
    fun verifyPassword(password: String, expectedData: String): Boolean
    fun deriveVerificationData(password: String): String
}
