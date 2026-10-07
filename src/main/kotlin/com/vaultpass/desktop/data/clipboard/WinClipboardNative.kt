package com.vaultpass.desktop.data.clipboard

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

internal interface WinUser32 : StdCallLibrary {
    fun OpenClipboard(hWndNewOwner: HWND?): Boolean
    fun CloseClipboard(): Boolean
    fun EmptyClipboard(): Boolean
    fun SetClipboardData(uFormat: Int, hMem: Pointer?): Pointer?
    fun RegisterClipboardFormatW(lpszFormat: WString): Int

    companion object {
        val INSTANCE: WinUser32 by lazy {
            Native.load("user32", WinUser32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}

internal interface WinKernel32 : StdCallLibrary {
    fun GlobalAlloc(uFlags: Int, dwBytes: NativeLong): Pointer?
    fun GlobalLock(hMem: Pointer?): Pointer?
    fun GlobalUnlock(hMem: Pointer?): Boolean
    fun GlobalFree(hMem: Pointer?): Pointer?

    companion object {
        const val GMEM_MOVEABLE = 0x0002
        val INSTANCE: WinKernel32 by lazy {
            Native.load("kernel32", WinKernel32::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }
}
