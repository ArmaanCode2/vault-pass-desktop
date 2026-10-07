package com.vaultpass.synccore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Sync session v3: how a reviewed sync is agreed, applied by both devices and confirmed.
 *
 * The device that started the sync reviews the differences and builds a [MergePlan]. The other
 * device sees a [PlanSummary], approves it, and applies the same plan. Both then exchange a
 * [SyncRecords]-based vault fingerprint, so success is only reported when the vaults really match.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */

/** Why a sync ended. The code travels between the apps; the message is what the user reads. */
enum class SyncError(val code: String, val message: String) {
    CANCELLED("cancelled", "The sync was cancelled."),
    DECLINED("declined", "The other device declined the sync."),
    PLAN_REJECTED("plan_rejected", "The other device declined the changes."),
    TIMED_OUT("timed_out", "The other device stopped responding."),
    CONNECTION_LOST("connection_lost", "The connection to the other device was lost."),
    PROTOCOL("protocol", "The other device sent something unexpected, so the sync was stopped."),
    PEER_OUTDATED("peer_outdated", "The other device runs an older version of VaultPass. Update it on both devices."),
    VAULT_LOCKED("vault_locked", "The vault was locked, so the sync was stopped."),
    VAULT_UNREADABLE("vault_unreadable", "This device could not read its own vault, so the sync was stopped."),
    MERGE_FAILED("merge_failed", "The changes could not be saved, so nothing was changed."),
    NOT_IDENTICAL("not_identical", "The devices still hold different entries. Sync them again."),
    TOO_MUCH_DATA("too_much_data", "The other device sent more data than a sync allows."),
    UNKNOWN("unknown", "The sync stopped for an unknown reason.");

