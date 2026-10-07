package com.vaultpass.synccore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Sync data model v2: fingerprints, chunking and the rules that plan a merge. */
class SyncRecordsTest {

    private val bank = SyncRecord(
        syncId = "id-bank",
        title = "Bank",
        username = "Alice",
        password = "pw-1",
        url = "https://bank.example",
        notes = "note",
        category = "Finance",
        tags = listOf("work", "money"),
        customFields = listOf(CustomFieldRecord("PIN", "1234"), CustomFieldRecord("Answer", "blue")),
        favorite = true,
        updatedAt = 1_000L
    )

    // --- Fingerprint ---

    @Test
    fun contentHash_matchesTheReferenceValue() {
        // Computed independently in Python from the same field encoding.
        assertEquals("81df8128722138f2c4515b1dfe6c399a72829c92db3a65fb565f744264029c9c", SyncRecords.contentHash(bank))
        assertEquals(
            "1185f37d33b0f89e331f101a51bb8e51165c7efda15950b86a3ebcbb363f898e",
            SyncRecords.contentHash(SyncRecords.tombstone("x", 5L))
        )
    }

    @Test
    fun contentHash_seesEveryFieldButNotTimestampsOrOrder() {
        val hash = SyncRecords.contentHash(bank)
        assertEquals(hash, SyncRecords.contentHash(bank.copy(updatedAt = 99L)))
        assertEquals(hash, SyncRecords.contentHash(bank.copy(tags = listOf("money", "work"))))
        assertEquals(hash, SyncRecords.contentHash(bank.copy(customFields = bank.customFields.reversed())))

        assertNotEquals(hash, SyncRecords.contentHash(bank.copy(favorite = false)))
        assertNotEquals(hash, SyncRecords.contentHash(bank.copy(title = "BANK")))
        assertNotEquals(hash, SyncRecords.contentHash(bank.copy(username = "alice")))
        assertNotEquals(hash, SyncRecords.contentHash(bank.copy(customFields = listOf(CustomFieldRecord("PIN", "1234")))))
        assertNotEquals(hash, SyncRecords.contentHash(bank.copy(notes = "note ")))
    }

    // --- Validation and chunking ---

    @Test
    fun sanitize_dropsBadAndRepeatedIds_andStripsTombstones() {
        val records = listOf(
            bank,
            bank.copy(title = "Duplicate"),
            bank.copy(syncId = "bad id!"),
            bank.copy(syncId = ""),
            bank.copy(syncId = "gone", deleted = true, deletedAt = 7L)
        )
        val clean = SyncRecords.sanitize(records)
        assertEquals(listOf("id-bank", "gone"), clean.map { it.syncId })
        assertEquals("Bank", clean[0].title)
        assertEquals(SyncRecords.tombstone("gone", 7L), clean[1])
        assertFalse(SyncRecords.isValidSyncId("x".repeat(65)))
        assertTrue(SyncRecords.isValidSyncId("4f0c2b8e-1a2b-4c3d-9e8f-0123456789ab"))
    }

    @Test
    fun chunks_roundTrip_andStayUnderTheLimit() {
        val records = (1..500).map { bank.copy(syncId = "id-$it", notes = "n".repeat(300)) }
        val chunks = SyncRecords.encodeChunks(records, maxBytes = 20_000)
        assertTrue(chunks.size > 5, "Expected several chunks")
        chunks.forEach { assertTrue(it.length <= 20_000) }
        assertEquals(records, chunks.flatMap { SyncRecords.decodeChunk(it) })

        assertEquals(listOf("[]"), SyncRecords.encodeChunks(emptyList()))
        try {
            SyncRecords.decodeChunk("not json")
            fail<Unit>("Expected a protocol error")
        } catch (expected: SyncProtocolException) {
        }
    }

    // --- Planning ---

    private fun plan(local: List<SyncRecord>, remote: List<SyncRecord>, lastSyncAt: Long = 0L, blocked: Set<String> = emptySet()) =
        SyncPlanner.plan(local, remote, lastSyncAt, blocked).associateBy { it.syncId }

    @Test
    fun newAndUnchangedEntries() {
        val result = plan(
            local = listOf(bank, bank.copy(syncId = "only-here", title = "Mail")),
            remote = listOf(bank.copy(updatedAt = 5_000L), bank.copy(syncId = "only-there", title = "Shop"))
        )
        assertEquals(SyncChange.UNCHANGED, result["id-bank"]?.change)
        assertEquals(SyncChange.NEW_LOCAL, result["only-here"]?.change)
        assertFalse(result["only-here"]!!.preselect)
        assertEquals(SyncChange.NEW_REMOTE, result["only-there"]?.change)
        assertTrue(result["only-there"]!!.preselect)
    }

