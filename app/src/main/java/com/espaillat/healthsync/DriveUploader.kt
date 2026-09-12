package com.espaillat.healthsync

import android.content.Context
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import java.io.File as JavaFile
import java.io.IOException
import java.time.ZoneOffset

class DriveUploaderException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Appends rows to <Owner>_Samsung_Health_Sync[_<year>].csv inside the Drive folder named
 * "Wearable_Data", authenticating with a service-account key from app-private storage.
 *
 * The Wearable_Data folder must already exist and be shared with the service account's
 * email as Editor (see README step 0) — a service account has no Drive storage of its own,
 * so it can only see files/folders explicitly shared with it, regardless of their parent
 * chain. That's why folder lookup below searches by name globally rather than walking down
 * from "File Archive".
 *
 * One CSV per calendar year, not one file forever. Every daily-aggregated metric here already
 * keeps growth modest -- min/avg/max or a sum per day, regardless of how densely a sensor
 * actually samples -- so the realistic long-run rate lands somewhere around a few thousand
 * bytes a day, on the order of 1-2 MB/year even with every metric this app now reads (checked
 * against the real first-time backfill of the four newest Samsung metrics: ~8,100 rows across
 * roughly 13 months of history). That alone would take a decade-plus to become "big" in raw
 * storage terms. The actual problem year-rotation fixes isn't file size, it's that every sync
 * does a full read-then-rewrite of the *entire* existing file (see the update branch below) --
 * there's no true incremental-append call being used here, Drive API v3 has none for plain
 * files. Without rotation, that means the bytes downloaded, held in memory, parsed for dedup,
 * and re-uploaded on every single sync keeps growing forever, even though the amount of
 * genuinely new data each sync adds stays small and constant. Splitting by year bounds that
 * cost permanently: no matter how many years of history accumulate, any one sync only ever
 * touches the current year's file, which never holds more than a year's worth of rows. Past
 * years' files are simply never touched again once the year rolls over, which is also exactly
 * the "rotation" a human would want for browsing -- one bounded, dated file per year in Drive,
 * not one ever-growing scroll.
 */
class DriveUploader(private val context: Context) {

    private fun serviceAccountKeyFile(): JavaFile = JavaFile(context.filesDir, SERVICE_ACCOUNT_KEY_FILENAME)

    fun hasServiceAccountKey(): Boolean = serviceAccountKeyFile().exists()

    private fun buildDriveClient(): Drive {
        val keyFile = serviceAccountKeyFile()
        if (!keyFile.exists()) {
            throw DriveUploaderException(
                "Drive service-account key not found in app storage. See README step 0 " +
                    "for the adb/run-as command to push $SERVICE_ACCOUNT_KEY_FILENAME."
            )
        }
        val credentials = keyFile.inputStream().use { stream ->
            GoogleCredentials.fromStream(stream).createScoped(listOf(SCOPE))
        }
        val transport = NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        return Drive.Builder(transport, jsonFactory, HttpCredentialsAdapter(credentials))
            .setApplicationName("HealthSync")
            .build()
    }

    /**
     * Appends [rows] to [owner]'s CSV, creating the file (with header) on first use. Throws on
     * any failure so the caller can avoid advancing the sync cursor.
     *
     * Rows are split by calendar year first (see class doc for why) and each year's slice is
     * appended to that year's own file independently -- normally a no-op split, since almost
     * every sync's rows all fall in the current year, but a full history backfill (first-ever
     * sync, or the "Resync Full History" button) can span several years in one call and has to
     * fan out to several files at once rather than assuming "this batch" means "one file".
     */
    fun appendRows(owner: String, rows: List<CsvRow>, syncState: SyncState) {
        if (rows.isEmpty()) return

        // Dedup *within* this batch, not just against what's already in the Drive file below.
        // Single-source callers (Health Connect alone) never produce internal duplicates, so
        // this is a no-op for them -- it matters once a batch can combine rows from more than
        // one source in the same call (Health Connect data plus a staged Samsung Health Monitor
        // PDF import, see PendingImports/SyncWorker), where overlapping export windows routinely
        // produce the same source_record_id from two different staged files. The existing-file
        // check further down can't catch that -- it only knows about rows already on Drive, not
        // duplicates sitting next to each other in the same incoming batch.
        val deduped = rows.distinctBy { it.sourceRecordId }

        val drive = buildDriveClient()
        val folderId = findWearableDataFolderId(drive)
            ?: throw DriveUploaderException(
                "'$WEARABLE_DATA_FOLDER_NAME' folder not found or not shared with the service " +
                    "account. See README step 0: share File Archive/Health/Wearable_Data with " +
                    "the service account's email as Editor."
            )

        // Every row's timestamp is UTC -- for daily-aggregated rows that's deliberately UTC
        // midnight of the local calendar date (see HealthConnectReader/SamsungHealthExportImporter
        // doc comments), so its UTC year already matches the year a human would file that row
        // under. Point-in-time rows (weight, blood pressure, AGE, ...) could in principle land a
        // handful of hours on the "wrong" side of a year boundary relative to local time, but
        // that's at most a few readings misfiled by one calendar year right at New Year's --
        // not worth a timezone-aware split for.
        val byYear = deduped.groupBy { it.timestampUtc.atZone(ZoneOffset.UTC).year }
        for ((year, yearRows) in byYear.entries.sortedBy { it.key }) {
            appendRowsForYear(drive, folderId, owner, year, yearRows, syncState)
        }
    }

