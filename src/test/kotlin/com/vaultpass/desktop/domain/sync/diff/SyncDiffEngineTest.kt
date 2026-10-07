package com.vaultpass.desktop.domain.sync.diff

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.SQLiteVaultDataSource
import com.vaultpass.desktop.data.repository.VaultRepositoryImpl
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.VaultRepository
import com.vaultpass.desktop.domain.crypto.CryptoManager
import com.vaultpass.desktop.domain.errors.DatabaseError
import com.vaultpass.desktop.domain.models.CustomField
import com.vaultpass.desktop.domain.models.SyncTombstone
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.synccore.CustomFieldRecord
import com.vaultpass.synccore.EntryLimits
import com.vaultpass.synccore.ItemOutcome
import com.vaultpass.synccore.MergePlans
import com.vaultpass.synccore.PlanAction
import com.vaultpass.synccore.PlanInput
import com.vaultpass.synccore.PlanItem
import com.vaultpass.synccore.SyncOutcomes
import com.vaultpass.synccore.SyncPlanner
import com.vaultpass.synccore.SyncRecord
import com.vaultpass.synccore.SyncRecords
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import java.io.File

class SyncDiffEngineTest {

    private lateinit var tempDbFile: File
    private lateinit var connectionManager: SQLiteConnectionManager
    private lateinit var dataSource: SQLiteVaultDataSource
    private lateinit var repository: VaultRepositoryImpl
    private val mockCryptoManager = mock<CryptoManager> {
        on { encryptData(any()) }.thenAnswer { it.arguments[0] as ByteArray }
        on { decryptData(any()) }.thenAnswer { it.arguments[0] as ByteArray }
    }

    @BeforeEach
    fun setUp() {
        tempDbFile = File.createTempFile("vaultpass_sync_diff_test_", ".db")
        connectionManager = SQLiteConnectionManager(tempDbFile.absolutePath)
        connectionManager.openConnection()
        dataSource = SQLiteVaultDataSource(connectionManager)
        repository = VaultRepositoryImpl(dataSource, mockCryptoManager)
    }

    @AfterEach
    fun tearDown() {
        connectionManager.closeConnection()
        if (tempDbFile.exists()) {
            tempDbFile.delete()
        }
    }

    private fun record(entry: VaultEntry): SyncRecord = SyncRecordMapper.toRecord(entry)

    private suspend fun active(): List<VaultEntry> = (repository.observeAllEntries(false).first() as RepositoryResult.Success).data

    private suspend fun recycled(): List<VaultEntry> = (repository.observeAllEntries(true).first() as RepositoryResult.Success).data

    private suspend fun tombstones(): List<SyncTombstone> = (repository.getTombstones() as RepositoryResult.Success).data

    private suspend fun diffAgainstVault(remote: List<SyncRecord>, lastSyncAt: Long = 0L): SyncDiffResult =
        SyncDiffEngine.computeDiff(active(), remote, lastSyncAt, recycled(), tombstones())

    /** What confirming the review does on this device: build the agreed plan and apply it. */
    private suspend fun merge(items: List<EntryDiffItem>, repo: VaultRepository = repository): Result<Int> =
        SyncMergeExecutor.applyPlan(MergePlans.build(items.map { it.toPlanInput() }, System.currentTimeMillis()), repo)

    private suspend fun localRecords(): List<SyncRecord> = SyncRecordMapper.localRecords(active(), recycled(), tombstones())

    private val gitHub = VaultEntry(
        id = "id-github",
        title = "GitHub",
        username = "alice",
        secret = "oldPassword123",
        url = "https://github.com",
        notes = "Original notes",
        category = "Development",
        tags = listOf("work", "git"),
        createdAt = 1000L,
        updatedAt = 1000L
    )

    // --- Categories ---

    @Test
    fun testIdenticalEntriesCategorizedAsUnchanged() {
        val remote = gitHub.copy(updatedAt = 2000L)

        val diffResult = SyncDiffEngine.computeDiff(listOf(gitHub), listOf(record(remote)))

        assertEquals(1, diffResult.unchangedCount)
        assertEquals(0, diffResult.newRemoteCount)
        assertEquals(0, diffResult.newLocalCount)
        assertEquals(0, diffResult.modifiedCount)

        val item = diffResult.diffItems.first()
        assertEquals(EntrySyncCategory.UNCHANGED, item.category)
        assertEquals("id-github", item.syncKey)
        assertEquals(ItemOutcome.SKIP, item.outcome, "Identical items are left unchanged")
        assertFalse(item.changesSomething)
        assertEquals(
            listOf("Title", "Username", "Password", "URL", "Notes", "Category", "Tags", "Custom Fields", "Favorite"),
            item.fieldDiffs.map { it.fieldName }
        )
        assertTrue(item.fieldDiffs.all { it.changeType == FieldChangeType.UNCHANGED })
    }

