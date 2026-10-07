package com.vaultpass.desktop.domain.exportimport

import com.vaultpass.desktop.domain.models.VaultEntry
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * KeePass importer supporting KeePass 2.x XML (with strict XXE protection and recursive group hierarchy)
 * and KeePass CSV exports.
 */
object KeePassImporter {

    private fun createSecureDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {}
        try {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        } catch (_: Exception) {}
        try {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        } catch (_: Exception) {}
        try {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        } catch (_: Exception) {}
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory
    }

    fun parse(content: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        val trimmed = content.trim()
        return if (trimmed.startsWith("<?xml") || trimmed.contains("<KeePassFile") || trimmed.startsWith("<")) {
            parseXml(content, existingEntries)
        } else {
            parseCsv(content, existingEntries)
        }
    }

    fun parseXml(xmlContent: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        val validEntries = mutableListOf<VaultEntry>()
        val duplicateKeys = mutableSetOf<String>()
        val counts = IntArray(2) // [invalidCount, duplicateCount]

        val existingKeys = existingEntries.map {
            "${it.title.trim().lowercase()}|${it.username.trim().lowercase()}"
        }.toSet()

        try {
            val factory = createSecureDocumentBuilderFactory()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xmlContent.toByteArray(Charsets.UTF_8)))
            doc.documentElement.normalize()

            val rootElement = doc.documentElement

            // Extract RecycleBinUUID from Meta if configured
            var recycleBinUuid: String? = null
            val metaList = rootElement.getElementsByTagName("Meta")
            if (metaList.length > 0) {
                val metaElem = metaList.item(0) as? Element
                recycleBinUuid = metaElem?.directChildText("RecycleBinUUID")
            }

            // Locate Root element
            val rootNodeList = rootElement.getElementsByTagName("Root")
            val rootNode = if (rootNodeList.length > 0) rootNodeList.item(0) as? Element else rootElement

            if (rootNode != null) {
                val childNodes = rootNode.childNodes
                for (i in 0 until childNodes.length) {
                    val node = childNodes.item(i)
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        val elem = node as Element
                        when (elem.tagName) {
                            "Group" -> {
                                val groupName = elem.directChildText("Name") ?: ""
                                val groupUuid = elem.directChildText("UUID") ?: ""
                                val isRecycleBin = (recycleBinUuid != null && groupUuid.equals(recycleBinUuid, ignoreCase = true)) ||
                                        groupName.equals("Recycle Bin", ignoreCase = true) ||
                                        groupName.equals("Trash", ignoreCase = true)
                                if (!isRecycleBin) {
                                    val isTopRoot = groupName.equals("Root", ignoreCase = true)
                                    val initialPath = if (isTopRoot) emptyList() else listOf(groupName)
                                    traverseGroup(
                                        groupElement = elem,
                                        currentPath = initialPath,
                                        recycleBinUuid = recycleBinUuid,
                                        existingKeys = existingKeys,
                                        validEntries = validEntries,
                                        duplicateKeys = duplicateKeys,
                                        counts = counts
                                    )
                                }
                            }
                            "Entry" -> {
                                parseEntry(
                                    entryElem = elem,
                                    category = "Personal",
                                    existingKeys = existingKeys,
                                    validEntries = validEntries,
                                    duplicateKeys = duplicateKeys,
                                    counts = counts
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return ImportParseResult(
                validEntries = validEntries,
                invalidCount = counts[0] + 1,
                duplicateCount = counts[1],
                totalCount = validEntries.size + counts[0] + 1,
                duplicateKeys = duplicateKeys
            )
        }

        return ImportParseResult(
            validEntries = validEntries,
            invalidCount = counts[0],
            duplicateCount = counts[1],
            totalCount = validEntries.size + counts[0],
            duplicateKeys = duplicateKeys
        )
    }

    private fun traverseGroup(
        groupElement: Element,
        currentPath: List<String>,
        recycleBinUuid: String?,
        existingKeys: Set<String>,
        validEntries: MutableList<VaultEntry>,
        duplicateKeys: MutableSet<String>,
        counts: IntArray
    ) {
        val childNodes = groupElement.childNodes
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val elem = node as Element
                when (elem.tagName) {
                    "Group" -> {
                        val groupName = elem.directChildText("Name") ?: ""
                        val groupUuid = elem.directChildText("UUID") ?: ""
                        val isRecycleBin = (recycleBinUuid != null && groupUuid.equals(recycleBinUuid, ignoreCase = true)) ||
                                groupName.equals("Recycle Bin", ignoreCase = true) ||
                                groupName.equals("Trash", ignoreCase = true)
                        if (!isRecycleBin) {
                            val newPath = if (groupName.isNotBlank()) currentPath + groupName else currentPath
                            traverseGroup(
                                groupElement = elem,
                                currentPath = newPath,
                                recycleBinUuid = recycleBinUuid,
                                existingKeys = existingKeys,
                                validEntries = validEntries,
                                duplicateKeys = duplicateKeys,
                                counts = counts
                            )
                        }
                    }
                    "Entry" -> {
                        val category = if (currentPath.isNotEmpty()) currentPath.joinToString("/") else "Personal"
                        parseEntry(
                            entryElem = elem,
                            category = category,
                            existingKeys = existingKeys,
                            validEntries = validEntries,
                            duplicateKeys = duplicateKeys,
                            counts = counts
                        )
                    }
                }
            }
        }
    }

    private fun parseEntry(
        entryElem: Element,
        category: String,
        existingKeys: Set<String>,
        validEntries: MutableList<VaultEntry>,
        duplicateKeys: MutableSet<String>,
        counts: IntArray
    ) {
        var title = ""
        var username = ""
        var password = ""
        var url = ""
        var notes = ""
        val customFields = mutableListOf<Pair<String, String>>()

        val stringNodes = entryElem.directChildren("String")
        for (strNode in stringNodes) {
            val key = strNode.directChildText("Key") ?: ""
            val value = strNode.directChild("Value")?.textContent ?: ""

            when (key) {
                "Title" -> title = value
                "UserName" -> username = value
                "Password" -> password = value
                "URL" -> url = value
                "Notes" -> notes = value
                else -> {
                    if (key.isNotBlank()) {
                        customFields.add(key to value)
                    }
                }
            }
        }

        val tagsStr = entryElem.directChildText("Tags")
        val tags = if (!tagsStr.isNullOrBlank()) {
            tagsStr.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        val now = System.currentTimeMillis()
        val timesElem = entryElem.directChild("Times")
        val creationStr = timesElem?.directChildText("CreationTime")
        val modStr = timesElem?.directChildText("LastModificationTime")
        val createdAt = parseTimestamp(creationStr, now)
        val updatedAt = parseTimestamp(modStr, now)

        val noteParts = mutableListOf<String>()
        if (notes.isNotBlank()) {
            noteParts.add(notes)
        }
        if (customFields.isNotEmpty()) {
            val customFieldLines = customFields.map { "${it.first}: ${it.second}" }
            noteParts.add("[Custom Fields]\n" + customFieldLines.joinToString("\n"))
        }
        val finalNotes = noteParts.joinToString("\n\n")

        val finalTitle = if (title.isNotBlank()) title else (if (url.isNotBlank()) url else username)
        if (finalTitle.isBlank()) {
            counts[0]++ // invalidCount
            return
        }

        val entryKey = "${finalTitle.trim().lowercase()}|${username.trim().lowercase()}"
        if (existingKeys.contains(entryKey)) {
            counts[1]++ // duplicateCount
            duplicateKeys.add(entryKey)
        }

        validEntries.add(
            VaultEntry(
                id = UUID.randomUUID().toString(),
                title = finalTitle,
                username = username,
                secret = password,
                url = url,
                notes = finalNotes,
                category = category,
                tags = tags,
                history = emptyList(),
                isFavorite = false,
                isDeleted = false,
                deletedAt = null,
                createdAt = createdAt,
                updatedAt = updatedAt
            )
        )
    }

    fun parseCsv(csvContent: String, existingEntries: List<VaultEntry> = emptyList()): ImportParseResult {
        val result = CsvImporter.parse(csvContent, existingEntries)
        val nonTrashEntries = result.validEntries.filterNot { entry ->
            val cat = entry.category?.trim()?.lowercase() ?: ""
            cat == "recycle bin" || cat == "trash" || cat.endsWith("/recycle bin") || cat.endsWith("/trash")
        }

        val existingKeys = existingEntries.map {
            "${it.title.trim().lowercase()}|${it.username.trim().lowercase()}"
        }.toSet()

        var duplicateCount = 0
        val duplicateKeys = mutableSetOf<String>()
        for (entry in nonTrashEntries) {
            val key = "${entry.title.trim().lowercase()}|${entry.username.trim().lowercase()}"
            if (existingKeys.contains(key)) {
                duplicateCount++
                duplicateKeys.add(key)
            }
        }

        return ImportParseResult(
            validEntries = nonTrashEntries,
            invalidCount = result.invalidCount,
            duplicateCount = duplicateCount,
            totalCount = nonTrashEntries.size + result.invalidCount,
            duplicateKeys = duplicateKeys
        )
    }

    private fun parseTimestamp(timeStr: String?, fallback: Long): Long {
        if (timeStr.isNullOrBlank()) return fallback
        return try {
            Instant.parse(timeStr).toEpochMilli()
        } catch (_: Exception) {
            fallback
        }
    }

    private fun Element.directChildren(tagName: String): List<Element> {
        val result = mutableListOf<Element>()
        val list = this.childNodes
        for (i in 0 until list.length) {
            val node = list.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tagName) {
                result.add(node)
            }
        }
        return result
    }

    private fun Element.directChild(tagName: String): Element? {
        val list = this.childNodes
        for (i in 0 until list.length) {
            val node = list.item(i)
            if (node.nodeType == Node.ELEMENT_NODE && (node as Element).tagName == tagName) {
                return node
            }
        }
        return null
    }

    private fun Element.directChildText(tagName: String): String? {
        return directChild(tagName)?.textContent?.trim()
    }
}
