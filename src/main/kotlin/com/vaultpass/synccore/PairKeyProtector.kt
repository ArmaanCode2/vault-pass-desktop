package com.vaultpass.synccore

/**
 * Seals long-term pair keys with the vault key so they are never stored in plaintext. Each app
 * implements it with its own vault encryption; sealing and opening need an unlocked vault.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
interface PairKeyProtector {
    /** Returns the stored form of [pairKey] (starting with [PairKeyFormat.PREFIX]), or null on failure. */
    fun seal(pairKey: ByteArray): String?

    /** Returns the pair key, or null if the vault is locked or [stored] isn't a valid v2 key. */
    fun open(stored: String): ByteArray?
}

object PairKeyFormat {
    /** Marks pair keys of protocol v2. Stored secrets without it are v1 pairings and are deleted. */
    const val PREFIX = "v2:"

    fun isCurrent(stored: String): Boolean = stored.startsWith(PREFIX)
}
