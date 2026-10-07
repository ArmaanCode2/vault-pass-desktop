package com.vaultpass.desktop.domain.models

/** A user-defined key/value pair on an entry. The value is treated as secret. */
data class CustomField(
    val key: String,
    val value: String
) {
    // Its limits are in com.vaultpass.synccore.EntryLimits, shared with the Android app.
    override fun toString(): String = "CustomField(key='$key')"
}
