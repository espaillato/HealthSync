package com.espaillat.healthsync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
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

/** Reads steps/heart-rate/sleep/exercise from Health Connect since a cursor and flattens to CSV rows. */
class HealthConnectReader(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    /**
     * Reads all in-scope records with an end time after [since] (or all retained history if
     * null) up to [until], flattened into CSV rows tagged with [owner].
     */
    suspend fun readSince(since: Instant?, until: Instant, owner: String): List<CsvRow> {
        val range = TimeRangeFilter.between(since ?: Instant.EPOCH, until)
        val rows = mutableListOf<CsvRow>()
        rows += readSteps(range, owner)
        rows += readHeartRate(range, owner)
        rows += readSleep(range, owner)
        rows += readExercise(range, owner)
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
        )

        fun isAvailable(context: Context): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }
}
