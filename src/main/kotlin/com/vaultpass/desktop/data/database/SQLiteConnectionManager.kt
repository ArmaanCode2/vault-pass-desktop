package com.vaultpass.desktop.data.database

import com.vaultpass.desktop.domain.db.DatabaseBackupException
import com.vaultpass.desktop.domain.db.DatabaseClosedException
import com.vaultpass.desktop.domain.db.DatabaseConnectionManager
import com.vaultpass.desktop.domain.db.NewerVaultException
import java.sql.Connection
import java.sql.DriverManager
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Manages the lifecycle of the SQLite connection.
 * Ensuring that connections are securely established and destroyed.
 *
 * The one connection is used by one coroutine at a time: [withConnection] and [inTransaction]
 * hold [gate] until their block ends, so nobody reads another caller's uncommitted rows or
 * writes inside its transaction. Code running inside such a block (and coroutines it starts)
 * carries a lease and reuses the connection without waiting for the gate again.
 * A Mutex rather than a single-thread dispatcher: a transaction's block can suspend, and another
 * coroutine would then run on that thread inside the open transaction.
 */
class SQLiteConnectionManager(private val databasePath: String) : DatabaseConnectionManager {

    @Volatile
    private var connection: Connection? = null

    /** Guards [connection], [retired] and the commit-or-closed decision of a transaction. */
    private val stateLock = Any()

    /** Held by whoever is using the connection; see [withConnection]. */
    private val gate = Mutex()

    /** Closed connections still in use by the gate holder; it closes them when it lets go. */
    private val retired = mutableSetOf<Connection>()

    /** How many times [closeConnection] ran; guarded by [stateLock]. See [openConnection]. */
    private var closeCount = 0L

    /** Per manager, so a lease of one manager never stands in for another's. */
    private val leaseKey = object : CoroutineContext.Key<Lease> {}

    /** Marks the coroutines running inside a transaction's block; see [inTransaction]. */
    private val transactionKey = object : CoroutineContext.Key<TransactionMarker> {}

    private val _connectionState = MutableStateFlow(false)
    override val connectionState: StateFlow<Boolean> = _connectionState.asStateFlow()

    init {
        // Ensure parent directories exist
        val file = File(databasePath)
        file.parentFile?.mkdirs()
    }

