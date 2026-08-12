package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry

/**
 * Serializes a list of domain VaultEntry items into the exact Plain Text format used by Android.
 * Compatible with Android VaultPass Plain Text import.
 */
object PlainTextExporter {
    fun format(entries: List<VaultEntry>): String {
        val builder = StringBuilder()
        for (entry in entries) {
            builder.appendLine("Title: ${entry.title}")
            builder.appendLine()
            builder.appendLine("Username:")
            builder.appendLine(entry.username)
            builder.appendLine()
            builder.appendLine("Password:")
            builder.appendLine(entry.secret)
            builder.appendLine()
            if (entry.isFavorite) {
                builder.appendLine("Favorite:")
                builder.appendLine("Yes")
                builder.appendLine()
            }
            if (entry.url.isNotEmpty()) {
                builder.appendLine("Website:")
                builder.appendLine(entry.url)
                builder.appendLine()
            }
            if (entry.notes.isNotEmpty()) {
                builder.appendLine("Notes:")
                builder.appendLine(entry.notes)
                builder.appendLine()
            }
            if (!entry.category.isNullOrEmpty() && entry.category != "Personal") {
                builder.appendLine("Category:")
                builder.appendLine(entry.category)
                builder.appendLine()
            }
            if (entry.tags.isNotEmpty()) {
                builder.appendLine("Tags:")
                builder.appendLine(entry.tags.joinToString(", "))
                builder.appendLine()
            }
            builder.appendLine("---")
            builder.appendLine()
        }
        return builder.toString()
    }
}
