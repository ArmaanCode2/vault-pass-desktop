package com.vaultpass.desktop.data.sync

import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.synccore.Hex
import com.vaultpass.synccore.PairKeyFormat
import com.vaultpass.synccore.PairKeyProtector
import com.vaultpass.synccore.SyncCrypto

/** Stores pair keys encrypted with the vault key (DEK); needs an unlocked vault. */
class DesktopPairKeyProtector(private val cryptoManager: CryptoManager) : PairKeyProtector {

    override fun seal(pairKey: ByteArray): String? {
        if (pairKey.size != SyncCrypto.KEY_BYTES) return null
        return try {
            PairKeyFormat.PREFIX + Hex.encode(cryptoManager.encryptData(pairKey))
        } catch (e: Exception) {
            null
        }
    }

    override fun open(stored: String): ByteArray? {
        if (!PairKeyFormat.isCurrent(stored)) return null
        return try {
            val key = cryptoManager.decryptData(Hex.decode(stored.removePrefix(PairKeyFormat.PREFIX)))
            key.takeIf { it.size == SyncCrypto.KEY_BYTES }
        } catch (e: Exception) {
            null
        }
    }
}
