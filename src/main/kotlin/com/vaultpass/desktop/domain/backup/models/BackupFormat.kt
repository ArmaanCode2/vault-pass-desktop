package com.vaultpass.desktop.domain.backup.models

import kotlinx.serialization.Serializable

/**
 * Represents the format of a VaultPass backup.
 */
@Serializable
enum class BackupFormat(val id: String) {
    JSON_V1("VP_JSON_V1"),
    MSGPACK_V1("VP_MSGPACK_V1"),
    ENCRYPTED_ZIP("VP_ENCRYPTED_ZIP");

    companion object {
        fun fromId(id: String): BackupFormat? = values().find { it.id == id }
    }
}