    private fun appendRowsForYear(drive: Drive, folderId: String, owner: String, year: Int, rows: List<CsvRow>, syncState: SyncState) {
        val fileName = fileNameForYear(owner, year)
        val fileId = resolveFileId(drive, folderId, fileName, year, syncState)

        if (fileId == null) {
            val newLines = rows.joinToString("\n") { it.toCsvLine() }
            val content = "${CsvRow.HEADER}\n$newLines\n"
            val created = try {
                drive.files().create(
                    DriveFile().apply {
                        name = fileName
                        parents = listOf(folderId)
                        mimeType = "text/csv"
                    },
                    ByteArrayContent("text/csv", content.toByteArray(Charsets.UTF_8))
                ).setFields("id").execute()
            } catch (e: GoogleJsonResponseException) {
                if (e.details?.errors.orEmpty().any { it.reason == "storageQuotaExceeded" }) {
                    throw DriveUploaderException(
                        "Drive rejected creating '$fileName': service accounts have no " +
                            "storage quota of their own, so they can't create a brand-new " +
                            "file even in a folder shared with them as Editor. One-time " +
                            "fix: create an empty file named exactly '$fileName' yourself " +
                            "(signed in as your own Google account) inside Wearable_Data. " +
                            "After that it already exists, so every sync only updates it " +
                            "instead of creating it, which works fine for a service " +
                            "account. A new one of these is needed once a year, right after " +
                            "the first sync of January -- see README step 0.",
                        e
                    )
                }
                throw e
            }
            syncState.setDriveFileId(year, created.id)
        } else {
            val existing = drive.files().get(fileId).executeMediaAsInputStream()
                .use { it.readBytes().toString(Charsets.UTF_8) }
            // The documented setup flow (README step 0) always pre-creates this file empty,
            // since a service account can't create it itself (storageQuotaExceeded above) --
            // so an empty existing file is the normal first-sync case, not an edge case, and
            // needs the header written here rather than relying on the files.create() branch,
            // which the standard flow never actually reaches.
            val base = existing.ifEmpty { "${CsvRow.HEADER}\n" }

            // Backstop against duplicate rows if the local sync cursor was ever lost (app
            // reinstall, cleared data, etc.) and a sync re-reads data already present in the
            // file -- per the design doc's definition of done. source_record_id values are
            // assumed comma-free (Health Connect UUIDs, or our own synthetic hr_hourly_* ids),
            // so a cheap last-column extraction is enough without a full CSV parser.
            val existingIds = base.lineSequence().drop(1)
                .mapNotNullTo(mutableSetOf()) { it.substringAfterLast(',', "").ifEmpty { null } }
            val newRows = rows.filterNot { it.sourceRecordId in existingIds }

            if (newRows.isEmpty()) {
                syncState.setDriveFileId(year, fileId)
                return
            }

            val newLines = newRows.joinToString("\n") { it.toCsvLine() }
            val separator = if (!base.endsWith("\n")) "\n" else ""
            val updated = "$base$separator$newLines\n"
            drive.files().update(fileId, null, ByteArrayContent("text/csv", updated.toByteArray(Charsets.UTF_8)))
                .execute()
            syncState.setDriveFileId(year, fileId)
        }
    }

    /**
     * [LEGACY_UNSUFFIXED_YEAR] *and everything before it* keeps the original, un-suffixed name --
     * not just rows from exactly that year. Real bug hit in practice: a Samsung full-export
     * backfill can include history from well before rotation was introduced (confirmed live --
     * a watch's pre-2026 history from before its current device came through in one backfill),
     * and there's no pre-created file for any of those older years, nor should there be one per
     * past year -- unlike 2027 onward, past years aren't something to plan file names for ahead
     * of time. Every year *after* [LEGACY_UNSUFFIXED_YEAR] gets its own explicit "_<year>" file
     * instead, since those are genuinely new, growing years going forward.
     *
     * Also sanitized to filesystem/Drive-safe characters, since [owner] is free text (see
     * SyncState.owner) rather than a fixed set of choices -- otherwise a name with e.g. a slash
     * in it would silently create a subpath instead of a literal filename.
     */
    private fun fileNameForYear(owner: String, year: Int): String {
        val safeOwner = owner.trim().replace(Regex("[^A-Za-z0-9_ -]+"), "_")
        val base = "${safeOwner}_Samsung_Health_Sync"
        return if (year <= LEGACY_UNSUFFIXED_YEAR) "$base.csv" else "${base}_$year.csv"
    }

    private fun resolveFileId(drive: Drive, folderId: String, fileName: String, year: Int, syncState: SyncState): String? {
        val cachedId = syncState.driveFileId(year)
        if (cachedId != null) {
            try {
                val file = drive.files().get(cachedId).setFields("id,trashed,parents,name").execute()
                if (file.trashed != true && file.name == fileName) return cachedId
            } catch (_: Exception) {
                // Cached ID is stale (file deleted/inaccessible) — fall through to a fresh search.
            }
        }
        val result = drive.files().list()
            .setQ("'$folderId' in parents and name = '$fileName' and trashed = false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
        return result.files.firstOrNull()?.id
    }

    private fun findWearableDataFolderId(drive: Drive): String? {
        val result = drive.files().list()
            .setQ("name = '$WEARABLE_DATA_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
        return result.files.firstOrNull()?.id
    }

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/drive"
        private const val WEARABLE_DATA_FOLDER_NAME = "Wearable_Data"
        const val SERVICE_ACCOUNT_KEY_FILENAME = "drive_service_account.json"

        /** The last calendar year whose data still lands in the original, un-suffixed file --
         *  see [fileNameForYear] and [SyncState.driveFileId]. Not just an exact-year match: any
         *  year at or before this one (including real pre-2026 history a Samsung export backfill
         *  can surface) shares this one file too, since there's no reason to pre-create a
         *  separate file per past year the way there is for 2027-onward. The year this rotation
         *  scheme shipped. */
        const val LEGACY_UNSUFFIXED_YEAR = 2026
    }
}
