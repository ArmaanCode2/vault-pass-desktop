package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.serialization.json.*
import java.util.UUID

object BitwardenImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(content: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        return if (content.trim().startsWith("{")) {
            parseJson(content, existingEntries)
        } else {
            parseCsv(content, existingEntries)
        }
    }

    fun parseJson(jsonString: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        val validEntries = mutableListOf<VaultEntry>()
        val duplicateKeys = mutableSetOf<String>()
        var invalidCount = 0
        var duplicateCount = 0

        val existingKeys = existingEntries.map {
            "${it.title.trim().lowercase()}|${it.username.trim().lowercase()}"
        }.toSet()

        val now = System.currentTimeMillis()

        try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val foldersMap = mutableMapOf<String, String>()
            root["folders"]?.jsonArray?.forEach { folderElem ->
                val fObj = folderElem.jsonObject
                val id = fObj["id"]?.jsonPrimitive?.contentOrNull
                val name = fObj["name"]?.jsonPrimitive?.contentOrNull
                if (id != null && name != null) {
                    foldersMap[id] = name
                }
            }

            val items = root["items"]?.jsonArray ?: emptyList()
            for (itemElem in items) {
                try {
                    val item = itemElem.jsonObject
                    val name = item["name"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
                    val notesRaw = item["notes"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isFavorite = item["favorite"]?.jsonPrimitive?.booleanOrNull ?: false
                    val folderId = item["folderId"]?.jsonPrimitive?.contentOrNull
                    val category = folderId?.let { foldersMap[it] } ?: "Personal"
                    val itemType = item["type"]?.jsonPrimitive?.intOrNull ?: 1

                    var username = ""
                    var password = ""
                    var url = ""
                    val noteParts = mutableListOf<String>()
                    if (notesRaw.isNotBlank()) {
                        noteParts.add(notesRaw)
                    }

                    when (itemType) {
                        1 -> {
                            // Login
                            val login = item["login"]?.jsonObject
                            username = login?.get("username")?.jsonPrimitive?.contentOrNull ?: ""
                            password = login?.get("password")?.jsonPrimitive?.contentOrNull ?: ""
                            val totp = login?.get("totp")?.jsonPrimitive?.contentOrNull
                            if (!totp.isNullOrBlank()) {
                                noteParts.add("[2FA TOTP Seed]: $totp")
                            }

                            val uris = login?.get("uris")?.jsonArray
                            if (!uris.isNullOrEmpty()) {
                                val firstUri = uris[0].jsonObject["uri"]?.jsonPrimitive?.contentOrNull ?: ""
                                url = firstUri
                                if (uris.size > 1) {
                                    val additional = uris.drop(1).mapNotNull {
                                        it.jsonObject["uri"]?.jsonPrimitive?.contentOrNull
                                    }
                                    if (additional.isNotEmpty()) {
                                        noteParts.add("[Additional URIs]\n" + additional.joinToString("\n"))
                                    }
                                }
                            }
                        }
                        2 -> {
                            // Secure Note
                        }
                        3 -> {
                            // Card
                            val card = item["card"]?.jsonObject
                            val cardholder = card?.get("cardholderName")?.jsonPrimitive?.contentOrNull ?: ""
                            val brand = card?.get("brand")?.jsonPrimitive?.contentOrNull ?: ""
                            val number = card?.get("number")?.jsonPrimitive?.contentOrNull ?: ""
                            val expMonth = card?.get("expMonth")?.jsonPrimitive?.contentOrNull ?: ""
                            val expYear = card?.get("expYear")?.jsonPrimitive?.contentOrNull ?: ""
                            val code = card?.get("code")?.jsonPrimitive?.contentOrNull ?: ""

                            password = number
                            val cardDetails = buildString {
                                appendLine("[Card Details]")
                                if (cardholder.isNotBlank()) appendLine("Cardholder: $cardholder")
                                if (brand.isNotBlank()) appendLine("Brand: $brand")
                                if (number.isNotBlank()) appendLine("Number: $number")
                                if (expMonth.isNotBlank() || expYear.isNotBlank()) appendLine("Expires: $expMonth/$expYear")
                                if (code.isNotBlank()) appendLine("CVV: $code")
                            }.trimEnd()
                            noteParts.add(cardDetails)
                        }
                        4 -> {
                            // Identity
                            val identity = item["identity"]?.jsonObject
                            if (identity != null) {
                                val identityDetails = buildString {
                                    appendLine("[Identity Details]")
                                    for ((key, value) in identity) {
                                        val str = value.jsonPrimitive.contentOrNull
                                        if (!str.isNullOrBlank()) {
                                            appendLine("${key.replaceFirstChar { it.uppercase() }}: $str")
                                        }
                                    }
                                }.trimEnd()
                                noteParts.add(identityDetails)
                            }
                        }
                    }

                    // Custom fields
                    val fields = item["fields"]?.jsonArray
                    if (!fields.isNullOrEmpty()) {
                        val customFieldLines = fields.mapNotNull { f ->
                            val fObj = f.jsonObject
                            val fName = fObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                            val fVal = fObj["value"]?.jsonPrimitive?.contentOrNull ?: ""
                            if (fName.isNotBlank()) "$fName: $fVal" else null
                        }
                        if (customFieldLines.isNotEmpty()) {
                            noteParts.add("[Custom Fields]\n" + customFieldLines.joinToString("\n"))
                        }
                    }

                    val title = if (name.isNotBlank()) name else (if (url.isNotBlank()) url else username)
                    if (title.isBlank()) {
                        invalidCount++
                        continue
                    }

                    val finalNotes = noteParts.joinToString("\n\n")

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
                            notes = finalNotes,
                            category = category,
                            tags = emptyList(),
                            history = emptyList(),
                            isFavorite = isFavorite,
                            isDeleted = false,
                            deletedAt = null,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                } catch (e: Exception) {
                    invalidCount++
                }
            }
        } catch (e: Exception) {
            invalidCount++
        }

        return ImportParseResult(
            validEntries = validEntries,
            invalidCount = invalidCount,
            duplicateCount = duplicateCount,
            totalCount = validEntries.size + invalidCount,
            duplicateKeys = duplicateKeys
        )
    }

    fun parseCsv(csvContent: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        val rows = CsvImporter.parseRows(csvContent)
        if (rows.isEmpty()) {
            return ImportParseResult(emptyList(), 0, 0, 0)
        }

        val headerRow = rows[0]
        val headerMap = headerRow.mapIndexed { index, header ->
            header.trim().lowercase().replace(" ", "").replace("_", "") to index
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

            fun getVal(key: String): String {
                val idx = headerMap[key] ?: return ""
                return if (idx < row.size) row[idx] else ""
            }

            val name = getVal("name").ifBlank { getVal("title") }
            val folder = getVal("folder").ifBlank { "Personal" }
            val username = getVal("loginusername").ifBlank { getVal("username") }
            val password = getVal("loginpassword").ifBlank { getVal("password") }
            val uri = getVal("loginuri").ifBlank { getVal("url") }
            val notesRaw = getVal("notes")
            val totp = getVal("logintotp").ifBlank { getVal("totp") }
            val fields = getVal("fields")
            val favoriteStr = getVal("favorite").lowercase()
            val isFavorite = favoriteStr == "1" || favoriteStr == "true" || favoriteStr == "yes"

            val title = if (name.isNotBlank()) name else (if (uri.isNotBlank()) uri else username)
            if (title.isBlank()) {
                invalidCount++
                continue
            }

            val noteParts = mutableListOf<String>()
            if (notesRaw.isNotBlank()) {
                noteParts.add(notesRaw)
            }
            if (totp.isNotBlank()) {
                noteParts.add("[2FA TOTP Seed]: $totp")
            }
            if (fields.isNotBlank()) {
                noteParts.add("[Custom Fields]\n$fields")
            }

            val finalNotes = noteParts.joinToString("\n\n")

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
                    url = uri,
                    notes = finalNotes,
                    category = folder,
                    tags = emptyList(),
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
