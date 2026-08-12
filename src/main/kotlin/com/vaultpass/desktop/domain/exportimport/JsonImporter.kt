package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
data class VaultExportDto(
    val title: String = "",
    val username: String = "",
    val password: String = "",
    val customFields: List<String> = emptyList(),
    val isFavorite: Boolean = false
)

/**
 * Parses Android-compatible JSON export files into domain VaultEntry models.
 * Supports both current simplified JSON object format and legacy VaultExportDto array format.
 */
object JsonImporter {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(jsonString: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        val validEntries = mutableListOf<VaultEntry>()
        var invalidCount = 0
        var duplicateCount = 0

        val existingKeys = existingEntries.map {
            "${it.title.trim().lowercase()}|${it.username.trim().lowercase()}"
        }.toSet()

        try {
            // Attempt 1: Current simplified JSON object schema
            val rootObj = jsonConfig.parseToJsonElement(jsonString).jsonObject
            for ((title, element) in rootObj) {
                try {
                    val array = element.jsonArray
                    val username = array.getOrNull(0)?.jsonPrimitive?.content ?: ""
                    val password = array.getOrNull(1)?.jsonPrimitive?.content ?: ""

                    var isFavorite = false
                    var website = ""
                    var notes = ""
                    var category = "Personal"
                    val tags = mutableListOf<String>()
                    val customFields = mutableListOf<Pair<String, String>>()

                    val meta = array.getOrNull(2)?.jsonObject
                    if (meta != null) {
                        isFavorite = meta["isFavorite"]?.jsonPrimitive?.booleanOrNull ?: false
                        website = meta["website"]?.jsonPrimitive?.content ?: ""
                        notes = meta["notes"]?.jsonPrimitive?.content ?: ""
                        category = meta["category"]?.jsonPrimitive?.content ?: "Personal"

                        meta["tags"]?.jsonArray?.forEach { tags.add(it.jsonPrimitive.content) }

                        meta["customFields"]?.jsonObject?.forEach { (key, value) ->
                            customFields.add(Pair(key, value.jsonPrimitive.content))
                        }
                    } else if (array.size > 2) {
                        // Legacy support where elements 2+ in array were raw strings
                        for (i in 2 until array.size) {
                            customFields.add(Pair("Field ${i - 1}", array[i].jsonPrimitive.content))
                        }
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

                        val now = System.currentTimeMillis()
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
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    } else {
                        invalidCount++
                    }
                } catch (e: Exception) {
                    invalidCount++
                }
            }
        } catch (e: Exception) {
            // Attempt 2: Legacy VaultExportDto array schema fallback
            try {
                val list = jsonConfig.decodeFromString<List<VaultExportDto>>(jsonString)
                for (dto in list) {
                    if (dto.title.isNotEmpty()) {
                        val combinedNotes = if (dto.customFields.isNotEmpty()) {
                            dto.customFields.mapIndexed { idx, valStr -> "Field ${idx + 1}: $valStr" }.joinToString("\n")
                        } else ""

                        val key = "${dto.title.trim().lowercase()}|${dto.username.trim().lowercase()}"
                        if (existingKeys.contains(key)) {
                            duplicateCount++
                        }

                        val now = System.currentTimeMillis()
                        validEntries.add(
                            VaultEntry(
                                id = UUID.randomUUID().toString(),
                                title = dto.title,
                                username = dto.username,
                                secret = dto.password,
                                url = "",
                                notes = combinedNotes,
                                category = "Personal",
                                tags = emptyList(),
                                history = emptyList(),
                                isFavorite = dto.isFavorite,
                                isDeleted = false,
                                deletedAt = null,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    } else {
                        invalidCount++
                    }
                }
            } catch (e2: Exception) {
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
