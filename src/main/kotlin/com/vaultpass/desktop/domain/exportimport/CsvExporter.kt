package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry

/**
 * Exports VaultEntry items to RFC 4180 compliant CSV format.
 * Includes CSV/DDE formula injection mitigation.
 */
object CsvExporter {
    val HEADERS = listOf("Title", "Username", "Password", "URL", "Notes", "Category", "Tags", "Favorite")
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')

    fun format(entries: List<VaultEntry>): String {
        val sb = StringBuilder()
        sb.append(HEADERS.joinToString(",")).append("\r\n")

        for (entry in entries) {
            val row = listOf(
                escapeField(entry.title),
                escapeField(entry.username),
                escapeField(entry.secret),
                escapeField(entry.url),
                escapeField(entry.notes),
                escapeField(entry.category ?: ""),
                escapeField(entry.tags.joinToString(", ")),
                escapeField(if (entry.isFavorite) "true" else "false")
            )
            sb.append(row.joinToString(",")).append("\r\n")
        }

        return sb.toString()
    }

    fun escapeField(raw: String): String {
        var value = raw
        // Mitigate spreadsheet formula injection
        if (value.isNotEmpty() && FORMULA_PREFIXES.contains(value[0])) {
            value = "'$value"
        }

        // RFC 4180 escaping: if contains comma, quote, or newline, wrap in quotes and double quotes
        return if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
