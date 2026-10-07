package com.vaultpass.desktop.domain.sync.diff

import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.errors.AuthError
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.models.VaultEntryHistory
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords

object SyncMergeExecutor {

    /**
     * Applies the agreed merge plan inside one database transaction; any failure rolls everything
     * back. The plan is validated first, because a device must never apply one it didn't check.
     * Returns how many entries this device really changed.
     */
    suspend fun applyPlan(
        items: List<PlanItem>,
        vaultRepository: VaultRepository,
        clock: () -> Long = System::currentTimeMillis
    ): Result<Int> {
        try {
            MergePlans.validate(items)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("The merge plan was refused: ${e.message}"))
        }

        val txResult = vaultRepository.transaction {
            var changed = 0
            for (item in items) {
                requireValidSyncId(item.syncId)
                // A legacy entry is given the agreed syncId first, whatever else happens to it.
                val oldId = item.renameFrom?.takeIf { it != item.syncId }
                if (oldId != null && existingEntry(oldId) != null) {
                    changeEntryId(oldId, item.syncId).orThrow("Could not rename entry to ${item.syncId}")
                }

                when (item.action) {
                    PlanAction.UPSERT -> {
                        val record = item.record ?: throw IllegalStateException("Missing record for ${item.syncId}")
                        val existing = existingEntry(item.syncId)
                        if (existing != null) {
                            updateEntry(applied(existing, record, clock()))
                                .orThrow("Database update failed for ${item.syncId}")
                        } else {
                            // Inserting an id that only has a tombstone here also clears it.
                            createEntry(newEntry(record, item.syncId, clock()))
                                .orThrow("Database insert failed for ${item.syncId}")
                        }
                        changed++
                    }

                    PlanAction.RECYCLE -> {
                        val existing = existingEntry(item.syncId)
                        if (existing != null && !existing.isDeleted) {
                            val deletedAt = item.deletedAt ?: clock()
                            updateEntry(existing.copy(isDeleted = true, deletedAt = deletedAt, updatedAt = deletedAt))
                                .orThrow("Could not move ${item.syncId} to the recycle bin")
                            changed++
                        }
                        // Nothing to do when this device doesn't have the entry.
                    }

                    PlanAction.RENAME_ONLY -> {
                        // Only the id changes, which happened above.
                    }
                }
            }
            changed
        }

        return when (txResult) {
            is RepositoryResult.Success -> Result.success(txResult.data)
            // The vault locked before or during the merge: rolled back, and the caller can say so.
            is RepositoryResult.Error -> if (txResult.error is AuthError.VaultLocked) {
                Result.failure(DatabaseClosedException())
            } else {
                Result.failure(IllegalStateException("Merge transaction failed: ${txResult.error}"))
            }
            is RepositoryResult.Loading -> Result.failure(IllegalStateException("Unexpected loading state during transaction"))
        }
    }

    /**
     * The fingerprint this vault will have once [items] are applied to [localRecords]: what the
     * reviewing device promises the other one, without touching the database.
     */
    fun fingerprintAfter(localRecords: List<SyncRecord>, items: List<PlanItem>): String {
        val byId = LinkedHashMap<String, SyncRecord>()
        localRecords.forEach { byId[it.syncId] = it }
        for (item in items) {
            val oldId = item.renameFrom?.takeIf { it != item.syncId }
            if (oldId != null) {
                byId.remove(oldId)?.let { byId[item.syncId] = it.copy(syncId = item.syncId) }
            }
            when (item.action) {
                PlanAction.UPSERT -> item.record?.let { byId[item.syncId] = it }
                PlanAction.RECYCLE -> byId[item.syncId]?.let {
                    byId[item.syncId] = SyncRecords.tombstone(item.syncId, item.deletedAt ?: it.updatedAt)
                }
                PlanAction.RENAME_ONLY -> Unit
            }
        }
        return MergePlans.vaultFingerprint(byId.values.toList())
    }

    private fun requireValidSyncId(syncId: String) {
        check(SyncRecords.isValidSyncId(syncId)) { "Invalid syncId" }
    }

    private fun <T> RepositoryResult<T>.orThrow(message: String): T = when (this) {
        is RepositoryResult.Success -> data
        is RepositoryResult.Error -> throw IllegalStateException("$message: $error")
        is RepositoryResult.Loading -> throw IllegalStateException(message)
    }

    private suspend fun VaultRepository.existingEntry(id: String): VaultEntry? =
        getEntryById(id).orThrow("Could not read entry $id")

    /** [local]'s row with the agreed content; the replaced password goes to history. */
    private fun applied(local: VaultEntry, record: SyncRecord, now: Long): VaultEntry {
        val source = SyncRecordMapper.toDomain(record, local.id)
        val history = if (local.secret != source.secret && local.secret.isNotEmpty()) {
            local.history + VaultEntryHistory(
                previousSecret = local.secret,
                changedAt = now,
                changeReason = "LAN Sync merge"
            )
        } else {
            local.history
        }
        return local.copy(
            title = source.title,
            username = source.username,
            secret = source.secret,
            url = source.url,
            notes = source.notes,
            category = source.category,
            tags = source.tags,
            customFields = source.customFields,
            history = history,
            isFavorite = source.isFavorite,
            isDeleted = false,
            deletedAt = null,
            isCorrupted = false,
            updatedAt = record.updatedAt
        )
    }

    private fun newEntry(record: SyncRecord, syncId: String, now: Long): VaultEntry {
        val source = SyncRecordMapper.toDomain(record, syncId)
        return source.copy(
            history = emptyList(),
            isDeleted = false,
            deletedAt = null,
            isCorrupted = false,
            createdAt = if (source.createdAt > 0) source.createdAt else now,
            updatedAt = record.updatedAt
        )
    }
}
