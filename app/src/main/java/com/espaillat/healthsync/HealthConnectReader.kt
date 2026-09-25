package com.espaillat.healthsync

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.reflect.KClass

/** One row of the target CSV: timestamp_utc,owner,metric,value,unit,source_record_id */
data class CsvRow(
    val timestampUtc: Instant,
    val owner: String,
    val metric: String,
    val value: String,
    val unit: String,
    val sourceRecordId: String,
    /**
     * When true and a row with the same [sourceRecordId] is already on Drive, the existing row is
     * *replaced* if its value differs, instead of the new row being dropped as a duplicate (the
     * default). Only meant for rows whose value can legitimately be revised after first being
     * written -- a daily total that was provisional when synced, or one an authoritative later
     * source (a Samsung Health export) corrects. Never part of the Drive file itself, only of how
     * an upload treats the row; see [toStagedLine] for how it survives [PendingImports] staging.
     */
    val replaceExisting: Boolean = false,
) {
    /** The exact 6-column line written to Drive. */
    fun toCsvLine(zone: ZoneId = ZoneId.systemDefault()): String =
        listOf(localTimestamp(zone), owner, metric, value, unit, sourceRecordId).joinToString(",") { escapeCsv(it) }

    /**
     * The one timestamp format used for every row on Drive: local wall-clock time with an explicit
     * numeric offset, whole seconds, e.g. `2026-09-20T00:00:00+09:00` -- no UTC/local mix for a
     * reader to keep track of. A daily-bucketed row (its ID says `_daily_`) carries the *date* it
     * was bucketed to, internally encoded as that date at UTC midnight, so it's rendered as local
     * midnight of that same date rather than converted like a real instant; everything else is a
     * real instant and is converted to [zone].
     */
    fun localTimestamp(zone: ZoneId = ZoneId.systemDefault()): String =
        if (isDaily()) timestampUtc.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).format(LOCAL_TIMESTAMP_FORMAT)
        else timestampUtc.atZone(zone).format(LOCAL_TIMESTAMP_FORMAT)

    private fun isDaily(): Boolean = sourceRecordId.contains("_daily_")

    /** Which year's file this row belongs in, by the same local date the row displays. */
    fun fileYear(zone: ZoneId = ZoneId.systemDefault()): Int =
        if (isDaily()) timestampUtc.atZone(ZoneOffset.UTC).year else timestampUtc.atZone(zone).year

    /** [toCsvLine]'s columns, but with the timestamp as a plain instant (which round-trips through
     *  [fromCsvLine]) plus a trailing marker when [replaceExisting] is set -- used only for
     *  [PendingImports] staging, so both survive being written to disk and read back. */
    fun toStagedLine(): String {
        val base = listOf(DateTimeFormatter.ISO_INSTANT.format(timestampUtc), owner, metric, value, unit, sourceRecordId)
            .joinToString(",") { escapeCsv(it) }
        return if (replaceExisting) "$base,$STAGED_REPLACE_MARKER" else base
    }

    private fun escapeCsv(field: String): String =
        if (field.contains(',') || field.contains('"') || field.contains('\n')) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }

    companion object {
        const val HEADER = "timestamp_local,owner,metric,value,unit,source_record_id"

        /** The header files were written with before timestamps were standardized on local time;
         *  see [migrateLegacyTimestamps]. */
        const val LEGACY_HEADER = "timestamp_utc,owner,metric,value,unit,source_record_id"
        private val LOCAL_TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")
        private const val STAGED_REPLACE_MARKER = "replace"

        /**
         * Reverses [toCsvLine]. Used by [PendingImports] to read back rows staged from a
         * non-Health-Connect source (currently just the Samsung Health Monitor PDF import) --
         * never called on Drive's own file content, which only ever gets read as raw text for
         * dedup/header purposes, not parsed back into CsvRow. Returns null (never throws) on a
         * line that doesn't split into exactly 6 fields or has an unparseable timestamp, so one
         * corrupted staged line can't take down an entire sync.
         */
        fun fromCsvLine(line: String): CsvRow? {
            val fields = splitCsvLine(line)
            if (fields.size != 6 && !(fields.size == 7 && fields[6] == STAGED_REPLACE_MARKER)) return null
            return try {
                CsvRow(
                    timestampUtc = Instant.parse(fields[0]),
                    owner = fields[1],
                    metric = fields[2],
                    value = fields[3],
                    unit = fields[4],
                    sourceRecordId = fields[5],
                    replaceExisting = fields.size == 7,
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun splitCsvLine(line: String): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        current.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = !inQuotes
                    c == ',' && !inQuotes -> {
                        fields += current.toString()
                        current.clear()
                    }
                    else -> current.append(c)
                }
                i++
            }
            fields += current.toString()
            return fields
        }
    }
}

