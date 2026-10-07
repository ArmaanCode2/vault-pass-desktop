package com.vaultpass.desktop.data.database

import com.vaultpass.desktop.data.models.VaultEntity
import com.vaultpass.desktop.data.repository.VaultRepositoryImpl
import com.vaultpass.desktop.data.sync.FakeCryptoManager
import com.vaultpass.desktop.data.sync.PairedDeviceRepository
import com.vaultpass.desktop.domain.RepositoryResult
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.errors.AuthError
import com.vaultpass.desktop.domain.models.VaultEntry
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The one SQLite connection is used by one caller at a time: nobody writes inside someone
 * else's transaction or reads its uncommitted rows, and a lock never lets a write commit.
 */
class ConnectionGateTest {

    private lateinit var dbFile: File
    private lateinit var manager: SQLiteConnectionManager
    private lateinit var dataSource: SQLiteVaultDataSource
    private lateinit var pairedDevices: PairedDeviceRepository

    @BeforeEach
    fun setUp() {
        dbFile = File.createTempFile("vaultpass_gate_test_", ".db")
        manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        dataSource = SQLiteVaultDataSource(manager)
        pairedDevices = PairedDeviceRepository(manager)
    }

    @AfterEach
    fun tearDown() {
        manager.closeAndWait()
        for (suffix in listOf("", "-wal", "-shm")) File(dbFile.absolutePath + suffix).delete()
    }

    private fun entity(id: String) = VaultEntity(
        id = id,
        type = "PASSWORD",
        encryptedPayload = byteArrayOf(1, 2, 3),
        createdAt = 10L,
        updatedAt = 20L,
        isFavorite = false,
        isDeleted = false,
        deletedAt = null,
        syncVersion = 1
    )

    private suspend fun ids(): Set<String> = dataSource.observeAll().first().map { it.id }.toSet()

    /**
     * Waits until [count] coroutines are queued for the connection, so a test checks a waiter that
     * really is waiting (a handshake), rather than guessing with a sleep.
     */
    private suspend fun awaitWaiters(count: Int) = withTimeout(5_000) {
        while (manager.gateWaiterCount < count) delay(2)
    }

    @Test
    fun beaconWriteDuringATransaction_waitsForIt_andPersistsWhenItRollsBack() = runBlocking {
        pairedDevices.savePairedDevice(
            PairedDevice(deviceId = "phone", deviceName = "Phone", sharedSecret = "v2:abcd", ipAddress = "10.0.0.1", port = 1, pairedAt = 1L)
        )
        val inTransaction = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val merge = async(Dispatchers.IO) {
            runCatching {
                dataSource.transaction {
                    dataSource.insert(entity("merged"))
                    inTransaction.complete(Unit)
                    finish.await()
                    throw IllegalStateException("merge failed on purpose")
                }
            }
        }
        inTransaction.await()

        // What LanDiscoveryManager does for an authentic beacon: a coroutine of its own.
        val beacon = launch(Dispatchers.IO) { pairedDevices.updateNetworkLocation("phone", "10.0.0.2", 2) }
        awaitWaiters(1)
        assertTrue(beacon.isActive, "The beacon write must wait for the open transaction")

        finish.complete(Unit)
        assertTrue(merge.await().isFailure)
        withTimeout(5_000) { beacon.join() }

        val phone = pairedDevices.getPairedDevice("phone")!!
        assertEquals("10.0.0.2", phone.ipAddress)
        assertEquals(2, phone.port)
        assertEquals(emptySet<String>(), ids(), "The vault row of the failed merge rolls back")
    }

    @Test
    fun readerDuringATransaction_neverSeesItsUncommittedRows() = runBlocking {
        dataSource.insert(entity("kept"))
        val inTransaction = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val import = async(Dispatchers.IO) {
            runCatching {
                dataSource.transaction {
                    dataSource.insert(entity("uncommitted"))
                    // Inside the transaction its own rows are visible.
                    assertNotNull(dataSource.getById("uncommitted"))
                    inTransaction.complete(Unit)
                    finish.await()
                    throw IllegalStateException("import failed on purpose")
                }
            }
        }
        inTransaction.await()

        val byId = async(Dispatchers.IO) { dataSource.getById("uncommitted") }
        val all = async(Dispatchers.IO) { ids() }
        awaitWaiters(2)
        assertTrue(byId.isActive && all.isActive, "Readers wait for the open transaction")

        finish.complete(Unit)
        assertTrue(import.await().isFailure)
        assertNull(withTimeout(5_000) { byId.await() })
        assertEquals(setOf("kept"), withTimeout(5_000) { all.await() })
    }

