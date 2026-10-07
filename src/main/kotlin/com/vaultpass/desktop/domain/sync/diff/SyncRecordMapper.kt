package com.vaultpass.desktop.domain.sync.diff

import com.vaultpass.desktop.domain.models.CustomField
import com.vaultpass.desktop.domain.models.SyncTombstone
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.synccore.CustomFieldRecord
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords

/** Converts between vault entries and sync records (sync data model v2). */
object SyncRecordMapper {

    fun toRecord(entry: VaultEntry): SyncRecord = SyncRecord(
        syncId = entry.id,
        title = entry.title,
        username = entry.username,
        password = entry.secret,
        url = entry.url,
        notes = entry.notes,
        category = entry.category ?: "",
        tags = entry.tags,
        customFields = entry.customFields.map { CustomFieldRecord(it.key, it.value) },
        favorite = entry.isFavorite,
        updatedAt = entry.updatedAt
    )

    /** The entry a (non-deleted) remote record describes, with the id [id]. */
    fun toDomain(record: SyncRecord, id: String = record.syncId): VaultEntry = VaultEntry(
        id = id,
        title = record.title,
        username = record.username,
        secret = record.password,
        url = record.url,
        notes = record.notes,
        category = record.category.ifEmpty { null },
        tags = record.tags,
        customFields = record.customFields.map { CustomField(it.key, it.value) },
        isFavorite = record.favorite,
        createdAt = record.updatedAt,
        updatedAt = record.updatedAt
    )

    /**
     * What this device sends: active entries in full, recycle-bin entries and permanently deleted
     * ones as tombstones. Entries that failed to decrypt are left out (see [blockedIds]).
     */
    fun localRecords(
        activeEntries: List<VaultEntry>,
        deletedEntries: List<VaultEntry>,
        tombstones: List<SyncTombstone>
    ): List<SyncRecord> {
        val records = LinkedHashMap<String, SyncRecord>()
        activeEntries.filter { !it.isCorrupted }.forEach { records.putIfAbsent(it.id, toRecord(it)) }
        deletedEntries.filter { !it.isCorrupted }.forEach {
            records.putIfAbsent(it.id, SyncRecords.tombstone(it.id, it.deletedAt ?: it.updatedAt))
        }
        tombstones.forEach { records.putIfAbsent(it.syncId, SyncRecords.tombstone(it.syncId, it.deletedAt)) }
        return records.values.toList()
    }

    /** Ids of entries this device can't read: never sent, and left alone by the merge. */
    fun blockedIds(activeEntries: List<VaultEntry>, deletedEntries: List<VaultEntry>): Set<String> =
        (activeEntries + deletedEntries).filter { it.isCorrupted }.map { it.id }.toSet()
}
