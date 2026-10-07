package com.vaultpass.desktop.domain.sync.diff

import com.vaultpass.desktop.domain.models.CustomField
import com.vaultpass.desktop.domain.models.SyncTombstone
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.SyncChange
import com.vaultpass.synccore.SyncDecision
import com.vaultpass.synccore.SyncOutcomes
import com.vaultpass.synccore.SyncPlanner
import com.vaultpass.synccore.SyncRecord

object SyncDiffEngine {

    /**
     * Builds the review list for a sync. [localEntries] are this vault's active entries,
     * [deletedLocalEntries] its recycle bin and [tombstones] its permanently deleted ids;
     * [lastSyncAt] is when this device last synced with the peer (0 = never).
     */
    fun computeDiff(
        localEntries: List<VaultEntry>,
        remoteRecords: List<SyncRecord>,
        lastSyncAt: Long = 0L,
        deletedLocalEntries: List<VaultEntry> = emptyList(),
        tombstones: List<SyncTombstone> = emptyList()
    ): SyncDiffResult {
        val localRecords = SyncRecordMapper.localRecords(localEntries, deletedLocalEntries, tombstones)
        val blockedIds = SyncRecordMapper.blockedIds(localEntries, deletedLocalEntries)
        val decisions = SyncPlanner.plan(localRecords, remoteRecords, lastSyncAt, blockedIds)

        val activeById = localEntries.associateBy { it.id }
        val deletedById = deletedLocalEntries.associateBy { it.id }

        val diffItems = decisions.map { decision ->
            val localId = decision.localIdToReplace ?: decision.syncId
            val local = activeById[localId] ?: deletedById[localId]
            val remote = decision.remote?.takeIf { !it.deleted }?.let { SyncRecordMapper.toDomain(it, decision.syncId) }
            EntryDiffItem(
                syncKey = decision.syncId,
                category = categoryOf(decision.change),
                change = decision.change,
                localEntry = local,
                remoteEntry = remote,
                localRecord = decision.local,
                remoteRecord = decision.remote,
                fieldDiffs = if (local != null && remote != null) computeFieldDiffs(local, remote) else emptyList(),
                outcome = defaultOutcome(decision),
                isAnswered = !SyncOutcomes.needsChoice(decision.change),
                localSyncIdToReplace = decision.localIdToReplace
            )
        }

        return SyncDiffResult(
            newRemoteCount = diffItems.count { it.category == EntrySyncCategory.NEW_REMOTE },
            newLocalCount = diffItems.count { it.category == EntrySyncCategory.NEW_LOCAL },
            modifiedCount = diffItems.count { it.category == EntrySyncCategory.MODIFIED },
            unchangedCount = diffItems.count { it.category == EntrySyncCategory.UNCHANGED },
            deletedLocalCount = diffItems.count { it.category == EntrySyncCategory.DELETED_LOCAL },
            conflictCount = diffItems.count { it.category == EntrySyncCategory.CONFLICT },
            deletedRemoteCount = diffItems.count { it.category == EntrySyncCategory.DELETED_REMOTE },
            diffItems = diffItems
        )
    }

    /** What the review shows first; a remote deletion depends on when this device last edited it. */
    private fun defaultOutcome(decision: SyncDecision): ItemOutcome =
        if (decision.change == SyncChange.DELETED_REMOTE && decision.remote != null) {
            SyncOutcomes.defaultForRemoteDeletion(decision.local, decision.remote)
        } else {
            SyncOutcomes.defaultFor(decision.change)
        }

    private fun categoryOf(change: SyncChange): EntrySyncCategory = when (change) {
        SyncChange.NEW_REMOTE -> EntrySyncCategory.NEW_REMOTE
        SyncChange.NEW_LOCAL -> EntrySyncCategory.NEW_LOCAL
        SyncChange.UNCHANGED -> EntrySyncCategory.UNCHANGED
        SyncChange.REMOTE_CHANGED, SyncChange.LOCAL_CHANGED -> EntrySyncCategory.MODIFIED
        SyncChange.CONFLICT -> EntrySyncCategory.CONFLICT
        SyncChange.DELETED_REMOTE -> EntrySyncCategory.DELETED_REMOTE
        SyncChange.DELETED_LOCAL -> EntrySyncCategory.DELETED_LOCAL
    }

    /** Every synced field, compared exactly (tags and custom fields regardless of order). */
    fun computeFieldDiffs(local: VaultEntry, remote: VaultEntry): List<FieldDiff> {
        fun diff(name: String, localValue: String, remoteValue: String, same: Boolean = localValue == remoteValue) =
            FieldDiff(name, localValue, remoteValue, if (same) FieldChangeType.UNCHANGED else FieldChangeType.MODIFIED)

        return listOf(
            diff("Title", local.title, remote.title),
            diff("Username", local.username, remote.username),
            diff("Password", local.secret, remote.secret),
            diff("URL", local.url, remote.url),
            diff("Notes", local.notes, remote.notes),
            diff("Category", local.category ?: "", remote.category ?: ""),
            diff(
                "Tags",
                local.tags.sorted().joinToString(", "),
                remote.tags.sorted().joinToString(", "),
                same = local.tags.sorted() == remote.tags.sorted()
            ),
            diff(
                "Custom Fields",
                formatCustomFields(local.customFields),
                formatCustomFields(remote.customFields),
                same = sortedFields(local.customFields) == sortedFields(remote.customFields)
            ),
            diff("Favorite", if (local.isFavorite) "Yes" else "No", if (remote.isFavorite) "Yes" else "No")
        )
    }

    private fun sortedFields(fields: List<CustomField>) =
        fields.sortedWith(compareBy<CustomField>({ it.key }, { it.value }))

    private fun formatCustomFields(fields: List<CustomField>): String =
        fields.joinToString("\n") { "${it.key}: ${it.value}" }
}
