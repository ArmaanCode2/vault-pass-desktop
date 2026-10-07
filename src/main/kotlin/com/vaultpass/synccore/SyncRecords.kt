package com.vaultpass.synccore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Sync data model v2: how entries travel between the apps and how the two vaults are compared.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
@Serializable
data class CustomFieldRecord(val key: String, val value: String)

/**
 * One vault entry on the wire. [syncId] is the same on every device. A deleted entry travels as a
 * tombstone: [deleted] set, [deletedAt] filled, and no content.
 */
@Serializable
data class SyncRecord(
    val syncId: String,
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val customFields: List<CustomFieldRecord> = emptyList(),
    val favorite: Boolean = false,
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
    val deletedAt: Long? = null
)

object SyncRecords {
    /** Each chunk of records is kept well under the 10 MB frame limit. */
    const val DEFAULT_CHUNK_BYTES = 1_000_000

    private val SYNC_ID = Regex("[A-Za-z0-9_-]{1,64}")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(SyncRecord.serializer())

    fun isValidSyncId(id: String): Boolean = SYNC_ID.matches(id)

    fun tombstone(syncId: String, deletedAt: Long): SyncRecord =
        SyncRecord(syncId = syncId, updatedAt = deletedAt, deleted = true, deletedAt = deletedAt)

    /** How legacy entries (from before syncIds) are matched on the first sync. */
    fun legacyKey(title: String, username: String): String =
        title.trim().lowercase() + "|" + username.trim().lowercase()

    /**
     * Fingerprint of an entry's content: every synced field (exact case), sorted tags and custom
     * fields, and the favorite flag. Timestamps are not part of it.
     */
    fun contentHash(record: SyncRecord): String {
        if (record.deleted) return Hex.encode(SyncCrypto.sha256("deleted".toByteArray(Charsets.UTF_8)))
        val parts = mutableListOf<ByteArray>()
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            parts += SyncCrypto.u32(bytes.size)
            parts += bytes
        }
        add(record.title)
        add(record.username)
        add(record.password)
        add(record.url)
        add(record.notes)
        add(record.category)
        val tags = record.tags.sorted()
        parts += SyncCrypto.u32(tags.size)
        tags.forEach { add(it) }
        val fields = record.customFields.sortedWith(compareBy<CustomFieldRecord>({ it.key }, { it.value }))
        parts += SyncCrypto.u32(fields.size)
        fields.forEach {
            add(it.key)
            add(it.value)
        }
        parts += byteArrayOf(if (record.favorite) 1 else 0)
        return Hex.encode(SyncCrypto.sha256(*parts.toTypedArray()))
    }

    /** Drops records with an invalid or repeated syncId, and strips any content from tombstones. */
    fun sanitize(records: List<SyncRecord>): List<SyncRecord> {
        val seen = HashSet<String>()
        return records.mapNotNull { record ->
            if (!isValidSyncId(record.syncId) || !seen.add(record.syncId)) return@mapNotNull null
            if (record.deleted) tombstone(record.syncId, record.deletedAt ?: record.updatedAt) else record
        }
    }

    /** Encodes [records] as JSON arrays of at most about [maxBytes] each; always at least one chunk. */
    fun encodeChunks(records: List<SyncRecord>, maxBytes: Int = DEFAULT_CHUNK_BYTES): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var count = 0
        for (record in records) {
            val item = json.encodeToString(SyncRecord.serializer(), record)
            if (count > 0 && current.length + item.length + 2 > maxBytes) {
                chunks += "[$current]"
                current.clear()
                count = 0
            }
            if (count > 0) current.append(',')
            current.append(item)
            count++
        }
        chunks += "[$current]"
        return chunks
    }

    fun decodeChunk(text: String): List<SyncRecord> = try {
        json.decodeFromString(listSerializer, text)
    } catch (e: Exception) {
        throw SyncProtocolException("Malformed sync records")
    }
}

/** What happened to one entry between the two vaults. */
enum class SyncChange {
    /** Only the other device has it. */
    NEW_REMOTE,

    /** Only this device has it. */
    NEW_LOCAL,

    /** Same content on both. */
    UNCHANGED,

    /** The other device's version should replace ours. */
    REMOTE_CHANGED,

    /** Our version is the one to keep. */
    LOCAL_CHANGED,

    /** Both devices changed it since the last sync. */
    CONFLICT,

    /** The other device deleted it; we still have it. */
    DELETED_REMOTE,

    /** We deleted it; the other device still has it. */
    DELETED_LOCAL
}

/**
 * The plan for one entry. [preselect] is the default the review screen shows. When a legacy entry
 * was matched by title and username, [localIdToReplace] is our current syncId for it, which must
 * be replaced by [syncId] when the merge is applied (whatever the user selects).
 */