    @Test
    fun testNewRemoteAndNewLocalEntriesDetected() {
        val local = VaultEntry(id = "local-only", title = "Bitbucket", username = "alice", secret = "BitSecret1!", createdAt = 1000L, updatedAt = 1000L)
        val remote = VaultEntry(id = "remote-only", title = "GitHub", username = "bob", secret = "GitSecret2@", createdAt = 1000L, updatedAt = 1000L)

        val diffResult = SyncDiffEngine.computeDiff(listOf(local), listOf(record(remote)))

        assertEquals(1, diffResult.newRemoteCount)
        assertEquals(1, diffResult.newLocalCount)

        val remoteItem = diffResult.diffItems.first { it.syncKey == "remote-only" }
        assertEquals(EntrySyncCategory.NEW_REMOTE, remoteItem.category)
        assertEquals(ItemOutcome.USE_REMOTE, remoteItem.outcome)
        assertNull(remoteItem.localEntry)
        assertEquals("GitHub", remoteItem.remoteEntry?.title)

        val localItem = diffResult.diffItems.first { it.syncKey == "local-only" }
        assertEquals(EntrySyncCategory.NEW_LOCAL, localItem.category)
        assertEquals(ItemOutcome.USE_LOCAL, localItem.outcome, "Sent to the other device by default")
        assertNull(localItem.remoteEntry)
    }

    @Test
    fun testFieldLevelDifferencesDetected() {
        val remote = gitHub.copy(
            secret = "newPassword456",
            notes = "Updated notes from phone",
            tags = listOf("git", "work"), // same tags, different order
            updatedAt = 1500L
        )

        val diffResult = SyncDiffEngine.computeDiff(listOf(gitHub), listOf(record(remote)))

        assertEquals(1, diffResult.modifiedCount)
        val item = diffResult.diffItems.first()
        assertEquals(EntrySyncCategory.MODIFIED, item.category)
        assertEquals(ItemOutcome.USE_REMOTE, item.outcome, "The newer remote version wins by default on a first sync")

        val byName = item.fieldDiffs.associateBy { it.fieldName }
        assertEquals(FieldChangeType.MODIFIED, byName.getValue("Password").changeType)
        assertEquals("oldPassword123", byName.getValue("Password").localValue)
        assertEquals("newPassword456", byName.getValue("Password").remoteValue)
        assertEquals(FieldChangeType.MODIFIED, byName.getValue("Notes").changeType)
        assertEquals(FieldChangeType.UNCHANGED, byName.getValue("URL").changeType)
        assertEquals(FieldChangeType.UNCHANGED, byName.getValue("Category").changeType)
        assertEquals(FieldChangeType.UNCHANGED, byName.getValue("Tags").changeType)
    }

    @Test
    fun testOlderRemoteVersionIsNotPreselected() {
        val remote = gitHub.copy(secret = "older", updatedAt = 500L)
        val item = SyncDiffEngine.computeDiff(listOf(gitHub), listOf(record(remote))).diffItems.single()
        assertEquals(EntrySyncCategory.MODIFIED, item.category)
        assertEquals(ItemOutcome.USE_LOCAL, item.outcome, "This PC's newer version is kept and sent")
    }

    @Test
    fun testFavoriteOnlyChangeIsDetected() {
        val remote = gitHub.copy(isFavorite = true, updatedAt = 2000L)
        val item = SyncDiffEngine.computeDiff(listOf(gitHub), listOf(record(remote))).diffItems.single()

        assertEquals(EntrySyncCategory.MODIFIED, item.category)
        assertEquals(listOf("Favorite"), item.fieldDiffs.filter { it.changeType == FieldChangeType.MODIFIED }.map { it.fieldName })
    }

    @Test
    fun testCaseOnlyChangeIsDetected() {
        val remote = gitHub.copy(username = "Alice", updatedAt = 2000L)
        val item = SyncDiffEngine.computeDiff(listOf(gitHub), listOf(record(remote))).diffItems.single()
        assertEquals(EntrySyncCategory.MODIFIED, item.category)
        assertEquals(listOf("Username"), item.fieldDiffs.filter { it.changeType == FieldChangeType.MODIFIED }.map { it.fieldName })
    }

