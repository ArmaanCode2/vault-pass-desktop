package com.vaultpass.desktop.data

import com.vaultpass.desktop.domain.AuthRepository
import java.security.MessageDigest
import java.util.prefs.Preferences

/**
 * Temporary implementation of AuthRepository for the Authentication Milestone.
 * Uses Java Preferences and SHA-256 to securely store a hash of the master password
 * without implementing PBKDF2 or AES yet, fulfilling the milestone constraints
 * (no hardcoded passwords, no skipping verification).
 */
class AuthRepositoryImpl : AuthRepository {
    private val prefs = Preferences.userNodeForPackage(AuthRepositoryImpl::class.java)
    
    companion object {
        private const val KEY_VAULT_EXISTS = "vault_exists"
        private const val KEY_PASSWORD_HASH = "master_password_hash"
        private const val KEY_SALT = "master_salt"
    }

    override suspend fun hasVault(): Boolean {
        return prefs.getBoolean(KEY_VAULT_EXISTS, false)
    }

    override suspend fun createMasterPassword(password: String): Boolean {
        // Generate a random salt
        val salt = java.util.UUID.randomUUID().toString()
        val hash = hashPassword(password, salt)
        
        prefs.put(KEY_SALT, salt)
        prefs.put(KEY_PASSWORD_HASH, hash)
        prefs.putBoolean(KEY_VAULT_EXISTS, true)
        
        // Ensure it is flushed to disk
        prefs.flush()
        return true
    }

    override suspend fun verifyMasterPassword(password: String): Boolean {
        if (!hasVault()) return false
        
        val salt = prefs.get(KEY_SALT, null) ?: return false
        val storedHash = prefs.get(KEY_PASSWORD_HASH, null) ?: return false
        
        val attemptHash = hashPassword(password, salt)
        return attemptHash == storedHash
    }

    override fun lock() {
        // In this milestone, locking is purely a state transition handled in the ViewModel.
        // In the future, this will clear the CryptoManager's in-memory keys (DEK).
    }

    private fun hashPassword(password: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = (password + salt).toByteArray(Charsets.UTF_8)
        val hashBytes = md.digest(input)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
