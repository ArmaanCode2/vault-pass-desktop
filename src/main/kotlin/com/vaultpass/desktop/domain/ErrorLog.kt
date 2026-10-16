package com.vaultpass.desktop.domain

/**
 * D24: errors VaultPass handles go to the console by exception class only. A message or a stack
 * trace can quote vault data (a JSON parser's message quotes its input), and the console can end up
 * in a log. Dev runs (-Dvaultpass.env=dev) also print the full trace.
 */
object ErrorLog {

    private val devMode: Boolean
        get() = System.getProperty("vaultpass.env")?.equals("dev", ignoreCase = true) == true

    /**
     * [throwable] for crash.log: its type and stack frames, and its causes', without any message
     * (a message can quote vault data). Dev runs get the full trace.
     */
    fun crashTrace(throwable: Throwable): String {
        if (devMode) return throwable.stackTraceToString()
        return buildString {
            var current: Throwable? = throwable
            var first = true
            val seen = mutableSetOf<Throwable>()
            while (current != null && seen.add(current)) {
                appendLine(if (first) current.javaClass.name else "Caused by: ${current.javaClass.name}")
                current.stackTrace.forEach { appendLine("\tat $it") }
                first = false
                current = current.cause
            }
        }
    }

    /** [throwable]'s message for the crash dialog: its type only, outside dev runs. */
    fun crashSummary(throwable: Throwable): String =
        if (devMode) "${throwable.javaClass.name}: ${throwable.message}" else throwable.javaClass.name

    /** [where]: what was being done, in a few words. */
    fun warn(where: String, e: Throwable) {
        System.err.println("[WARN] $where failed (${e.javaClass.name})")
        if (devMode) e.printStackTrace()
    }
}
