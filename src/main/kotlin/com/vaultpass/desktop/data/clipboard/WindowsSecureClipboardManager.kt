package com.vaultpass.desktop.data.clipboard

import com.sun.jna.NativeLong
import com.sun.jna.WString
import com.vaultpass.desktop.domain.clipboard.ClipboardCountdown
import com.vaultpass.desktop.domain.clipboard.SecureClipboardManager
import com.vaultpass.desktop.domain.security.SecureCharArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class WindowsSecureClipboardManager(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val isWindows: Boolean = System.getProperty("os.name")?.lowercase()?.contains("win") == true,
    private val clipboardReader: () -> String? = { readAwtClipboard() },
    private val clipboardWriter: (String) -> Unit = { writeAwtClipboard(it) },
    private val nativeWriter: ((CharArray) -> Boolean)? = null,
    private val nativeClearer: (() -> Boolean)? = null,
    private val delayMillisProvider: suspend (Long) -> Unit = { delay(it) }
) : SecureClipboardManager {

    private val _countdownState = MutableStateFlow(ClipboardCountdown())
    override val countdownState: StateFlow<ClipboardCountdown> = _countdownState.asStateFlow()

    private var _countdownJob: Job? = null
    @Volatile
    private var activeHash: String? = null

    // Windows 10/11 Registered Clipboard Formats
    private val cfIgnore by lazy {
        try {
            WinUser32.INSTANCE.RegisterClipboardFormatW(WString("Clipboard Viewer Ignore"))
        } catch (_: Throwable) { 0 }
    }

    private val cfHistory by lazy {
        try {
            WinUser32.INSTANCE.RegisterClipboardFormatW(WString("CanIncludeInClipboardHistory"))
        } catch (_: Throwable) { 0 }
    }

    private val cfCloud by lazy {
        try {
            WinUser32.INSTANCE.RegisterClipboardFormatW(WString("CanUploadToCloudClipboard"))
        } catch (_: Throwable) { 0 }
    }

    override fun copySensitive(data: SecureCharArray, label: String, ttlSeconds: Int) {
        _countdownJob?.cancel()
        val chars = data.data
        val hash = computeSha256(chars)
        activeHash = hash

        var nativeSuccess = false
        if (nativeWriter != null) {
            nativeSuccess = nativeWriter.invoke(chars)
        } else if (isWindows) {
            try {
                nativeSuccess = writeNativeWindowsClipboard(chars)
            } catch (_: Throwable) {
                nativeSuccess = false
            }
        }

        if (!nativeSuccess) {
            clipboardWriter(String(chars))
        }

        // Memory scrubbing: wipe intermediate char array immediately
        data.wipe()

        if (ttlSeconds > 0) {
            startCountdown(ttlSeconds, label)
        } else {
            _countdownState.value = ClipboardCountdown(isActive = false)
        }
    }

    override fun copyPlain(text: String, label: String, ttlSeconds: Int) {
        _countdownJob?.cancel()
        clipboardWriter(text)
        if (ttlSeconds > 0) {
            val chars = text.toCharArray()
            activeHash = computeSha256(chars)
            chars.fill('\u0000')
            startCountdown(ttlSeconds, label)
        } else {
            activeHash = null
            _countdownState.value = ClipboardCountdown(isActive = false)
        }
    }

    override fun clearClipboard() {
        _countdownJob?.cancel()
        activeHash = null
        _countdownState.value = ClipboardCountdown(isActive = false)
        performClear()
    }

    override fun dispose() {
        clearClipboard()
    }

    private fun startCountdown(ttlSeconds: Int, label: String) {
        _countdownState.value = ClipboardCountdown(
            isActive = true,
            remainingSeconds = ttlSeconds,
            totalSeconds = ttlSeconds,
            label = label
        )

        _countdownJob = coroutineScope.launch {
            var remaining = ttlSeconds
            while (remaining > 0) {
                delayMillisProvider(1000L)
                remaining--
                if (remaining > 0) {
                    _countdownState.update { it.copy(remainingSeconds = remaining) }
                }
            }

            // Timeout reached: verify SHA-256 fingerprint before purging
            val current = clipboardReader()
            if (current != null) {
                val currentChars = current.toCharArray()
                val currentHash = computeSha256(currentChars)
                currentChars.fill('\u0000')
                if (currentHash == activeHash) {
                    performClear()
                }
            } else {
                performClear()
            }
            _countdownState.value = ClipboardCountdown(isActive = false)
            activeHash = null
        }
    }

    private fun performClear() {
        var nativeSuccess = false
        if (nativeClearer != null) {
            nativeSuccess = nativeClearer.invoke()
        } else if (isWindows) {
            try {
                nativeSuccess = clearNativeWindowsClipboard()
            } catch (_: Throwable) {
                nativeSuccess = false
            }
        }
        if (!nativeSuccess) {
            clipboardWriter("")
        }
    }

    private fun openClipboardWithRetry(maxAttempts: Int = 5, delayMillis: Long = 25): Boolean {
        for (attempt in 1..maxAttempts) {
            if (WinUser32.INSTANCE.OpenClipboard(null)) {
                return true
            }
            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(delayMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return false
    }

    private fun writeNativeWindowsClipboard(chars: CharArray): Boolean {
        if (!openClipboardWithRetry()) return false
        return try {
            WinUser32.INSTANCE.EmptyClipboard()
            writeUnicodeTextToClipboard(chars)
            if (cfHistory != 0) writeDwordToClipboard(cfHistory, 0)
            if (cfCloud != 0) writeDwordToClipboard(cfCloud, 0)
            if (cfIgnore != 0) writeByteToClipboard(cfIgnore, 0)
            true
        } catch (e: Throwable) {
            false
        } finally {
            WinUser32.INSTANCE.CloseClipboard()
        }
    }

    private fun clearNativeWindowsClipboard(): Boolean {
        if (!openClipboardWithRetry()) return false
        return try {
            WinUser32.INSTANCE.EmptyClipboard()
            true
        } catch (e: Throwable) {
            false
        } finally {
            WinUser32.INSTANCE.CloseClipboard()
        }
    }

    private fun writeUnicodeTextToClipboard(chars: CharArray): Boolean {
        val byteCount = (chars.size + 1) * 2L
        val hMem = WinKernel32.INSTANCE.GlobalAlloc(WinKernel32.GMEM_MOVEABLE, NativeLong(byteCount)) ?: return false
        val ptr = WinKernel32.INSTANCE.GlobalLock(hMem)
        if (ptr == null) {
            WinKernel32.INSTANCE.GlobalFree(hMem)
            return false
        }
        for (i in chars.indices) {
            ptr.setChar(i * 2L, chars[i])
        }
        ptr.setChar(chars.size * 2L, '\u0000')
        WinKernel32.INSTANCE.GlobalUnlock(hMem)
        val res = WinUser32.INSTANCE.SetClipboardData(13 /* CF_UNICODETEXT */, hMem)
        if (res == null) {
            WinKernel32.INSTANCE.GlobalFree(hMem)
            return false
        }
        return true
    }

    private fun writeDwordToClipboard(formatId: Int, value: Int): Boolean {
        val hMem = WinKernel32.INSTANCE.GlobalAlloc(WinKernel32.GMEM_MOVEABLE, NativeLong(4L)) ?: return false
        val ptr = WinKernel32.INSTANCE.GlobalLock(hMem)
        if (ptr == null) {
            WinKernel32.INSTANCE.GlobalFree(hMem)
            return false
        }
        ptr.setInt(0, value)
        WinKernel32.INSTANCE.GlobalUnlock(hMem)
        val res = WinUser32.INSTANCE.SetClipboardData(formatId, hMem)
        if (res == null) {
            WinKernel32.INSTANCE.GlobalFree(hMem)
            return false
        }
        return true
    }

    private fun writeByteToClipboard(formatId: Int, value: Byte): Boolean {
        val hMem = WinKernel32.INSTANCE.GlobalAlloc(WinKernel32.GMEM_MOVEABLE, NativeLong(1L)) ?: return false
        val ptr = WinKernel32.INSTANCE.GlobalLock(hMem)
        if (ptr == null) {
            WinKernel32.INSTANCE.GlobalFree(hMem)
            return false
        }
        ptr.setByte(0, value)
        WinKernel32.INSTANCE.GlobalUnlock(hMem)
        val res = WinUser32.INSTANCE.SetClipboardData(formatId, hMem)
        if (res == null) {
            WinKernel32.INSTANCE.GlobalFree(hMem)
            return false
        }
        return true
    }

    private fun computeSha256(chars: CharArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val cb = CharBuffer.wrap(chars)
        val bb = StandardCharsets.UTF_8.encode(cb)
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        try {
            val digest = md.digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        private fun readAwtClipboard(): String? {
            return try {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    clipboard.getData(DataFlavor.stringFlavor) as? String
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun writeAwtClipboard(text: String) {
            try {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(text), null)
            } catch (e: Exception) {
                System.err.println("SecureClipboardManager: AWT clipboard write error: ${e.message}")
            }
        }
    }
}
