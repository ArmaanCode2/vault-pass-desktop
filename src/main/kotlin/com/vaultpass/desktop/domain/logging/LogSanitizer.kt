package com.vaultpass.desktop.domain.logging

/**
 * Middleware interface for heuristically scrubbing strings before they are committed to logs.
 * Implementations of this interface will run regex checks to redact things that look
 * like JWTs, 64-character hex keys, or other sensitive patterns if a developer accidentally
 * interpolates them into a log message.
 */
interface LogSanitizer {
    /**
     * Inspects the input string and returns a redacted version if sensitive patterns are found.
     * Returns the original string if it is deemed safe.
     */
    fun sanitize(input: String): String
}