    @Test
    fun testCustomFieldDifferencesAreDetected() {
        val local = gitHub.copy(customFields = listOf(CustomField("PIN", "1234"), CustomField("Q", "blue")))
        val reordered = local.copy(customFields = local.customFields.reversed(), updatedAt = 2000L)
        assertEquals(
            EntrySyncCategory.UNCHANGED,
            SyncDiffEngine.computeDiff(listOf(local), listOf(record(reordered))).diffItems.single().category,
            "Order of custom fields doesn't matter"
        )

        val changed = local.copy(customFields = listOf(CustomField("PIN", "9999"), CustomField("Q", "blue")), updatedAt = 2000L)
        val item = SyncDiffEngine.computeDiff(listOf(local), listOf(record(changed))).diffItems.single()
        assertEquals(EntrySyncCategory.MODIFIED, item.category)
        val fieldDiff = item.fieldDiffs.single { it.changeType == FieldChangeType.MODIFIED }
        assertEquals("Custom Fields", fieldDiff.fieldName)
        assertEquals("PIN: 1234\nQ: blue", fieldDiff.localValue)
        assertEquals("PIN: 9999\nQ: blue", fieldDiff.remoteValue)
    }

    @Test
    fun testChangesOnBothSidesSinceLastSyncAreAConflict() {
        val local = gitHub.copy(secret = "pc-change", updatedAt = 3000L)
        val remote = gitHub.copy(secret = "phone-change", updatedAt = 4000L)

        val diff = SyncDiffEngine.computeDiff(listOf(local), listOf(record(remote)), lastSyncAt = 2000L)

        assertEquals(1, diff.conflictCount)
        assertEquals(0, diff.modifiedCount)
        val item = diff.diffItems.single()
        assertEquals(EntrySyncCategory.CONFLICT, item.category)
        assertTrue(item.isUnanswered, "Conflicts are never resolved by default")
        assertEquals(1, diff.unansweredCount)
        assertEquals(listOf(ItemOutcome.USE_REMOTE, ItemOutcome.USE_LOCAL, ItemOutcome.SKIP), item.choices)
    }

    @Test
    fun testCorruptedLocalEntriesAreBlocked() {
        val corrupted = VaultEntry(id = "broken", title = "Corrupted Entry", username = "", secret = "", createdAt = 1L, updatedAt = 1L, isCorrupted = true)
        val remote = SyncRecord(syncId = "broken", title = "Fine on the phone", password = "pw", updatedAt = 5L)

        assertTrue(SyncRecordMapper.localRecords(listOf(corrupted), emptyList(), emptyList()).isEmpty(), "Never sent")
        val diff = SyncDiffEngine.computeDiff(listOf(corrupted), listOf(remote))
        assertTrue(diff.diffItems.isEmpty(), "Neither side's copy may be touched")
    }

    // --- Merging ---

    @Test
    fun testModifiedMergeWithHistoryTracking() = runBlocking {
        repository.createEntry(gitHub)
        val remote = gitHub.copy(secret = "RotatedRootPass2@", notes = "Updated on Android", updatedAt = 2000L)

        val diffResult = diffAgainstVault(listOf(record(remote)))
        assertEquals(1, diffResult.modifiedCount)

        val mergeResult = merge(diffResult.diffItems)
        assertEquals(1, mergeResult.getOrThrow())

        val merged = (repository.getEntryById("id-github") as RepositoryResult.Success).data!!
        assertEquals("RotatedRootPass2@", merged.secret)
        assertEquals("Updated on Android", merged.notes)
        assertEquals(2000L, merged.updatedAt, "The merged entry keeps the other device's updatedAt")
        assertEquals(1, merged.history.size)
        assertEquals("oldPassword123", merged.history[0].previousSecret)
    }