    @Test
    fun closeDuringATransaction_rollsItBack_andClosesTheConnectionAfterwards() = runBlocking {
        dataSource.insert(entity("kept"))
        val connection = manager.getConnection()
        val inTransaction = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val merge = async(Dispatchers.IO) {
            runCatching {
                dataSource.transaction {
                    dataSource.insert(entity("written-before-lock"))
                    inTransaction.complete(Unit)
                    finish.await()
                    // Still usable until the transaction ends; it is rolled back anyway.
                    dataSource.insert(entity("written-after-lock"))
                    "done"
                }
            }
        }
        inTransaction.await()

        manager.closeConnection()
        assertFalse(manager.connectionState.value)
        assertFalse(connection.isClosed, "The transaction still holds the connection")

        finish.complete(Unit)
        val result = merge.await()
        assertTrue(result.exceptionOrNull() is DatabaseClosedException, "Got ${result.exceptionOrNull()}")
        assertTrue(connection.isClosed, "Closed once the transaction let go")

        manager.openConnection()
        assertEquals(setOf("kept"), ids())
    }

    @Test
    fun closeWhileNobodyUsesTheConnection_returnsAtOnce_andClosesItOffTheCallersThread() = runBlocking {
        val connection = manager.getConnection()
        val closing = java.util.concurrent.CountDownLatch(1)
        val letClose = java.util.concurrent.CountDownLatch(1)
        var closingThread: Thread? = null
        manager.testHooks.beforePhysicalClose = {
            closingThread = Thread.currentThread()
            closing.countDown()
            letClose.await()
        }

        // The lock's caller (the UI thread): it must not wait for the physical close.
        val caller = Thread { manager.closeConnection() }
        caller.start()
        caller.join(5_000)
        assertFalse(caller.isAlive, "closeConnection() returned while the physical close was held up")
        assertFalse(manager.connectionState.value)
        assertThrows(DatabaseClosedException::class.java) { manager.getConnection() }

        assertTrue(closing.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertNotSame(caller, closingThread, "Closed on the closer, not on the caller's thread")
        letClose.countDown()
        manager.testHooks.beforePhysicalClose = null

        manager.closeConnectionAndWait() // returns once it really is closed
        assertTrue(connection.isClosed)
    }

    @Test
    fun failedNestedTransaction_rollsBackTheOuterOne() = runBlocking {
        dataSource.insert(entity("kept"))

        val error = runCatching {
            dataSource.transaction {
                dataSource.insert(entity("outer"))
                // Swallowed here, but the outer transaction must not commit half of the work.
                runCatching { dataSource.changeId("missing", "other") }
                "done"
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message == "A nested transaction failed", "Got $error")
        assertEquals(setOf("kept"), ids())

        // The same through the repository, whose methods turn failures into results.
        val repository = VaultRepositoryImpl(dataSource, FakeCryptoManager())
        val result = repository.transaction {
            createEntry(VaultEntry(id = "via-repository", title = "T", username = "u", secret = "s", createdAt = 1L, updatedAt = 1L))
            changeEntryId("missing", "other")
        }
        assertTrue(result is RepositoryResult.Error)
        assertEquals(setOf("kept"), ids())

        // The connection is still fine for the next transaction.
        dataSource.insert(entity("after"))
        assertEquals(setOf("kept", "after"), ids())
    }

    @Test
    fun waiterAfterClose_getsDatabaseClosedException_andObserversSeeNothing() = runBlocking {
        dataSource.insert(entity("kept"))
        pairedDevices.savePairedDevice(PairedDevice(deviceId = "phone", deviceName = "Phone", sharedSecret = "v2:abcd", pairedAt = 1L))
        val inTransaction = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.IO) {
            runCatching {
                manager.inTransaction {
                    inTransaction.complete(Unit)
                    finish.await()
                }
            }
        }
        inTransaction.await()
        val waiter = async(Dispatchers.IO) { runCatching { dataSource.getById("kept") } }
        // Queued while the database is open, then woken after the close.
        awaitWaiters(1)
        assertTrue(waiter.isActive)

        manager.closeConnection()
        finish.complete(Unit)

        assertTrue(holder.await().exceptionOrNull() is DatabaseClosedException)
        assertTrue(withTimeout(5_000) { waiter.await() }.exceptionOrNull() is DatabaseClosedException)
        assertThrows(DatabaseClosedException::class.java) { runBlocking { pairedDevices.getPairedDevices() } }
        assertEquals(emptyList<VaultEntity>(), dataSource.observeAll().first())
        assertEquals(emptyList<PairedDevice>(), pairedDevices.observePairedDevices().first())
    }

    /** What SessionManagerImpl.lock() does, in its order: close the database, then wipe the key. */
    private fun lockVault(crypto: FakeCryptoManager) {
        manager.closeConnection()
        crypto.locked = true
    }

    @Test
    fun readsInATransaction_neverPassOffAClosedVaultAsEmpty() = runBlocking {
        val crypto = FakeCryptoManager()
        val repository = VaultRepositoryImpl(dataSource, crypto)
        repository.createEntry(VaultEntry(id = "kept", title = "T", username = "u", secret = "s", createdAt = 1L, updatedAt = 1L))

        // Inside the lease the reads use the leased connection: the whole vault, one snapshot.
        val snapshot = repository.transaction { getAllEntries(false) }
        val read = (snapshot as RepositoryResult.Success).data
        assertEquals(listOf("kept"), (read as RepositoryResult.Success).data.map { it.id })

        // Locked while the transaction reads: whatever it read is refused as "locked".
        val locked = repository.transaction {
            lockVault(crypto)
            getAllEntries(false)
        }
        assertTrue(locked is RepositoryResult.Error && locked.error is AuthError.VaultLocked, "Got $locked")

        // Closed before the transaction starts.
        val alreadyLocked = repository.transaction { getAllEntries(false) }
        assertTrue(alreadyLocked is RepositoryResult.Error && alreadyLocked.error is AuthError.VaultLocked, "Got $alreadyLocked")
    }

    @Test
    fun workThatFailsBecauseOfALock_isReportedAsClosed_withItsOwnFailureAttached() = runBlocking {
        val crypto = FakeCryptoManager()
        val repository = VaultRepositoryImpl(dataSource, crypto)
        repository.createEntry(VaultEntry(id = "kept", title = "T", username = "u", secret = "s", createdAt = 1L, updatedAt = 1L))

        // A merge-like transaction: the lock wipes the key, so its next write fails on encryption.
        val error = runCatching {
            dataSource.transaction {
                dataSource.insert(entity("written-before-lock"))
                lockVault(crypto)
                val result = repository.createEntry(VaultEntry(id = "after", title = "T", username = "u", secret = "s", createdAt = 1L, updatedAt = 1L))
                check(result is RepositoryResult.Success) { "Database insert failed" }
            }
        }.exceptionOrNull()
        assertTrue(error is DatabaseClosedException, "Got $error")
        // (In tests, coroutine stack trace recovery may put a copy of the exception in between.)
        val causes = generateSequence(error!!.cause) { it.cause }.toList()
        assertTrue(causes.any { it.message == "Database insert failed" }, error.stackTraceToString())

        // Through the repository the same is "the vault is locked", not a generic failure.
        manager.openConnection()
        crypto.locked = false
        val result = repository.transaction {
            lockVault(crypto)
            createEntry(VaultEntry(id = "after", title = "T", username = "u", secret = "s", createdAt = 1L, updatedAt = 1L)).also {
                check(it is RepositoryResult.Success) { "Database insert failed" }
            }
        }
        assertTrue(result is RepositoryResult.Error && result.error is AuthError.VaultLocked, "Got $result")

        manager.openConnection()
        assertEquals(setOf("kept"), ids())
    }

    @Test
    fun unlockWhileTheOldConnectionIsInATransaction_theOldOneRollsBack_andWaitersUseTheNewOne() = runBlocking {
        dataSource.insert(entity("kept"))
        val old = manager.getConnection()
        val inTransaction = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val merge = async(Dispatchers.IO) {
            runCatching {
                dataSource.transaction {
                    dataSource.insert(entity("from-before-the-lock"))
                    inTransaction.complete(Unit)
                    finish.await()
                }
            }
        }
        inTransaction.await()

        manager.closeConnection() // lock
        manager.openConnection() // unlock: a new connection, while the old one is still in use
        val fresh = manager.getConnection()
        assertNotSame(old, fresh)
        assertFalse(old.isClosed)
        assertTrue(manager.connectionState.value)

        val waiter = async(Dispatchers.IO) { ids() }
        awaitWaiters(1)
        assertTrue(waiter.isActive, "Waits for the old transaction")

        finish.complete(Unit)
        assertTrue(merge.await().exceptionOrNull() is DatabaseClosedException)
        assertEquals(setOf("kept"), withTimeout(5_000) { waiter.await() })
        assertTrue(old.isClosed)
        assertFalse(fresh.isClosed)

        dataSource.insert(entity("after-unlock"))
        assertEquals(setOf("kept", "after-unlock"), ids())
    }

    @Test
    fun cancellingTheCoroutineThatHoldsTheConnection_rollsBack_andLetsGo() = runBlocking {
        dataSource.insert(entity("kept"))
        val inTransaction = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            dataSource.transaction {
                dataSource.insert(entity("cancelled"))
                inTransaction.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        inTransaction.await()
        holder.cancel()
        holder.join()

        assertEquals(setOf("kept"), withTimeout(5_000) { ids() })
        dataSource.insert(entity("next"))
        assertEquals(setOf("kept", "next"), ids())
    }

    @Test
    fun aCloseWhileTheDatabaseOpens_winsOverTheNewConnection() {
        manager.closeConnection()
        manager.testHooks.beforeInstall = { manager.closeConnection() }
        assertThrows(DatabaseClosedException::class.java) { manager.openConnection() }
        assertFalse(manager.connectionState.value)
        assertThrows(DatabaseClosedException::class.java) { manager.getConnection() }

        manager.testHooks.beforeInstall = null
        manager.openConnection()
        assertTrue(manager.connectionState.value)
        assertFalse(manager.getConnection().isClosed)
    }

    @Test
    fun aSecondTransactionBesideARunningOne_onTheSameConnection_isRefused() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val second = manager.withConnection {
            kotlinx.coroutines.coroutineScope {
                val first = launch {
                    manager.inTransaction {
                        started.complete(Unit)
                        finish.await()
                    }
                }
                started.await()
                val attempt = runCatching { manager.inTransaction { } }
                finish.complete(Unit)
                first.join()
                attempt
            }
        }
        val error = second.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message!!.contains("already running"), "Got $error")
    }

