package com.vaultpass.desktop.domain.backup

import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.synccore.SyncRecords

/** How a restore treats entries the vault already has (D15). */
enum class RestoreMode {
    /** Adds what the vault lacks and takes versions newer than the vault's. Nothing is reverted, deleted or undeleted. */
    MERGE,

    /** Makes the vault match the backup: later changes are reverted, entries not in the backup go to the Recycle Bin. */
    REPLACE
}

/** What restoring a backup would change, counted before anything is written (D15). */
data class RestorePreview(
    /** When the backup was made. */
    val backupCreatedAt: Long,
    val inBackup: Int,
    /** Not in the vault (and not deleted from it): both modes add them. */
    val added: Int,
    /** Of [added], the ones that were in the backup's Recycle Bin and go to the vault's. */
    val addedToBin: Int,
    /** The same in the backup and the vault. */
    val unchanged: Int,
    /** The backup's version is newer: both modes take it. */
    val newerInBackup: Int,
    /** Changed in the vault after the backup: Merge keeps the vault's version, Replace reverts it. */
    val changedSinceBackup: Int,
    /** Deleted from the vault after the backup (Recycle Bin or for good): restored only when asked. */
    val deletedSinceBackup: Int,
    /** Active entries the backup doesn't have: Replace moves them to the Recycle Bin. */
    val onlyInVault: Int,
    /** Entries in the vault that can't be decrypted: never changed. */
    val unreadableInVault: Int
)

/** What a restore changed. */
data class RestoreOutcome(val added: Int, val updated: Int, val broughtBack: Int, val movedToBin: Int)

internal enum class RestoreKind { ADDED, UNCHANGED, NEWER_IN_BACKUP, CHANGED_SINCE_BACKUP, DELETED_SINCE_BACKUP, UNREADABLE_IN_VAULT }

/** A backup entry, the vault's matching entry (if any), and how the two relate. */
internal data class RestoreItem(val backup: VaultEntry, val current: VaultEntry?, val kind: RestoreKind)

internal data class RestorePlan(
    val items: List<RestoreItem>,
    /** Active, readable vault entries the backup doesn't have. */
    val onlyInVault: List<VaultEntry>,
    val unreadableInVault: Int
) {
    /**
     * What this plan would do, entry by entry. A restore applies only the plan its preview showed:
     * if the vault changed meanwhile (a sync), the signatures differ.
     */
    val signature: Pair<List<Triple<String, String?, RestoreKind>>, List<String>>
        get() = items.map { Triple(it.backup.id, it.current?.id, it.kind) } to onlyInVault.map { it.id }

    fun preview(backupCreatedAt: Long) = RestorePreview(
        backupCreatedAt = backupCreatedAt,
        inBackup = items.size,
        added = count(RestoreKind.ADDED),
        addedToBin = items.count { it.kind == RestoreKind.ADDED && it.backup.isDeleted },
        unchanged = count(RestoreKind.UNCHANGED),
        newerInBackup = count(RestoreKind.NEWER_IN_BACKUP),
        changedSinceBackup = count(RestoreKind.CHANGED_SINCE_BACKUP),
        deletedSinceBackup = count(RestoreKind.DELETED_SINCE_BACKUP),
        onlyInVault = onlyInVault.size,
        unreadableInVault = unreadableInVault
    )

    private fun count(kind: RestoreKind) = items.count { it.kind == kind }
}

/**
 * Compares [backup] with the vault: [vaultEntries] holds its active and Recycle Bin entries,
 * [tombstoneIds] the ids of entries deleted from it for good. Writes nothing.
 *
 * - A backup entry matches the vault entry with its id. The first sync with a phone may have
 *   given a vault entry the phone's id, without a tombstone for the old one; so an entry with
 *   no id match matches the vault entry with the same title and username, when that pair is
 *   unique on both sides (as sync matches them).
 * - A change between the Recycle Bin and the vault made after the backup always counts as a
 *   later change, whatever the dates say: moving an entry to or from the bin doesn't change its
 *   date on every device.
 */
internal fun planRestore(backup: List<VaultEntry>, vaultEntries: List<VaultEntry>, tombstoneIds: Set<String>): RestorePlan {
    val vaultById = vaultEntries.associateBy { it.id }
    val backupEntries = backup.distinctBy { it.id }
    val backupIds = backupEntries.map { it.id }.toSet()

    val unmatchedBackup = backupEntries.filter { it.id !in vaultById && it.id !in tombstoneIds }
        .groupBy { SyncRecords.legacyKey(it.title, it.username) }
    val unmatchedVault = vaultEntries.filter { it.id !in backupIds && !it.isCorrupted }
        .groupBy { SyncRecords.legacyKey(it.title, it.username) }
    val byName = HashMap<String, VaultEntry>()
    for ((key, entries) in unmatchedBackup) {
        val candidates = unmatchedVault[key] ?: continue
        if (entries.size == 1 && candidates.size == 1) byName[entries[0].id] = candidates[0]
    }
    val matchedByName = byName.values.map { it.id }.toSet()

    val items = backupEntries.map { entry ->
        val current = vaultById[entry.id] ?: byName[entry.id]
        val kind = when {
            current == null -> when {
                entry.id !in tombstoneIds -> RestoreKind.ADDED
                // Deleted in both: the backup had it in the Recycle Bin, the vault has since purged it.
                entry.isDeleted -> RestoreKind.UNCHANGED
                else -> RestoreKind.DELETED_SINCE_BACKUP
            }
            current.isCorrupted -> RestoreKind.UNREADABLE_IN_VAULT
            sameContent(entry, current) -> RestoreKind.UNCHANGED
            current.isDeleted && !entry.isDeleted -> RestoreKind.DELETED_SINCE_BACKUP
            // Restored from the Recycle Bin after the backup.
            !current.isDeleted && entry.isDeleted -> RestoreKind.CHANGED_SINCE_BACKUP
            entry.updatedAt > current.updatedAt -> RestoreKind.NEWER_IN_BACKUP
            else -> RestoreKind.CHANGED_SINCE_BACKUP
        }
        RestoreItem(entry, current, kind)
    }
    return RestorePlan(
        items = items,
        onlyInVault = vaultEntries.filter {
            it.id !in backupIds && it.id !in matchedByName && !it.isDeleted && !it.isCorrupted
        },
        unreadableInVault = vaultEntries.count { it.isCorrupted }
    )
}

/**
 * The same entry as far as a user can tell: dates and password history aside, and tags and custom
 * fields in any order (as sync compares them).
 */
private fun sameContent(a: VaultEntry, b: VaultEntry): Boolean =
    a.title == b.title &&
        a.username == b.username &&
        a.secret == b.secret &&
        a.url == b.url &&
        a.notes == b.notes &&
        a.category.orEmpty() == b.category.orEmpty() &&
        a.tags.sorted() == b.tags.sorted() &&
        a.customFields.sortedWith(compareBy({ it.key }, { it.value })) ==
        b.customFields.sortedWith(compareBy({ it.key }, { it.value })) &&
        a.isFavorite == b.isFavorite &&
        a.isDeleted == b.isDeleted
