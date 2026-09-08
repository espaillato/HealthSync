package com.espaillat.healthsync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** WorkManager worker: HealthConnectReader -> DriveUploader, cursor only advances on success. */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = mutex.withLock {
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

        // Query as fresh as possible -- completeness (has today, or the current sleep day,
        // actually finished?) is HealthConnectReader's job, not the query window's. See
        // HealthConnectReader.readSince's doc for why those are deliberately separate: a
        // calendar day and a sleep day (noon-to-noon) become "complete" at different wall-clock
        // times, so narrowing the query window to one shared cutoff can't correctly serve both
        // regardless of what that cutoff is.
        //
        // No `since` computed here anymore (changed 2026-09-07) -- each Health Connect record
        // type now tracks and advances its own independent cursor internally (see
        // HealthConnectReader's class doc), rather than this worker managing one shared value
        // for all of them.
        val now = Instant.now()

        // Folder-scan for a fresh Samsung Health full-data export (HRV, respiratory rate --
        // neither reaches Health Connect at all, see SamsungHealthExportImporter's doc). A cheap
        // no-op directory listing when nothing's been exported since the last sync; stages
        // straight into PendingImports so it's picked up by the snapshot right below in the same
        // run, rather than deferred to the next one.
        SamsungHealthExportImporter.stageAvailableExports(applicationContext, owner)

        // Snapshotted up front, not re-read after uploading -- see PendingImports' own doc for
        // why: only the files captured in *this* snapshot get cleared below, so anything staged
        // while this worker is running (a share arriving mid-run, say) isn't deleted before it's
        // ever actually been uploaded.
        val pendingSnapshot = PendingImports.snapshot(applicationContext)
        val pendingRows = pendingSnapshot.flatMap { it.second }

        return try {
            val hcRows = reader.readSince(now, owner)
            // Health Connect and the staged Samsung Health Monitor import (see PendingImports)
            // are two independent data sources feeding the same upload -- deliberately one
            // appendRows call for both rather than two separate uploads, so there's a single
            // place that authenticates, dedups, and reports status for everything this app
            // writes to Drive.
            val allRows = hcRows + pendingRows
            if (allRows.isNotEmpty()) {
                // Only the upload itself needs Wi-Fi -- everything above (Health Connect reads,
                // the Samsung export folder scan/parse/stage) is local disk/DB work with nothing
                // to do with network state, so it isn't gated on this at all. Checked here,
                // right before the one step that actually needs it, rather than as a WorkManager
                // Constraint on the whole job: a Constraints.NetworkType requirement doesn't just
                // delay a job's *start*, it stops an *already-running* one the instant the
                // condition briefly stops holding -- confirmed live, a momentary Wi-Fi drop
                // around a screen lock/unlock was enough to cancel and restart the entire sync,
                // including the local processing that never needed network at all. Skipping just
                // this one step leaves syncState completely untouched below -- the cursor stays
                // where it was, and both Health Connect rows and the Samsung-export rows (already
                // safely staged in PendingImports by this point regardless of what happens here)
                // simply wait for the next sync that does have Wi-Fi, rather than this attempt
                // being recorded as either a success (the data isn't actually on Drive yet) or a
                // failure (nothing actually went wrong).
                if (!isOnUnmeteredNetwork(applicationContext)) {
                    Log.i(TAG, "Parsed ${allRows.size} new row(s) but no Wi-Fi available right now -- upload deferred to the next sync.")
                    return@withLock Result.success()
                }
                DriveUploader(applicationContext).appendRows(owner, allRows, syncState)
            }
            // Pending-import files are only cleared once appendRows above has actually
            // succeeded -- an exception there is caught below and returns a failure before this
            // line runs, leaving the staged files in place for the next attempt. Same
            // "only advance state on success" rule the cursor itself follows just below.
            if (pendingSnapshot.isNotEmpty()) {
                PendingImports.clear(pendingSnapshot.map { it.first })
            }

            // Health Connect's own per-record-type cursors already advanced (or, on a failure
            // isolated to one record type, forced forward a bounded amount) inside readSince
            // above -- nothing left to do here for that. Health Connect write sources can
            // backfill *already-passed* timestamps -- e.g. Samsung Health, the moment it's first
            // granted write access, wrote several hours of that day's heart-rate history
            // retroactively -- which is exactly why each record type's cursor there only ever
            // advances on its own success, never gets pushed forward by some other metric's
            // success, and never moves backward.
            syncState.lastSyncTimestamp = now
            syncState.lastSyncStatus = SyncStatus.SUCCESS
            syncState.lastSyncError = null
            Result.success(workDataOf(KEY_ROWS_SYNCED to allRows.size))
        } catch (e: CancellationException) {
            // WorkManager stopping this worker mid-run -- e.g. schedulePeriodicSync()'s own
            // CANCEL_AND_REENQUEUE re-registering the periodic schedule at the exact moment it's
            // mid-execution (a known, already-documented race there), or the OS deciding to stop
            // it for any other reason -- surfaces as coroutine cancellation, not a real failure.
            // (No longer a network-constraint issue specifically -- see enqueue()'s doc for why
            // that's not a WorkManager Constraint at all anymore.) WorkManager automatically
            // retries the same unit of work once conditions are met again, often within seconds.
            // Recording this as SyncStatus.ERROR would be actively
            // misleading: confirmed live, a routine constraint interruption right around a
            // screen lock/unlock briefly showed "last sync failed" even though the automatic
            // retry succeeded moments later with no user action at all. Rethrown deliberately
            // rather than swallowed, syncState left untouched -- letting a CancellationException
            // propagate is also a real Kotlin-coroutines correctness requirement on its own
            // (structured concurrency depends on it), not just a cosmetic fix.
            throw e
        } catch (e: Exception) {
            // Full stack trace to logcat -- the UI only ever gets to show e.message, which for
            // a generic IllegalArgumentException-style message ("startTime must be before
            // endTime") is nowhere near enough to find the actual throw site. Diagnosing that
            // exact case without this cost a redundant round trip once already.
            Log.e(TAG, "Sync failed", e)
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
        private const val TAG = "HealthSyncWorker"

        // Real concurrency bug, confirmed live (2026-08-23): WorkManager's ExistingWorkPolicy.KEEP
        // (see enqueue() below) only de-duplicates *within* one unique work name -- it does
        // nothing to stop the manual/auto-triggered sync (MANUAL_WORK_NAME) and the daily
        // periodic sync (PERIODIC_WORK_NAME) from running truly concurrently if their schedules
        // happen to overlap, since those are two entirely separate unique-work chains that both
        // invoke this same class. Observed directly on a real device: six simultaneous
        // executions of the Samsung-export processing step, on six different threads in the same
        // process, all racing to read/delete the same export folder -- one got the real data,
        // the other five found nothing left and logged a harmless-looking but symptomatic
        // "0 rows" each. Deliberately a companion-object (shared across every instance) lock,
        // not an instance field -- WorkManager creates a fresh SyncWorker object per execution,
        // so an instance-level Mutex would give each concurrent run its own separate lock and
        // provide no real exclusion at all. This forces every doWork() call in this process to
        // run strictly one at a time regardless of which WorkManager chain triggered it: a
        // second call arriving while one's already in flight simply waits its turn instead of
        // racing it.
        private val mutex = Mutex()

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
        // Afternoon, not the middle of the night -- deliberately not "2am" despite that being
        // the obvious off-peak-battery choice. A calendar day is complete at midnight, but a
        // sleep day (noon-to-noon, see HealthConnectReader.sleepDayOf) isn't complete until
        // noon the *next* day, so a 2am run can never see last night's sleep as complete: it's
        // structurally always ~1-2 days stale for sleep specifically, no matter how many times a
        // day it runs, since the boundary itself hasn't been crossed yet. One run shortly after
        // noon covers both boundaries in a single sync instead of needing two schedules.
        private const val TARGET_HOUR_OF_DAY = 14

        /**
         * Enqueues a one-off sync, joining any already-pending/running one rather than stacking
         * up. Deliberately no network Constraints here at all (removed 2026-08-23) -- local
         * processing (Health Connect reads, the Samsung export scan/parse/stage) has nothing to
         * do with network state and shouldn't wait on it or be interrupted by it; only the
         * actual Drive upload needs Wi-Fi, checked directly in [doWork] right before that one
         * step. A WorkManager Constraint doesn't just delay a job's start, it stops an
         * already-running one the instant the condition briefly stops holding -- confirmed live,
         * that was cancelling and restarting entire syncs on a momentary Wi-Fi drop, taking the
         * local processing down with it for no reason.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Registers the recurring background sync (default: once a day, ~2pm). Safe to call
         * every app launch. Uses [ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE], not UPDATE --
         * verified on a real device that UPDATE does not reliably re-anchor an already-scheduled
         * periodic work to a freshly computed initial delay (the previous schedule, set up under
         * this app's original 2am target, was still landing on roughly its old cadence after
         * switching the constant to 2pm and calling UPDATE). CANCEL_AND_REENQUEUE guarantees the
         * new target actually takes hold, at the cost of a narrow theoretical race if the app
         * happens to be reopened at the exact moment the periodic worker is mid-execution (it
         * would be cancelled and simply retried next cycle -- the cursor-safety logic already
         * tolerates an interrupted/skipped sync gracefully).
         *
         * Deliberately NOT passing a flex window here (i.e. not using the
         * (interval, intervalUnit, flex, flexUnit) constructor). That was tried and verified on
         * a real device to backfire: WorkManager only lets a flex window narrow *where inside a
         * period* a run can land, it does not let setInitialDelay skip ahead within that period --
         * the first execution still waits out (interval - flex) beyond the initial delay, same as
         * every later one. With a 1-hour flex on a 1-day interval that silently added ~23 extra
         * hours to the very first run (observed: computed initial delay ~18h, actual
         * "Minimum latency" in `dumpsys jobscheduler` ~41h -- 18h + (24h - 1h)). Omitting the
         * flex makes the effective flex equal to the full interval, so the first run fires right
         * at the initial delay as intended; the OS still has the entire day to batch/optimize
         * exactly as it would with no flex requested at all.
         */
        fun schedulePeriodicSync(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(SYNC_INTERVAL_DAYS, TimeUnit.DAYS)
                .setInitialDelay(millisUntilNextTargetHour(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        }

        // Checked directly (not via a WorkManager Constraint -- see enqueue()'s doc for why)
        // right before the one step that actually needs network at all: the Drive upload.
        // UNMETERED specifically (Wi-Fi in practice), not just "any connection" -- with per-year
        // rotation the file a sync touches can still run to a couple MB by the end of a year,
        // and both the daily background sync and the auto-sync-on-app-open would otherwise
        // happily burn mobile data to move that, with no user-facing indication it happened.
        // Worst case this just delays the upload until Wi-Fi is available; the cursor/
        // PendingImports-staging logic already tolerates that with no data loss.
        private fun isOnUnmeteredNetwork(context: Context): Boolean {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        private fun millisUntilNextTargetHour(): Long {
            val now = ZonedDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(TARGET_HOUR_OF_DAY, 0)).atZone(now.zone)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next).toMillis()
        }
    }
}
