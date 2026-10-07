package com.vaultpass.desktop.data.session

import com.sun.jna.platform.win32.Crypt32Util
import com.vaultpass.desktop.domain.security.SecureByteArray
import com.vaultpass.desktop.domain.session.SessionKeyStorage
import java.io.File
import java.nio.file.Files

/**
 * Windows DPAPI implementation of SessionKeyStorage.
 * Protects the DEK using the logged-in user's credentials and stores it locally.
 */
class DpapiSessionKeyStorage(private val storageFile: File) : SessionKeyStorage {

    companion object {
        private val isWindows: Boolean = try {
            System.getProperty("os.name")?.lowercase()?.contains("win") == true
        } catch (e: Throwable) {
            false
        }

        private val ENTROPY_HASH: ByteArray by lazy {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("VaultPass-Session-Entropy-v1".toByteArray(Charsets.UTF_8))
        }

        private fun applyEntropy(data: ByteArray): ByteArray {
            val hash = ENTROPY_HASH
            val result = ByteArray(data.size)
            for (i in data.indices) {
                result[i] = (data[i].toInt() xor hash[i % hash.size].toInt()).toByte()
            }
            return result
        }
    }

    @Volatile
    private var inMemorySession: ByteArray? = null

    override fun hasProtectedSession(): Boolean {
        return if (isWindows) {
            storageFile.exists() && storageFile.isFile
        } else {
            inMemorySession != null
        }
    }

    override fun saveProtectedSession(dek: SecureByteArray) {
        if (!isWindows) {
            inMemorySession?.fill(0)
            inMemorySession = dek.data.clone()
            return
        }

        try {
            val parent = storageFile.parentFile
            if (!parent.exists()) {
                parent.mkdirs()
            }
            
            // Mask with application entropy before DPAPI encryption
            val masked = applyEntropy(dek.data)
            val protectedData = try {
                Crypt32Util.cryptProtectData(masked)
            } finally {
                masked.fill(0)
            }
            
            // Write to file
            storageFile.writeBytes(protectedData)
            
            // Best effort to clear local array reference
            protectedData.fill(0)
        } catch (e: Throwable) {
            // Fallback to in-memory session if DPAPI / native library fails
            inMemorySession?.fill(0)
            inMemorySession = dek.data.clone()
        }
    }

    override fun loadProtectedSession(): SecureByteArray? {
        if (!hasProtectedSession()) return null

        if (!isWindows) {
            return inMemorySession?.let { SecureByteArray(it.clone()) }
        }
        
        return try {
            val protectedData = storageFile.readBytes()
            val masked = Crypt32Util.cryptUnprotectData(protectedData)
            protectedData.fill(0)
            
            val unmasked = applyEntropy(masked)
            masked.fill(0)
            
            SecureByteArray(unmasked)
        } catch (e: Throwable) {
            // Fallback to in-memory session if file read or unprotect fails
            inMemorySession?.let { SecureByteArray(it.clone()) }
        }
    }

    override fun clearProtectedSession() {
        inMemorySession?.fill(0)
        inMemorySession = null

        try {
            if (storageFile.exists()) {
                Files.deleteIfExists(storageFile.toPath())
            }
        } catch (e: Exception) {
            // Ignore file deletion errors
        }
    }
}
