package com.vaultpass.desktop.data.crypto

import com.vaultpass.desktop.domain.crypto.CryptoException
import com.vaultpass.desktop.domain.crypto.KeyManager
import com.vaultpass.desktop.domain.security.SecureByteArray

/**
 * Concrete implementation of KeyManager that holds the active DEK in memory.
 */
class InMemoryKeyManager : KeyManager {
    
    @Volatile
    private var activeDek: SecureByteArray? = null

    /** Taken by [loadActiveDek], [wipeKeys] and [copyActiveDek], so a copy is never half wiped. */
    private val keyLock = Any()

    override fun loadActiveDek(dek: SecureByteArray) {
println("[TRACE] loadActiveDek called on InMemoryKeyManager@${System.identityHashCode(this)} on thread ${Thread.currentThread().name}")
println("[KEY_TRACE] loadActiveDek called on thread: ${Thread.currentThread().name}")
        synchronized(keyLock) {
            // Wipe existing key if overwriting
            activeDek?.wipe()
            activeDek = dek
        }
    }

    override fun wipeKeys() {
println("[TRACE] wipeKeys called on InMemoryKeyManager@${System.identityHashCode(this)} on thread ${Thread.currentThread().name}")
println("[KEY_TRACE] wipeKeys called on thread: ${Thread.currentThread().name}")
Exception().printStackTrace(System.out)
        synchronized(keyLock) {
            activeDek?.wipe()
            activeDek = null
        }
    }

    override fun copyActiveDek(): SecureByteArray = synchronized(keyLock) {
        val dek = activeDek ?: throw CryptoException.KeyNotLoaded()
        SecureByteArray(dek.data.clone())
    }

    override fun getActiveDek(): SecureByteArray {
println("[TRACE] getActiveDek called on InMemoryKeyManager@${System.identityHashCode(this)} on thread ${Thread.currentThread().name}, activeDek is null? ${activeDek == null}")
println("[KEY_TRACE] getActiveDek called on thread: ${Thread.currentThread().name}, activeDek is null? ${activeDek == null}")
        return activeDek ?: throw CryptoException.KeyNotLoaded()
    }

    override fun hasActiveDek(): Boolean = activeDek != null
}
