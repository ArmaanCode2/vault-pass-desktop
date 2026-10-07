package com.vaultpass.desktop.domain.sync.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Messages exchanged after the sync protocol v2 handshake. Every one of them travels inside an
 * encrypted, authenticated record (see com.vaultpass.synccore.SecureChannel).
 */
@Serializable
sealed class SyncFrame {
    /** Sent by the phone right after a pairing handshake: who it is. */
    @Serializable
    @SerialName("PairingInfo")
    data class PairingInfo(
        val deviceId: String,
        val deviceName: String
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

    /**
     * One part of the sender's vault: a chunk from SyncRecords.encodeChunks (a JSON array of
     * SyncRecord). Parts are numbered from 0; [last] marks the final one.
     */
    @Serializable
    @SerialName("PayloadBatch")
    data class PayloadBatch(
        val encryptedBatchJson: String,
        val part: Int = 0,
        val last: Boolean = true
    ) : SyncFrame()

    /**
     * One part of the reviewed merge plan: a chunk from MergePlans.encodeChunks (a JSON array of
     * PlanItem). [expectedFingerprint] travels in the last part only.
     */
    @Serializable
    @SerialName("MergePlanBatch")
    data class MergePlanBatch(
        val planJson: String,
        val part: Int = 0,
        val last: Boolean = true,
        val expectedFingerprint: String = ""
    ) : SyncFrame()

    /** The approving device's answer to a merge plan; [code] is a SyncError code on a refusal. */
    @Serializable
    @SerialName("MergePlanDecision")
    data class MergePlanDecision(
        val accepted: Boolean,
        val code: String = ""
    ) : SyncFrame()

    /**
     * What one device ended up with: an empty [code] on success, its vault fingerprint after
     * applying the plan, and how many entries it changed.
     */
    @Serializable
    @SerialName("SyncFinished")
    data class SyncFinished(
        val code: String = "",
        val fingerprint: String = "",
        val applied: Int = 0
    ) : SyncFrame()

    @Serializable
    @SerialName("CancelSync")
    data class CancelSync(
        val reason: String,
        val code: String = ""
    ) : SyncFrame()

    /**
     * Sent every few seconds on an open sync connection, so a long review doesn't let either
     * socket hit its idle limit. Only proves the peer is alive; it carries nothing else.
     */
    @Serializable
    @SerialName("KeepAlive")
    data class KeepAlive(val sentAt: Long = 0L) : SyncFrame()
}