    @Test
    fun firstSync_theNewerVersionIsPreselected() {
        val newerThere = plan(listOf(bank), listOf(bank.copy(password = "pw-2", updatedAt = 2_000L)))["id-bank"]!!
        assertEquals(SyncChange.REMOTE_CHANGED, newerThere.change)
        assertTrue(newerThere.preselect)

        val newerHere = plan(listOf(bank.copy(updatedAt = 3_000L)), listOf(bank.copy(password = "pw-2", updatedAt = 2_000L)))["id-bank"]!!
        assertEquals(SyncChange.LOCAL_CHANGED, newerHere.change)
        assertFalse(newerHere.preselect)
    }

    @Test
    fun laterSyncs_useTheLastSyncTime() {
        val lastSync = 10_000L
        val onlyThere = plan(listOf(bank.copy(updatedAt = 5_000L)), listOf(bank.copy(password = "new", updatedAt = 12_000L)), lastSync)["id-bank"]!!
        assertEquals(SyncChange.REMOTE_CHANGED, onlyThere.change)
        assertTrue(onlyThere.preselect)

        // An older remote edit still loses to a local edit made since the last sync.
        val onlyHere = plan(listOf(bank.copy(updatedAt = 11_000L)), listOf(bank.copy(password = "old", updatedAt = 9_000L)), lastSync)["id-bank"]!!
        assertEquals(SyncChange.LOCAL_CHANGED, onlyHere.change)
        assertFalse(onlyHere.preselect)

        val both = plan(listOf(bank.copy(password = "mine", updatedAt = 11_000L)), listOf(bank.copy(password = "theirs", updatedAt = 12_000L)), lastSync)["id-bank"]!!
        assertEquals(SyncChange.CONFLICT, both.change)
        assertFalse(both.preselect)
    }

    @Test
    fun deletions() {
        // Deleted there, untouched here since: delete by default.
        val deletedThere = plan(listOf(bank), listOf(SyncRecords.tombstone("id-bank", 2_000L)))["id-bank"]!!
        assertEquals(SyncChange.DELETED_REMOTE, deletedThere.change)
        assertTrue(deletedThere.preselect)

        // Edited here after it was deleted there: keep it unless the user says otherwise.
        val editedAfter = plan(listOf(bank.copy(updatedAt = 3_000L)), listOf(SyncRecords.tombstone("id-bank", 2_000L)))["id-bank"]!!
        assertEquals(SyncChange.DELETED_REMOTE, editedAfter.change)
        assertFalse(editedAfter.preselect)

        // Deleted here: never brought back by default.
        val deletedHere = plan(listOf(SyncRecords.tombstone("id-bank", 2_000L)), listOf(bank.copy(updatedAt = 5_000L)))["id-bank"]!!
        assertEquals(SyncChange.DELETED_LOCAL, deletedHere.change)
        assertFalse(deletedHere.preselect)

        // Deleted on both, or a tombstone for something we never had: nothing to show.
        assertTrue(plan(listOf(SyncRecords.tombstone("id-bank", 1L)), listOf(SyncRecords.tombstone("id-bank", 2L))).isEmpty())
        assertTrue(plan(emptyList(), listOf(SyncRecords.tombstone("unknown", 2L))).isEmpty())
    }

    @Test
    fun legacyEntries_areMatchedByUniqueTitleAndUsername_andAdoptTheOtherDevicesId() {
        val here = bank.copy(syncId = "zzz-local")
        val there = bank.copy(syncId = "aaa-remote", title = " bank ", username = "ALICE")
        val decision = SyncPlanner.plan(listOf(here), listOf(there), lastSyncAt = 0L).single()
        assertEquals("aaa-remote", decision.syncId)
        assertEquals("zzz-local", decision.localIdToReplace)
        assertEquals("zzz-local", decision.local?.syncId)

        // The same when this device's id sorts first: only the planning device renames, because
        // a merge plan can carry only its old id.
        val mirrored = SyncPlanner.plan(listOf(there), listOf(here), lastSyncAt = 0L).single()
        assertEquals("zzz-local", mirrored.syncId)
        assertEquals("aaa-remote", mirrored.localIdToReplace)
    }

    @Test
    fun legacyDuplicates_areNeverCollapsed() {
        val local = listOf(bank.copy(syncId = "l1"), bank.copy(syncId = "l2", password = "other"))
        val remote = listOf(bank.copy(syncId = "r1"))
        val result = SyncPlanner.plan(local, remote, lastSyncAt = 0L)
        assertEquals(3, result.size)
        assertEquals(setOf(SyncChange.NEW_LOCAL), result.filter { it.local != null }.map { it.change }.toSet())
        assertEquals(SyncChange.NEW_REMOTE, result.single { it.remote != null }.change)
        assertTrue(result.all { it.localIdToReplace == null })
    }

    @Test
    fun blockedEntries_areLeftOut() {
        val result = plan(listOf(bank), listOf(bank.copy(password = "new", updatedAt = 9_000L)), blocked = setOf("id-bank"))
        assertTrue(result.isEmpty())
    }
}
