package com.vaultpass.desktop.data.session

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser
import com.vaultpass.desktop.domain.AppSettingsRepository
import com.vaultpass.desktop.domain.session.SessionManager
import com.vaultpass.desktop.domain.session.SessionSecurityManager
import com.vaultpass.desktop.domain.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Windows-aware implementation of [SessionSecurityManager].
 *
 * Implements dual-source inactivity tracking:
 * 1. In-app interaction (mouse, keyboard, wheel events) resets the internal in-app idle timer.
 * 2. Windows OS-level idle tracking via [User32.GetLastInputInfo] and [Kernel32.GetTickCount].
 *
 * The effective idle duration is `minOf(inAppIdleMillis, osIdleMillis)`, ensuring that
 * active computer work outside VaultPass keeps the vault unlocked, while walking away from
 * the workstation triggers auto-lock after the configured timeout.
 */
class WindowsSessionSecurityManager(
    private val sessionManager: SessionManager,
    private val appSettingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val osIdleProvider: (() -> Long)? = null,
    private val checkIntervalMillis: Long = 2000L,
    private val isWindows: Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true
) : SessionSecurityManager {

    @Volatile
    private var lastInAppActivityTimestamp: Long = timeProvider()

    @Volatile
    private var monitoringJob: Job? = null

    override val isMonitoring: Boolean
        get() = monitoringJob?.isActive == true

    override fun notifyUserActivity() {
        lastInAppActivityTimestamp = timeProvider()
    }

    override fun startMonitoring() {
        if (monitoringJob?.isActive == true) return
        lastInAppActivityTimestamp = timeProvider()

        monitoringJob = scope.launch {
            while (isActive) {
                delay(checkIntervalMillis)
                checkInactivityAndLockIfNeeded()
            }
        }
    }

    override fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    internal suspend fun checkInactivityAndLockIfNeeded() {
        if (sessionManager.sessionState.value !is SessionState.Unlocked) {
            return
        }

        val settings = appSettingsRepository.getSettings()
        val timeoutMinutes = settings.autoLockTimeoutMinutes
        if (timeoutMinutes <= 0) {
            return
        }

        val timeoutMillis = timeoutMinutes * 60 * 1000L
        val now = timeProvider()
        val inAppIdleMillis = (now - lastInAppActivityTimestamp).coerceAtLeast(0L)
        val osIdleMillis = getOsIdleMillis(inAppIdleMillis)

        val effectiveIdleMillis = minOf(inAppIdleMillis, osIdleMillis)
        if (effectiveIdleMillis >= timeoutMillis) {
            sessionManager.lock()
        }
    }

    private fun getOsIdleMillis(fallback: Long): Long {
        if (osIdleProvider != null) {
            return osIdleProvider.invoke()
        }
        if (!isWindows) {
            return fallback
        }
        return try {
            val lastInputInfo = WinUser.LASTINPUTINFO()
            if (User32.INSTANCE.GetLastInputInfo(lastInputInfo)) {
                val currentTick = Kernel32.INSTANCE.GetTickCount()
                (currentTick - lastInputInfo.dwTime).toLong().coerceAtLeast(0L)
            } else {
                fallback
            }
        } catch (t: Throwable) {
            fallback
        }
    }

    override fun onWindowMinimized() {
        scope.launch {
            if (sessionManager.sessionState.value is SessionState.Unlocked) {
                val settings = appSettingsRepository.getSettings()
                if (settings.lockWhenMinimized) {
                    sessionManager.lock()
                }
            }
        }
    }

    override fun onWorkstationLocked() {
        scope.launch {
            if (sessionManager.sessionState.value is SessionState.Unlocked) {
                val settings = appSettingsRepository.getSettings()
                if (settings.lockOnWorkstationLock) {
                    sessionManager.lock()
                }
            }
        }
    }
}
