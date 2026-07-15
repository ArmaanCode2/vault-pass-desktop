package com.vaultpass.desktop.domain.backup.models

import kotlinx.serialization.Serializable

/**
 * Represents the version of the backup schema.
 */
@Serializable
data class BackupVersion(
    val major: Int,
    val minor: Int
) : Comparable<BackupVersion> {
    override fun compareTo(other: BackupVersion): Int {
        val majorCompare = major.compareTo(other.major)
        if (majorCompare != 0) return majorCompare
        return minor.compareTo(other.minor)
    }

    override fun toString(): String = "$major.$minor"

    companion object {
        val CURRENT = BackupVersion(1, 0)
    }
}
