package com.espaillat.healthsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

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
        // Query as fresh as possible -- completeness (has today, or the current sleep day,
        // actually finished?) is HealthConnectReader's job, not the query window's. See
        // HealthConnectReader.readSince's doc for why those are deliberately separate: a
        // calendar day and a sleep day (noon-to-noon) become "complete" at different wall-clock
        // times, so narrowing the query window to one shared cutoff can't correctly serve both
        // regardless of what that cutoff is.
        val now = Instant.now()

        return try {
            val rows = reader.readSince(since, now, owner.label)
            if (rows.isNotEmpty()) {
                DriveUploader(applicationContext).appendRows(owner, rows, syncState)
            }
            // Only advance the cursor when a sync actually found something. Health Connect
            // write sources can backfill *already-passed* timestamps -- e.g. Samsung Health,
            // the moment it's first granted write access, wrote several hours of that day's
            // heart-rate history retroactively. If the cursor had already advanced past that
            // window (because an earlier sync ran before the backfill happened and legitimately
            // found nothing), that data would become permanently unreachable: the cursor never
            // looks backward. Leaving the cursor unmoved on an empty result costs nothing here
            // (Health Connect reads are local, not network calls) and guarantees a late backfill
            // into a previously-empty window still gets picked up on the next sync.
            //
            // The cursor advances to safeCursorBoundary(), not to `now` -- never past a point
            // that could still receive more data for some metric. Never move it backward either
            // way, in case a prior cursor (from before this boundary logic existed) is somehow
            // already ahead of it.
            val safeBoundary = reader.safeCursorBoundary()
            if (rows.isNotEmpty() && (since == null || safeBoundary.isAfter(since))) {
                syncState.lastSyncCursor = safeBoundary
            }
            syncState.lastSyncTimestamp = now
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
        /** Unique name for on-demand syncs (app launch, manual "Sync Now" tap). */
        const val MANUAL_WORK_NAME = "health_sync_manual_work"

        /** Unique name for the once-a-day background schedule (see [schedulePeriodicSync]). */
        const val PERIODIC_WORK_NAME = "health_sync_periodic_work"

        const val KEY_ERROR = "error"
        const val KEY_ROWS_SYNCED = "rows_synced"

        /** How often the background sync runs on its own, absent any user-initiated trigger.
         *  Once a day is already more than enough for step/HR/sleep/exercise data; bump this
         *  to 2-3 if even-less-frequent background syncing is preferred. */
        private const val SYNC_INTERVAL_DAYS = 1L
        private const val FLEX_WINDOW_HOURS = 1L
        // Afternoon, not the middle of the night -- deliberately not "2am" despite that being
        // the obvious off-peak-battery choice. A calendar day is complete at midnight, but a
        // sleep day (noon-to-noon, see HealthConnectReader.sleepDayOf) isn't complete until
        // noon the *next* day, so a 2am run can never see last night's sleep as complete: it's
        // structurally always ~1-2 days stale for sleep specifically, no matter how many times a
        // day it runs, since the boundary itself hasn't been crossed yet. One run shortly after
        // noon covers both boundaries in a single sync instead of needing two schedules.
        private const val TARGET_HOUR_OF_DAY = 14

        /** Enqueues a one-off sync, joining any already-pending/running one rather than stacking up. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Registers the recurring background sync (default: once a day, ~2pm, +/- a 1-hour flex
         * window). Safe to call every app launch — [ExistingPeriodicWorkPolicy.UPDATE] keeps a
         * single schedule alive and just refreshes its parameters in place rather than
         * duplicating or resetting it. This is the only source of unattended background sync;
         * it does not ride on the widget's update cycle, so battery impact is one network call
         * a day, not one every 30 minutes.
         */
        fun schedulePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                SYNC_INTERVAL_DAYS, TimeUnit.DAYS,
                FLEX_WINDOW_HOURS, TimeUnit.HOURS
            )
                .setInitialDelay(millisUntilNextTargetHour(), TimeUnit.MILLISECONDS)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private fun networkConstraints(): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun millisUntilNextTargetHour(): Long {
            val now = ZonedDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(TARGET_HOUR_OF_DAY, 0)).atZone(now.zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next).toMillis()
        }
    }
}