/**
 * Reads as much of Health Connect as is reachable with a plain read permission and a
 * straightforward value mapping. Deliberately left out, per the same rule: anything needing an
 * extra sensitive permission beyond the normal per-type read grant (exercise GPS routes, which
 * need the separate PERMISSION_READ_EXERCISE_ROUTES consent), anything that doesn't reduce to a
 * scalar-per-row without real custom parsing (full NutritionRecord has 30+ optional nutrient
 * fields), and the reproductive-health category (Menstruation, Ovulation, SexualActivity, and
 * similar record types) -- mechanically trivial, but not applicable to this app's two named
 * users and would otherwise bloat the permission consent screen with irrelevant categories.
 *
 * Everything except point-in-time body measurements (weight, height, body fat, bone mass, lean
 * body mass, basal metabolic rate, blood pressure, exercise sessions -- deliberate spot readings
 * or discrete events, not dense enough to need smoothing) is aggregated to one local calendar
 * day per row -- sums for additive metrics (steps, distance, calories, sleep-stage minutes, ...),
 * and min/avg/max for genuinely fluctuating ones (heart rate, speed/power/cadence, ...). Sleep
 * specifically buckets by a noon-to-noon "sleep day" instead (see [sleepDayOf]) -- sessions
 * normally cross midnight, so calendar-day bucketing would routinely split or misattribute a
 * single night's sleep. This is all for long-run trend tracking (weeks/months/years), not live
 * same-day monitoring: per-record granularity for something like steps produced ~80 rows/day,
 * almost all of it noise for that purpose.
 *
 * A day (or sleep day) is only ever aggregated into an output row once it's actually complete --
 * see [isCompleteCalendarDay]/[isCompleteSleepDay]. This is checked independently of how far the
 * query itself reads (always up to `until`, typically "now"): querying fresh but filtering
 * incomplete buckets out afterward, rather than narrowing the query window, is what lets a sync
 * running at any time of day correctly pick up whatever has newly become complete since the last
 * one. That distinction matters because "complete" means different things for different metrics
 * -- a calendar day is done at midnight, but a sleep day isn't done until noon the next day, so a
 * sync that ran right after midnight and one that ran mid-afternoon need to agree on what's safe
 * to emit without needing two different query windows to get there.
 *
 * **Cursor model (changed 2026-09-07):** each Health Connect record type tracks its own
 * independent cursor (see [SyncState.healthConnectCursor]) rather than one shared value across
 * everything. Real, confirmed-live motivation: `StepsRecord` hit Health Connect's own
 * corrupt-stored-record bug (see [readAllPages]) twice on one account, each time stalling for
 * about 5 days under the old shared-cursor design before *other* record types succeeding
 * happened to drag the shared cursor far enough forward for Steps to clear on its own. A shared
 * cursor cuts both ways, though: it's also what would let one record type's success silently
 * drag a *different*, still-failing record type's cursor forward past data it never actually
 * read, which on a first-ever sync or a "Resync Full History" tap (a multi-week query window,
 * not one day) could silently skip a large stretch of that metric's history in a single jump.
 * Per-record-type cursors avoid both failure modes: no cross-metric contamination in either
 * direction, and [readAllPages] guarantees forward progress (one day at a time, from whatever
 * reference point is available) on every failed attempt, so a stuck metric can't stay stuck
 * forever either.
 */
