package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import java.util.UUID

/**
 * Zero-dependency RFC 4180 compliant CSV parser with tolerant header aliasing.
 */
object CsvImporter {

    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')

    enum class ColumnRole {
        TITLE,
        USERNAME,
        PASSWORD,
        URL,
        NOTES,
        CATEGORY,
        TAGS,
        FAVORITE,
        UNKNOWN
    }

    fun parseRows(csvText: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var fieldWasQuoted = false
        var i = 0
        val len = csvText.length

        fun addField() {
            var str = currentField.toString()
            if (!fieldWasQuoted) {
                str = str.trim()
            }
            if (str.startsWith("'") && str.length > 1 && FORMULA_PREFIXES.contains(str[1])) {
                str = str.substring(1)
            }
            currentRow.add(str)
            currentField.setLength(0)
            fieldWasQuoted = false
        }

        fun addRow() {
            addField()
            if (currentRow.size > 1 || (currentRow.size == 1 && currentRow[0].isNotEmpty())) {
                rows.add(ArrayList(currentRow))
            }
            currentRow.clear()
        }

        while (i < len) {
            val c = csvText[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && csvText[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                when (c) {
                    '"' -> {
                        inQuotes = true
                        fieldWasQuoted = true
                    }
                    ',' -> {
                        addField()
                    }
                    '\r' -> {
                        if (i + 1 < len && csvText[i + 1] == '\n') {
                            i++
                        }
                        addRow()
                    }
                    '\n' -> {
                        addRow()
                    }
                    else -> {
                        currentField.append(c)
                    }
                }
            }
            i++
        }

        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            addRow()
        }

        return rows
    }

    fun mapHeaderToRole(header: String): ColumnRole {
        val clean = header.trim().lowercase().replace("\"", "").replace(" ", "").replace("_", "").replace("-", "")
        return when (clean) {
            "title", "name", "service", "account", "item" -> ColumnRole.TITLE
            "username", "user", "login", "loginname", "email", "loginusername" -> ColumnRole.USERNAME
            "password", "pass", "secret", "loginpassword" -> ColumnRole.PASSWORD
            "url", "website", "link", "address", "loginuri" -> ColumnRole.URL
            "notes", "note", "comments", "comment", "description" -> ColumnRole.NOTES
            "category", "folder", "group", "collection" -> ColumnRole.CATEGORY
            "tags", "tag", "labels", "label" -> ColumnRole.TAGS
            "favorite", "fav", "isfavorite", "starred" -> ColumnRole.FAVORITE
            else -> ColumnRole.UNKNOWN
        }
    }

    fun parse(csvContent: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        val rows = parseRows(csvContent)
        if (rows.isEmpty()) {
            return ImportParseResult(emptyList(), 0, 0, 0)
        }

        val headerRow = rows[0]
        val columnMapping = headerRow.mapIndexed { index, header ->
            index to mapHeaderToRole(header)
        }.toMap()

        val validEntries = mutableListOf<VaultEntry>()
        val duplicateKeys = mutableSetOf<String>()
        var invalidCount = 0
        var duplicateCount = 0

        val existingKeys = existingEntries.map {
            "${it.title.trim().lowercase()}|${it.username.trim().lowercase()}"
        }.toSet()

        val now = System.currentTimeMillis()

        for (rowIndex in 1 until rows.size) {
            val row = rows[rowIndex]
            if (row.all { it.isBlank() }) {
                invalidCount++
                continue
            }

            var title = ""
            var username = ""
            var password = ""
            var url = ""
            var notes = ""
            var category: String? = null
            var tags = emptyList<String>()
            var isFavorite = false

            for ((colIndex, role) in columnMapping) {
                if (colIndex >= row.size) continue
                val value = row[colIndex]
                when (role) {
                    ColumnRole.TITLE -> title = value
                    ColumnRole.USERNAME -> username = value
                    ColumnRole.PASSWORD -> password = value
                    ColumnRole.URL -> url = value
                    ColumnRole.NOTES -> notes = value
                    ColumnRole.CATEGORY -> category = value.takeIf { it.isNotBlank() }
                    ColumnRole.TAGS -> {
                        if (value.isNotBlank()) {
                            tags = value.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
                        }
                    }
                    ColumnRole.FAVORITE -> {
                        val favStr = value.trim().lowercase()
                        isFavorite = favStr == "true" || favStr == "1" || favStr == "yes"
                    }
                    ColumnRole.UNKNOWN -> {
                        // Optionally preserve unknown columns in notes if needed
                    }
                }
            }

            if (title.isBlank()) {
                if (url.isNotBlank()) {
                    title = url
                } else if (username.isNotBlank()) {
                    title = username
                } else {
                    invalidCount++
                    continue
                }
            }

            val key = "${title.trim().lowercase()}|${username.trim().lowercase()}"
            if (existingKeys.contains(key)) {
                duplicateCount++
                duplicateKeys.add(key)
            }

            validEntries.add(
                VaultEntry(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    username = username,
                    secret = password,
                    url = url,
                    notes = notes,
                    category = category ?: "Personal",
                    tags = tags,
                    history = emptyList(),
                    isFavorite = isFavorite,
                    isDeleted = false,
                    deletedAt = null,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        return ImportParseResult(
            validEntries = validEntries,
            invalidCount = invalidCount,
            duplicateCount = duplicateCount,
            totalCount = validEntries.size + invalidCount,
            duplicateKeys = duplicateKeys
        )
    }
}