class SyncDecision(
    val syncId: String,
    val local: SyncRecord?,
    val remote: SyncRecord?,
    val change: SyncChange,
    val preselect: Boolean,
    val localIdToReplace: String? = null
)

object SyncPlanner {

    /**
     * Compares the two vaults. [local] and [remote] contain active entries and tombstones;
     * [lastSyncAt] is when this device last synced with the peer (0 = never). Entries whose
     * syncId is in [blockedIds] (for example ones that fail to decrypt here) are left out.
     */
    fun plan(
        local: List<SyncRecord>,
        remote: List<SyncRecord>,
        lastSyncAt: Long,
        blockedIds: Set<String> = emptySet()
    ): List<SyncDecision> {
        val localById = LinkedHashMap<String, SyncRecord>()
        local.forEach { if (it.syncId !in blockedIds) localById.putIfAbsent(it.syncId, it) }
        val remoteById = LinkedHashMap<String, SyncRecord>()
        SyncRecords.sanitize(remote).forEach { if (it.syncId !in blockedIds) remoteById.putIfAbsent(it.syncId, it) }

        // Entries from before syncIds existed have different ids on each device. Match those
        // by title and username, but only when that pair is unique on both sides. The match keeps
        // the other device's id and this device renames its entry: the merge plan carries only
        // this device's old id, so the other device must not need a rename.
        val legacyLocalFor = HashMap<String, SyncRecord>()
        val localLoose = localById.values.filter { !it.deleted && it.syncId !in remoteById }
            .groupBy { SyncRecords.legacyKey(it.title, it.username) }
        val remoteLoose = remoteById.values.filter { !it.deleted && it.syncId !in localById }
            .groupBy { SyncRecords.legacyKey(it.title, it.username) }
        for ((key, locals) in localLoose) {
            val remotes = remoteLoose[key] ?: continue
            if (locals.size == 1 && remotes.size == 1) legacyLocalFor[remotes[0].syncId] = locals[0]
        }

        val decisions = mutableListOf<SyncDecision>()
        val handledLocal = HashSet<String>()
        for (remoteRecord in remoteById.values) {
            val byId = localById[remoteRecord.syncId]
            val legacy = if (byId == null) legacyLocalFor[remoteRecord.syncId] else null
            val localRecord = byId ?: legacy
            if (localRecord == null) {
                if (!remoteRecord.deleted) {
                    decisions += SyncDecision(remoteRecord.syncId, null, remoteRecord, SyncChange.NEW_REMOTE, preselect = true)
                }
                continue
            }
            handledLocal += localRecord.syncId
            val (change, preselect) = compare(localRecord, remoteRecord, lastSyncAt) ?: continue
            decisions += SyncDecision(
                syncId = remoteRecord.syncId,
                local = localRecord,
                remote = remoteRecord,
                change = change,
                preselect = preselect,
                localIdToReplace = legacy?.syncId
            )
        }
        for (localRecord in localById.values) {
            if (localRecord.syncId in handledLocal || localRecord.deleted) continue
            decisions += SyncDecision(localRecord.syncId, localRecord, null, SyncChange.NEW_LOCAL, preselect = false)
        }
        return decisions
    }

    /** Null when both sides deleted the entry: nothing to show. */
    private fun compare(local: SyncRecord, remote: SyncRecord, lastSyncAt: Long): Pair<SyncChange, Boolean>? {
        if (local.deleted && remote.deleted) return null
        if (remote.deleted) {
            // Deleting is the default, unless we edited the entry after it was deleted there.
            val deletedAt = remote.deletedAt ?: remote.updatedAt
            return SyncChange.DELETED_REMOTE to (local.updatedAt <= deletedAt)
        }
        // Never brought back by default.
        if (local.deleted) return SyncChange.DELETED_LOCAL to false
        if (SyncRecords.contentHash(local) == SyncRecords.contentHash(remote)) return SyncChange.UNCHANGED to false

        if (lastSyncAt > 0) {
            val localChanged = local.updatedAt > lastSyncAt
            val remoteChanged = remote.updatedAt > lastSyncAt
            when {
                remoteChanged && !localChanged -> return SyncChange.REMOTE_CHANGED to true
                localChanged && !remoteChanged -> return SyncChange.LOCAL_CHANGED to false
                localChanged && remoteChanged -> return SyncChange.CONFLICT to false
            }
        }
        // First sync with this device (or neither changed since): the newer version wins.
        return if (remote.updatedAt > local.updatedAt) {
            SyncChange.REMOTE_CHANGED to true
        } else {
            SyncChange.LOCAL_CHANGED to false
        }
    }
}
