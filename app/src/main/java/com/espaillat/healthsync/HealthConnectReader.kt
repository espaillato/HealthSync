package com.espaillat.healthsync

import android.content.Context
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
) {
    fun toCsvLine(): String {
        val ts = DateTimeFormatter.ISO_INSTANT.format(timestampUtc)
        return listOf(ts, owner, metric, value, unit, sourceRecordId).joinToString(",") { escapeCsv(it) }
    }

    private fun escapeCsv(field: String): String =
        if (field.contains(',') || field.contains('"') || field.contains('\n')) {
            "\"${field.replace("\"", "\"\"")}\""
        } else {
            field
        }

    companion object {
        const val HEADER = "timestamp_utc,owner,metric,value,unit,source_record_id"
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
 * body mass, basal metabolic rate) is aggregated to one local calendar day per row -- sums for
 * additive metrics (steps, distance, calories, sleep-stage minutes, exercise minutes, ...), and
 * min/avg/max for fluctuating ones (heart rate, blood pressure, speed/power/cadence, ...). Sleep
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
 */
class HealthConnectReader(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    /**
     * The most recent instant it's safe to advance the sync cursor to: the more conservative of
     * "start of today" (the boundary for every calendar-day-bucketed metric) and "start of the
     * current, still-in-progress sleep day" (noon-to-noon, see [sleepDayOf]). Always safe to use
     * as a cursor -- never past a boundary that could still receive more data -- but sometimes
     * more conservative than strictly necessary (e.g. calendar-day metrics could technically
     * advance further before noon, when sleep is the binding constraint). The cost of that slack
     * is a bit of redundant local re-scanning on the next sync, which the dedup backstop in
     * DriveUploader makes harmless; the alternative would be a separate cursor per metric type,
     * not worth the complexity for what this saves.
     */
    fun safeCursorBoundary(): Instant {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val currentSleepDayStart = sleepDayOf(Instant.now()).atTime(12, 0).atZone(zone).toInstant()
        return minOf(todayStart, currentSleepDayStart)
    }

    /**
     * Reads all in-scope records with an end time after [since] (or all retained history if
     * null) up to [until], flattened into CSV rows tagged with [owner]. Queries as fresh as
     * [until] allows, but only ever emits rows for days (or sleep days) that are actually
     * complete as of *now* -- a still-forming today's/current-sleep-day's data gets read from
     * Health Connect same as anything else, then silently dropped before aggregation, rather
     * than excluded by narrowing the query window. That split matters: it's what lets a sync
     * running at any time of day correctly pick up whatever has newly become complete since the
     * last one, without needing the query window and the completeness boundary to be the same
     * thing (they aren't, for sleep -- see [sleepDayOf]).
     */
    suspend fun readSince(since: Instant?, until: Instant, owner: String): List<CsvRow> {
        // Health Connect's TimeRangeFilter requires a strictly-after end time -- since == until
        // is not just "empty", it's rejected outright. Guards the degenerate case (e.g. two
        // syncs firing back to back); in practice `since` is always some past cursor and `until`
        // is the current instant, so this rarely trips.
        if (since != null && !since.isBefore(until)) return emptyList()
        val range = TimeRangeFilter.between(since ?: Instant.EPOCH, until)
        val rows = mutableListOf<CsvRow>()

        // Activity -- additive, one summed row per local day
        rows += readSumDaily(range, owner, StepsRecord::class, "steps", "count", asCount = true, endTime = { it.endTime }) { it.count.toDouble() }
        rows += readSleep(range, owner)
        rows += readExercise(range, owner)
        rows += readSumDaily(range, owner, DistanceRecord::class, "distance", "meters", endTime = { it.endTime }) { it.distance.inMeters }
        rows += readSumDaily(range, owner, ElevationGainedRecord::class, "elevation_gained", "meters", endTime = { it.endTime }) { it.elevation.inMeters }
        rows += readSumDaily(range, owner, FloorsClimbedRecord::class, "floors_climbed", "floors", asCount = true, endTime = { it.endTime }) { it.floors }
        rows += readSumDaily(range, owner, ActiveCaloriesBurnedRecord::class, "active_calories_burned", "kcal", endTime = { it.endTime }) { it.energy.inKilocalories }
        rows += readSumDaily(range, owner, TotalCaloriesBurnedRecord::class, "total_calories_burned", "kcal", endTime = { it.endTime }) { it.energy.inKilocalories }
        rows += readSumDaily(range, owner, WheelchairPushesRecord::class, "wheelchair_pushes", "count", asCount = true, endTime = { it.endTime }) { it.count.toDouble() }
        rows += readSumDaily(range, owner, HydrationRecord::class, "hydration", "liters", endTime = { it.endTime }) { it.volume.inLiters }

        // Vitals -- fluctuating, daily min/avg/max
        rows += readAggregatedDaily(range, owner, HeartRateRecord::class, "heart_rate", "bpm") { r -> r.samples.map { it.time to it.beatsPerMinute.toDouble() } }
        rows += readStatsDaily(range, owner, RestingHeartRateRecord::class, "resting_heart_rate", "bpm", time = { it.time }) { it.beatsPerMinute.toDouble() }
        rows += readStatsDaily(range, owner, HeartRateVariabilityRmssdRecord::class, "heart_rate_variability_rmssd", "ms", time = { it.time }) { it.heartRateVariabilityMillis }
        rows += readStatsDaily(range, owner, OxygenSaturationRecord::class, "oxygen_saturation", "percent", time = { it.time }) { it.percentage.value }
        rows += readStatsDaily(range, owner, RespiratoryRateRecord::class, "respiratory_rate", "breaths_per_min", time = { it.time }) { it.rate }
        rows += readStatsDaily(range, owner, BodyTemperatureRecord::class, "body_temperature", "celsius", time = { it.time }) { it.temperature.inCelsius }
        rows += readStatsDaily(range, owner, BasalBodyTemperatureRecord::class, "basal_body_temperature", "celsius", time = { it.time }) { it.temperature.inCelsius }
        rows += readStatsDaily(range, owner, BloodGlucoseRecord::class, "blood_glucose", "mg_per_dL", time = { it.time }) { it.level.inMilligramsPerDeciliter }
        rows += readBloodPressure(range, owner)
        rows += readStatsDaily(range, owner, Vo2MaxRecord::class, "vo2_max", "mL_per_kg_min", time = { it.time }) { it.vo2MillilitersPerMinuteKilogram }

        // Body measurements -- point-in-time, not aggregated. Not from the Samsung watch (its
        // BIA sensor doesn't pass through Health Connect at all, per the design doc), but a
        // smart scale or other device writing standard Health Connect records for these is just
        // as easy to read as anything else.
        rows += readScalarInstant(range, owner, WeightRecord::class, "weight", "kg", time = { it.time }) { it.weight.inKilograms }
        rows += readScalarInstant(range, owner, HeightRecord::class, "height", "meters", time = { it.time }) { it.height.inMeters }
        rows += readScalarInstant(range, owner, BodyFatRecord::class, "body_fat", "percent", time = { it.time }) { it.percentage.value }
        rows += readScalarInstant(range, owner, BoneMassRecord::class, "bone_mass", "kg", time = { it.time }) { it.mass.inKilograms }
        rows += readScalarInstant(range, owner, LeanBodyMassRecord::class, "lean_body_mass", "kg", time = { it.time }) { it.mass.inKilograms }
        rows += readScalarInstant(range, owner, BasalMetabolicRateRecord::class, "basal_metabolic_rate", "kcal_per_day", time = { it.time }) { it.basalMetabolicRate.inKilocaloriesPerDay }

        // Dense sample-based interval records -- same daily min/avg/max treatment as heart
        // rate, for the same reason: continuous sampling during workouts would otherwise be by
        // far the dominant row source.
        rows += readAggregatedDaily(range, owner, SpeedRecord::class, "speed", "m_per_s") { r -> r.samples.map { it.time to it.speed.inMetersPerSecond } }
        rows += readAggregatedDaily(range, owner, PowerRecord::class, "power", "watts") { r -> r.samples.map { it.time to it.power.inWatts } }
        rows += readAggregatedDaily(range, owner, CyclingPedalingCadenceRecord::class, "cycling_cadence", "rpm") { r -> r.samples.map { it.time to it.revolutionsPerMinute } }
        rows += readAggregatedDaily(range, owner, StepsCadenceRecord::class, "steps_cadence", "steps_per_min") { r -> r.samples.map { it.time to it.rate } }

        return rows
    }

    private suspend fun <T : Record> readAllPages(recordType: KClass<T>, range: TimeRangeFilter): List<T> {
        val all = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(recordType = recordType, timeRangeFilter = range, pageToken = pageToken)
            )
            all += response.records
            pageToken = response.pageToken
        } while (!pageToken.isNullOrEmpty())
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
        range: TimeRangeFilter,
        owner: String,
        recordType: KClass<T>,
        metric: String,
        unit: String,
        time: (T) -> Instant,
        value: (T) -> Double,
    ): List<CsvRow> =
        readAllPages(recordType, range).map { r ->
            CsvRow(time(r), owner, metric, formatValue(value(r)), unit, r.metadata.id)
        }

    /** Additive interval metrics (steps, distance, calories, ...): one summed row per local day. */
    private suspend fun <T : Record> readSumDaily(
        range: TimeRangeFilter,
        owner: String,
        recordType: KClass<T>,
        metric: String,
        unit: String,
        asCount: Boolean = false,
        endTime: (T) -> Instant,
        value: (T) -> Double,
    ): List<CsvRow> {
        val byDay = readAllPages(recordType, range).groupBy { localDayOf(endTime(it)) }
        return byDay.entries.filter { isCompleteCalendarDay(it.key) }.sortedBy { it.key }.map { (day, records) ->
            val total = records.sumOf(value)
            CsvRow(day.asTimestamp(), owner, metric, formatSum(total, asCount), unit, "${metric}_daily_$day")
        }
    }

    /** Fluctuating scalar-instant metrics (blood pressure components, SpO2, ...): daily min/avg/max. */
    private suspend fun <T : Record> readStatsDaily(
        range: TimeRangeFilter,
        owner: String,
        recordType: KClass<T>,
        metricPrefix: String,
        unit: String,
        time: (T) -> Instant,
        value: (T) -> Double,
    ): List<CsvRow> {
        val byDay = readAllPages(recordType, range).groupBy { localDayOf(time(it)) }
        return byDay.entries.filter { isCompleteCalendarDay(it.key) }.sortedBy { it.key }.flatMap { (day, records) ->
            dailyMinAvgMaxRows(day, owner, metricPrefix, unit, records.map(value))
        }
    }

    /**
     * Dense sample-based interval records (heart rate, speed, power, cadence): daily min/avg/max
     * over every raw sample in the day, not just one value per record -- a single interval
     * record can span hours and contain hundreds of samples.
     */
    private suspend fun <T : Record> readAggregatedDaily(
        range: TimeRangeFilter,
        owner: String,
        recordType: KClass<T>,
        metricPrefix: String,
        unit: String,
        samplesOf: (T) -> List<Pair<Instant, Double>>,
    ): List<CsvRow> {
        val byDay = readAllPages(recordType, range)
            .flatMap(samplesOf)
            .groupBy { localDayOf(it.first) }
        return byDay.entries.filter { isCompleteCalendarDay(it.key) }.sortedBy { it.key }.flatMap { (day, samples) ->
            dailyMinAvgMaxRows(day, owner, metricPrefix, unit, samples.map { it.second })
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
    private fun sleepDayOf(instant: Instant): LocalDate =
        instant.atZone(ZoneId.systemDefault()).minusHours(12).toLocalDate()

    /**
     * A daily bucket's date, encoded as UTC midnight of that same date -- deliberately NOT a
     * true timezone conversion (that would shift the displayed date by the local UTC offset,
     * e.g. local midnight in KST becomes the previous day at 15:00 UTC). The point of daily
     * aggregation is a file that's trivially pivotable by calendar date; a human reading
     * timestamp_utc's date portion should see the same date they experienced, not a UTC-shifted
     * one. See README's daily-aggregation note for the full rationale.
     */
    private fun LocalDate.asTimestamp(): Instant = atStartOfDay(ZoneOffset.UTC).toInstant()

    private fun formatValue(v: Double): String = String.format(Locale.US, "%.1f", v)

    private fun formatSum(total: Double, asCount: Boolean): String =
        if (asCount) Math.round(total).toString() else formatValue(total)

    /** Two scalars per record, not one -- doesn't fit the generic stats-daily helper. */
    private suspend fun readBloodPressure(range: TimeRangeFilter, owner: String): List<CsvRow> {
        val records = readAllPages(BloodPressureRecord::class, range)
        val rows = mutableListOf<CsvRow>()
        records.groupBy { localDayOf(it.time) }
            .filterKeys { isCompleteCalendarDay(it) }
            .forEach { (day, dayRecords) ->
                rows += dailyMinAvgMaxRows(day, owner, "blood_pressure_systolic", "mmHg", dayRecords.map { it.systolic.inMillimetersOfMercury })
                rows += dailyMinAvgMaxRows(day, owner, "blood_pressure_diastolic", "mmHg", dayRecords.map { it.diastolic.inMillimetersOfMercury })
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
    private suspend fun readSleep(range: TimeRangeFilter, owner: String): List<CsvRow> {
        val sessions = readAllPages(SleepSessionRecord::class, range)
        val rows = mutableListOf<CsvRow>()

        sessions.groupBy { sleepDayOf(it.endTime) }
            .filterKeys { isCompleteSleepDay(it) }
            .forEach { (day, daySessions) ->
                val totalMinutes = daySessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                rows += CsvRow(day.asTimestamp(), owner, "sleep_session_duration", totalMinutes.toString(), "minutes", "sleep_session_duration_daily_$day")
            }

        val stageMinutesByDayAndType = mutableMapOf<Pair<LocalDate, String>, Long>()
        for (session in sessions) {
            for (stage in session.stages) {
                val day = sleepDayOf(stage.endTime)
                if (!isCompleteSleepDay(day)) continue
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

    /** Exercise minutes summed per local day, per exercise type (two runs the same day -> one row). */
    private suspend fun readExercise(range: TimeRangeFilter, owner: String): List<CsvRow> {
        val minutesByDayAndType = mutableMapOf<Pair<LocalDate, String>, Long>()
        for (r in readAllPages(ExerciseSessionRecord::class, range)) {
            val day = localDayOf(r.endTime)
            if (!isCompleteCalendarDay(day)) continue
            val key = day to exerciseTypeName(r.exerciseType)
            val minutes = Duration.between(r.startTime, r.endTime).toMinutes()
            minutesByDayAndType.merge(key, minutes, Long::plus)
        }
        return minutesByDayAndType.entries.sortedBy { it.key.first }.map { (key, minutes) ->
            val (day, type) = key
            CsvRow(day.asTimestamp(), owner, "exercise_$type", minutes.toString(), "minutes", "exercise_${type}_daily_$day")
        }
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
