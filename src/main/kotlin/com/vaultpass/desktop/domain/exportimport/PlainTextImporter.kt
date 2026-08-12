package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import java.util.UUID

data class ImportParseResult(
    val validEntries: List<VaultEntry>,
    val invalidCount: Int,
    val duplicateCount: Int,
    val totalCount: Int
)

/**
 * Parses Android-compatible Plain Text format into domain VaultEntry models.
 * Calculates valid, invalid, duplicate, and total counts.
 */
object PlainTextImporter {
    fun parse(text: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        val validEntries = mutableListOf<VaultEntry>()
        var invalidCount = 0
        var duplicateCount = 0

        val existingKeys = existingEntries.map { 
            "${it.title.trim().lowercase()}|${it.username.trim().lowercase()}" 
        }.toSet()

        val blocks = text.split("---")
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var title = ""
            var username = ""
            var password = ""
            var isFavorite = false
            var website = ""
            var notes = ""
            var category = "Personal"
            var createdAt = System.currentTimeMillis()
            var updatedAt = System.currentTimeMillis()
            val tags = mutableListOf<String>()
            val customFields = mutableListOf<Pair<String, String>>()

            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("Title:")) {
                    title = line.substringAfter("Title:").trim()
                } else if (line == "Username:" && i + 1 < lines.size) {
                    username = lines[i + 1]
                    i++
                } else if (line == "Password:" && i + 1 < lines.size) {
                    password = lines[i + 1]
                    i++
                } else if (line == "Favorite:" && i + 1 < lines.size) {
                    isFavorite = lines[i + 1].equals("Yes", ignoreCase = true)
                    i++
                } else if (line == "Website:" && i + 1 < lines.size) {
                    website = lines[i + 1]
                    i++
                } else if (line == "Notes:" && i + 1 < lines.size) {
                    notes = lines[i + 1]
                    i++
                } else if (line == "Category:" && i + 1 < lines.size) {
                    category = lines[i + 1]
                    i++
                } else if (line == "Created:" && i + 1 < lines.size) {
                    lines[i + 1].toLongOrNull()?.let { createdAt = it }
                    i++
                } else if (line == "Modified:" && i + 1 < lines.size) {
                    lines[i + 1].toLongOrNull()?.let { updatedAt = it }
                    i++
                } else if (line == "Tags:" && i + 1 < lines.size) {
                    lines[i + 1].split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tags.add(it) }
                    i++
                } else if (line.endsWith(":") && i + 1 < lines.size) {
                    val key = line.dropLast(1).trim()
                    customFields.add(Pair(key, lines[i + 1]))
                    i++
                }
                i++
            }

            if (title.isNotEmpty()) {
                val combinedNotes = if (customFields.isNotEmpty()) {
                    val customNotes = customFields.joinToString("\n") { "${it.first}: ${it.second}" }
                    if (notes.isEmpty()) customNotes else "$notes\n\n[Custom Fields]\n$customNotes"
                } else {
                    notes
                }

                val key = "${title.trim().lowercase()}|${username.trim().lowercase()}"
                if (existingKeys.contains(key)) {
                    duplicateCount++
                }

                validEntries.add(
                    VaultEntry(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        username = username,
                        secret = password,
                        url = website,
                        notes = combinedNotes,
                        category = category.takeIf { it.isNotBlank() },
                        tags = tags,
                        history = emptyList(),
                        isFavorite = isFavorite,
                        isDeleted = false,
                        deletedAt = null,
                        createdAt = createdAt,
                        updatedAt = updatedAt
                    )
                )
            } else {
                invalidCount++
            }
        }

        return ImportParseResult(
            validEntries = validEntries,
            invalidCount = invalidCount,
            duplicateCount = duplicateCount,
            totalCount = validEntries.size + invalidCount
        )
    }
}