    @Test
    fun testCustomFieldsSurviveTheWholeRoundTrip() = runBlocking {
        val remote = VaultEntry(
            id = "remote-fields",
            title = "Bank",
            username = "me",
            secret = "pw",
            customFields = listOf(CustomField("PIN", "1234"), CustomField("Account", "0042")),
            createdAt = 1000L,
            updatedAt = 1000L
        )
        // Over the wire and back.
        val wire = SyncRecords.decodeChunk(SyncRecords.encodeChunks(listOf(record(remote))).single())
        assertEquals(listOf(CustomFieldRecord("PIN", "1234"), CustomFieldRecord("Account", "0042")), wire.single().customFields)

        val diff = diffAgainstVault(wire)
        merge(diff.diffItems).getOrThrow()

        // Through the encrypted payload in the database.
        repository.clearCache()
        val stored = (repository.getEntryById("remote-fields") as RepositoryResult.Success).data!!
        assertEquals(remote.customFields, stored.customFields)

        // And back out as this device's record.
        assertEquals(wire.single(), SyncRecordMapper.localRecords(active(), recycled(), tombstones()).single())
    }

    @Test
    fun testNewRemoteIsInsertedWithItsSyncId() = runBlocking {
        val remote = SyncRecord(syncId = "phone-uuid-1", title = "AWS", username = "root", password = "pw", favorite = true, updatedAt = 1234L)

        merge(diffAgainstVault(listOf(remote)).diffItems).getOrThrow()

        val inserted = active().single()
        assertEquals("phone-uuid-1", inserted.id)
        assertEquals(1234L, inserted.updatedAt)
        assertTrue(inserted.isFavorite)
        assertNull(inserted.category)
    }

    @Test
    fun testEditedEntryOverrideHonoredInMerge() = runBlocking {
        val remote = VaultEntry(id = "rem-1", title = "DigitalOcean", username = "sammy", secret = "DropletPass123", createdAt = 1000L, updatedAt = 1000L)

        val baseDiffItem = SyncDiffEngine.computeDiff(emptyList(), listOf(record(remote))).diffItems.first()
        // User edits entry directly in review modal before clicking Merge
        val diffItem = baseDiffItem.copy(
            editedEntry = baseDiffItem.remoteEntry!!.copy(notes = "User custom override note", category = "Hosting")
        )

        val before = System.currentTimeMillis()
        val mergeResult = merge(listOf(diffItem))
        assertEquals(1, mergeResult.getOrThrow())

        val entry = active().single()
        assertEquals("rem-1", entry.id)
        assertEquals("User custom override note", entry.notes)
        assertEquals("Hosting", entry.category)
        assertTrue(entry.updatedAt >= before, "An inline edit counts as a new change")
    }

    @Test
    fun testAtomicMergeTransactionRollsBackOnError() = runBlocking {
        val item1 = SyncDiffEngine.computeDiff(
            emptyList(),
            listOf(SyncRecord(syncId = "rem-1", title = "Service 1", username = "user1", password = "pass1", updatedAt = 1000L))
        ).diffItems.single()
        val item2 = SyncDiffEngine.computeDiff(
            emptyList(),
            listOf(SyncRecord(syncId = "rem-2", title = "FAIL_ON_THIS", username = "user2", password = "pass2", updatedAt = 1000L))
        ).diffItems.single()

        // Wrap repository with a decorator that throws on "FAIL_ON_THIS"
        class FailingRepo : VaultRepository by repository {
            override suspend fun createEntry(entry: VaultEntry): RepositoryResult<Unit> {
                if (entry.title == "FAIL_ON_THIS") {
                    return RepositoryResult.Error(DatabaseError.CorruptionDetected("Simulated disk write failure"))
                }
                return repository.createEntry(entry)
            }

            override suspend fun <T> transaction(block: suspend VaultRepository.() -> T): RepositoryResult<T> {
                return repository.transaction {
                    this@FailingRepo.block()
                }
            }
        }

        val result = merge(listOf(item1, item2), FailingRepo())
        assertTrue(result.isFailure, "Merge should fail when an item write fails")
        assertTrue(active().isEmpty(), "Database should contain 0 entries due to atomic rollback")
    }

    @Test
    fun testLegacyEntryIsRenamedOnMergeEvenWhenLeftUnchanged() = runBlocking {
        // Created before syncIds: the two devices gave the same entry different ids.
        repository.createEntry(gitHub.copy(id = "zz-local-id"))
        val remote = SyncRecord(syncId = "aa-phone-id", title = "GitHub", username = "alice", password = "phone-pw", updatedAt = 500L)

        val item = diffAgainstVault(listOf(remote)).diffItems.single()
        assertEquals("aa-phone-id", item.syncKey, "The phone's id is kept; this PC renames its entry")
        assertEquals("zz-local-id", item.localSyncIdToReplace)
        assertEquals("zz-local-id", item.localEntry?.id)
        assertEquals(ItemOutcome.USE_LOCAL, item.outcome, "This PC's version is the newer one")

        merge(listOf(item.copy(outcome = ItemOutcome.SKIP))).getOrThrow()

        val renamed = active().single()
        assertEquals("aa-phone-id", renamed.id)
        assertEquals("oldPassword123", renamed.secret, "Left unchanged: only the id changes")
        assertNull((repository.getEntryById("zz-local-id") as RepositoryResult.Success).data)
    }

