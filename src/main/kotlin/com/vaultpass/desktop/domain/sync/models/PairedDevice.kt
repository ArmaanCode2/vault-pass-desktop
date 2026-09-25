package com.vaultpass.desktop.domain.sync.models

data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val sharedSecret: String, // Base64 256-bit AES key
    val ipAddress: String? = null,
    val port: Int = 53853,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long = 0L,
    val isOnline: Boolean = false
)
