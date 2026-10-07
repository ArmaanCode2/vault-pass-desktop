package com.vaultpass.desktop.domain.exportimport

import java.util.Base64

/**
 * Supported import formats for cross-platform and external password manager migration.
 */
enum class DetectedFormat(val displayName: String) {
    VAULTPASS_VPEX("VaultPass Encrypted (.vpex)"),
    VAULTPASS_JSON("VaultPass JSON (.json)"),
    VAULTPASS_TXT("VaultPass Plain Text (.txt)"),
    GENERIC_CSV("Generic / Standard CSV (.csv)"),
    BITWARDEN_JSON("Bitwarden JSON (.json)"),
    BITWARDEN_CSV("Bitwarden CSV (.csv)"),
    KEEPASS_XML("KeePass 2.x XML (.xml)"),
    KEEPASS_CSV("KeePass CSV (.csv)"),
    UNKNOWN("Unknown / Unsupported Format")
}

/**
 * Detects the data format of an imported vault file based on content analysis and filename hints.
 */
object ImportFormatDetector {

    fun detect(fileContent: String, fileName: String = ""): DetectedFormat {
        val trimmed = fileContent.trim()
        if (trimmed.isEmpty()) {
            return if (fileName.endsWith(".vpex", ignoreCase = true)) {
                DetectedFormat.VAULTPASS_VPEX
            } else {
                DetectedFormat.UNKNOWN
            }
        }

        // 1. Explicit filename extension check for encrypted VPEX
        if (fileName.endsWith(".vpex", ignoreCase = true)) {
            return DetectedFormat.VAULTPASS_VPEX
        }

        // 2. KeePass XML check
        if (trimmed.contains("<KeePassFile") || (fileName.endsWith(".xml", ignoreCase = true) && trimmed.startsWith("<"))) {
            return DetectedFormat.KEEPASS_XML
        }

        // 3. JSON format inspection
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (trimmed.contains("\"items\"") && (trimmed.contains("\"folders\"") || trimmed.contains("\"encrypted\""))) {
                return DetectedFormat.BITWARDEN_JSON
            }
            if (trimmed.endsWith("}") || trimmed.endsWith("]") || fileName.endsWith(".json", ignoreCase = true)) {
                return DetectedFormat.VAULTPASS_JSON
            }
        }

        // 4. VaultPass Plain Text check
        if (trimmed.contains("Title:") && (trimmed.contains("---") || trimmed.contains("Password:"))) {
            return DetectedFormat.VAULTPASS_TXT
        }

        // 5. CSV format inspection
        val firstLine = trimmed.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        if (firstLine.contains(",") || firstLine.contains(";") || fileName.endsWith(".csv", ignoreCase = true)) {
            val firstLineClean = firstLine.lowercase()
                .replace("\"", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")

            // Bitwarden CSV detection
            if (firstLineClean.contains("loginusername") ||
                firstLineClean.contains("loginpassword") ||
                firstLineClean.contains("loginuri") ||
                firstLineClean.contains("logintotp") ||
                firstLineClean.contains("reprompt")
            ) {
                return DetectedFormat.BITWARDEN_CSV
            }

            // KeePass CSV detection (1.x and 2.x standard column layouts)
            val isKeePass1 = firstLineClean.contains("account") &&
                    firstLineClean.contains("loginname") &&
                    (firstLineClean.contains("website") || firstLineClean.contains("comments"))
            val isKeePass2 = firstLineClean.contains("group") &&
                    firstLineClean.contains("title") &&
                    firstLineClean.contains("username") &&
                    (firstLineClean.contains("url") || firstLineClean.contains("notes"))

            if (isKeePass1 || isKeePass2) {
                return DetectedFormat.KEEPASS_CSV
            }

            // Generic CSV detection
            val roles = firstLine.split(',', ';').map { CsvImporter.mapHeaderToRole(it) }
            val hasRecognizedHeaders = roles.any {
                it == CsvImporter.ColumnRole.TITLE ||
                        it == CsvImporter.ColumnRole.PASSWORD ||
                        it == CsvImporter.ColumnRole.USERNAME
            }

            if (hasRecognizedHeaders || fileName.endsWith(".csv", ignoreCase = true)) {
                return DetectedFormat.GENERIC_CSV
            }
        }

        // 6. Base64 payload detection for VPEX (without extension hint)
        val compact = trimmed.replace("\r", "").replace("\n", "").replace(" ", "")
        if (compact.length >= 60 && compact.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            try {
                val bytes = Base64.getDecoder().decode(compact)
                if (bytes.size >= 44) {
                    return DetectedFormat.VAULTPASS_VPEX
                }
            } catch (_: Exception) {}
        }

        // 7. Fallback based on filename extension
        return when {
            fileName.endsWith(".json", ignoreCase = true) -> DetectedFormat.VAULTPASS_JSON
            fileName.endsWith(".txt", ignoreCase = true) -> DetectedFormat.VAULTPASS_TXT
            fileName.endsWith(".csv", ignoreCase = true) -> DetectedFormat.GENERIC_CSV
            fileName.endsWith(".xml", ignoreCase = true) -> DetectedFormat.KEEPASS_XML
            else -> DetectedFormat.UNKNOWN
        }
    }
}