    @Test
    fun testLegacyEntryRenameAndReplaceWhenSelected() = runBlocking {
        repository.createEntry(gitHub.copy(id = "zz-local-id"))
        repository.observeAllEntries(false).first() // fills the decrypted cache under the old id
        val remote = SyncRecord(syncId = "aa-phone-id", title = "GitHub", username = "alice", password = "phone-pw", updatedAt = 5000L)

        val item = diffAgainstVault(listOf(remote)).diffItems.single()
        assertEquals(ItemOutcome.USE_REMOTE, item.outcome)
        merge(listOf(item)).getOrThrow()

        val merged = active().single()
        assertEquals("aa-phone-id", merged.id)
        assertEquals("phone-pw", merged.secret)
        assertEquals(listOf("oldPassword123"), merged.history.map { it.previousSecret })
    }

    @Test
    fun testLegacyEntryAdoptsThePhonesIdEvenWhenOursSortsFirst() = runBlocking {
        // A merge plan carries only the reviewer's old id, so the reviewer must be the one to rename.
        repository.createEntry(gitHub.copy(id = "aa-local-id"))
        val remote = SyncRecord(syncId = "zz-phone-id", title = "GitHub", username = "alice", password = "phone-pw", updatedAt = 5000L)

        val item = diffAgainstVault(listOf(remote)).diffItems.single()
        assertEquals("zz-phone-id", item.syncKey)
        assertEquals("aa-local-id", item.localSyncIdToReplace)

        merge(listOf(item)).getOrThrow()
        assertEquals(listOf("zz-phone-id"), active().map { it.id })
    }

    @Test
    fun testLegacyMatchesEndIdenticalWhenThisPcApprovesThePhonesPlan() = runBlocking {
        // This PC is the approver. The phone holds the same pre-syncId entries under other ids,
        // which sort before ours for Bank and Mail and after for Shop.
        repository.createEntry(gitHub.copy(id = "zz-bank", title = "Bank"))
        repository.createEntry(gitHub.copy(id = "zz-mail", title = "Mail"))
        repository.createEntry(gitHub.copy(id = "aa-shop", title = "Shop"))
        val here = localRecords()
        val phoneVault = listOf(
            here.single { it.title == "Bank" }.copy(syncId = "aa-bank"),
            here.single { it.title == "Mail" }.copy(syncId = "aa-mail", password = "new", updatedAt = 9_000L),
            here.single { it.title == "Shop" }.copy(syncId = "zz-shop")
        )

        // The phone's side: plan against this PC's records and accept every default.
        val plan = MergePlans.build(
            SyncPlanner.plan(phoneVault, here, lastSyncAt = 0L).map {
                PlanInput(it.syncId, it.change, SyncOutcomes.defaultFor(it.change), it.local, it.remote, localIdToReplace = it.localIdToReplace)
            },
            now = 10_000L
        )
        SyncMergeExecutor.applyPlan(plan, repository).getOrThrow()

        val after = active()
        assertEquals(listOf("Bank", "Mail", "Shop"), after.map { it.title }.sorted(), "No entry is duplicated")
        assertEquals(setOf("zz-bank", "zz-mail", "aa-shop"), after.map { it.id }.toSet())
        assertEquals("new", after.single { it.title == "Mail" }.secret)
    }

    @Test
    fun testDeletedRemoteMovesTheEntryToTheRecycleBin() = runBlocking {
        repository.createEntry(gitHub)
        val remoteTombstone = SyncRecords.tombstone("id-github", deletedAt = 5000L)

        val diff = diffAgainstVault(listOf(remoteTombstone))
        assertEquals(1, diff.deletedRemoteCount)
        val item = diff.diffItems.single()
        assertEquals(EntrySyncCategory.DELETED_REMOTE, item.category)
        assertEquals(ItemOutcome.DELETE_BOTH, item.outcome, "Unchanged here since it was deleted there: delete by default")
        assertNull(item.remoteEntry)

        merge(diff.diffItems).getOrThrow()

        assertTrue(active().isEmpty())
        val binned = recycled().single()
        assertEquals("id-github", binned.id)
        assertEquals(5000L, binned.deletedAt, "Deleted at the time the other device deleted it")
    }

