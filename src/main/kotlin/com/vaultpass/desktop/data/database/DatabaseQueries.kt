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

    const val CREATE_PAIRED_DEVICE_TABLE = """
        CREATE TABLE IF NOT EXISTS paired_device (
            device_id TEXT PRIMARY KEY NOT NULL,
            device_name TEXT NOT NULL,
            shared_secret TEXT NOT NULL,
            ip_address TEXT,
            port INTEGER NOT NULL DEFAULT 53853,
            paired_at INTEGER NOT NULL,
            last_sync_at INTEGER NOT NULL DEFAULT 0
        );
    """

    /** Ids of entries deleted for good, so a sync can tell the other device. Kept forever. */
    const val CREATE_SYNC_TOMBSTONE_TABLE = """
        CREATE TABLE IF NOT EXISTS sync_tombstone (
            sync_id TEXT PRIMARY KEY NOT NULL,
            deleted_at INTEGER NOT NULL
        );
    """

    /** The schema version this app writes (stored in PRAGMA user_version). Databases from before versioning are 0. */
    const val SCHEMA_VERSION = 2

    /**
     * Schema migrations by the version they bring the database to. Every statement must be safe to
     * run on a database that already has the change.
     */
    val MIGRATIONS: List<Pair<Int, List<String>>> = listOf(
        1 to listOf(CREATE_VAULT_ENTRY_TABLE, CREATE_PAIRED_DEVICE_TABLE),
        2 to listOf(CREATE_SYNC_TOMBSTONE_TABLE)
    )
}
