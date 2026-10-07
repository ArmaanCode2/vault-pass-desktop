package com.vaultpass.desktop.data.clipboard

import com.vaultpass.desktop.domain.security.toSecureCharArray
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SecureClipboardManagerTest {

    @Test
    fun testCountdownTicksDownAndEmitsState() = runTest {
        var mockClipboard = ""
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val manager = WindowsSecureClipboardManager(
            coroutineScope = testScope,
            isWindows = false,
            clipboardReader = { mockClipboard },
            clipboardWriter = { mockClipboard = it }
        )

        val secret = "Secret123".toSecureCharArray()
        manager.copySensitive(secret, "Password", 3)

        // Verify memory scrubbing
        assertTrue(secret.data.all { it == '\u0000' })
        assertEquals("Secret123", mockClipboard)

        // Verify initial countdown state
        assertEquals(true, manager.countdownState.value.isActive)
        assertEquals(3, manager.countdownState.value.totalSeconds)
        assertEquals(3, manager.countdownState.value.remainingSeconds)

        // Advance 1 second
        testScope.advanceTimeBy(1000L)
        testScope.runCurrent()
        assertEquals(2, manager.countdownState.value.remainingSeconds)

        // Advance to completion
        testScope.advanceTimeBy(2000L)
        testScope.runCurrent()
        assertEquals(false, manager.countdownState.value.isActive)
        assertEquals("", mockClipboard)
    }

    @Test
    fun testFingerprintMismatchDoesNotWipeUserClipboard() = runTest {
        var mockClipboard = ""
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val manager = WindowsSecureClipboardManager(
            coroutineScope = testScope,
            isWindows = false,
            clipboardReader = { mockClipboard },
            clipboardWriter = { mockClipboard = it }
        )

        manager.copySensitive("SecretA".toSecureCharArray(), "Password", 5)
        assertEquals("SecretA", mockClipboard)

        // Advance 2 seconds
        testScope.advanceTimeBy(2000L)
        testScope.runCurrent()

        // User copies something else externally before TTL expires
        mockClipboard = "UserExternalCopiedTextB"

        // Advance remaining time
        testScope.advanceTimeBy(4000L)
        testScope.runCurrent()

        // Verify that external text B was NOT wiped because hash mismatched
        assertEquals("UserExternalCopiedTextB", mockClipboard)
        assertEquals(false, manager.countdownState.value.isActive)
    }

    @Test
    fun testClearClipboardImmediatelyCancelsCountdown() = runTest {
        var mockClipboard = ""
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val manager = WindowsSecureClipboardManager(
            coroutineScope = testScope,
            isWindows = false,
            clipboardReader = { mockClipboard },
            clipboardWriter = { mockClipboard = it }
        )

        manager.copySensitive("SecretData".toSecureCharArray(), "Password", 30)
        assertEquals(true, manager.countdownState.value.isActive)
        assertEquals("SecretData", mockClipboard)

        manager.clearClipboard()
        assertEquals(false, manager.countdownState.value.isActive)
        assertEquals("", mockClipboard)
    }

    @Test
    fun testPlainCopyDoesNotTriggerAutoClearIfTtlIsZero() = runTest {
        var mockClipboard = ""
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val testScope = TestScope(testDispatcher)

        val manager = WindowsSecureClipboardManager(
            coroutineScope = testScope,
            isWindows = false,
            clipboardReader = { mockClipboard },
            clipboardWriter = { mockClipboard = it }
        )

        manager.copyPlain("plainUsername", "Username", 0)
        assertEquals("plainUsername", mockClipboard)
        assertEquals(false, manager.countdownState.value.isActive)

        testScope.advanceTimeBy(10000L)
        testScope.runCurrent()
        assertEquals("plainUsername", mockClipboard)
    }
}