    @Test
    fun testDeletedRemoteIsKeptByDefaultWhenEditedHereAfterwards() = runBlocking {
        repository.createEntry(gitHub.copy(updatedAt = 9000L))
        val item = diffAgainstVault(listOf(SyncRecords.tombstone("id-github", deletedAt = 5000L))).diffItems.single()
        assertEquals(EntrySyncCategory.DELETED_REMOTE, item.category)
        assertEquals(ItemOutcome.USE_LOCAL, item.outcome)

        merge(listOf(item)).getOrThrow()
        assertEquals(1, active().size, "Kept here, and sent back to the other device")
        assertTrue(recycled().isEmpty())
    }

    @Test
    fun testDeletedLocalEntriesPreventResurrection() = runBlocking {
        repository.createEntry(gitHub.copy(isDeleted = true, deletedAt = 1500L))
        val remote = gitHub.copy(secret = "remoteSecret", updatedAt = 2000L)

        val diffResult = diffAgainstVault(listOf(record(remote)))

        assertEquals(0, diffResult.newRemoteCount, "Should not be categorized as NEW_REMOTE")
        assertEquals(1, diffResult.deletedLocalCount, "Should be categorized as DELETED_LOCAL")
        val item = diffResult.diffItems.first()
        assertEquals(EntrySyncCategory.DELETED_LOCAL, item.category)
        assertEquals(ItemOutcome.DELETE_BOTH, item.outcome, "DELETED_LOCAL items are not brought back by default")
        assertEquals("id-github", item.localEntry?.id, "The recycle-bin row is shown as the local side")
    }

    @Test
    fun testDeletedLocalRestoresTheRecycleBinRowWithoutDuplicate() = runBlocking {
        repository.createEntry(gitHub.copy(isDeleted = true, deletedAt = 1500L))
        val remote = gitHub.copy(secret = "remoteSecret", updatedAt = 2000L)

        val item = diffAgainstVault(listOf(record(remote))).diffItems.single().copy(outcome = ItemOutcome.USE_REMOTE)
        assertEquals(1, merge(listOf(item)).getOrThrow())

        assertTrue(recycled().isEmpty())
        val restored = active().single()
        assertEquals("id-github", restored.id)
        assertEquals("remoteSecret", restored.secret)
        assertNull(restored.deletedAt)
        assertEquals(listOf("oldPassword123"), restored.history.map { it.previousSecret })
    }

    @Test
    fun testDeletedLocalRestoresFromTombstoneWithoutDuplicate() = runBlocking {
        repository.createEntry(gitHub.copy(isDeleted = true, deletedAt = 1500L))
        repository.emptyRecycleBin()
        assertEquals(listOf(SyncTombstone("id-github", 1500L)), tombstones())

        val remote = gitHub.copy(secret = "remoteSecret", updatedAt = 2000L)
        val item = diffAgainstVault(listOf(record(remote))).diffItems.single()
        assertEquals(EntrySyncCategory.DELETED_LOCAL, item.category)
        assertNull(item.localEntry, "Only a tombstone is left here")

        merge(listOf(item.copy(outcome = ItemOutcome.USE_REMOTE))).getOrThrow()

        val restored = active().single()
        assertEquals("id-github", restored.id)
        assertEquals("remoteSecret", restored.secret)
        assertTrue(recycled().isEmpty())
        assertTrue(tombstones().isEmpty(), "Bringing the entry back removes its tombstone")
    }

    @Test
    fun testBothSidesDeletedShowsNothing() = runBlocking {
        repository.createEntry(gitHub.copy(isDeleted = true, deletedAt = 1500L))
        assertTrue(diffAgainstVault(listOf(SyncRecords.tombstone("id-github", 1600L))).diffItems.isEmpty())
    }

    @Test
    fun testInvalidRemoteIdsAreDropped() {
        val diff = SyncDiffEngine.computeDiff(
            emptyList(),
            listOf(SyncRecord(syncId = "../../etc", title = "Evil"), SyncRecord(syncId = "", title = "Empty"))
        )
        assertTrue(diff.diffItems.isEmpty())
    }

