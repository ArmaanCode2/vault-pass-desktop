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

    override fun hasProtectedSession(): Boolean {
        return storageFile.exists() && storageFile.isFile
    }

    override fun saveProtectedSession(dek: SecureByteArray) {
        try {
            val parent = storageFile.parentFile
            if (!parent.exists()) {
                parent.mkdirs()
            }
            
            // Protect the data with DPAPI (tied to current user context)
            val protectedData = Crypt32Util.cryptProtectData(dek.data)
            
            // Write to file
            storageFile.writeBytes(protectedData)
            
            // Best effort to clear local array reference (Crypt32Util copies it though)
            protectedData.fill(0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun loadProtectedSession(): SecureByteArray? {
        if (!hasProtectedSession()) return null
        
        return try {
            val protectedData = storageFile.readBytes()
            val unprotectedData = Crypt32Util.cryptUnprotectData(protectedData)
            
            protectedData.fill(0)
            
            SecureByteArray(unprotectedData)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun clearProtectedSession() {
        try {
            if (storageFile.exists()) {
                Files.deleteIfExists(storageFile.toPath())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