    /**
     * Opens the database connection and initializes the schema if necessary.
     *
     * Throws [NewerVaultException] for a database from a newer version, and
     * [DatabaseBackupException] when the copy made before an upgrade fails. In both cases the
     * database is not changed. The connection is only kept when everything succeeded, so the
     * next call runs the checks again.
     */
    override fun openConnection() {
        // A close that comes in while this opens must win: the new connection is then dropped.
        val closesAtStart = synchronized(stateLock) {
            val current = connection
            if (current != null && !current.isClosed) return
            connection = null
            closeCount
        }

        // A read-only connection never writes or checkpoints, so a newer vault is left exactly as it is,
        // including frames still in its -wal file.
        if (File(databasePath).exists()) {
            val found = try {
                readOnlyConnection(databasePath).use { userVersion(it) }
            } catch (e: Exception) {
                // A hot rollback journal (a write cut off by a crash) can't be rolled back read-only.
                // The header still tells the version; the read-write open below then rolls it back.
                try {
                    readUserVersionFromHeader(File(databasePath))
                } catch (_: Exception) {
                    throw e
                }
            }
            if (found > DatabaseQueries.SCHEMA_VERSION) {
                throw NewerVaultException(found, DatabaseQueries.SCHEMA_VERSION)
            }
        }

        val conn = DriverManager.getConnection("jdbc:sqlite:$databasePath")
        try {
            conn.createStatement().use { stmt -> stmt.execute("PRAGMA busy_timeout = 5000;") }
            val current = userVersion(conn)
            if (current > DatabaseQueries.SCHEMA_VERSION) {
                throw NewerVaultException(current, DatabaseQueries.SCHEMA_VERSION)
            }
            if (current < DatabaseQueries.SCHEMA_VERSION && hasTables(conn)) {
                backupBeforeUpgrade(conn)
            }
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = WAL;")
                stmt.execute("PRAGMA synchronous = NORMAL;")
                stmt.execute("PRAGMA foreign_keys = ON;")
            }
            initializeSchema(conn, current)
        } catch (e: Exception) {
            try { conn.close() } catch (_: Exception) { }
            throw e
        }
        testHooks.beforeInstall?.invoke()
        val installed = synchronized(stateLock) {
            when {
                closeCount != closesAtStart -> null
                // Another openConnection() got there first: keep its connection.
                connection?.isClosed == false -> false
                else -> {
                    connection = conn
                    _connectionState.value = true
                    true
                }
            }
        }
        if (installed != true) {
            try { conn.close() } catch (_: Exception) { }
            if (installed == null) throw DatabaseClosedException()
        }
    }

    /**
     * Test-only hooks, so tests can stop or fail this class at exact points instead of sleeping.
     * All of them are null in the app.
     */
    internal class TestHooks {
        /** Runs right before a newly opened connection is installed. */
        @Volatile var beforeInstall: (() -> Unit)? = null
        /** Runs right before a decided transaction commits (outside [stateLock]). */
        @Volatile var beforeCommit: (() -> Unit)? = null
        /** Replaces a transaction's rollback, e.g. to make it fail. */
        @Volatile var rollback: ((Connection) -> Unit)? = null
        /** Runs on the closer, right before retired connections are physically closed. */
        @Volatile var beforePhysicalClose: (() -> Unit)? = null
    }

    /** Test-only; see [TestHooks]. */
    internal val testHooks = TestHooks()

    /** Coroutines waiting for [gate]; tests wait for a waiter to queue instead of sleeping. */
    private val gateWaiters = java.util.concurrent.atomic.AtomicInteger()

    /** Test-only: how many coroutines are waiting for the connection right now. */
    internal val gateWaiterCount: Int get() = gateWaiters.get()

    /** Called when the open connection had to be dropped (a failed rollback); see [retire]. */
    @Volatile
    private var connectionLost: (() -> Unit)? = null

    override fun setOnConnectionLost(listener: (() -> Unit)?) {
        connectionLost = listener
    }

    /**
     * Returns the active connection. Throws [DatabaseClosedException] if not opened.
     * Only for tests: app code uses [withConnection] or [inTransaction].
     */
    internal fun getConnection(): Connection {
        return connection ?: throw DatabaseClosedException()
    }

    private suspend fun lockGate() {
        gateWaiters.incrementAndGet()
        try {
            gate.lock()
        } finally {
            gateWaiters.decrementAndGet()
        }
    }

    /**
     * Runs [block] with the connection, after whoever is using it has finished. Inside another
     * [withConnection] or [inTransaction] block it runs at once on the same connection.
     * Throws [DatabaseClosedException] when the database is closed.
     */
    suspend fun <T> withConnection(block: suspend (Connection) -> T): T {
        currentCoroutineContext()[leaseKey]?.let { lease -> return block(lease.connection) }
        lockGate()
        try {
            val conn = connection ?: throw DatabaseClosedException()
            return withContext(Dispatchers.IO + Lease(leaseKey, conn)) { block(conn) }
        } finally {
            releaseGate()
        }
    }

    /**
     * Runs [block] in a transaction: everything it wrote is committed together, or nothing is.
     * Inside another transaction it joins that one, and if it fails the outer one rolls back too.
     * A transaction still open when [closeConnection] runs is rolled back with
     * [DatabaseClosedException], so nothing is committed after the vault locked.
     */
    suspend fun <T> inTransaction(block: suspend (Connection) -> T): T = withConnection { conn ->
        val lease = currentCoroutineContext()[leaseKey] ?: throw IllegalStateException("No connection lease")
        val owner = lease.transaction.get()
        when {
            owner == null -> runTransaction(conn, lease, block)
            // Called from inside the running transaction's block: join it.
            currentCoroutineContext()[transactionKey] === owner -> try {
                block(conn)
            } catch (e: Throwable) {
                lease.rollbackOnly = true
                throw e
            }
            // A coroutine beside the transaction (sharing the lease, not inside its block) must not
            // slip its writes into it or start a second one on the same connection.
            else -> throw IllegalStateException("Another transaction is already running on this connection")
        }
    }

    private suspend fun <T> runTransaction(conn: Connection, lease: Lease, block: suspend (Connection) -> T): T {
        val marker = TransactionMarker(transactionKey)
        if (!lease.transaction.compareAndSet(null, marker)) {
            throw IllegalStateException("Another transaction is already running on this connection")
        }
        lease.rollbackOnly = false
        val initialAutoCommit = conn.autoCommit
        // When the work is undone by closing the connection rather than by a rollback.
        var abandoned = false
        try {
            conn.autoCommit = false
            val result = withContext(marker) { block(conn) }
            // Decided under stateLock: a closeConnection() before this point rolls the work back.
            // One after it doesn't stop the commit, and never waits for it (it runs outside the
            // lock): everything was encrypted under this lease before the decision, while the key
            // was still intact. The connection is closed once the commit is done and the gate free.
            synchronized(stateLock) {
                if (conn in retired) throw DatabaseClosedException()
                if (lease.rollbackOnly) throw IllegalStateException("A nested transaction failed")
            }
            testHooks.beforeCommit?.invoke()
            conn.commit()
            return result
        } catch (e: Throwable) {
            val rolledBack = try {
                testHooks.rollback?.invoke(conn) ?: conn.rollback()
                true
            } catch (_: Exception) {
                false
            }
            val closed = synchronized(stateLock) { conn in retired }
            abandoned = closed || !rolledBack
            // A lock also makes the work itself fail (the key is wiped right after the close, so an
            // encryption or a read fails): it failed because the vault locked.
            if (closed && e !is CancellationException && e !is DatabaseClosedException) {
                throw DatabaseClosedException(e)
            }
            throw e
        } finally {
            lease.rollbackOnly = false
            lease.transaction.set(null)
            if (abandoned) {
                // Restoring autoCommit would commit what is still open; closing undoes it instead.
                retire(conn)
                try { conn.close() } catch (_: Exception) { }
            } else {
                try {
                    if (!conn.isClosed) conn.autoCommit = initialAutoCommit
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Takes [conn] out of use after a rollback that failed, or after a lock. When it was the open
     * connection the database counts as closed, as after a lock, and the listener set with
     * [setOnConnectionLost] is told, so the session can lock and a new unlock opens a new one.
     */
    private fun retire(conn: Connection) {
        val lost = synchronized(stateLock) {
            retired.add(conn)
            if (connection === conn) {
                connection = null
                closeCount++
                _connectionState.value = false
                true
            } else {
                false
            }
        }
        if (lost) {
            try {
                connectionLost?.invoke()
            } catch (e: Exception) {
                System.err.println("[WARN] SQLiteConnectionManager: the connection-lost listener failed (${e.javaClass.simpleName})")
            }
        }
    }

    /**
     * Closes the connection securely. It never waits: the connection is taken out of use at once
     * (an open transaction is then rolled back instead of committed), and physically closed on
     * the IO dispatcher once nobody uses it, never on the caller's thread: closing the last WAL
     * connection checkpoints and syncs, which must not run on the UI thread that locks.
     */
    override fun closeConnection() {
        synchronized(stateLock) {
            connection?.let { retired.add(it) }
            connection = null
            closeCount++
            _connectionState.value = false
        }
        if (gate.tryLock()) releaseGate()
    }

    /**
     * [closeConnection], then waits until whoever is using the connection let go and it is
     * physically closed (the file may be deleted or replaced next).
     */
    override suspend fun closeConnectionAndWait() {
        // Checked first: refused inside a lease, it closes nothing.
        if (currentCoroutineContext()[leaseKey] != null) {
            throw IllegalStateException("closeConnectionAndWait() can't wait for the connection it is using")
        }
        closeConnection()
        // The closer holds the gate until the retired connections are closed.
        lockGate()
        releaseGate()
    }

    /** Closes retired connections, off the thread that let go of them. */
    private val closer = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Lets go of [gate]; must be called by its holder. Retired connections are closed first, by
     * [closer], which keeps holding the gate until they are closed, so [closeConnectionAndWait]
     * (which takes the gate) returns only after the physical close.
     */
    private fun releaseGate() {
        if (synchronized(stateLock) { retired.isNotEmpty() }) {
            closer.launch { closeRetiredAndRelease() }
            return
        }
        gate.unlock()
        // A close that came in just now found the gate still held: its connection is ours to close.
        if (synchronized(stateLock) { retired.isNotEmpty() } && gate.tryLock()) {
            closer.launch { closeRetiredAndRelease() }
        }
    }

    /** Runs on [closer], holding the gate. */
    private fun closeRetiredAndRelease() {
        try {
            val toClose = synchronized(stateLock) { retired.toList().also { retired.clear() } }
            testHooks.beforePhysicalClose?.invoke()
            toClose.forEach { try { it.close() } catch (_: Exception) { } }
        } finally {
            releaseGate()
        }
    }

    /** The connection a coroutine holds through [withConnection], and its transaction state. */
    private class Lease(
        override val key: CoroutineContext.Key<Lease>,
        val connection: Connection
    ) : CoroutineContext.Element {
        /** The running transaction, if any; set atomically so two can never start on one connection. */
        val transaction = AtomicReference<TransactionMarker?>(null)
        @Volatile var rollbackOnly = false
    }

    /** Carried by the coroutines inside one transaction's block, so their calls join it. */
    private class TransactionMarker(
        override val key: CoroutineContext.Key<TransactionMarker>
    ) : CoroutineContext.Element

    /**
     * Copies the database to `<db>.before-schema-v<SCHEMA_VERSION>.bak` and checks the copy.
     * Runs with autoCommit on (VACUUM can't run in a transaction). Only the newest backup is kept.
     */
    private fun backupBeforeUpgrade(conn: Connection) {
        val databaseFile = File(databasePath).absoluteFile
        val directory = databaseFile.parentFile
        val tempFile = File(directory, "${databaseFile.name}.backup-tmp").toPath()
        val backupFile = File(directory, backupFileName(databaseFile.name, DatabaseQueries.SCHEMA_VERSION)).toPath()
        try {
            Files.deleteIfExists(tempFile)
            deleteSideFiles(tempFile.toString())
            conn.prepareStatement("VACUUM INTO ?").use { stmt ->
                stmt.setString(1, tempFile.toString())
                stmt.execute()
            }
            val expectedEntries = vaultEntryCount(conn)
            DriverManager.getConnection("jdbc:sqlite:$tempFile").use { copy ->
                // VACUUM INTO keeps the WAL flag of a WAL source; the backup must be one self-contained file.
                copy.createStatement().use { stmt -> stmt.execute("PRAGMA journal_mode = DELETE") }
                val check = copy.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA quick_check").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
                if (check != listOf("ok")) {
                    throw IllegalStateException("the copy failed its integrity check")
                }
                if (vaultEntryCount(copy) != expectedEntries) {
                    throw IllegalStateException("the copy doesn't have the same number of entries")
                }
            }
            deleteSideFiles(tempFile.toString())
            Files.move(tempFile, backupFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            try { Files.deleteIfExists(tempFile) } catch (_: Exception) { }
            deleteSideFiles(tempFile.toString())
            throw DatabaseBackupException(e.message ?: e.javaClass.simpleName, e)
        }
        deleteOlderBackups(directory, databaseFile.name)
    }

    private fun deleteOlderBackups(directory: File, databaseName: String) {
        val pattern = Regex("^${Regex.escape(databaseName)}\\.before-schema-v(\\d+)\\.bak$")
        directory.listFiles()?.forEach { file ->
            val version = pattern.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (version < DatabaseQueries.SCHEMA_VERSION && file.isFile) {
                try {
                    Files.deleteIfExists(file.toPath())
                } catch (e: Exception) {
                    println("[WARN] SQLiteConnectionManager: couldn't delete old backup ${file.name}: ${e.message}")
                }
            }
        }
    }

    /**
     * Brings the schema up to [DatabaseQueries.SCHEMA_VERSION], one migration at a time. Each
     * migration and its new user_version are committed together, so an interrupted upgrade
     * resumes where it stopped.
     */
    private fun initializeSchema(conn: Connection, current: Int) {
        for ((version, statements) in DatabaseQueries.MIGRATIONS) {
            if (version <= current) continue
            val initialAutoCommit = conn.autoCommit
            try {
                conn.autoCommit = false
                conn.createStatement().use { statement ->
                    statements.forEach { statement.execute(it) }
                    statement.execute("PRAGMA user_version = $version")
                }
                conn.commit()
            } catch (e: Exception) {
                try {
                    conn.rollback()
                } catch (rollbackFailure: Exception) {
                    // Restoring autoCommit would commit the half migration; closing undoes it.
                    e.addSuppressed(rollbackFailure)
                    try { conn.close() } catch (_: Exception) { }
                }
                throw e
            } finally {
                if (!conn.isClosed) conn.autoCommit = initialAutoCommit
            }
        }
    }

    private fun deleteSideFiles(path: String) {
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            try { Files.deleteIfExists(File(path + suffix).toPath()) } catch (_: Exception) { }
        }
    }

    private fun readOnlyConnection(path: String): Connection {
        val readOnly = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }.toProperties()
        return DriverManager.getConnection("jdbc:sqlite:$path", readOnly)
    }

    private fun userVersion(conn: Connection): Int =
        conn.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun hasTables(conn: Connection): Boolean =
        conn.createStatement().use { statement ->
            statement.executeQuery("SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table')").use { rs ->
                rs.next() && rs.getInt(1) == 1
            }
        }

    /** Rows in vault_entry, or null when the table doesn't exist. */
    private fun vaultEntryCount(conn: Connection): Long? =
        conn.createStatement().use { statement ->
            val hasTable = statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'vault_entry'"
            ).use { it.next() }
            if (!hasTable) null
            else statement.executeQuery("SELECT COUNT(*) FROM vault_entry").use { rs -> rs.next(); rs.getLong(1) }
        }

    companion object {
        private val HEADER_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private const val USER_VERSION_OFFSET = 60

        internal fun backupFileName(databaseName: String, schemaVersion: Int): String =
            "$databaseName.before-schema-v$schemaVersion.bak"

        /**
         * Reads user_version straight from the database header (bytes 60-63, big-endian) without
         * SQLite, after checking the 16-byte header string. Throws when [file] isn't an SQLite database.
         */
        internal fun readUserVersionFromHeader(file: File): Int {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val header = ByteBuffer.allocate(USER_VERSION_OFFSET + 4)
                while (header.hasRemaining()) {
                    if (channel.read(header) < 0) throw IOException("the file is too short to be a database")
                }
                header.flip()
                val magic = ByteArray(HEADER_MAGIC.size)
                header.get(magic)
                if (!magic.contentEquals(HEADER_MAGIC)) throw IOException("the file isn't an SQLite database")
                return header.order(ByteOrder.BIG_ENDIAN).getInt(USER_VERSION_OFFSET)
            }
        }
    }
}
