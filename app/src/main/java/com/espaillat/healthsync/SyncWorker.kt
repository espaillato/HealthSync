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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
        // Truncated to the start of today (local time) so a daily bucket (see
        // HealthConnectReader's readSumDaily/readStatsDaily/readAggregatedDaily) never gets
        // split across two sync runs -- a day is only ever aggregated once it's actually over,
        // by whichever sync first reads past its end. Concretely: this excludes all of today's
        // still-accumulating data from every sync until tomorrow, when today becomes a complete
        // past day. That's intentional, not a bug -- daily aggregates are for trend tracking
        // (weeks/months/years), not a live same-day total, and it pairs naturally with the
        // nightly ~2am schedule (by then yesterday is long since complete). If since is still
        // ahead of this (e.g. two syncs on the same local day), HealthConnectReader.readSince
        // short-circuits to empty rather than querying Health Connect with degenerate bounds.
        val until = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant()

        return try {
            val rows = reader.readSince(since, until, owner.label)
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
            // into a previously-empty window still gets picked up on the next sync. Never move
            // the cursor backward either way -- right after upgrading from an older
            // differently-truncated cursor to this one, `until` can briefly land earlier than an
            // existing cursor, and writing that back would cause the next sync to re-read
            // already-synced data.
            if (rows.isNotEmpty() && (since == null || until.isAfter(since))) {
                syncState.lastSyncCursor = until
            }
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
        /** Unique name for on-demand syncs (app launch, manual "Sync Now" tap). */
        const val MANUAL_WORK_NAME = "health_sync_manual_work"

        /** Unique name for the nightly background schedule (see [schedulePeriodicSync]). */
        const val PERIODIC_WORK_NAME = "health_sync_periodic_work"

        const val KEY_ERROR = "error"
        const val KEY_ROWS_SYNCED = "rows_synced"

        /** How often the background sync runs on its own, absent any user-initiated trigger.
         *  Once a day is already more than enough for step/HR/sleep/exercise data; bump this
         *  to 2-3 if even-less-frequent background syncing is preferred. */
        private const val SYNC_INTERVAL_DAYS = 1L
        private const val FLEX_WINDOW_HOURS = 1L
        private const val TARGET_HOUR_OF_DAY = 2 // run around 2am local time, not mid-battery-use

        /** Enqueues a one-off sync, joining any already-pending/running one rather than stacking up. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Registers the recurring background sync (default: nightly, ~2am, +/- a 1-hour flex
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