    // --- A plan from the other device (this PC approves) ---

    @Test
    fun testAPlanFromTheOtherDeviceIsAppliedAsIs() = runBlocking {
        repository.createEntry(gitHub)
        repository.createEntry(VaultEntry(id = "id-mail", title = "Mail", username = "me", secret = "m", createdAt = 1L, updatedAt = 1L))
        repository.createEntry(VaultEntry(id = "zz-legacy", title = "Shop", username = "me", secret = "s", createdAt = 1L, updatedAt = 1L))
        val before = localRecords()
        val plan = listOf(
            PlanItem("phone-1", PlanAction.UPSERT, SyncRecord(syncId = "phone-1", title = "AWS", password = "aws", updatedAt = 50L)),
            PlanItem("id-github", PlanAction.UPSERT, record(gitHub).copy(password = "rotated", updatedAt = 60L)),
            PlanItem("id-mail", PlanAction.RECYCLE, deletedAt = 777L),
            PlanItem("id-absent", PlanAction.RECYCLE, deletedAt = 778L),
            PlanItem("aa-legacy", PlanAction.RENAME_ONLY, renameFrom = "zz-legacy")
        )

        assertEquals(3, SyncMergeExecutor.applyPlan(plan, repository).getOrThrow())

        assertEquals(setOf("phone-1", "id-github", "aa-legacy"), active().map { it.id }.toSet())
        assertEquals("rotated", active().single { it.id == "id-github" }.secret)
        assertEquals(777L, recycled().single().deletedAt)
        assertEquals(
            SyncMergeExecutor.fingerprintAfter(before, plan),
            MergePlans.vaultFingerprint(localRecords()),
            "The reviewer's promise matches what applying the plan really gives"
        )
    }

    @Test
    fun testABrokenPlanIsRefusedAndChangesNothing() = runBlocking {
        repository.createEntry(gitHub)
        val broken = listOf(
            PlanItem("id-github", PlanAction.UPSERT, record(gitHub).copy(password = "evil")),
            PlanItem("id-github", PlanAction.RECYCLE)
        )
        assertTrue(SyncMergeExecutor.applyPlan(broken, repository).isFailure)
        assertTrue(SyncMergeExecutor.applyPlan(listOf(PlanItem("../x", PlanAction.RECYCLE)), repository).isFailure)
        assertEquals("oldPassword123", active().single().secret)
        assertTrue(recycled().isEmpty())
    }

    @Test
    fun testAnEmptyPlanChangesNothing() = runBlocking {
        repository.createEntry(gitHub)
        val before = localRecords()
        assertEquals(0, SyncMergeExecutor.applyPlan(emptyList(), repository).getOrThrow())
        assertEquals(before, localRecords())
    }

    // --- Field limits ---

    @Test
    fun testRaisedFieldLimitsSurviveAMerge() = runBlocking {
        val biggest = SyncRecord(
            syncId = "big-1",
            title = "t".repeat(EntryLimits.TITLE),
            username = "u".repeat(EntryLimits.USERNAME),
            password = "p".repeat(EntryLimits.PASSWORD),
            url = "w".repeat(EntryLimits.WEBSITE),
            notes = "n".repeat(EntryLimits.NOTES),
            tags = List(EntryLimits.MAX_TAGS) { i -> "$i" + "g".repeat(EntryLimits.TAG - "$i".length) },
            customFields = List(EntryLimits.MAX_CUSTOM_FIELDS) { i ->
                CustomFieldRecord("$i" + "k".repeat(EntryLimits.CUSTOM_FIELD_KEY - "$i".length), "v".repeat(EntryLimits.CUSTOM_FIELD_VALUE))
            },
            updatedAt = 10L
        )
        assertTrue(EntryLimits.fits(biggest))

        merge(diffAgainstVault(listOf(biggest)).diffItems).getOrThrow()
        repository.clearCache()
        assertEquals(biggest, localRecords().single(), "Every field at its limit is stored whole")

        // A hand edit past a limit is caught before the plan is built.
        val item = diffAgainstVault(listOf(biggest.copy(title = "changed", updatedAt = 20L))).diffItems.single()
        assertTrue(item.editsFit)
        val tooLong = item.copy(editedEntry = item.chosenEntry!!.copy(notes = "n".repeat(EntryLimits.NOTES + 1)))
        assertFalse(tooLong.editsFit)
    }
}