    @Test
    fun aWriteQueuedBehindAHolder_encryptsOnlyOnceItHasTheConnection() = runBlocking {
        val crypto = FakeCryptoManager()
        val repository = VaultRepositoryImpl(dataSource, crypto)
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            manager.withConnection {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()

        lockVault(crypto)
        // Started while locked: encrypting now would use a wiped key. It waits for the connection.
        val write = async(Dispatchers.IO) {
            repository.createEntry(VaultEntry(id = "queued", title = "T", username = "u", secret = "s", createdAt = 1L, updatedAt = 1L))
        }
        awaitWaiters(1)
        assertTrue(write.isActive, "The write queued without encrypting with the wiped key (it would have failed)")
        manager.openConnection()
        crypto.locked = false // unlocked again before the write gets the connection
        release.complete(Unit)
        holder.join()

        assertTrue(withTimeout(5_000) { write.await() } is RepositoryResult.Success)
        val stored = repository.getEntryById("queued")
        assertEquals("s", ((stored as RepositoryResult.Success).data)!!.secret)
    }

    @Test
    fun observePairedDevices_readsAgainWhenTheDatabaseOpens() = runBlocking {
        pairedDevices.savePairedDevice(PairedDevice(deviceId = "phone", deviceName = "Phone", sharedSecret = "v2:abcd", pairedAt = 1L))
        val seen = kotlinx.coroutines.flow.MutableStateFlow<List<String>?>(null)
        val collector = launch(Dispatchers.IO) {
            pairedDevices.observePairedDevices().collect { list -> seen.value = list.map { it.deviceId } }
        }
        withTimeout(5_000) { seen.first { it == listOf("phone") } }

        manager.closeConnection()
        withTimeout(5_000) { seen.first { it == emptyList<String>() } }

        manager.openConnection()
        withTimeout(5_000) { seen.first { it == listOf("phone") } }
        collector.cancel()
    }

    @Test
    fun closeConnectionAndWait_returnsOnlyOnceTheConnectionIsClosed() = runBlocking {
        val connection = manager.getConnection()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            manager.withConnection {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()

        val closing = launch(Dispatchers.IO) { manager.closeConnectionAndWait() }
        awaitWaiters(1)
        assertTrue(closing.isActive, "Waits for the connection's user")
        assertFalse(connection.isClosed)

        release.complete(Unit)
        withTimeout(5_000) { closing.join() }
        holder.join()
        assertTrue(connection.isClosed)
    }

    @Test
    fun observeAll_emitsNothingWhenTheConnectionClosesBeforeItsRead() = runBlocking {
        dataSource.insert(entity("kept"))
        val inTransaction = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.IO) {
            runCatching {
                manager.withConnection {
                    inTransaction.complete(Unit)
                    finish.await()
                }
            }
        }
        inTransaction.await()
        // Starts while the database is still open, then waits for the connection.
        val observed = async(Dispatchers.IO) { dataSource.observeAll().first() }
        awaitWaiters(1)

        manager.closeConnection()
        finish.complete(Unit)
        assertTrue(holder.await().isSuccess)
        assertEquals(emptyList<VaultEntity>(), withTimeout(5_000) { observed.await() })
    }

    @Test
    fun closeConnectionAndWait_insideALease_isRefused_andClosesNothing() = runBlocking {
        val error = runCatching { manager.withConnection { manager.closeConnectionAndWait() } }.exceptionOrNull()
        assertTrue(error is IllegalStateException && error.message!!.contains("can't wait"), "Got $error")
        assertTrue(manager.connectionState.value, "Refused before closing anything")
        assertFalse(manager.getConnection().isClosed)
    }

    @Test
    fun aCloseDuringACommit_returnsAtOnce_andTheDecidedCommitStands() = runBlocking {
        val connection = manager.getConnection()
        val committing = java.util.concurrent.CountDownLatch(1)
        val letCommit = java.util.concurrent.CountDownLatch(1)
        manager.testHooks.beforeCommit = {
            committing.countDown()
            letCommit.await()
        }
        val write = async(Dispatchers.IO) { runCatching { dataSource.insert(entity("decided-before-the-lock")) } }
        assertTrue(committing.await(5, java.util.concurrent.TimeUnit.SECONDS))
        manager.testHooks.beforeCommit = null

        // The lock runs on the UI thread: it must not wait for the commit.
        val closer = Thread { manager.closeConnection() }
        closer.start()
        closer.join(5_000)
        assertFalse(closer.isAlive, "closeConnection() returned while the commit was still running")
        assertFalse(connection.isClosed, "Not closed under the commit")

        letCommit.countDown()
        assertTrue(write.await().isSuccess, "Decided before the lock: committed")
        manager.closeConnectionAndWait() // the physical close follows the commit, on the closer
        assertTrue(connection.isClosed, "Closed once the commit finished")

        manager.openConnection()
        assertEquals(setOf("decided-before-the-lock"), ids())
    }

    @Test
    fun aRollbackThatFails_dropsTheConnection_insteadOfCommittingTheWork() = runBlocking {
        dataSource.insert(entity("kept"))
        val connection = manager.getConnection()
        var lost = 0
        manager.setOnConnectionLost { lost++ }
        manager.testHooks.rollback = { throw java.sql.SQLException("disk I/O error") }

        val error = runCatching {
            dataSource.transaction {
                dataSource.insert(entity("half-done"))
                throw IllegalStateException("the work failed")
            }
        }.exceptionOrNull()
        manager.testHooks.rollback = null

        assertEquals("the work failed", error?.message)
        assertTrue(connection.isClosed, "Closed, which undoes the open transaction")
        assertFalse(manager.connectionState.value)
        assertEquals(1, lost, "The session is told, so it can lock")

        manager.openConnection()
        assertEquals(setOf("kept"), ids(), "Nothing of the failed work was committed")
    }
}
