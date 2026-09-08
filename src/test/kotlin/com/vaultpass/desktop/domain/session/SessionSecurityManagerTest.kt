package com.vaultpass.desktop.domain.session

import com.vaultpass.desktop.data.session.WindowsSessionSecurityManager
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.models.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSecurityManagerTest {

    private class FakeSessionManager(
        initialState: SessionState = SessionState.Unlocked
    ) : SessionManager {
        val _sessionState = MutableStateFlow<SessionState>(initialState)
        override val sessionState: StateFlow<SessionState> = _sessionState

        var lockCallCount = 0

        override fun lock() {
            lockCallCount++
            _sessionState.value = SessionState.Locked
        }

        override suspend fun initialize() {}
        override suspend fun unlock(password: String): Boolean = true
        override fun notifyBackground() {}
        override fun close() {}
        override suspend fun vaultCreated(password: String): Boolean = true
        override fun beginSetup() {}
    }

    private class FakeAppSettingsRepository(
        var settings: AppSettings = AppSettings()
    ) : AppSettingsRepository {
        override suspend fun getSettings(): AppSettings = settings
        override suspend fun updateSettings(settings: AppSettings) {
            this.settings = settings
        }
    }

    @Test
    fun testAutoLockTriggersWhenIdleExceedsTimeout() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var currentTime = 1_000_000L
        val sessionManager = FakeSessionManager(SessionState.Unlocked)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(autoLockTimeoutMinutes = 1) // 1 minute = 60,000 ms
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope,
            timeProvider = { currentTime },
            osIdleProvider = null,
            checkIntervalMillis = 1000L,
            isWindows = false // fallback to in-app idle
        )

        manager.startMonitoring()
        assertTrue(manager.isMonitoring)

        // Advance 30 seconds - should not lock
        currentTime += 30_000L
        testScope.advanceTimeBy(30_000L)
        testScope.runCurrent()
        assertEquals(0, sessionManager.lockCallCount)

        // Advance another 31 seconds (total 61s since last activity) - should lock!
        currentTime += 31_000L
        testScope.advanceTimeBy(31_000L)
        testScope.runCurrent()
        assertEquals(1, sessionManager.lockCallCount)
        assertEquals(SessionState.Locked, sessionManager.sessionState.value)

        manager.stopMonitoring()
        assertFalse(manager.isMonitoring)
    }

    @Test
    fun testUserActivityResetsInactivityTimer() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var currentTime = 1_000_000L
        val sessionManager = FakeSessionManager(SessionState.Unlocked)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(autoLockTimeoutMinutes = 1) // 60,000 ms
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope,
            timeProvider = { currentTime },
            osIdleProvider = null,
            checkIntervalMillis = 1000L,
            isWindows = false
        )

        manager.startMonitoring()

        // Advance 40 seconds
        currentTime += 40_000L
        testScope.advanceTimeBy(40_000L)
        testScope.runCurrent()
        assertEquals(0, sessionManager.lockCallCount)

        // User interacts with the app (clicks / types)
        manager.notifyUserActivity()

        // Advance another 40 seconds (total 80s elapsed, but only 40s since activity)
        currentTime += 40_000L
        testScope.advanceTimeBy(40_000L)
        testScope.runCurrent()
        assertEquals(0, sessionManager.lockCallCount)

        // Advance another 25 seconds (65s since activity) - should lock!
        currentTime += 25_000L
        testScope.advanceTimeBy(25_000L)
        testScope.runCurrent()
        assertEquals(1, sessionManager.lockCallCount)

        manager.stopMonitoring()
    }

    @Test
    fun testAutoLockDisabledWhenTimeoutIsZero() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var currentTime = 1_000_000L
        val sessionManager = FakeSessionManager(SessionState.Unlocked)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(autoLockTimeoutMinutes = 0) // 0 = Never
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope,
            timeProvider = { currentTime },
            checkIntervalMillis = 1000L,
            isWindows = false
        )

        manager.startMonitoring()

        // Advance 120 minutes
        val twoHoursMillis = 120 * 60 * 1000L
        currentTime += twoHoursMillis
        testScope.advanceTimeBy(twoHoursMillis)
        testScope.runCurrent()

        assertEquals(0, sessionManager.lockCallCount)
        assertEquals(SessionState.Unlocked, sessionManager.sessionState.value)

        manager.stopMonitoring()
    }

    @Test
    fun testWindowMinimizedLocksWhenEnabled() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val sessionManager = FakeSessionManager(SessionState.Unlocked)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(lockWhenMinimized = true)
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope
        )

        manager.onWindowMinimized()
        testScope.runCurrent()

        assertEquals(1, sessionManager.lockCallCount)
        assertEquals(SessionState.Locked, sessionManager.sessionState.value)
    }

    @Test
    fun testWindowMinimizedDoesNotLockWhenDisabled() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val sessionManager = FakeSessionManager(SessionState.Unlocked)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(lockWhenMinimized = false)
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope
        )

        manager.onWindowMinimized()
        testScope.runCurrent()

        assertEquals(0, sessionManager.lockCallCount)
        assertEquals(SessionState.Unlocked, sessionManager.sessionState.value)
    }

    @Test
    fun testWorkstationLockTriggersLock() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val sessionManager = FakeSessionManager(SessionState.Unlocked)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(lockOnWorkstationLock = true)
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope
        )

        manager.onWorkstationLocked()
        testScope.runCurrent()

        assertEquals(1, sessionManager.lockCallCount)
        assertEquals(SessionState.Locked, sessionManager.sessionState.value)
    }

    @Test
    fun testWorkstationLockDoesNotLockWhenDisabled() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val sessionManager = FakeSessionManager(SessionState.Unlocked)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(lockOnWorkstationLock = false)
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope
        )

        manager.onWorkstationLocked()
        testScope.runCurrent()

        assertEquals(0, sessionManager.lockCallCount)
        assertEquals(SessionState.Unlocked, sessionManager.sessionState.value)
    }

    @Test
    fun testDualIdleDetectionKeepsVaultUnlockedIfOsActive() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        var currentTime = 1_000_000L
        var mockOsIdle = 2_000L // User active in another app: OS idle only 2 seconds

        val sessionManager = FakeSessionManager(SessionState.Unlocked)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(autoLockTimeoutMinutes = 1) // 60,000 ms
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope,
            timeProvider = { currentTime },
            osIdleProvider = { mockOsIdle },
            checkIntervalMillis = 1000L,
            isWindows = true
        )

        manager.startMonitoring()

        // 75 seconds pass with no in-app activity, BUT user is actively typing in Word (OS idle = 2s)
        currentTime += 75_000L
        testScope.advanceTimeBy(75_000L)
        testScope.runCurrent()

        // effectiveIdle = minOf(75s, 2s) = 2s < 60s -> should NOT lock
        assertEquals(0, sessionManager.lockCallCount)

        // User now walks away from computer. OS idle also advances past 60s
        mockOsIdle = 65_000L
        currentTime += 2_000L
        testScope.advanceTimeBy(2_000L)
        testScope.runCurrent()

        // effectiveIdle = minOf(77s, 65s) = 65s >= 60s -> SHOULD lock
        assertEquals(1, sessionManager.lockCallCount)

        manager.stopMonitoring()
    }

    @Test
    fun testDoesNotLockWhenVaultNotUnlocked() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val sessionManager = FakeSessionManager(SessionState.SetupMasterPassword)
        val appSettingsRepo = FakeAppSettingsRepository(
            AppSettings(lockWhenMinimized = true, lockOnWorkstationLock = true)
        )

        val manager = WindowsSessionSecurityManager(
            sessionManager = sessionManager,
            appSettingsRepository = appSettingsRepo,
            scope = testScope
        )

        manager.onWindowMinimized()
        manager.onWorkstationLocked()
        testScope.runCurrent()

        // Should never call lock() when not in Unlocked state
        assertEquals(0, sessionManager.lockCallCount)
    }
}
