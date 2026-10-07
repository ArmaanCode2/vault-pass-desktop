package com.vaultpass.synccore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/** Sync session v3: outcomes, the agreed merge plan and the vault fingerprint. */
class SyncSessionTest {

    private val bank = SyncRecord(
        syncId = "id-bank",
        title = "Bank",
        username = "Alice",
        password = "pw-1",
        updatedAt = 1_000L
    )

    // --- Errors and timeouts ---

    @Test
    fun errorCodes_surviveARoundTrip_andAreDistinct() {
        SyncError.values().forEach { assertEquals(it, SyncError.fromCode(it.code)) }
        assertEquals(SyncError.UNKNOWN, SyncError.fromCode("something else"))
        assertEquals(SyncError.values().size, SyncError.values().map { it.code }.toSet().size)
        assertTrue(SyncError.values().all { it.message.isNotBlank() })
    }

    @Test
    fun theIdleLimitIsAboveEveryApprovalLimit() {
        val longest = SyncSessionState.values().mapNotNull { it.timeoutMs }.max()
        assertTrue(SyncTimeouts.IDLE_MS > longest, "The connection must outlive every step")
        assertNull(SyncSessionState.REVIEWING.timeoutMs)
        assertNull(SyncSessionState.IDLE.timeoutMs)
        assertNull(SyncSessionState.DONE.timeoutMs)
    }

    // --- Outcomes ---

    @Test
    fun everyChangeOffersOutcomes_andOnlyAConflictNeedsAChoice() {
        SyncChange.values().forEach { change ->
            val choices = SyncOutcomes.choicesFor(change)
            assertTrue(choices.isNotEmpty(), "$change offers nothing")
            assertTrue(choices.size == choices.toSet().size, "$change repeats a choice")
            assertEquals(choices.first(), SyncOutcomes.defaultFor(change))
            assertEquals(change == SyncChange.CONFLICT, SyncOutcomes.needsChoice(change))
        }
        assertEquals(ItemOutcome.USE_REMOTE, SyncOutcomes.defaultFor(SyncChange.NEW_REMOTE))
        assertEquals(ItemOutcome.USE_LOCAL, SyncOutcomes.defaultFor(SyncChange.NEW_LOCAL))
        assertEquals(ItemOutcome.DELETE_BOTH, SyncOutcomes.defaultFor(SyncChange.DELETED_LOCAL))
        assertEquals(listOf(ItemOutcome.SKIP), SyncOutcomes.choicesFor(SyncChange.UNCHANGED))
    }

    @Test
    fun aRemoteDeletionIsUndoneByDefaultOnlyIfWeEditedItLater() {
        val tombstone = SyncRecords.tombstone("id-bank", deletedAt = 2_000L)
        assertEquals(ItemOutcome.DELETE_BOTH, SyncOutcomes.defaultForRemoteDeletion(bank, tombstone))
        assertEquals(ItemOutcome.USE_LOCAL, SyncOutcomes.defaultForRemoteDeletion(bank.copy(updatedAt = 3_000L), tombstone))
        assertEquals(ItemOutcome.DELETE_BOTH, SyncOutcomes.defaultForRemoteDeletion(null, tombstone))
    }

    // --- Building the plan ---

    private fun input(
        change: SyncChange,
        outcome: ItemOutcome,
        local: SyncRecord? = bank,
        remote: SyncRecord? = bank.copy(password = "pw-2", updatedAt = 2_000L),
        edited: SyncRecord? = null,
        rename: String? = null
    ) = PlanInput("id-bank", change, outcome, local, remote, edited, rename)

    @Test
    fun useRemoteAndUseLocal_storeTheChosenVersionUnderTheAgreedId() {
        val remote = MergePlans.build(listOf(input(SyncChange.CONFLICT, ItemOutcome.USE_REMOTE)), now = 9_000L).single()
        assertEquals(PlanAction.UPSERT, remote.action)
        assertEquals("pw-2", remote.record?.password)
        assertEquals(2_000L, remote.record?.updatedAt)

        val local = MergePlans.build(listOf(input(SyncChange.CONFLICT, ItemOutcome.USE_LOCAL)), now = 9_000L).single()
        assertEquals("pw-1", local.record?.password)
        assertEquals(1_000L, local.record?.updatedAt)
    }

