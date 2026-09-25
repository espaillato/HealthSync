package com.espaillat.healthsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deletes Samsung export folders that have already been parsed and staged. Split out of the sync
 * itself because deleting a folder of tens of thousands of files is slow -- the system's storage
 * layer does per-file work inside the one delete call -- and that time has no business counting
 * against the sync's own job limit or delaying the upload. Resumable: a delete cut short is just
 * retried, and never re-parses the folder (see [SamsungHealthExportImporter.deleteProcessedExports]).
 */
class ExportCleanupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (SamsungHealthExportImporter.deleteProcessedExports(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "export_cleanup"

        /** KEEP: a cleanup already queued or running is the one that will do the work. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ExportCleanupWorker>().build())
        }
    }
}
