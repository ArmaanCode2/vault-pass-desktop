package com.vaultpass.desktop.data.sync

import java.io.File
import java.util.UUID

/**
 * The desktop's sync device ID: a random UUID created once and kept in the data folder. It
 * replaces the old ID derived from the user and computer names.
 */
object DeviceIdentity {

    fun loadOrCreate(file: File): String {
        val existing = try {
            file.takeIf { it.isFile }?.readText()?.trim()
        } catch (e: Exception) {
            null
        }
        if (existing != null && isValid(existing)) return existing

        val id = UUID.randomUUID().toString()
        try {
            file.parentFile?.mkdirs()
            file.writeText(id)
        } catch (e: Exception) {
            // Still usable for this run; a new ID is created next time.
        }
        return id
    }

    private fun isValid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (e: IllegalArgumentException) {
        false
    }
}
