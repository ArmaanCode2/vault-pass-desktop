package com.vaultpass.desktop.domain.exportimport

enum class ImportConflictStrategy(val title: String, val description: String) {
    SKIP_DUPLICATES(
        title = "Skip Duplicates",
        description = "Entries matching an existing Title and Username will be skipped."
    ),
    OVERWRITE_EXISTING(
        title = "Overwrite Existing",
        description = "Existing entries with matching Title and Username will be updated with imported data."
    ),
    KEEP_BOTH(
        title = "Keep Both (Create Copy)",
        description = "Import all valid entries as new records, preserving duplicates."
    )
}
