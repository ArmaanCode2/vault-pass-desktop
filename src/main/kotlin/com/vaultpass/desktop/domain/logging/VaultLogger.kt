package com.vaultpass.desktop.domain.logging

/**
 * The core logging contract for VaultPass.
 * By enforcing all logs to pass through this interface, we can guarantee that
 * our LogSanitizer middleware scrubs any sensitive information before it reaches
 * standard output or a log file.
 */
interface VaultLogger {
    fun v(tag: String, message: String)
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
