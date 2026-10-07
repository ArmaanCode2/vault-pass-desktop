package com.vaultpass.desktop.data.sync

import com.vaultpass.desktop.data.database.SQLiteConnectionManager
import com.vaultpass.desktop.data.database.closeAndWait
import com.vaultpass.desktop.domain.sync.models.PairedDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where an authentic beacon says a paired device is gets stored: never a crash when the vault
 * is locked, no write when nothing moved, and at most one write per device at a time.
 */
class BeaconLocationTest {

    private lateinit var dbFile: File
    private lateinit var manager: SQLiteConnectionManager
    private lateinit var repository: PairedDeviceRepository
    private lateinit var discovery: LanDiscoveryManager

    /** What reached the scope's exception handler: in the app, the fatal error dialog. */
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e -> uncaught += e })

    @BeforeEach
    fun setUp() {
        dbFile = File.createTempFile("vaultpass_beacon_test_", ".db")
        manager = SQLiteConnectionManager(dbFile.absolutePath)
        manager.openConnection()
        repository = PairedDeviceRepository(manager)
        runBlocking {
            repository.savePairedDevice(
                PairedDevice(deviceId = "phone", deviceName = "Phone", sharedSecret = "v2:abcd", ipAddress = "10.0.0.1", port = 1, pairedAt = 1L)
            )
        }
        discovery = LanDiscoveryManager(deviceId = "me", deviceName = "Me", pairedDeviceRepository = repository, scope = scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        manager.closeAndWait()
        for (suffix in listOf("", "-wal", "-shm")) File(dbFile.absolutePath + suffix).delete()
    }

    private suspend fun location(): Pair<String?, Int> = repository.getPairedDevice("phone")!!.let { it.ipAddress to it.port }

    @Test
    fun aBeaconWhileTheVaultIsLocked_isIgnored_andALaterOneStillStoresIt() = runBlocking {
        manager.closeConnection()
        val write = discovery.onAuthenticBeacon("phone", "10.0.0.2", 2)!!
        withTimeout(5_000) { write.join() }
        assertFalse(write.isCancelled, "The write ended normally")
        assertEquals(emptyList<Throwable>(), uncaught.toList(), "Nothing may reach the fatal error handler")

        manager.openConnection()
        assertEquals("10.0.0.1" to 1, location())

        withTimeout(5_000) { discovery.onAuthenticBeacon("phone", "10.0.0.2", 2)!!.join() }
        assertEquals("10.0.0.2" to 2, location())
    }

    @Test
    fun aBeaconFromWhereTheDeviceAlreadyIs_writesNothing() = runBlocking {
        withTimeout(5_000) { discovery.onAuthenticBeacon("phone", "10.0.0.2", 2)!!.join() }
        assertEquals("10.0.0.2" to 2, location())

        assertNull(discovery.onAuthenticBeacon("phone", "10.0.0.2", 2), "Same place: no write")

        withTimeout(5_000) { discovery.onAuthenticBeacon("phone", "10.0.0.3", 2)!!.join() }
        assertEquals("10.0.0.3" to 2, location())
    }

    @Test
    fun aLocationSeenJustAsAWriteEnds_isStoredToo() = runBlocking {
        var lateBeacon: Job? = Job() // replaced by what the late beacon returned
        var injected = false
        // Right after the write's last check, before it lets go of the device: a newer beacon.
        discovery.beforeLocationWriteEndsForTesting = {
            if (!injected) {
                injected = true
                lateBeacon = discovery.onAuthenticBeacon("phone", "10.0.0.7", 7)
            }
        }
        val write = discovery.onAuthenticBeacon("phone", "10.0.0.2", 2)!!
        withTimeout(5_000) { write.join() }

        assertTrue(injected)
        assertNull(lateBeacon, "The late beacon found the write still running and left it to it")
        assertEquals("10.0.0.7" to 7, location(), "...and that write stored it before finishing")
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }

    @Test
    fun beaconsWhileTheDatabaseIsBusy_queueOneWrite_thatStoresTheNewestLocation() = runBlocking {
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.IO) {
            manager.withConnection {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()

        val first = discovery.onAuthenticBeacon("phone", "10.0.0.2", 2)
        assertNotNull(first)
        for (i in 3..9) {
            assertNull(discovery.onAuthenticBeacon("phone", "10.0.0.$i", 2), "One pending write per device")
        }

        release.complete(Unit)
        holder.join()
        withTimeout(5_000) { first!!.join() }
        assertEquals("10.0.0.9" to 2, location())
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }
}