class HealthConnectReader(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val syncState by lazy { SyncState(context) }

    /**
     * Where each record type's cursor should move to, held in memory by [readAllPages] and only
     * persisted by [commitCursors] once the caller has actually uploaded what this read produced.
     * Advancing a cursor at read time (as this used to) meant a sync whose upload was skipped or
     * failed had already moved past rows that never reached Drive -- and unlike the Samsung export
     * rows, Health Connect rows aren't staged anywhere, so nothing would ever re-read them.
     */
    private val pendingCursors = mutableMapOf<String, Instant>()

    /** Record types read from the very beginning this sync, and whether sleep rows may replace
     *  existing ones regardless of age -- both set only during the one-time sleep re-bucketing. */
    private val fullHistorySources = mutableSetOf<String>()
    private var sleepRebucketPending = false

    /** Persists every cursor move [readSince] queued. Call only after the rows it returned have
     *  been uploaded successfully (or there was nothing to upload). */
    fun commitCursors() {
        pendingCursors.forEach { (recordType, cursor) -> syncState.setHealthConnectCursor(recordType, cursor) }
        pendingCursors.clear()
        if (sleepRebucketPending) {
            syncState.markSleepStartBucketingApplied()
            sleepRebucketPending = false
            fullHistorySources.clear()
        }
    }

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    /**
     * The most recent instant it's safe to advance a cursor to: the more conservative of
     * "start of today" (the boundary for every calendar-day-bucketed metric) and "start of the
     * current, still-in-progress sleep day" (noon-to-noon, see [sleepDayOf]). Always safe to use
     * as a cursor -- never past a boundary that could still receive more data -- but sometimes
     * more conservative than strictly necessary (e.g. calendar-day metrics could technically
     * advance further before noon, when sleep is the binding constraint). The cost of that slack
     * is a bit of redundant local re-scanning on the next sync for metrics unaffected by sleep's
     * boundary, which the dedup backstop in DriveUploader makes harmless.
     */
    fun safeCursorBoundary(): Instant {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val currentSleepDayStart = sleepDayOf(Instant.now()).atTime(12, 0).atZone(zone).toInstant()
        return minOf(todayStart, currentSleepDayStart)
    }

    /**
     * Reads all in-scope records up to [until], flattened into CSV rows tagged with [owner].
     * Each record type queries from its own independently-persisted cursor (see class doc) up to
     * [until], but only ever emits rows for days (or sleep days) that are actually complete as of
     * *now* -- a still-forming today's/current-sleep-day's data gets read from Health Connect
     * same as anything else, then silently dropped before aggregation, rather than excluded by
     * narrowing the query window. That split matters: it's what lets a sync running at any time
     * of day correctly pick up whatever has newly become complete since the last one, without
     * needing the query window and the completeness boundary to be the same thing (they aren't,
     * for sleep -- see [sleepDayOf]).
     */
    suspend fun readSince(until: Instant, owner: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()

        // One-time: sleep rows were first written with each session bucketed by its END time (see
        // [sessionDayOf]). Re-read the whole retained sleep and heart-rate history once and let the
        // sleep rows replace what's on Drive whatever their age; only marked done by
        // [commitCursors], i.e. after the upload succeeded, so a failed attempt simply repeats.
        sleepRebucketPending = !syncState.sleepStartBucketingApplied
        fullHistorySources.clear()
        if (sleepRebucketPending) fullHistorySources += setOf("SleepSessionRecord", "HeartRateRecord")

        // Fetched once, reused below by more than one metric -- SleepSessionRecord by both
        // readSleep and readSleepHeartRate, ExerciseSessionRecord by readExercise alone but kept
        // to the same "fetch once, pass down" style, HeartRateRecord by the plain daily
        // heart_rate aggregation, readSleepHeartRate, and readExercise -- rather than querying
        // Health Connect for the same data more than once for each.
        val sleepSessions = readAllPages(SleepSessionRecord::class, until) { it.endTime }
        val exerciseSessions = readAllPages(ExerciseSessionRecord::class, until) { it.endTime }
        val heartRateSamples = readAllPages(HeartRateRecord::class, until) { it.endTime }
            .flatMap { r -> r.samples.map { it.time to it.beatsPerMinute.toDouble() } }

        // Activity -- additive, one summed row per local day
        rows += readSumDaily(until, owner, StepsRecord::class, "steps", "count", asCount = true, pickLargestOrigin = true, endTime = { it.endTime }) { it.count.toDouble() }
        rows += readSleep(sleepSessions, owner)
        rows += readSleepHeartRate(sleepSessions, owner, heartRateSamples)
        rows += readExercise(exerciseSessions, owner, heartRateSamples)
        rows += readSumDaily(until, owner, DistanceRecord::class, "distance", "meters", pickLargestOrigin = true, endTime = { it.endTime }) { it.distance.inMeters }
        rows += readSumDaily(until, owner, ElevationGainedRecord::class, "elevation_gained", "meters", pickLargestOrigin = true, endTime = { it.endTime }) { it.elevation.inMeters }
        rows += readSumDaily(until, owner, FloorsClimbedRecord::class, "floors_climbed", "floors", asCount = true, pickLargestOrigin = true, endTime = { it.endTime }) { it.floors }
        rows += readSumDaily(until, owner, ActiveCaloriesBurnedRecord::class, "active_calories_burned", "kcal", pickLargestOrigin = true, endTime = { it.endTime }) { it.energy.inKilocalories }
        rows += readSumDaily(until, owner, TotalCaloriesBurnedRecord::class, "total_calories_burned", "kcal", pickLargestOrigin = true, endTime = { it.endTime }) { it.energy.inKilocalories }
        rows += readSumDaily(until, owner, WheelchairPushesRecord::class, "wheelchair_pushes", "count", asCount = true, endTime = { it.endTime }) { it.count.toDouble() }
        rows += readSumDaily(until, owner, HydrationRecord::class, "hydration", "liters", endTime = { it.endTime }) { it.volume.inLiters }

        // Vitals -- fluctuating, daily min/avg/max
        rows += aggregateSamplesDaily(owner, "heart_rate", "bpm", heartRateSamples)
        rows += readStatsDaily(until, owner, RestingHeartRateRecord::class, "resting_heart_rate", "bpm", time = { it.time }) { it.beatsPerMinute.toDouble() }
        rows += readStatsDaily(until, owner, HeartRateVariabilityRmssdRecord::class, "heart_rate_variability_rmssd", "ms", time = { it.time }) { it.heartRateVariabilityMillis }
        rows += readStatsDaily(until, owner, OxygenSaturationRecord::class, "oxygen_saturation", "percent", time = { it.time }) { it.percentage.value }
        rows += readStatsDaily(until, owner, RespiratoryRateRecord::class, "respiratory_rate", "breaths_per_min", time = { it.time }) { it.rate }
        rows += readStatsDaily(until, owner, BodyTemperatureRecord::class, "body_temperature", "celsius", time = { it.time }) { it.temperature.inCelsius }
        rows += readStatsDaily(until, owner, BasalBodyTemperatureRecord::class, "basal_body_temperature", "celsius", time = { it.time }) { it.temperature.inCelsius }
        rows += readStatsDaily(until, owner, BloodGlucoseRecord::class, "blood_glucose", "mg_per_dL", time = { it.time }) { it.level.inMilligramsPerDeciliter }

        // Body measurements -- point-in-time, not aggregated. Not from the Samsung watch (its
        // BIA sensor doesn't pass through Health Connect at all, per the design doc), but a
        // smart scale or other device writing standard Health Connect records for these is just
        // as easy to read as anything else.
        //
        // Blood pressure lives here too, not with the dense fluctuating metrics above --
        // originally grouped with heart rate/speed/etc. on the assumption that it'd need the
        // same noise-reduction treatment, but real data (once the Samsung Health Monitor import
        // existed to actually produce some, see SamsungHealthMonitorPdfImporter) showed 2-3
        // deliberate spot readings a day, not hundreds of continuous samples. Aggregating that
        // into a daily average would blur exactly the individual readings someone would
        // actually want to see, for noise-reduction benefit that was never really there.
        rows += readBloodPressure(until, owner)
        rows += readScalarInstant(until, owner, WeightRecord::class, "weight", "kg", time = { it.time }) { it.weight.inKilograms }
        rows += readScalarInstant(until, owner, HeightRecord::class, "height", "meters", time = { it.time }) { it.height.inMeters }
        rows += readScalarInstant(until, owner, BodyFatRecord::class, "body_fat", "percent", time = { it.time }) { it.percentage.value }
        rows += readScalarInstant(until, owner, BoneMassRecord::class, "bone_mass", "kg", time = { it.time }) { it.mass.inKilograms }
        rows += readScalarInstant(until, owner, LeanBodyMassRecord::class, "lean_body_mass", "kg", time = { it.time }) { it.mass.inKilograms }
        rows += readScalarInstant(until, owner, BasalMetabolicRateRecord::class, "basal_metabolic_rate", "kcal_per_day", time = { it.time }) { it.basalMetabolicRate.inKilocaloriesPerDay }

        // VO2max belongs here too, not with the fluctuating vitals above -- originally grouped
        // with heart rate/HRV/etc. on the assumption it'd need the same daily min/avg/max
        // treatment, but real data showed every single day ever recorded has exactly one
        // Vo2MaxRecord, never more. That's because the watch only computes it as a byproduct of
        // a qualifying outdoor walk/run exercise session (roughly 30+ min), not on a periodic
        // schedule independent of exercise -- one estimate per matching session, same
        // point-in-time nature as the exercise session itself. Splitting one reading into three
        // identical rows was pure noise, not noise reduction.
        rows += readScalarInstant(until, owner, Vo2MaxRecord::class, "vo2_max", "mL_per_kg_min", time = { it.time }) { it.vo2MillilitersPerMinuteKilogram }

        // Dense sample-based interval records -- same daily min/avg/max treatment as heart
        // rate, for the same reason: continuous sampling during workouts would otherwise be by
        // far the dominant row source.
        rows += readAggregatedDaily(until, owner, SpeedRecord::class, "speed", "m_per_s", recordTime = { it.endTime }) { r -> r.samples.map { it.time to it.speed.inMetersPerSecond } }
        rows += readAggregatedDaily(until, owner, PowerRecord::class, "power", "watts", recordTime = { it.endTime }) { r -> r.samples.map { it.time to it.power.inWatts } }
        rows += readAggregatedDaily(until, owner, CyclingPedalingCadenceRecord::class, "cycling_cadence", "rpm", recordTime = { it.endTime }) { r -> r.samples.map { it.time to it.revolutionsPerMinute } }
        rows += readAggregatedDaily(until, owner, StepsCadenceRecord::class, "steps_cadence", "steps_per_min", recordTime = { it.endTime }) { r -> r.samples.map { it.time to it.rate } }

        // A day's row that's still inside the trailing window can still change (late-arriving
        // watch data), so it's allowed to replace what's already on Drive if the value moved.
        // Anything older is insert-only: by then the Samsung export is the authority for the
        // overlapping metrics, and a re-read must never overwrite what it wrote.
        return rows.map { markReplaceableIfRecent(it) }
    }

    private fun isSleepDerivedDaily(id: String): Boolean =
        id.startsWith("sleep_session_duration_daily_") || id.startsWith("sleep_stage_") || id.startsWith("sleep_heart_rate_daily_")

    private fun markReplaceableIfRecent(row: CsvRow): CsvRow {
        if (!row.sourceRecordId.contains("_daily_")) return row
        if (sleepRebucketPending && isSleepDerivedDaily(row.sourceRecordId)) return row.copy(replaceExisting = true)
        val day = row.timestampUtc.atZone(ZoneOffset.UTC).toLocalDate()
        val oldestReplaceable = LocalDate.now(ZoneId.systemDefault()).minusDays(TRAILING_DAYS)
        return if (!day.isBefore(oldestReplaceable)) row.copy(replaceExisting = true) else row
    }


    /**
     * Pages through every record of [recordType] from that record type's own persisted cursor
     * (see [SyncState.healthConnectCursor]) up to [until]. [timestampOf] extracts each record's
     * own natural reference timestamp -- needed to compute where to advance this record type's
     * cursor to, both on success and (see below) on failure.
     */
    private suspend fun <T : Record> readAllPages(recordType: KClass<T>, until: Instant, timestampOf: (T) -> Instant): List<T> {
        val sourceKey = recordType.simpleName ?: "UnknownRecordType"
        val cursor = if (sourceKey in fullHistorySources) null else syncState.healthConnectCursor(sourceKey)
        // Re-reads the last TRAILING_DAYS behind the cursor on every sync, not just what's new:
        // a day's records can land in Health Connect *after* the sync that first passed that day
        // (the watch data isn't pushed into Health Connect until Samsung Health next runs -- seen
        // live, whole days of steps/heart rate/SpO2 arriving hours after the nightly sync had
        // already moved on). Re-emitting rows that are already on Drive is harmless, since the
        // uploader dedups by ID. Skipped while this record type has an outstanding read failure:
        // a corrupt record sitting inside the trailing window would otherwise be re-hit before the
        // forced-forward logic below could ever carry the cursor past it, so the window is
        // dropped until a read succeeds again.
        val since = if (cursor != null && syncState.sourceError(sourceKey) == null) {
            cursor.minus(Duration.ofDays(TRAILING_DAYS))
        } else cursor
        // Health Connect's TimeRangeFilter requires a strictly-after end time -- since == until
        // is not just "empty", it's rejected outright. Guards the degenerate case (e.g. two
        // syncs firing back to back, or a cursor that's already caught all the way up to `until`
        // from a previous forced-forward advance); in practice this rarely trips.
        if (since != null && !since.isBefore(until)) return emptyList()
        val range = TimeRangeFilter.between(since ?: Instant.EPOCH, until)

        val all = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = try {
                client.readRecords(
                    // pageSize deliberately far below the library's own default (confirmed via
                    // decompiling: 1000) -- Health Connect's page-conversion failure is
                    // page-atomic, not record-atomic (confirmed live: the stack trace throws
                    // deep inside converting the whole page's response, before this function
                    // ever gets a chance to add anything from that page to `all`), so one
                    // corrupted record doesn't just cost itself, it costs everything else that
                    // happened to share its page. At the default page size, a dense metric like
                    // Steps (~80 records/day) can lose ~12+ days of perfectly good data to a
                    // single bad record in one shot -- directly why the forced-forward reference
                    // below can fall all the way back to this attempt's starting cursor instead
                    // of landing much closer to the actual bad record. A much smaller page
                    // shrinks that blast radius to roughly a day or so for a metric that dense,
                    // at the cost of a few more (cheap, local) round trips through this loop for
                    // the ordinary all-pages-succeed case.
                    ReadRecordsRequest(recordType = recordType, timeRangeFilter = range, pageToken = pageToken, pageSize = 100)
                )
            } catch (e: Exception) {
                // Health Connect's own client can throw while deserializing a record that's
                // already sitting in the platform store -- observed in practice as
                // IllegalArgumentException("startTime must be before endTime") from a malformed
                // record some other app (Samsung Health, in the one case seen so far) wrote
                // directly, not from anything about this query's window. There's no API to skip
                // just the bad record and keep paginating past it -- the whole read call for
                // that record type aborts. Caught here rather than at the readSince level so it
                // can't take the entire sync down: readAllPages runs once per record type, so
                // one corrupted metric fails on its own and every other metric in the same sync
                // still succeeds normally. Logged loudly (not silently dropped), and persisted
                // via SyncState.setSourceError so it's visible in the app's UI too, not just
                // logcat.
                Log.w(TAG, "readRecords failed for $sourceKey, skipping this metric for this sync", e)
                syncState.setSourceError(
                    sourceKey,
                    "Health Connect couldn't read this record type (${e.message ?: e.javaClass.simpleName}) -- " +
                        "likely a corrupted stored record. Other metrics are unaffected; this one will keep " +
                        "advancing a day at a time past it automatically."
                )
                // Guaranteed forward progress on every failed attempt, one day at a time, rather
                // than either freezing here forever (a literal corrupted record blocks this exact
                // spot permanently -- see class doc) or jumping all the way to `until`/now in one
                // shot (which is what a shared cursor did, and which risks silently skipping
                // weeks of this one metric's history in a single jump on a large first-time
                // backfill). The reference point is whatever's most specific and available:
                // the latest record this attempt *did* manage to read before hitting the bad
                // one, else the cursor this attempt started from, else the very beginning of
                // time for a record type that's never synced anything at all yet. Capped at
                // safeCursorBoundary() so a failure can never push a cursor into "today"/the
                // still-forming sleep day, same safety rule the success path already follows.
                val reference = all.maxOfOrNull(timestampOf) ?: since ?: Instant.EPOCH
                val forcedCursor = minOf(reference.plus(Duration.ofDays(1)), safeCursorBoundary())
                pendingCursors[sourceKey] = maxOf(cursor ?: Instant.EPOCH, forcedCursor)
                return all
            }
            all += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
        syncState.clearSourceError(sourceKey)
        pendingCursors[sourceKey] = maxOf(cursor ?: Instant.EPOCH, safeCursorBoundary())
        return all
    }

    /**
     * Instantaneous body-measurement records (single `time` point): one row per record, no
     * aggregation -- these are point-in-time readings (a scale weigh-in), not a rate to smooth
     * over a day. `time` is passed explicitly rather than inferred from a shared interface --
     * Health Connect's InstantaneousRecord/IntervalRecord marker interfaces exist but are
     * library-internal, not part of the public API surface.
     */
    private suspend fun <T : Record> readScalarInstant(
        until: Instant,
        owner: String,
        recordType: KClass<T>,
        metric: String,
        unit: String,
        time: (T) -> Instant,
        value: (T) -> Double,
    ): List<CsvRow> =
        readAllPages(recordType, until, time).map { r ->
            CsvRow(time(r), owner, metric, formatValue(value(r)), unit, r.metadata.id)
        }

    /** Additive interval metrics (steps, distance, calories, ...): one summed row per local day. */
    private suspend fun <T : Record> readSumDaily(
        until: Instant,
        owner: String,
        recordType: KClass<T>,
        metric: String,
        unit: String,
        asCount: Boolean = false,
        pickLargestOrigin: Boolean = false,
        endTime: (T) -> Instant,
        value: (T) -> Double,
    ): List<CsvRow> {
        val byDay = readAllPages(recordType, until, endTime).groupBy { localDayOf(endTime(it)) }
        return byDay.entries.filter { isCompleteCalendarDay(it.key) }.sortedBy { it.key }.map { (day, records) ->
            // Summing every record is only right when the records are disjoint. For steps,
            // distance, calories and the like, several apps write overlapping records for the same
            // activity -- confirmed live: Samsung Health writes one whole-day total, and Health
            // Connect's own on-phone step counter writes ~100 small records of steps already
            // inside that total, so adding them inflated a day by 40-80%. Health Connect's own
            // aggregate call doesn't fix it either (it only de-overlaps records that overlap in
            // time; a whole-day record overlaps the small ones only partly). So for these metrics
            // the day's value is the single data origin with the largest total -- the app reporting
            // the whole picture -- instead of the sum across origins.
            val total = if (pickLargestOrigin) {
                records.groupBy { it.metadata.dataOrigin.packageName }.values.maxOf { it.sumOf(value) }
            } else records.sumOf(value)
            CsvRow(day.asTimestamp(), owner, metric, formatSum(total, asCount), unit, "${metric}_daily_$day")
        }
    }

    /** Fluctuating scalar-instant metrics (SpO2, respiratory rate, ...): daily min/avg/max. */
    private suspend fun <T : Record> readStatsDaily(
        until: Instant,
        owner: String,
        recordType: KClass<T>,
        metricPrefix: String,
        unit: String,
        time: (T) -> Instant,
        value: (T) -> Double,
    ): List<CsvRow> {
        val byDay = readAllPages(recordType, until, time).groupBy { localDayOf(time(it)) }
        return byDay.entries.filter { isCompleteCalendarDay(it.key) }.sortedBy { it.key }.flatMap { (day, records) ->
            dailyMinAvgMaxRows(day, owner, metricPrefix, unit, records.map(value))
        }
    }

    /**
     * Dense sample-based interval records (heart rate, speed, power, cadence): daily min/avg/max
     * over every raw sample in the day, not just one value per record -- a single interval
     * record can span hours and contain hundreds of samples. [recordTime] is separate from
     * [samplesOf] -- it's the record's own natural timestamp for cursor purposes (see
     * [readAllPages]), not one of its individual samples.
     */
    private suspend fun <T : Record> readAggregatedDaily(
        until: Instant,
        owner: String,
        recordType: KClass<T>,
        metricPrefix: String,
        unit: String,
        recordTime: (T) -> Instant,
        samplesOf: (T) -> List<Pair<Instant, Double>>,
    ): List<CsvRow> = aggregateSamplesDaily(owner, metricPrefix, unit, readAllPages(recordType, until, recordTime).flatMap(samplesOf))

    /** The day-bucketing/filtering/min-avg-max part of [readAggregatedDaily], split out so a
     *  caller that already has samples in hand (heart rate, reused by [readSleepHeartRate] and
     *  [readExercise] too) doesn't have to re-query Health Connect just to reuse this logic. */
    private fun aggregateSamplesDaily(owner: String, metricPrefix: String, unit: String, samples: List<Pair<Instant, Double>>): List<CsvRow> {
        val byDay = samples.groupBy { localDayOf(it.first) }
        return byDay.entries.filter { isCompleteCalendarDay(it.key) }.sortedBy { it.key }.flatMap { (day, daySamples) ->
            dailyMinAvgMaxRows(day, owner, metricPrefix, unit, daySamples.map { it.second })
        }
    }

    private fun dailyMinAvgMaxRows(day: LocalDate, owner: String, metricPrefix: String, unit: String, values: List<Double>): List<CsvRow> {
        val ts = day.asTimestamp()
        val bucketId = "${metricPrefix}_daily_$day"
        return listOf(
            CsvRow(ts, owner, "${metricPrefix}_min", formatValue(values.min()), unit, "$bucketId#min"),
            CsvRow(ts, owner, "${metricPrefix}_avg", formatValue(values.average()), unit, "$bucketId#avg"),
            CsvRow(ts, owner, "${metricPrefix}_max", formatValue(values.max()), unit, "$bucketId#max"),
        )
    }

    /** The phone's local calendar date for an instant -- the day a human actually experienced. */
    private fun localDayOf(instant: Instant): LocalDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()

    /** True once [day] can no longer receive more data -- strictly before today. */
    private fun isCompleteCalendarDay(day: LocalDate): Boolean =
        day.isBefore(LocalDate.now(ZoneId.systemDefault()))

    /** True once [day]'s sleep day (noon-to-noon) can no longer receive more data. */
    private fun isCompleteSleepDay(day: LocalDate): Boolean =
        day.isBefore(sleepDayOf(Instant.now()))

    /**
     * Sleep sessions naturally span midnight (bedtime 11pm, wake 7am) -- bucketing them by
     * calendar day like everything else would routinely split or misattribute a single night's
     * sleep depending on which side of midnight bedtime happened to fall. Noon is used as the
     * day boundary instead: a session ending anytime from noon one day to noon the next belongs
     * to the earlier date's "sleep day", matching how people actually think about "last night's
     * sleep" regardless of exact bedtime. Nobody is asleep at noon under a normal schedule, so
     * that's a safe place to draw the line.
     */
    private fun sleepDayOf(instant: Instant): LocalDate = sleepDayOf(instant, ZoneId.systemDefault())

    /**
     * The sleep day a whole session belongs to: the noon-to-noon day its *start* falls in, not its
     * end. A session that begins at 3:30 AM and runs to 1:19 PM is "last night's sleep" -- by end
     * time it would land in the following day and merge with the next night (seen live: 18-hour
     * "days", and a day with no sleep at all), which is what happens to anyone who sleeps past noon.
     * Start-based also matches how the Samsung export's Sleep Score rows are bucketed.
     */
    private fun sessionDayOf(session: SleepSessionRecord): LocalDate = sleepDayOf(session.startTime)

    /**
     * A daily bucket's date, carried internally as UTC midnight of that same date -- deliberately
     * NOT a true timezone conversion (that would shift the date by the local UTC offset, e.g.
     * local midnight at UTC+9 becomes the previous day at 15:00 UTC). [CsvRow.localTimestamp]
     * recognizes a daily row (its ID says `_daily_`) and writes it to Drive as local midnight of
     * that date, so the file is trivially pivotable by calendar date and a reader sees the date
     * they experienced. See README's timestamp note for the full rationale.
     */
    private fun LocalDate.asTimestamp(): Instant = atStartOfDay(ZoneOffset.UTC).toInstant()

    private fun formatValue(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun formatSum(total: Double, asCount: Boolean): String =
        if (asCount) Math.round(total).toString() else formatValue(total)

    /**
     * Point-in-time, one row per record -- two scalars per record rather than one, so this
     * doesn't fit [readScalarInstant]'s single-value shape, but it's the same treatment: no
     * daily bucketing, no completeness filtering (nothing to protect against re-finalizing an
     * incomplete day, since each record is immutable and independently identified by Health
     * Connect's own record UUID). See the call site's comment for why this moved out of the
     * dense/fluctuating-metric bucket it started in.
     */
    private suspend fun readBloodPressure(until: Instant, owner: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        for (r in readAllPages(BloodPressureRecord::class, until) { it.time }) {
            rows += CsvRow(r.time, owner, "blood_pressure_systolic", formatValue(r.systolic.inMillimetersOfMercury), "mmHg", "${r.metadata.id}#systolic")
            rows += CsvRow(r.time, owner, "blood_pressure_diastolic", formatValue(r.diastolic.inMillimetersOfMercury), "mmHg", "${r.metadata.id}#diastolic")
        }
        return rows
    }

    /**
     * Sleep sessions/stages summed per sleep day (noon-to-noon, see [sleepDayOf]) rather than
     * one row per session or per stage segment -- a night's sleep normally alternates through
     * several light/deep/REM/awake segments, which was by far the single densest metric in the
     * file (confirmed against real data: ~35 `sleep_stage_light` rows/day alone) for no
     * trend-relevant benefit over "total minutes of each stage that sleep day".
     */
    private fun readSleep(sessions: List<SleepSessionRecord>, owner: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()

        sleepMinutesByDay(sessions.map { it.startTime to it.endTime }, ZoneId.systemDefault())
            .filterKeys { isCompleteSleepDay(it) }
            .forEach { (day, totalMinutes) ->
                rows += CsvRow(day.asTimestamp(), owner, "sleep_session_duration", totalMinutes.toString(), "minutes", "sleep_session_duration_daily_$day")
            }

        val stageMinutesByDayAndType = mutableMapOf<Pair<LocalDate, String>, Long>()
        for (session in sessions) {
            // A session's stages all belong to the session's own day. Bucketing each stage by its
            // own end time split a session that crosses noon across two days, so its stage minutes
            // stopped adding up to its duration.
            val day = sessionDayOf(session)
            if (!isCompleteSleepDay(day)) continue
            for (stage in session.stages) {
                val key = day to stageTypeName(stage.stage)
                val minutes = Duration.between(stage.startTime, stage.endTime).toMinutes()
                stageMinutesByDayAndType.merge(key, minutes, Long::plus)
            }
        }
        stageMinutesByDayAndType.entries.sortedBy { it.key.first }.forEach { (key, minutes) ->
            val (day, type) = key
            rows += CsvRow(day.asTimestamp(), owner, "sleep_stage_$type", minutes.toString(), "minutes", "sleep_stage_${type}_daily_$day")
        }

        return rows
    }

    /**
     * Heart rate specifically *during sleep*, not the whole calendar day's range like the plain
     * `heart_rate_*` metric -- this device never exposes a distinct resting-heart-rate figure at
     * all (Samsung computed it internally and, as of a recent update, dropped even the on-screen
     * display of it -- confirmed 2026-08-23, not present in the full-data export either). Sleep
     * HR is the standard fallback several other wearable platforms use as their own definition
     * of "resting heart rate" in the first place, and arguably a *better* one than a discrete
     * post-wake reading: `_min` specifically is the closer analog to genuine physiological rest,
     * since `_avg` still gets pulled up by REM-stage elevations and brief arousals across a
     * night. Both are kept, same as every other min/avg/max metric here, rather than picking one
     * and discarding the rest.
     *
     * Takes [sessions] and [heartRateSamples] as already-fetched lists rather than querying
     * Health Connect itself -- both record types are also needed elsewhere in [readSince]
     * ([SleepSessionRecord] by [readSleep], [HeartRateRecord] by the plain `heart_rate`
     * aggregation), so fetching each once and passing it to every caller that needs it avoids
     * querying Health Connect twice for the same data.
     */
    private fun readSleepHeartRate(sessions: List<SleepSessionRecord>, owner: String, heartRateSamples: List<Pair<Instant, Double>>): List<CsvRow> {
        if (sessions.isEmpty() || heartRateSamples.isEmpty()) return emptyList()
        val hrSamples = heartRateSamples.sortedBy { it.first }

        val byDay = mutableMapOf<LocalDate, MutableList<Double>>()
        for (session in sessions) {
            val day = sessionDayOf(session)
            if (!isCompleteSleepDay(day)) continue
            val inSession = hrSamples.filter { it.first >= session.startTime && it.first <= session.endTime }
            if (inSession.isEmpty()) continue
            byDay.getOrPut(day) { mutableListOf() }.addAll(inSession.map { it.second })
        }

        return byDay.entries.sortedBy { it.key }.flatMap { (day, values) ->
            dailyMinAvgMaxRows(day, owner, "sleep_heart_rate", "bpm", values)
        }
    }

    private fun stageTypeName(stageType: Int): String = when (stageType) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> "awake"
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "awake_in_bed"
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> "sleeping"
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "out_of_bed"
        SleepSessionRecord.STAGE_TYPE_LIGHT -> "light"
        SleepSessionRecord.STAGE_TYPE_DEEP -> "deep"
        SleepSessionRecord.STAGE_TYPE_REM -> "rem"
        else -> "unknown"
    }

    /**
     * Point-in-time, one row group per session -- changed 2026-09-07 from the original
     * daily-summed-minutes-per-type design. There typically aren't many exercise sessions in a
     * day (unlike a dense sample stream), so summing them away loses more than it saves, and
     * collapsing to one daily figure per type is exactly what stood in the way of the two things
     * added alongside this change: a session's own heart-rate range, and its actual name.
     *
     * Heart rate specifically *during* this session -- not the whole day's `heart_rate_*` range,
     * same reasoning as [readSleepHeartRate]: a workout's real min/avg/max gets diluted into
     * meaninglessness once blended with every non-exercise hour that day.
     *
     * `title`/`notes` are real free-text fields on [ExerciseSessionRecord] (confirmed by
     * decompiling the library) that were never captured before this -- exactly what turns a
     * generic type code like `other_workout` back into a real session name (e.g. a custom
     * bodyweight routine that has no matching built-in Health Connect exercise type). Only
     * emitted when actually present and non-blank, same as every other optional field here.
     */
    private fun readExercise(sessions: List<ExerciseSessionRecord>, owner: String, heartRateSamples: List<Pair<Instant, Double>>): List<CsvRow> {
        if (sessions.isEmpty()) return emptyList()
        val hrSamples = heartRateSamples.sortedBy { it.first }
        val rows = mutableListOf<CsvRow>()

        for (session in sessions) {
            val day = localDayOf(session.endTime)
            if (!isCompleteCalendarDay(day)) continue
            val id = session.metadata.id
            val type = exerciseTypeName(session.exerciseType)
            val minutes = Duration.between(session.startTime, session.endTime).toMinutes()
            rows += CsvRow(session.startTime, owner, "exercise_$type", minutes.toString(), "minutes", id)

            session.title?.takeIf { it.isNotBlank() }?.let {
                rows += CsvRow(session.startTime, owner, "exercise_title", it, "text", "$id#title")
            }
            session.notes?.takeIf { it.isNotBlank() }?.let {
                rows += CsvRow(session.startTime, owner, "exercise_notes", it, "text", "$id#notes")
            }

            val inSession = hrSamples.filter { it.first >= session.startTime && it.first <= session.endTime }
            if (inSession.isNotEmpty()) {
                val values = inSession.map { it.second }
                rows += CsvRow(session.startTime, owner, "exercise_heart_rate_min", formatValue(values.min()), "bpm", "$id#hr_min")
                rows += CsvRow(session.startTime, owner, "exercise_heart_rate_avg", formatValue(values.average()), "bpm", "$id#hr_avg")
                rows += CsvRow(session.startTime, owner, "exercise_heart_rate_max", formatValue(values.max()), "bpm", "$id#hr_max")
            }
        }
        return rows
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "biking"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swimming_pool"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swimming_open_water"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "strength_training"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "other_workout"
        else -> "type_$type"
    }

    companion object {
        private const val TAG = "HealthConnectReader"

        /**
         * How many days behind the cursor every sync re-reads, and equally how many recent days a
         * row is still allowed to be revised in place. The Samsung export reconciliation uses the
         * same number as its settling window (it only writes days *older* than this), so the two
         * never both claim the same day.
         */
        const val TRAILING_DAYS = 3L

        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(FloorsClimbedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(WheelchairPushesRecord::class),
            HealthPermission.getReadPermission(HydrationRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RespiratoryRateRecord::class),
            HealthPermission.getReadPermission(BodyTemperatureRecord::class),
            HealthPermission.getReadPermission(BasalBodyTemperatureRecord::class),
            HealthPermission.getReadPermission(BloodGlucoseRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
            HealthPermission.getReadPermission(Vo2MaxRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(HeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(BoneMassRecord::class),
            HealthPermission.getReadPermission(LeanBodyMassRecord::class),
            HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(PowerRecord::class),
            HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
            HealthPermission.getReadPermission(StepsCadenceRecord::class),
            // Lets the nightly background sync (WorkManager, app not in foreground) actually
            // read Health Connect at all on Android versions that gate background reads behind
            // this -- permission-only, no extra code, so per the same rule: add it.
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
            // Removes the "only the last 30 days" cap that otherwise applies the first time an
            // app is granted a given data type, so a fresh install/reinstall can see everything
            // Health Connect is actually retaining, not just a rolling 30-day window from grant
            // time.
            HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        )

        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
}

/** The noon-to-noon "sleep day" [instant] falls in, for the given zone. Internal so it can be tested. */
internal fun sleepDayOf(instant: Instant, zone: ZoneId): LocalDate = instant.atZone(zone).minusHours(12).toLocalDate()

/**
 * Minutes of sleep per sleep day, each session counted once under the day its *start* falls in.
 * Internal so it can be tested against real session times.
 */
internal fun sleepMinutesByDay(sessions: List<Pair<Instant, Instant>>, zone: ZoneId): Map<LocalDate, Long> =
    sessions.groupBy({ sleepDayOf(it.first, zone) }, { Duration.between(it.first, it.second).toMinutes() })
        .mapValues { (_, minutes) -> minutes.sum() }
