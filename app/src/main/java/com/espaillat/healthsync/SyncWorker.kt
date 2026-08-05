package com.espaillat.healthsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Instant

/** WorkManager worker: HealthConnectReader -> DriveUploader, cursor only advances on success. */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val syncState = SyncState(applicationContext)
        val owner = syncState.owner
            ?: return recordFailure(syncState, "No owner selected yet")

        if (!HealthConnectReader.isAvailable(applicationContext)) {
            return recordFailure(syncState, "Health Connect is not available on this device")
        }

        val reader = HealthConnectReader(applicationContext)
        if (!reader.hasAllPermissions()) {
            return recordFailure(syncState, "Health Connect permissions not granted")
        }

        val since = syncState.lastSyncCursor
        val until = Instant.now()

        return try {
            val rows = reader.readSince(since, until, owner.label)
            if (rows.isNotEmpty()) {
                DriveUploader(applicationContext).appendRows(owner, rows, syncState)
            }
            syncState.lastSyncCursor = until
            syncState.lastSyncTimestamp = until
            syncState.lastSyncStatus = SyncStatus.SUCCESS
            syncState.lastSyncError = null
            Result.success(workDataOf(KEY_ROWS_SYNCED to rows.size))
        } catch (e: Exception) {
            recordFailure(syncState, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun recordFailure(syncState: SyncState, message: String): Result {
        syncState.lastSyncStatus = SyncStatus.ERROR
        syncState.lastSyncError = message
        syncState.lastSyncTimestamp = Instant.now()
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    companion object {
        const val WORK_NAME = "health_sync_work"
        const val KEY_ERROR = "error"
        const val KEY_ROWS_SYNCED = "rows_synced"

        /** Enqueues a sync, joining any already-pending/running one rather than stacking up. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
