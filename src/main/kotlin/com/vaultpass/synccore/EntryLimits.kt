package com.vaultpass.synccore

/**
 * How long each field of a vault entry may be. Both apps enforce the same limits, so an entry made
 * on one device can always be opened and saved on the other.
 *
 * This file is shared: keep it identical in the Android and desktop apps.
 */
object EntryLimits {
    const val TITLE = 200
    const val USERNAME = 300
    const val PASSWORD = 1_000
    const val WEBSITE = 500
    const val NOTES = 20_000
    const val CUSTOM_FIELD_KEY = 100
    const val CUSTOM_FIELD_VALUE = 2_000
    const val MAX_CUSTOM_FIELDS = 50
    const val TAG = 50
    const val MAX_TAGS = 25

    /** "Max 200 characters", for a field that is too long. */
    fun tooLong(limit: Int): String = "Max $limit characters"

    /** True when every field of [record] is within the limits. */
    fun fits(record: SyncRecord): Boolean =
        record.title.length <= TITLE &&
            record.username.length <= USERNAME &&
            record.password.length <= PASSWORD &&
            record.url.length <= WEBSITE &&
            record.notes.length <= NOTES &&
            record.tags.size <= MAX_TAGS &&
            record.tags.all { it.length <= TAG } &&
            record.customFields.size <= MAX_CUSTOM_FIELDS &&
            record.customFields.all { it.key.length <= CUSTOM_FIELD_KEY && it.value.length <= CUSTOM_FIELD_VALUE }
}
