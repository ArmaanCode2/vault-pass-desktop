package com.vaultpass.desktop.data.database

object DatabaseQueries {
    const val CREATE_VAULT_ENTRY_TABLE = """
        CREATE TABLE IF NOT EXISTS vault_entry (
            id TEXT PRIMARY KEY NOT NULL,
            type TEXT NOT NULL,
            encrypted_payload BLOB NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            is_favorite INTEGER NOT NULL DEFAULT 0,
            is_deleted INTEGER NOT NULL DEFAULT 0,
            deleted_at INTEGER,
            sync_version INTEGER NOT NULL DEFAULT 1
        );
    """
}
