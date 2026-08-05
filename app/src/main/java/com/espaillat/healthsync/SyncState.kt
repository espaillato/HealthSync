package com.espaillat.healthsync

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

enum class Owner(val label: String) {
    OZZY("Ozzy"),
    MAX("Max");

    val fileName: String get() = "${label}_Samsung_Health_Sync.csv"
}

enum class SyncStatus { NEVER, SUCCESS, ERROR }

/** SharedPreferences wrapper for owner identity, sync cursor, and last-sync status. */
class SyncState(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var owner: Owner?
        get() = prefs.getString(KEY_OWNER, null)?.let { runCatching { Owner.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_OWNER, value?.name).apply()

    /** High-water mark of Health Connect data already synced. Only advances on success. */
    var lastSyncCursor: Instant?
        get() = prefs.getLong(KEY_CURSOR, -1L).takeIf { it >= 0 }?.let { Instant.ofEpochMilli(it) }
        set(value) = prefs.edit().putLong(KEY_CURSOR, value?.toEpochMilli() ?: -1L).apply()

    /** Timestamp of the most recent sync attempt, success or failure. */
    var lastSyncTimestamp: Instant?
        get() = prefs.getLong(KEY_LAST_SYNC_TS, -1L).takeIf { it >= 0 }?.let { Instant.ofEpochMilli(it) }
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TS, value?.toEpochMilli() ?: -1L).apply()

    var lastSyncStatus: SyncStatus
        get() = prefs.getString(KEY_STATUS, null)
            ?.let { runCatching { SyncStatus.valueOf(it) }.getOrNull() } ?: SyncStatus.NEVER
        set(value) = prefs.edit().putString(KEY_STATUS, value.name).apply()

    var lastSyncError: String?
        get() = prefs.getString(KEY_ERROR, null)
        set(value) = prefs.edit().putString(KEY_ERROR, value).apply()

    /** Cached Drive file ID for this owner's CSV, to skip the find-by-name search each sync. */
    var driveFileId: String?
        get() = prefs.getString(KEY_DRIVE_FILE_ID, null)
        set(value) = prefs.edit().putString(KEY_DRIVE_FILE_ID, value).apply()

    companion object {
        private const val PREFS_NAME = "health_sync_state"
        private const val KEY_OWNER = "owner"
        private const val KEY_CURSOR = "last_sync_cursor_epoch_ms"
        private const val KEY_LAST_SYNC_TS = "last_sync_timestamp_epoch_ms"
        private const val KEY_STATUS = "last_sync_status"
        private const val KEY_ERROR = "last_sync_error"
        private const val KEY_DRIVE_FILE_ID = "drive_file_id"
    }
}
