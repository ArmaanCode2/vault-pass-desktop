package com.vaultpass.desktop.domain.sync.diff

import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.synccore.EntryLimits
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.PlanInput
import com.vaultpass.synccore.SyncChange
import com.vaultpass.synccore.SyncOutcomes
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords

enum class FieldChangeType {
    UNCHANGED,
    MODIFIED
}

data class FieldDiff(
    val fieldName: String,
    val localValue: String,
    val remoteValue: String,
    val changeType: FieldChangeType
)

enum class EntrySyncCategory {
    NEW_REMOTE,
    NEW_LOCAL,
    MODIFIED,
    UNCHANGED,
    /** Changed on both devices since the last sync. */
    CONFLICT,
    /** Deleted on the other device. */
    DELETED_REMOTE,
    /** Deleted on this device. */
    DELETED_LOCAL
}

data class EntryDiffItem(
    /** The entry's syncId: the list key and the id the entry has here after the merge. */
    val syncKey: String,
    val category: EntrySyncCategory,
    /** The change as the shared core sees it; it decides which outcomes are offered. */
    val change: SyncChange,
    val localEntry: VaultEntry?,
    val remoteEntry: VaultEntry?,
    /** Both sides as records (tombstones included): what the merge plan is built from. */
    val localRecord: SyncRecord? = null,
    val remoteRecord: SyncRecord? = null,
    val fieldDiffs: List<FieldDiff> = emptyList(),
    /** What is to happen to this entry on both devices. */
    val outcome: ItemOutcome,
    /** False while a conflict still waits for the user to choose. */
    val isAnswered: Boolean = true,
    val editedEntry: VaultEntry? = null, // Holds user overrides edited directly on the review screen
    /** A legacy entry's current id here, replaced by [syncKey] on merge whatever the outcome. */
    val localSyncIdToReplace: String? = null
) {
    val choices: List<ItemOutcome> get() = SyncOutcomes.choicesFor(change)

    val needsChoice: Boolean get() = SyncOutcomes.needsChoice(change)

    /** A conflict the user hasn't answered yet: the merge waits for it. */
    val isUnanswered: Boolean get() = needsChoice && !isAnswered

    /** True when applying the plan changes this entry on at least one device. */
    val changesSomething: Boolean get() = outcome != ItemOutcome.SKIP

    /** The version this entry keeps, before any hand edits: what the review's fields show. */
    val chosenEntry: VaultEntry?
        get() = if (outcome == ItemOutcome.USE_LOCAL) localEntry ?: remoteEntry else remoteEntry ?: localEntry

    /** Hand edits only make sense when one version is stored on both devices. */
    val isEditable: Boolean get() = outcome == ItemOutcome.USE_REMOTE || outcome == ItemOutcome.USE_LOCAL

    /** False when a hand edit made a field longer than an entry may be. */
    val editsFit: Boolean get() = editedEntry?.let { EntryLimits.fits(SyncRecordMapper.toRecord(it)) } ?: true

    /** This entry as MergePlans.build wants it, with the user's hand edit if it changes anything. */
    fun toPlanInput(): PlanInput {
        val chosen = when (outcome) {
            ItemOutcome.USE_REMOTE -> remoteRecord
            ItemOutcome.USE_LOCAL -> localRecord
            else -> null
        }
        val edited = editedEntry
            ?.let { SyncRecordMapper.toRecord(it).copy(syncId = syncKey) }
            ?.takeIf { chosen == null || SyncRecords.contentHash(it) != SyncRecords.contentHash(chosen) }
        return PlanInput(
            syncId = syncKey,
            change = change,
            outcome = outcome,
            local = localRecord,
            remote = remoteRecord,
            edited = edited,
            localIdToReplace = localSyncIdToReplace
        )
    }
}

data class SyncDiffResult(
    val newRemoteCount: Int,
    val newLocalCount: Int,
    val modifiedCount: Int,
    val unchangedCount: Int,
    val deletedLocalCount: Int = 0,
    val conflictCount: Int = 0,
    val deletedRemoteCount: Int = 0,
    val diffItems: List<EntryDiffItem>
) {
    /** Conflicts still to answer; the merge button stays disabled while any are left. */
    val unansweredCount: Int get() = diffItems.count { it.isUnanswered }

    /** How many entries the plan will change. */
    val plannedCount: Int get() = diffItems.count { it.changesSomething }
}
