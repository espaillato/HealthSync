package com.espaillat.healthsync

import android.content.Context
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import java.io.File as JavaFile
import java.io.IOException

class DriveUploaderException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Appends rows to <Owner>_Samsung_Health_Sync.csv inside the Drive folder named
 * "Wearable_Data", authenticating with a service-account key from app-private storage.
 *
 * The Wearable_Data folder must already exist and be shared with the service account's
 * email as Editor (see README step 0) — a service account has no Drive storage of its own,
 * so it can only see files/folders explicitly shared with it, regardless of their parent
 * chain. That's why folder lookup below searches by name globally rather than walking down
 * from "File Archive".
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
     */
    fun appendRows(owner: Owner, rows: List<CsvRow>, syncState: SyncState) {
        if (rows.isEmpty()) return

        val drive = buildDriveClient()
        val folderId = findWearableDataFolderId(drive)
            ?: throw DriveUploaderException(
                "'$WEARABLE_DATA_FOLDER_NAME' folder not found or not shared with the service " +
                    "account. See README step 0: share File Archive/Health/Wearable_Data with " +
                    "the service account's email as Editor."
            )

        val fileName = owner.fileName
        val fileId = resolveFileId(drive, folderId, fileName, syncState)
        val newLines = rows.joinToString("\n") { it.toCsvLine() }

        if (fileId == null) {
            val content = "${CsvRow.HEADER}\n$newLines\n"
            val created = drive.files().create(
                DriveFile().apply {
                    name = fileName
                    parents = listOf(folderId)
                    mimeType = "text/csv"
                },
                ByteArrayContent("text/csv", content.toByteArray(Charsets.UTF_8))
            ).setFields("id").execute()
            syncState.driveFileId = created.id
        } else {
            val existing = drive.files().get(fileId).executeMediaAsInputStream()
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val separator = if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else ""
            val updated = "$existing$separator$newLines\n"
            drive.files().update(fileId, null, ByteArrayContent("text/csv", updated.toByteArray(Charsets.UTF_8)))
                .execute()
            syncState.driveFileId = fileId
        }
    }

    private fun resolveFileId(drive: Drive, folderId: String, fileName: String, syncState: SyncState): String? {
        val cachedId = syncState.driveFileId
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
    }
}
