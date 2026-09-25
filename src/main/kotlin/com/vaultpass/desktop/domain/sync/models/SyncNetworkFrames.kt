package com.vaultpass.desktop.domain.sync.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class SyncFrame {
    @Serializable
    @SerialName("PairingRequest")
    data class PairingRequest(
        val deviceId: String,
        val deviceName: String,
        val pairingToken: String
    ) : SyncFrame()

    @Serializable
    @SerialName("PairingAcceptance")
    data class PairingAcceptance(
        val deviceId: String,
        val isAccepted: Boolean
    ) : SyncFrame()

    @Serializable
    @SerialName("SyncRequest")
    data class SyncRequest(
        val deviceId: String,
        val deviceName: String
    ) : SyncFrame()

    @Serializable
    @SerialName("SyncAcceptance")
    data class SyncAcceptance(
        val deviceId: String,
        val isAccepted: Boolean
    ) : SyncFrame()

    @Serializable
    @SerialName("ManifestExchange")
    data class ManifestExchange(
        val manifestJson: String
    ) : SyncFrame()

    @Serializable
    @SerialName("PayloadBatch")
    data class PayloadBatch(
        val encryptedBatchJson: String
    ) : SyncFrame()

    @Serializable
    @SerialName("CancelSync")
    data class CancelSync(
        val reason: String
    ) : SyncFrame()
}