    @Test
    fun anEditedEntryIsStoredWithTheTimeItWasEdited() {
        val edited = bank.copy(password = "typed by hand")
        val item = MergePlans.build(listOf(input(SyncChange.CONFLICT, ItemOutcome.USE_REMOTE, edited = edited)), now = 9_000L).single()
        assertEquals("typed by hand", item.record?.password)
        assertEquals(9_000L, item.record?.updatedAt)
    }

    @Test
    fun deleteBoth_usesTheTimeTheEntryWasDeleted() {
        val tombstone = SyncRecords.tombstone("id-bank", deletedAt = 2_500L)
        val item = MergePlans.build(
            listOf(input(SyncChange.DELETED_REMOTE, ItemOutcome.DELETE_BOTH, remote = tombstone)),
            now = 9_000L
        ).single()
        assertEquals(PlanAction.RECYCLE, item.action)
        assertEquals(2_500L, item.deletedAt)
        assertNull(item.record)
    }

    @Test
    fun skipChangesNothing_unlessTheEntryStillNeedsItsAgreedId() {
        assertTrue(MergePlans.build(listOf(input(SyncChange.CONFLICT, ItemOutcome.SKIP)), now = 1L).isEmpty())

        val renamed = MergePlans.build(listOf(input(SyncChange.UNCHANGED, ItemOutcome.SKIP, rename = "old-id")), now = 1L).single()
        assertEquals(PlanAction.RENAME_ONLY, renamed.action)
        assertEquals("old-id", renamed.renameFrom)
        assertNull(renamed.record)
    }

    @Test
    fun aRenameTravelsWithTheEntryItBelongsTo() {
        val item = MergePlans.build(listOf(input(SyncChange.REMOTE_CHANGED, ItemOutcome.USE_REMOTE, rename = "old-id")), now = 1L).single()
        assertEquals("old-id", item.renameFrom)
        assertEquals("id-bank", item.syncId)
        assertEquals("id-bank", item.record?.syncId)
    }

    @Test
    fun anEntryOnlyOneDeviceHasTravelsInBothDirections() {
        val mine = MergePlans.build(
            listOf(PlanInput("id-bank", SyncChange.NEW_LOCAL, ItemOutcome.USE_LOCAL, bank, null)),
            now = 1L
        ).single()
        assertEquals(PlanAction.UPSERT, mine.action)
        assertEquals("pw-1", mine.record?.password)

        val theirs = MergePlans.build(
            listOf(PlanInput("id-bank", SyncChange.NEW_REMOTE, ItemOutcome.USE_REMOTE, null, bank)),
            now = 1L
        ).single()
        assertEquals(PlanAction.UPSERT, theirs.action)
    }

    // --- Checking a plan that arrived ---

    @Test
    fun aGoodPlanPasses_andABrokenOneIsRefused() {
        val good = listOf(
            PlanItem("id-bank", PlanAction.UPSERT, bank),
            PlanItem("id-mail", PlanAction.RECYCLE, deletedAt = 5L),
            PlanItem("id-shop", PlanAction.RENAME_ONLY, renameFrom = "old")
        )
        MergePlans.validate(good)

        refuses(listOf(PlanItem("bad id!", PlanAction.RECYCLE)))
        refuses(listOf(PlanItem("id-bank", PlanAction.UPSERT, bank), PlanItem("id-bank", PlanAction.RECYCLE)))
        refuses(listOf(PlanItem("id-bank", PlanAction.UPSERT, record = null)))
        refuses(listOf(PlanItem("id-bank", PlanAction.UPSERT, bank.copy(syncId = "id-other"))))
        refuses(listOf(PlanItem("id-bank", PlanAction.UPSERT, bank.copy(deleted = true))))
        refuses(listOf(PlanItem("id-bank", PlanAction.UPSERT, bank, renameFrom = "bad id!")))
    }

    private fun refuses(items: List<PlanItem>) {
        try {
            MergePlans.validate(items)
            fail<Unit>("Expected the plan to be refused")
        } catch (expected: SyncProtocolException) {
        }
    }