    companion object {
        fun fromCode(code: String): SyncError = values().firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Every time limit of a sync session, in one place so both apps agree. */
object SyncTimeouts {
    /** A peer that connects must finish the handshake within this time. */
    const val HANDSHAKE_MS = 10_000

    /** Longest silence allowed on an open connection. Above every approval limit below. */
    const val IDLE_MS = 120_000

    /** Waiting for the other device's user to accept or decline the sync. */
    const val APPROVAL_MS = 60_000L

    /** Waiting for the next chunk of the other device's vault. */
    const val TRANSFER_MS = 60_000L

    /** Waiting for the other device's answer to the merge plan. */
    const val PLAN_MS = 60_000L

    /** Waiting for the other device to apply the plan and confirm. */
    const val CONFIRM_MS = 60_000L
}

/** Where a sync session has got to. Each state has its own time limit (see [timeoutMs]). */
enum class SyncSessionState {
    IDLE,
    CONNECTING,
    AWAITING_APPROVAL,
    EXCHANGING,
    REVIEWING,
    AWAITING_PLAN_APPROVAL,
    APPLYING,
    CONFIRMING,
    DONE,
    FAILED;

    /** Null where only the idle-connection limit applies (the user may take as long as they like). */
    val timeoutMs: Long?
        get() = when (this) {
            CONNECTING -> SyncTimeouts.HANDSHAKE_MS.toLong()
            AWAITING_APPROVAL -> SyncTimeouts.APPROVAL_MS
            EXCHANGING -> SyncTimeouts.TRANSFER_MS
            AWAITING_PLAN_APPROVAL -> SyncTimeouts.PLAN_MS
            APPLYING, CONFIRMING -> SyncTimeouts.CONFIRM_MS
            IDLE, REVIEWING, DONE, FAILED -> null
        }
}

/** What the reviewer chose for one entry. Both devices end up with the same result. */
enum class ItemOutcome {
    /** Take the other device's version on both devices. */
    USE_REMOTE,

    /** Keep this device's version and send it to the other device. */
    USE_LOCAL,

    /** Move the entry to the recycle bin on both devices. */
    DELETE_BOTH,

    /** Change nothing anywhere. The entry may stay different on the two devices. */
    SKIP
}

object SyncOutcomes {

    /**
     * What the review screen offers for a [change], in the order to show them. The first is the
     * default, except where [needsChoice] is true.
     */
    fun choicesFor(change: SyncChange): List<ItemOutcome> = when (change) {
        SyncChange.NEW_REMOTE -> listOf(ItemOutcome.USE_REMOTE, ItemOutcome.SKIP)
        SyncChange.NEW_LOCAL -> listOf(ItemOutcome.USE_LOCAL, ItemOutcome.SKIP)
        SyncChange.UNCHANGED -> listOf(ItemOutcome.SKIP)
        SyncChange.REMOTE_CHANGED -> listOf(ItemOutcome.USE_REMOTE, ItemOutcome.USE_LOCAL, ItemOutcome.SKIP)
        SyncChange.LOCAL_CHANGED -> listOf(ItemOutcome.USE_LOCAL, ItemOutcome.USE_REMOTE, ItemOutcome.SKIP)
        SyncChange.CONFLICT -> listOf(ItemOutcome.USE_REMOTE, ItemOutcome.USE_LOCAL, ItemOutcome.SKIP)
        SyncChange.DELETED_REMOTE -> listOf(ItemOutcome.DELETE_BOTH, ItemOutcome.USE_LOCAL, ItemOutcome.SKIP)
        SyncChange.DELETED_LOCAL -> listOf(ItemOutcome.DELETE_BOTH, ItemOutcome.USE_REMOTE, ItemOutcome.SKIP)
    }

    /** True when the user must choose, because neither side is obviously right. */
    fun needsChoice(change: SyncChange): Boolean = change == SyncChange.CONFLICT

    /** The outcome to show first. For a conflict this is only a starting point; see [needsChoice]. */
    fun defaultFor(change: SyncChange): ItemOutcome = choicesFor(change).first()

    /**
     * The default for a remote deletion, which depends on whether this device edited the entry
     * after the other device deleted it. Editing it later means keeping it by default.
     */
    fun defaultForRemoteDeletion(local: SyncRecord?, remote: SyncRecord): ItemOutcome {
        val deletedAt = remote.deletedAt ?: remote.updatedAt
        return if (local != null && local.updatedAt > deletedAt) ItemOutcome.USE_LOCAL else ItemOutcome.DELETE_BOTH
    }
}

/** What one device must do to one entry. */
enum class PlanAction {
    /** Store [PlanItem.record] under [PlanItem.syncId], inserting it if it isn't there. */
    UPSERT,

    /** Move the entry with this syncId to the recycle bin. */
    RECYCLE,

    /** Only give the local entry the agreed syncId; its content doesn't change. */
    RENAME_ONLY
}

/**
 * One line of the agreed plan. [renameFrom] is the syncId the entry has on the device that must
 * rename it (entries from before syncIds existed have a different id on each device).
 */
@Serializable
data class PlanItem(
    val syncId: String,
    val action: PlanAction,
    val record: SyncRecord? = null,
    val renameFrom: String? = null,
    val deletedAt: Long? = null
)

/** The agreed result of a review, applied by both devices. */
@Serializable
data class MergePlan(
    val items: List<PlanItem> = emptyList(),
    /** The reviewer's vault fingerprint after applying the plan; the other device must match it. */
    val expectedFingerprint: String = ""
)

/** What a plan will change, for the approval screen on the other device. */
class PlanSummary(val added: Int, val updated: Int, val deleted: Int, val renamed: Int) {
    val total: Int get() = added + updated + deleted
    val isEmpty: Boolean get() = total == 0 && renamed == 0
}

/** One reviewed entry, as the app hands it to [MergePlans.build]. */
class PlanInput(
    val syncId: String,
    val change: SyncChange,
    val outcome: ItemOutcome,
    val local: SyncRecord?,
    val remote: SyncRecord?,
    /** The reviewer's edited version, when the user changed fields by hand. */
    val edited: SyncRecord? = null,
    /** The syncId this entry has on the reviewing device, when it differs from [syncId]. */
    val localIdToReplace: String? = null
)

object MergePlans {
    const val MAX_ITEMS = 200_000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val itemSerializer = ListSerializer(PlanItem.serializer())

    /**
     * Turns the reviewed entries into the plan both devices apply. [now] is used for entries the
     * reviewer edited by hand and for deletions.
     */
    fun build(inputs: List<PlanInput>, now: Long): List<PlanItem> {
        val items = mutableListOf<PlanItem>()
        for (input in inputs) {
            require(SyncRecords.isValidSyncId(input.syncId)) { "Invalid syncId in a merge plan" }
            val rename = input.localIdToReplace?.takeIf { it != input.syncId }
            when (input.outcome) {
                ItemOutcome.USE_REMOTE -> {
                    val source = input.edited ?: input.remote ?: continue
                    items += PlanItem(
                        syncId = input.syncId,
                        action = PlanAction.UPSERT,
                        record = finalRecord(source, input.syncId, input.edited != null, now),
                        renameFrom = rename
                    )
                }
                ItemOutcome.USE_LOCAL -> {
                    val source = input.edited ?: input.local ?: continue
                    items += PlanItem(
                        syncId = input.syncId,
                        action = PlanAction.UPSERT,
                        record = finalRecord(source, input.syncId, input.edited != null, now),
                        renameFrom = rename
                    )
                }
                ItemOutcome.DELETE_BOTH -> items += PlanItem(
                    syncId = input.syncId,
                    action = PlanAction.RECYCLE,
                    renameFrom = rename,
                    deletedAt = deletionTime(input, now)
                )
                ItemOutcome.SKIP -> if (rename != null) {
                    items += PlanItem(input.syncId, PlanAction.RENAME_ONLY, renameFrom = rename)
                }
            }
        }
        return items
    }

    private fun finalRecord(source: SyncRecord, syncId: String, edited: Boolean, now: Long): SyncRecord =
        source.copy(
            syncId = syncId,
            updatedAt = if (edited) now else source.updatedAt,
            deleted = false,
            deletedAt = null
        )

    private fun deletionTime(input: PlanInput, now: Long): Long {
        val remote = input.remote?.takeIf { it.deleted }?.let { it.deletedAt ?: it.updatedAt }
        val local = input.local?.takeIf { it.deleted }?.let { it.deletedAt ?: it.updatedAt }
        return remote ?: local ?: now
    }

    /**
     * Refuses a plan this device should not apply: a bad syncId, the same entry twice, a missing
     * record, or more entries than a sync allows.
     */
    fun validate(items: List<PlanItem>) {
        if (items.size > MAX_ITEMS) throw SyncProtocolException("The merge plan is too large")
        val seen = HashSet<String>()
        for (item in items) {
            if (!SyncRecords.isValidSyncId(item.syncId)) throw SyncProtocolException("The merge plan has an invalid entry id")
            if (!seen.add(item.syncId)) throw SyncProtocolException("The merge plan repeats an entry")
            item.renameFrom?.let {
                if (!SyncRecords.isValidSyncId(it)) throw SyncProtocolException("The merge plan has an invalid entry id")
            }
            when (item.action) {
                PlanAction.UPSERT -> {
                    val record = item.record ?: throw SyncProtocolException("The merge plan is missing an entry")
                    if (record.syncId != item.syncId) throw SyncProtocolException("The merge plan contradicts itself")
                    if (record.deleted) throw SyncProtocolException("The merge plan contradicts itself")
                }
                PlanAction.RECYCLE, PlanAction.RENAME_ONLY -> Unit
            }
        }
    }

    /**
     * What [items] change on the device that receives the plan. Its own syncIds are in [localIds],
     * so an entry it doesn't have counts as added rather than updated.
     */
    fun summarize(items: List<PlanItem>, localIds: Set<String>): PlanSummary {
        var added = 0
        var updated = 0
        var deleted = 0
        var renamed = 0
        for (item in items) {
            val known = item.syncId in localIds || (item.renameFrom?.let { it in localIds } == true)
            when (item.action) {
                PlanAction.UPSERT -> if (known) updated++ else added++
                PlanAction.RECYCLE -> if (known) deleted++
                PlanAction.RENAME_ONLY -> renamed++
            }
            if (item.renameFrom != null && item.action != PlanAction.RENAME_ONLY) renamed++
        }
        return PlanSummary(added, updated, deleted, renamed)
    }

    fun encodeChunks(items: List<PlanItem>, maxBytes: Int = SyncRecords.DEFAULT_CHUNK_BYTES): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var count = 0
        for (item in items) {
            val text = json.encodeToString(PlanItem.serializer(), item)
            if (count > 0 && current.length + text.length + 2 > maxBytes) {
                chunks += "[$current]"
                current.clear()
                count = 0
            }
            if (count > 0) current.append(',')
            current.append(text)
            count++
        }
        chunks += "[$current]"
        return chunks
    }

    fun decodeChunk(text: String): List<PlanItem> = try {
        json.decodeFromString(itemSerializer, text)
    } catch (e: Exception) {
        throw SyncProtocolException("Malformed merge plan")
    }

    /**
     * A short fingerprint of the vault's active entries: their ids and contents, order-independent.
     * Two devices that hold the same entries produce the same value.
     */
    fun vaultFingerprint(records: List<SyncRecord>): String {
        val lines = records.filter { !it.deleted }
            .map { it.syncId + ":" + SyncRecords.contentHash(it) }
            .sorted()
        val digest = SyncCrypto.sha256(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return Hex.encode(digest).take(32)
    }
}
