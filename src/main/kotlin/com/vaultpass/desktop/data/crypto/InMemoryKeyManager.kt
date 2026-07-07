package com.vaultpass.desktop.data.crypto

import com.vaultpass.desktop.domain.crypto.CryptoException
import com.vaultpass.desktop.domain.crypto.KeyManager
import com.vaultpass.desktop.domain.security.SecureByteArray

/**
 * Concrete implementation of KeyManager that holds the active DEK in memory.
 */
class InMemoryKeyManager : KeyManager {
    
    private var activeDek: SecureByteArray? = null

    override fun loadActiveDek(dek: SecureByteArray) {
        // Wipe existing key if overwriting
        activeDek?.wipe()
        activeDek = dek
    }

    override fun wipeKeys() {
        activeDek?.wipe()
        activeDek = null
    }

    override fun getActiveDek(): SecureByteArray {
        return activeDek ?: throw CryptoException.KeyNotLoaded()
    }
}
