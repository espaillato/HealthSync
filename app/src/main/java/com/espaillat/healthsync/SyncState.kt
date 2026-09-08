package com.espaillat.healthsync

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant

enum class SyncStatus { NEVER, SUCCESS, ERROR }

/** SharedPreferences wrapper for owner identity, sync cursor, and last-sync status. */
class SyncState(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Free text, not a fixed set of choices -- entered once on first launch (see
     * OwnerPickerActivity) and persisted forever after. Everything downstream (the Drive CSV
     * filename, the `owner` column in every row) reads from this stored value, so this app
     * works for any household composition rather than a hardcoded pair of names.
     */
    var owner: String?
        get() = prefs.getString(KEY_OWNER, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_OWNER, value?.trim()).apply()

    /**
     * High-water mark of Health Connect data already synced, keyed **per record type**
     * (`StepsRecord`, `HeartRateRecord`, ...) rather than one shared value across all of Health
     * Connect -- changed 2026-09-07, matching the Samsung export importer's already-proven
     * per-metric-cursor pattern (see [samsungHealthExportCursor]) for the same reason: a single
     * shared cursor meant one record type hitting Health Connect's own corrupt-record bug (a
     * malformed stored record the platform client refuses to deserialize -- see
     * [HealthConnectReader.readAllPages]) either froze *every* metric's cursor in place if
     * nothing else could advance it, or got silently dragged far forward by *other* record types
     * succeeding, which on a first-ever sync or a "Resync Full History" tap (a much wider query
     * window than one day) risked skipping weeks of that one metric's history in a single jump.
     * Real, confirmed-live case that motivated this: `StepsRecord` on Max's account hit this bug
     * twice, each time stalling for about 5 days before the shared cursor happened to drag far
     * enough past the bad record for Steps to work again on its own.
     *
     * Only advances on success -- see [HealthConnectReader.readAllPages] for the forced-forward
     * behavior on failure, which guarantees this can't get stuck at the same blocked point
     * forever either.
     */
    fun healthConnectCursor(recordType: String): Instant? =
        prefs.getLong(KEY_HC_CURSOR_PREFIX + recordType, -1L).takeIf { it >= 0 }?.let { Instant.ofEpochMilli(it) }

    fun setHealthConnectCursor(recordType: String, value: Instant) {
        prefs.edit().putLong(KEY_HC_CURSOR_PREFIX + recordType, value.toEpochMilli()).apply()
    }

    /**
     * The most conservative ("data through" in the UI sense) reading across every Health-Connect
     * record type that's ever successfully advanced its own cursor -- the honest answer to "as of
     * when is *everything* definitely synced," now that there's no single shared cursor to read
     * that off directly. A record type that's never yet had a successful read at all (a fresh
     * install, or one still stuck on its very first attempt) pulls this back to null ("nothing
     * synced yet") rather than being ignored, matching how a fresh install already behaves today.
     */
    fun minHealthConnectCursor(): Instant? =
        prefs.all
            .filterKeys { it.startsWith(KEY_HC_CURSOR_PREFIX) }
            .values
            .mapNotNull { (it as? Long)?.takeIf { ms -> ms >= 0 } }
            .minOrNull()
            ?.let { Instant.ofEpochMilli(it) }

    /** Clears every record type's cursor at once, so the next sync re-reads each one's entire
     *  currently-retained Health Connect history from scratch -- used by the "Resync Full
     *  History" button. Safe to call anytime: DriveUploader's source_record_id dedup backstop
     *  means already-uploaded data just gets filtered back out, not duplicated. */
    fun resetAllHealthConnectCursors() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_HC_CURSOR_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

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

    /**
     * Cached Drive file ID for one calendar year's CSV (see [DriveUploader]'s year-rotation
     * doc), to skip the find-by-name search each sync once resolved once. Every year up to and
     * including [DriveUploader.LEGACY_UNSUFFIXED_YEAR] -- not just that exact year -- reuses the
     * original single-file cache key rather than a new year-suffixed one, matching
     * [DriveUploader.fileNameForYear]'s "everything through that year shares one file" rule
     * (needed once real pre-2026 history showed up in a Samsung export backfill). Every year
     * after that gets its own independently-cached ID.
     */
    fun driveFileId(year: Int): String? =
        if (year <= DriveUploader.LEGACY_UNSUFFIXED_YEAR) prefs.getString(KEY_DRIVE_FILE_ID, null)
        else prefs.getString(KEY_DRIVE_FILE_ID_PREFIX + year, null)

    fun setDriveFileId(year: Int, value: String) {
        val key = if (year <= DriveUploader.LEGACY_UNSUFFIXED_YEAR) KEY_DRIVE_FILE_ID else KEY_DRIVE_FILE_ID_PREFIX + year
        prefs.edit().putString(key, value).apply()
    }

    /**
     * Persisted SAF tree URI (as a string) for the folder the user granted access to for
     * Samsung Health's own full-data-export imports (HRV, respiratory rate) -- see
     * SamsungHealthExportImporter. Null until the user picks a folder once via the
     * document-tree picker; the underlying permission grant itself persists across app
     * restarts and reboots independently of this (see `takePersistableUriPermission`), this
     * just remembers which folder to look in.
     */
    var samsungHealthExportFolderUri: String?
        get() = prefs.getString(KEY_EXPORT_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_EXPORT_FOLDER_URI, value).apply()

    /**
     * High-water mark for Samsung Health export data already processed, keyed per metric type
     * (e.g. "hrv", "respiratory_rate") rather than one shared value -- see
     * SamsungHealthExportImporter. Separate from [healthConnectCursor], which tracks Health
     * Connect specifically (and independently per record type there too, for the same reason).
     * Each export is a full re-dump of Samsung Health's entire history, not
     * incremental, so without this every future export would re-parse everything from scratch,
     * including reopening thousands of per-hour HRV files already processed. Per-metric rather
     * than shared specifically so that one metric type failing to parse (e.g. Samsung changes
     * that export's column layout) can't also freeze a different, still-working metric's cursor
     * in place -- each one only ever reflects what was actually, successfully processed for it.
     */
    fun samsungHealthExportCursor(metricKey: String): Instant? =
        prefs.getLong(KEY_EXPORT_CURSOR_PREFIX + metricKey, -1L).takeIf { it >= 0 }?.let { Instant.ofEpochMilli(it) }

    fun setSamsungHealthExportCursor(metricKey: String, value: Instant) {
        prefs.edit().putLong(KEY_EXPORT_CURSOR_PREFIX + metricKey, value.toEpochMilli()).apply()
    }

    /**
     * Persistent, per-source error state that sticks around across every subsequent sync attempt
     * until that *specific* source succeeds again -- not just until the next sync runs, and not
     * cleared just because the overall sync as a whole reports success. This exists because
     * several failure points (a Samsung export metric whose format changed, a Health Connect
     * record type Health Connect's own client refuses to deserialize) are deliberately isolated
     * per-source so they can't fail the whole sync -- which means nothing about the overall
     * lastSyncStatus/lastSyncError above ever reflects them. Without this there'd be no way to
     * notice a source silently and permanently broken short of reading logcat by hand.
     *
     * This matters especially for the Samsung export: its source folder gets deleted regardless
     * of whether every metric in it parsed successfully (see SamsungHealthExportImporter), so the
     * *next* sync has nothing left to fail against and would otherwise look like a clean success
     * even though that metric's data was silently lost this round. A persisted, source-keyed
     * error is the only thing that still shows the problem after the evidence that caused it is
     * already gone.
     */
    fun sourceError(sourceKey: String): String? = prefs.getString(KEY_SOURCE_ERROR_PREFIX + sourceKey, null)

    fun setSourceError(sourceKey: String, message: String) {
        prefs.edit().putString(KEY_SOURCE_ERROR_PREFIX + sourceKey, message).apply()
    }

    fun clearSourceError(sourceKey: String) {
        prefs.edit().remove(KEY_SOURCE_ERROR_PREFIX + sourceKey).apply()
    }

    /** Every source with a currently-outstanding (unresolved) error, keyed by source name, for
     *  display in the UI -- e.g. so a warning banner can list them regardless of when each one
     *  actually failed relative to the most recent sync. */
    fun allSourceErrors(): Map<String, String> =
        prefs.all
            .filterKeys { it.startsWith(KEY_SOURCE_ERROR_PREFIX) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(KEY_SOURCE_ERROR_PREFIX) to it } }
            .toMap()

    companion object {
        private const val PREFS_NAME = "health_sync_state"
        private const val KEY_OWNER = "owner"
        private const val KEY_HC_CURSOR_PREFIX = "hc_cursor_epoch_ms_"
        private const val KEY_LAST_SYNC_TS = "last_sync_timestamp_epoch_ms"
        private const val KEY_STATUS = "last_sync_status"
        private const val KEY_ERROR = "last_sync_error"
        private const val KEY_DRIVE_FILE_ID = "drive_file_id"
        private const val KEY_DRIVE_FILE_ID_PREFIX = "drive_file_id_year_"
        private const val KEY_EXPORT_FOLDER_URI = "samsung_health_export_folder_uri"
        private const val KEY_EXPORT_CURSOR_PREFIX = "samsung_health_export_cursor_epoch_ms_"
        private const val KEY_SOURCE_ERROR_PREFIX = "source_error_"
    }
}