    @Test
    fun theSummaryCountsWhatTheOtherDeviceWillSee() {
        val items = listOf(
            PlanItem("known", PlanAction.UPSERT, bank.copy(syncId = "known")),
            PlanItem("fresh", PlanAction.UPSERT, bank.copy(syncId = "fresh")),
            PlanItem("gone", PlanAction.RECYCLE, deletedAt = 1L),
            PlanItem("absent", PlanAction.RECYCLE, deletedAt = 1L),
            PlanItem("moved", PlanAction.RENAME_ONLY, renameFrom = "old")
        )
        val summary = MergePlans.summarize(items, localIds = setOf("known", "gone"))
        assertEquals(1, summary.added)
        assertEquals(1, summary.updated)
        assertEquals(1, summary.deleted)
        assertEquals(1, summary.renamed)
        assertEquals(3, summary.total)
        assertFalse(summary.isEmpty)
        assertTrue(MergePlans.summarize(emptyList(), emptySet()).isEmpty)
    }

    @Test
    fun planChunks_roundTrip_andRejectRubbish() {
        val items = (1..400).map { PlanItem("id-$it", PlanAction.UPSERT, bank.copy(syncId = "id-$it", notes = "n".repeat(200))) }
        val chunks = MergePlans.encodeChunks(items, maxBytes = 20_000)
        assertTrue(chunks.size > 4, "Expected several chunks")
        chunks.forEach { assertTrue(it.length <= 20_000) }
        assertEquals(items, chunks.flatMap { MergePlans.decodeChunk(it) })
        assertEquals(listOf("[]"), MergePlans.encodeChunks(emptyList()))
        try {
            MergePlans.decodeChunk("{}")
            fail<Unit>("Expected a protocol error")
        } catch (expected: SyncProtocolException) {
        }
    }

    // --- Field limits ---

    @Test
    fun fieldLimitsAreRoomyAndConsistent() {
        assertTrue(EntryLimits.NOTES > EntryLimits.PASSWORD)
        assertTrue(EntryLimits.CUSTOM_FIELD_VALUE > EntryLimits.CUSTOM_FIELD_KEY)
        assertEquals("Max 200 characters", EntryLimits.tooLong(EntryLimits.TITLE))

        assertTrue(EntryLimits.fits(bank))
        assertFalse(EntryLimits.fits(bank.copy(notes = "n".repeat(EntryLimits.NOTES + 1))))
        assertFalse(EntryLimits.fits(bank.copy(title = "t".repeat(EntryLimits.TITLE + 1))))
        assertFalse(EntryLimits.fits(bank.copy(tags = List(EntryLimits.MAX_TAGS + 1) { "t" })))
        assertFalse(EntryLimits.fits(bank.copy(tags = listOf("t".repeat(EntryLimits.TAG + 1)))))
        assertFalse(EntryLimits.fits(bank.copy(customFields = List(EntryLimits.MAX_CUSTOM_FIELDS + 1) { CustomFieldRecord("k", "v") })))
        assertFalse(EntryLimits.fits(bank.copy(customFields = listOf(CustomFieldRecord("k".repeat(EntryLimits.CUSTOM_FIELD_KEY + 1), "v")))))
        assertFalse(EntryLimits.fits(bank.copy(customFields = listOf(CustomFieldRecord("k", "v".repeat(EntryLimits.CUSTOM_FIELD_VALUE + 1))))))
    }

    // --- Fingerprint ---

    @Test
    fun theFingerprintMatchesOnlyWhenTheVaultsMatch() {
        val mail = bank.copy(syncId = "id-mail", title = "Mail")
        val mine = MergePlans.vaultFingerprint(listOf(bank, mail))

        assertEquals(mine, MergePlans.vaultFingerprint(listOf(mail, bank)))
        // Deleted entries are not part of it.
        assertEquals(mine, MergePlans.vaultFingerprint(listOf(bank, mail, SyncRecords.tombstone("id-old", 1L))))
        assertEquals(mine, MergePlans.vaultFingerprint(listOf(bank.copy(updatedAt = 77L), mail)))

        assertNotEquals(mine, MergePlans.vaultFingerprint(listOf(bank)))
        assertNotEquals(mine, MergePlans.vaultFingerprint(listOf(bank.copy(password = "other"), mail)))
        assertNotEquals(mine, MergePlans.vaultFingerprint(listOf(bank.copy(syncId = "id-other"), mail)))
        assertEquals(32, mine.length)
    }
}
