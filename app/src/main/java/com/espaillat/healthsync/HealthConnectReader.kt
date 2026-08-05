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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
 */
class HealthConnectReader(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    /**
     * Reads all in-scope records with an end time after [since] (or all retained history if
     * null) up to [until], flattened into CSV rows tagged with [owner].
     */
    suspend fun readSince(since: Instant?, until: Instant, owner: String): List<CsvRow> {
        // Health Connect's TimeRangeFilter requires a strictly-after end time -- since == until
        // is not just "empty", it's rejected outright. That happens legitimately whenever a
        // sync runs again within the same hour as the last one (e.g. a manual "Sync Now" right
        // after the nightly sync already advanced the cursor to this hour's boundary): there's
        // nothing new to read yet, so skip Health Connect entirely rather than querying it with
        // degenerate bounds.
        if (since != null && !since.isBefore(until)) return emptyList()
        val range = TimeRangeFilter.between(since ?: Instant.EPOCH, until)
        val rows = mutableListOf<CsvRow>()

        // Activity
        rows += readSteps(range, owner)
        rows += readSleep(range, owner)
        rows += readExercise(range, owner)
        rows += readScalarInterval(range, owner, DistanceRecord::class, "distance", "meters", endTime = { it.endTime }) { it.distance.inMeters }
        rows += readScalarInterval(range, owner, ElevationGainedRecord::class, "elevation_gained", "meters", endTime = { it.endTime }) { it.elevation.inMeters }
        rows += readScalarInterval(range, owner, FloorsClimbedRecord::class, "floors_climbed", "floors", endTime = { it.endTime }) { it.floors }
        rows += readScalarInterval(range, owner, ActiveCaloriesBurnedRecord::class, "active_calories_burned", "kcal", endTime = { it.endTime }) { it.energy.inKilocalories }
        rows += readScalarInterval(range, owner, TotalCaloriesBurnedRecord::class, "total_calories_burned", "kcal", endTime = { it.endTime }) { it.energy.inKilocalories }
        rows += readScalarInterval(range, owner, WheelchairPushesRecord::class, "wheelchair_pushes", "count", endTime = { it.endTime }) { it.count.toDouble() }
        rows += readScalarInterval(range, owner, HydrationRecord::class, "hydration", "liters", endTime = { it.endTime }) { it.volume.inLiters }

        // Vitals -- low frequency (typically a handful of readings/day), no aggregation needed
        rows += readHeartRate(range, owner)
        rows += readScalarInstant(range, owner, RestingHeartRateRecord::class, "resting_heart_rate", "bpm", time = { it.time }) { it.beatsPerMinute.toDouble() }
        rows += readScalarInstant(range, owner, HeartRateVariabilityRmssdRecord::class, "heart_rate_variability_rmssd", "ms", time = { it.time }) { it.heartRateVariabilityMillis }
        rows += readScalarInstant(range, owner, OxygenSaturationRecord::class, "oxygen_saturation", "percent", time = { it.time }) { it.percentage.value }
        rows += readScalarInstant(range, owner, RespiratoryRateRecord::class, "respiratory_rate", "breaths_per_min", time = { it.time }) { it.rate }
        rows += readScalarInstant(range, owner, BodyTemperatureRecord::class, "body_temperature", "celsius", time = { it.time }) { it.temperature.inCelsius }
        rows += readScalarInstant(range, owner, BasalBodyTemperatureRecord::class, "basal_body_temperature", "celsius", time = { it.time }) { it.temperature.inCelsius }
        rows += readScalarInstant(range, owner, BloodGlucoseRecord::class, "blood_glucose", "mg_per_dL", time = { it.time }) { it.level.inMilligramsPerDeciliter }
        rows += readBloodPressure(range, owner)
        rows += readScalarInstant(range, owner, Vo2MaxRecord::class, "vo2_max", "mL_per_kg_min", time = { it.time }) { it.vo2MillilitersPerMinuteKilogram }

        // Body measurements -- not from the Samsung watch (its BIA sensor doesn't pass through
        // Health Connect at all, per the design doc), but a smart scale or other device writing
        // standard Health Connect records for these is just as easy to read as anything else.
        rows += readScalarInstant(range, owner, WeightRecord::class, "weight", "kg", time = { it.time }) { it.weight.inKilograms }
        rows += readScalarInstant(range, owner, HeightRecord::class, "height", "meters", time = { it.time }) { it.height.inMeters }
        rows += readScalarInstant(range, owner, BodyFatRecord::class, "body_fat", "percent", time = { it.time }) { it.percentage.value }
        rows += readScalarInstant(range, owner, BoneMassRecord::class, "bone_mass", "kg", time = { it.time }) { it.mass.inKilograms }
        rows += readScalarInstant(range, owner, LeanBodyMassRecord::class, "lean_body_mass", "kg", time = { it.time }) { it.mass.inKilograms }
        rows += readScalarInstant(range, owner, BasalMetabolicRateRecord::class, "basal_metabolic_rate", "kcal_per_day", time = { it.time }) { it.basalMetabolicRate.inKilocaloriesPerDay }

        // Dense sample-based interval records -- same hourly min/avg/max treatment as heart
        // rate, for the same reason: continuous sampling during workouts would otherwise be by
        // far the dominant row source.
        rows += readAggregatedHourly(range, owner, SpeedRecord::class, "speed", "m_per_s") { r -> r.samples.map { it.time to it.speed.inMetersPerSecond } }
        rows += readAggregatedHourly(range, owner, PowerRecord::class, "power", "watts") { r -> r.samples.map { it.time to it.power.inWatts } }
        rows += readAggregatedHourly(range, owner, CyclingPedalingCadenceRecord::class, "cycling_cadence", "rpm") { r -> r.samples.map { it.time to it.revolutionsPerMinute } }
        rows += readAggregatedHourly(range, owner, StepsCadenceRecord::class, "steps_cadence", "steps_per_min") { r -> r.samples.map { it.time to it.rate } }

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
     * Instantaneous vitals/body-measurement records (single `time` point): one row per record.
     * Covers every such type used below except BloodPressureRecord, which has two values per
     * record rather than one. `time` is passed explicitly rather than inferred from a shared
     * interface -- Health Connect's InstantaneousRecord/IntervalRecord marker interfaces exist
     * but are library-internal, not part of the public API surface.
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

    /** Interval records (`startTime`/`endTime`, no dense sub-sampling): one row per record. */
    private suspend fun <T : Record> readScalarInterval(
        range: TimeRangeFilter,
        owner: String,
        recordType: KClass<T>,
        metric: String,
        unit: String,
        endTime: (T) -> Instant,
        value: (T) -> Double,
    ): List<CsvRow> =
        readAllPages(recordType, range).map { r ->
            CsvRow(endTime(r), owner, metric, formatValue(value(r)), unit, r.metadata.id)
        }

    /**
     * Shared hourly min/avg/max bucketing for dense sample-based records (speed, power, cycling
     * cadence, steps cadence) -- the same treatment as heart rate, factored out since it's now
     * used five times. Heart rate keeps its own hand-written version below rather than being
     * folded into this: it predates this helper, is already verified end-to-end against real
     * uploaded data, and its min/max are natively integer bpm rather than doubles -- not worth
     * the risk of touching proven code to save a few lines.
     */
    private suspend fun <T : Record> readAggregatedHourly(
        range: TimeRangeFilter,
        owner: String,
        recordType: KClass<T>,
        metricPrefix: String,
        unit: String,
        samplesOf: (T) -> List<Pair<Instant, Double>>,
    ): List<CsvRow> {
        val byHour = readAllPages(recordType, range)
            .flatMap(samplesOf)
            .groupBy { it.first.truncatedTo(ChronoUnit.HOURS) }

        return byHour.entries.sortedBy { it.key }.flatMap { (bucketStart, samples) ->
            val values = samples.map { it.second }
            val bucketId = "${metricPrefix}_hourly_${bucketStart.epochSecond}"
            listOf(
                CsvRow(bucketStart, owner, "${metricPrefix}_min", formatValue(values.min()), unit, "$bucketId#min"),
                CsvRow(bucketStart, owner, "${metricPrefix}_avg", formatValue(values.average()), unit, "$bucketId#avg"),
                CsvRow(bucketStart, owner, "${metricPrefix}_max", formatValue(values.max()), unit, "$bucketId#max"),
            )
        }
    }

    private fun formatValue(v: Double): String = String.format(Locale.US, "%.1f", v)

    private suspend fun readSteps(range: TimeRangeFilter, owner: String): List<CsvRow> =
        readAllPages(StepsRecord::class, range).map { r ->
            CsvRow(
                timestampUtc = r.endTime,
                owner = owner,
                metric = "steps",
                value = r.count.toString(),
                unit = "count",
                sourceRecordId = r.metadata.id,
            )
        }

    /**
     * Continuous heart-rate sampling is dense enough (one sample every ~20-30s on a Galaxy
     * Watch) that writing one CSV row per raw sample would produce ~100k rows/month from HR
     * alone -- by far the dominant driver of file growth, confirmed against a real 30-day
     * backfill. Bucketing into hourly min/avg/max keeps the same six-column schema and cuts
     * that by roughly 45x. Buckets are UTC-hour aligned (not per-owner local time) so the
     * result is deterministic regardless of the phone's timezone.
     *
     * SyncWorker truncates `until` down to the current hour boundary before calling this, so
     * a bucket's samples are never split across two sync runs -- each hour is aggregated
     * exactly once, from whichever sync run first reads records past its end.
     */
    private suspend fun readHeartRate(range: TimeRangeFilter, owner: String): List<CsvRow> {
        val samplesByHour = readAllPages(HeartRateRecord::class, range)
            .flatMap { it.samples }
            .groupBy { it.time.truncatedTo(ChronoUnit.HOURS) }

        return samplesByHour.entries.sortedBy { it.key }.flatMap { (bucketStart, samples) ->
            val bpms = samples.map { it.beatsPerMinute }
            val bucketId = "hr_hourly_${bucketStart.epochSecond}"
            listOf(
                CsvRow(bucketStart, owner, "heart_rate_min", bpms.min().toString(), "bpm", "$bucketId#min"),
                CsvRow(
                    bucketStart, owner, "heart_rate_avg",
                    String.format(Locale.US, "%.1f", bpms.average()), "bpm", "$bucketId#avg"
                ),
                CsvRow(bucketStart, owner, "heart_rate_max", bpms.max().toString(), "bpm", "$bucketId#max"),
            )
        }
    }

    /** Two scalars per record, not one -- doesn't fit the generic scalar-instant helper. */
    private suspend fun readBloodPressure(range: TimeRangeFilter, owner: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        for (r in readAllPages(BloodPressureRecord::class, range)) {
            rows += CsvRow(r.time, owner, "blood_pressure_systolic", formatValue(r.systolic.inMillimetersOfMercury), "mmHg", "${r.metadata.id}#systolic")
            rows += CsvRow(r.time, owner, "blood_pressure_diastolic", formatValue(r.diastolic.inMillimetersOfMercury), "mmHg", "${r.metadata.id}#diastolic")
        }
        return rows
    }

    private suspend fun readSleep(range: TimeRangeFilter, owner: String): List<CsvRow> {
        val rows = mutableListOf<CsvRow>()
        for (r in readAllPages(SleepSessionRecord::class, range)) {
            rows += CsvRow(
                timestampUtc = r.endTime,
                owner = owner,
                metric = "sleep_session_duration",
                value = Duration.between(r.startTime, r.endTime).toMinutes().toString(),
                unit = "minutes",
                sourceRecordId = r.metadata.id,
            )
            r.stages.forEachIndexed { index, stage ->
                rows += CsvRow(
                    timestampUtc = stage.endTime,
                    owner = owner,
                    metric = "sleep_stage_${stageTypeName(stage.stage)}",
                    value = Duration.between(stage.startTime, stage.endTime).toMinutes().toString(),
                    unit = "minutes",
                    sourceRecordId = "${r.metadata.id}#stage$index",
                )
            }
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

    private suspend fun readExercise(range: TimeRangeFilter, owner: String): List<CsvRow> =
        readAllPages(ExerciseSessionRecord::class, range).map { r ->
            CsvRow(
                timestampUtc = r.endTime,
                owner = owner,
                metric = "exercise_${exerciseTypeName(r.exerciseType)}",
                value = Duration.between(r.startTime, r.endTime).toMinutes().toString(),
                unit = "minutes",
                sourceRecordId = r.metadata.id,
            )
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
