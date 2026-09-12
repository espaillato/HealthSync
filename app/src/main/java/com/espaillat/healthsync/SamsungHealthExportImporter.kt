package com.espaillat.healthsync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Imports Samsung Health's own full personal-data export (Settings -> Download personal data --
 * NOT Samsung Health Monitor's single-metric PDF share used for blood pressure) as the only
 * source for several metrics that never reach Health Connect at all: heart rate variability,
 * respiratory rate, stress score, advanced glycation end-products (AGE), and post-exercise heart
 * rate recovery. Confirmed directly on-device for HRV/respiratory rate: Health Connect's own
 * "Data and access" screen lists no such category whatsoever, from any app, not just this one --
 * Samsung computes these purely internally for its Energy Score / Sleep Score features and never
 * writes the underlying values out through the platform's standard record types. Same wall as
 * blood pressure and body composition, just with its own way back in, since this one export
 * happens to include all of it as a side effect of being a full dump of every internal Samsung
 * Health data type. Blood pressure is *also* pulled from this same export, as a second source
 * alongside the PDF-share import -- see [parseBloodPressure]'s doc for how the two stay deduped
 * against each other rather than double-counting the same real-world reading.
 *
 * Deliberately not a share-target like the blood-pressure PDF import: the export lands in a
 * fixed Downloads subfolder rather than being shared to a specific app, so this instead reads
 * from a folder the user grants access to once (Storage Access Framework,
 * `ACTION_OPEN_DOCUMENT_TREE`), scans for a fresh export on every sync, stages what it finds via
 * [PendingImports] exactly like the PDF import does, and deletes the source export folder once
 * every metric type in it has been attempted -- a Drive-upload failure afterward can't lose that
 * data, since PendingImports itself doesn't clear until the upload actually succeeds.
 *
 * Every metric type here needed its real export file inspected before writing a parser against
 * it, not just its name -- the shapes turned out to vary more than expected:
 * - respiratory_rate and stress already have a ready numeric value per row -- a direct read.
 * - hrv has no numeric value at all in its own CSV, just a pointer to a separate per-hour JSON
 *   file (thousands of them, across a long export) holding the actual sdnn/rmssd sub-readings.
 * - advanced_glycation_endproduct has two exported files; the plain one (not `.raw`) already has
 *   a ready computed score per reading, so the more complex `.raw` file (itself JSON-backed) was
 *   never needed.
 * - exercise recovery heart rate points to a JSON holding a full post-exercise HR curve, not a
 *   single value -- the clinically standard "recovery" number (HRR1: how much HR dropped in the
 *   first minute after stopping) has to be derived from that curve, not read off a field.
 * - heart_health_score, stress.histogram, and alerted_stress were all inspected and left out
 *   deliberately: the first had essentially no populated data on this account at all, the second
 *   is an internal calibration record rather than a time series, and the third is a discrete
 *   alert log that's redundant with what the main stress score already captures via its own
 *   daily max. No dedicated "resting heart rate" export exists either -- Health Connect's own
 *   daily heart-rate minimum remains the closest available proxy for that.
 */
object SamsungHealthExportImporter {

    private const val TAG = "HealthSyncExportImport"

    private const val METRIC_RESPIRATORY_RATE = "respiratory_rate"
    private const val METRIC_HRV = "hrv"
    private const val METRIC_STRESS = "stress"
    private const val METRIC_AGE = "advanced_glycation_endproduct"
    private const val METRIC_RECOVERY_HR = "exercise_heart_rate_recovery"
    private const val METRIC_BLOOD_PRESSURE = "blood_pressure_export"
    private const val METRIC_SKIN_TEMP = "skin_temperature"
    private const val METRIC_SNORE = "snore"
    private const val METRIC_SLEEP_SCORE = "sleep_score"
    private const val METRIC_ANTIOXIDANT = "antioxidant"
    private const val METRIC_MEAN_ARTERIAL_PRESSURE = "mean_arterial_pressure"
    private const val METRIC_EXERCISE_TITLE = "exercise_title"

    private data class ParsedPart(val rows: List<CsvRow>, val maxInstant: Instant?)

    /**
     * Scans the user-granted export folder (if one's been configured) for Samsung Health export
     * subfolders, stages any rows found via [PendingImports], and deletes each subfolder once
     * every metric type in it has been attempted. Safe to call on every sync -- a no-op single
     * directory listing when nothing new has been exported since the last run.
     *
     * Each export is a full re-dump of Samsung Health's entire history, not an incremental one --
     * confirmed in practice: the very first export already contained over a year of hourly HRV
     * buckets. Re-parsing all of it on every future export (thousands of small per-hour JSON
     * files, reopened every single time) would make this scale with total history instead of with
     * what's actually new since the last import. A cursor -- same "only advance past what's
     * confirmed processed" rule the Health Connect sync cursor already follows -- fixes that: any
     * row older than the cursor is skipped before its data is even touched, which for HRV means
     * skipping the JSON file entirely rather than opening it and discarding the result. The
     * cursor is tracked *per metric type*, not shared, for the same reason each metric gets its
     * own try/catch below: Samsung's own export format for any one data type could change
     * independently (a renamed column, a restructured JSON shape), and that should degrade to
     * "this one metric stops updating, loudly, until fixed" -- not take every other metric down
     * with it, and not get silently masked by metrics that still work fine.
     */
    fun stageAvailableExports(context: Context, owner: String) {
        val syncState = SyncState(context)
        val folderUriString = syncState.samsungHealthExportFolderUri ?: return
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderUriString)) }.getOrNull()
        if (root == null || !root.isDirectory) {
            Log.w(TAG, "Configured export folder is no longer accessible (permission revoked or folder moved)")
            return
        }

        val exportFolders = findExportFolders(root, maxDepth = 3)
        // Logged unconditionally, not just on a match -- a silent zero-result scan is
        // indistinguishable from "this code path never ran at all" otherwise, which is exactly
        // the debugging gap that cost a redundant round trip the first time this ran for real.
        Log.i(TAG, "Scanned '${root.name}' for Samsung Health export folders: found ${exportFolders.size}")
        for (exportFolder in exportFolders) {
            // Captured before delete() -- a DocumentFile's `.name` is resolved live against the
            // content provider, not cached, so reading it *after* the underlying document is
            // gone returns null. Log lines below need the human-readable name either way.
            val exportFolderName = exportFolder.name ?: "(unnamed)"

            // The whole export folder is what gets deleted -- not just the specific CSV/JSON
            // files this importer actually reads. Samsung's export dumps dozens of internal data
            // types we never touch (sleep detail, location, workout GPS tracks, etc.) alongside
            // the handful this code parses, and none of it should linger in Downloads once it's
            // been read: the user exports this manually and doesn't want to have to remember to
            // clean it up, and it's real personal health data sitting in plain files in the
            // meantime. That deletion is wrapped in `finally` rather than placed after parsing
            // completes normally, specifically so it still runs even if something outside the
            // known per-metric try/catch blocks throws unexpectedly -- a bug in this importer
            // should never be the reason a full health-data export is left behind on disk.
            var rows: List<CsvRow> = emptyList()
            var anyFailure = false
            try {
                val parsed = parseExportFolder(context, exportFolder, owner, syncState)
                rows = parsed.first
                anyFailure = parsed.second
            } catch (e: Exception) {
                anyFailure = true
                Log.e(TAG, "Unexpected failure processing Samsung Health export '$exportFolderName' -- deleting it anyway", e)
            } finally {
                if (rows.isNotEmpty()) {
                    PendingImports.stage(context, rows)
                }
                // The folder is cleared once every known metric type has been *attempted*, not
                // once all of them succeeded -- a metric that failed to parse gets another chance
                // against next month's export regardless (a fresh full re-dump either way), and
                // the point of per-metric isolation is that a stuck metric doesn't also block
                // cleanup of the metrics that did work.
                val deleted = exportFolder.delete()
                if (!deleted) {
                    // Loud, not routine -- this means a folder full of real health data is still
                    // sitting in Downloads and needs manual attention, not just a log line buried
                    // among normal per-sync status output.
                    Log.e(TAG, "FAILED to delete Samsung Health export '$exportFolderName' -- it still contains raw personal health data and needs to be removed manually")
                } else {
                    Log.i(
                        TAG,
                        "Processed Samsung Health export '$exportFolderName': ${rows.size} rows staged" +
                            (if (anyFailure) " (at least one metric type failed to parse -- see the warning above)" else "") +
                            ", entire export folder deleted"
                    )
                }
            }
        }
    }

    /**
     * Runs [block], logging a clear, specific warning and returning null on failure instead of
     * throwing -- the mechanism behind per-metric isolation. A failure here is expected to mean
     * "Samsung changed this metric's export format", so the message says exactly that rather
     * than a generic error, and processing continues with whatever other metrics are left.
     *
     * Also persists that same message via [SyncState.setSourceError] (or clears it on success)
     * so it stays visible in the app's UI across every future sync -- not just this one -- until
     * this specific metric parses cleanly again. That persistence matters more than it might
     * look here: the export folder that caused the failure gets deleted regardless (see
     * [stageAvailableExports]), so a plain logcat message would be the only trace left, and the
     * very next sync would have nothing left to fail against and would look like a clean success.
     */
    private fun tryParseMetric(syncState: SyncState, metricLabel: String, block: () -> ParsedPart): ParsedPart? =
        runCatching(block).fold(
            onSuccess = { result ->
                syncState.clearSourceError(metricLabel)
                result
            },
            onFailure = { e ->
                val detail = e.message ?: e.javaClass.simpleName
                Log.e(
                    TAG,
                    "Couldn't parse Samsung Health's '$metricLabel' export this run -- its format may " +
                        "have changed. Other metrics are unaffected and still synced normally.",
                    e
                )
                syncState.setSourceError(
                    metricLabel,
                    "Samsung export format may have changed ($detail) -- this metric will stop " +
                        "updating until it's fixed. Other metrics are unaffected."
                )
                null
            },
        )

    // The export folder isn't assumed to be a direct child of whatever the user granted --
    // the folder picker gives no way to steer which level they land on (they might grant
    // "Download" itself, or navigate one level further into "Download/Samsung Health" before
    // confirming), so this searches a few levels deep rather than only root.listFiles(). Bounded
    // depth, not unbounded recursion, so an accidentally-broad grant (e.g. all of "Download")
    // can't turn one sync into a full-storage crawl.
    private fun findExportFolders(dir: DocumentFile, maxDepth: Int): List<DocumentFile> {
        if (maxDepth < 0) return emptyList()
        val found = mutableListOf<DocumentFile>()
        for (child in dir.listFiles()) {
            if (!child.isDirectory) continue
            if (child.name?.startsWith("samsunghealth_") == true) {
                found += child
            } else {
                found += findExportFolders(child, maxDepth - 1)
            }
        }
        return found
    }

    // Exact match on "<type>.<digits>.csv", not a loose prefix check -- several of these type
    // names are prefixes of a sibling type's name (e.g. "...stress" vs "...stress.histogram"),
    // so startsWith() alone risks silently parsing the wrong file under a format change.
    private fun findMetricFile(exportFolder: DocumentFile, exactTypeName: String): DocumentFile? {
        val pattern = Regex("^${Regex.escape(exactTypeName)}\\.\\d+\\.csv$")
        return exportFolder.listFiles().firstOrNull { it.name?.let(pattern::matches) == true }
    }

    /** Returns the combined rows from every metric type that parsed successfully, plus whether
     *  any metric type failed (for the caller's log line) -- each metric's own cursor is read
     *  and advanced independently as part of parsing it, not handled up here. */
    private fun parseExportFolder(
        context: Context,
        exportFolder: DocumentFile,
        owner: String,
        syncState: SyncState,
    ): Pair<List<CsvRow>, Boolean> {
        val rows = mutableListOf<CsvRow>()
        var anyFailure = false

        fun run(metricKey: String, typeName: String, parse: (DocumentFile, Instant?) -> ParsedPart) {
            val file = findMetricFile(exportFolder, typeName) ?: return
            val cursor = syncState.samsungHealthExportCursor(metricKey)
            val part = tryParseMetric(syncState, metricKey) { parse(file, cursor) }
            if (part == null) {
                anyFailure = true
                return
            }
            rows += part.rows
            if (part.maxInstant != null && (cursor == null || part.maxInstant.isAfter(cursor))) {
                syncState.setSamsungHealthExportCursor(metricKey, part.maxInstant)
            }
        }

        run(METRIC_RESPIRATORY_RATE, "com.samsung.health.respiratory_rate") { file, cursor ->
            parseRespiratoryRate(context, file, owner, cursor)
        }
        run(METRIC_HRV, "com.samsung.health.hrv") { file, cursor ->
            val jsonIndex = buildJsonIndex(exportFolder, "com.samsung.health.hrv")
            parseHrv(context, file, jsonIndex, owner, cursor)
        }
        run(METRIC_STRESS, "com.samsung.shealth.stress") { file, cursor ->
            parseStress(context, file, owner, cursor)
        }
        run(METRIC_AGE, "com.samsung.health.advanced_glycation_endproduct") { file, cursor ->
            parseAdvancedGlycationEndproduct(context, file, owner, cursor)
        }
        run(METRIC_RECOVERY_HR, "com.samsung.shealth.exercise.recovery_heart_rate") { file, cursor ->
            val jsonIndex = buildJsonIndex(exportFolder, "com.samsung.shealth.exercise.recovery_heart_rate")
            parseExerciseHeartRateRecovery(context, file, jsonIndex, owner, cursor)
        }
        run(METRIC_BLOOD_PRESSURE, "com.samsung.shealth.blood_pressure") { file, cursor ->
            parseBloodPressure(context, file, owner, cursor)
        }
        run(METRIC_SKIN_TEMP, "com.samsung.health.skin_temperature") { file, cursor ->
            parseSkinTemperature(context, file, owner, cursor)
        }
        run(METRIC_SNORE, "com.samsung.shealth.sleep_snoring") { file, cursor ->
            parseSnoring(context, file, owner, cursor)
        }
        // Exact type name "com.samsung.shealth.sleep" -- findMetricFile's exact-match regex
        // (requires a literal "." right after the type name, not just any prefix) already keeps
        // this from colliding with the several similarly-named sleep_* siblings in the same
        // export (sleep_combined, sleep_goal, sleep_raw_data, sleep_snoring), same protection
        // that's kept "stress" from matching "stress.histogram" since that regex was introduced.
        run(METRIC_SLEEP_SCORE, "com.samsung.shealth.sleep") { file, cursor ->
            parseSleepScore(context, file, owner, cursor)
        }
        run(METRIC_ANTIOXIDANT, "com.samsung.health.antioxidant") { file, cursor ->
            parseAntioxidant(context, file, owner, cursor)
        }
        run(METRIC_MEAN_ARTERIAL_PRESSURE, "com.samsung.shealth.mean_arterial_pressure") { file, cursor ->
            parseMeanArterialPressure(context, file, owner, cursor)
        }
        run(METRIC_EXERCISE_TITLE, "com.samsung.shealth.exercise") { file, cursor ->
            val customExerciseFile = findMetricFile(exportFolder, "com.samsung.shealth.exercise.custom_exercise")
            parseExerciseTitle(context, file, customExerciseFile, owner, cursor)
        }

        return rows to anyFailure
    }

    // Several metric types point at a separate per-record JSON file rather than carrying their
    // value inline (hrv, exercise recovery heart rate). Those files sit under
    // jsons/<type_name>/<some sharding subfolder>/<file>.json -- the top-level CSV's own pointer
    // column only ever gives the bare filename, not the sharding subfolder, and that scheme isn't
    // assumed stable. Indexed by filename with one recursive walk per metric type instead of one
    // SAF lookup per CSV row (each DocumentFile call is a binder round-trip; doing that per-row
    // across a year of hourly buckets would be the real cost here, not the JSON parsing itself).
    private fun buildJsonIndex(exportFolder: DocumentFile, typeName: String): Map<String, DocumentFile> {
        val typeJsonsDir = exportFolder.findFile("jsons")?.findFile(typeName) ?: return emptyMap()
        val index = mutableMapOf<String, DocumentFile>()
        fun walk(dir: DocumentFile) {
            for (child in dir.listFiles()) {
                if (child.isDirectory) walk(child) else child.name?.let { index[it] = child }
            }
        }
        walk(typeJsonsDir)
        return index
    }

    private fun parseRespiratoryRate(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val byDay = mutableMapOf<LocalDate, MutableList<Double>>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "start_time") ?: continue
            // Real export data has surfaced a stray 0.0 reading here and there -- physiologically
            // impossible (nobody has a respiratory rate of zero breaths/min while this export
            // exists to report on them) and clearly a bad sensor sample slipping through
            // unfiltered on Samsung's side, not a genuine measurement. Dropped rather than kept,
            // so it can't drag a day's min down to a nonsense value.
            val avg = row.valueOf(header, "average")?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: continue
            val instant = parseLocalDateTimeWithOffset(startRaw, row.valueOf(header, "time_offset")) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            // Same completeness reasoning as HealthConnectReader's isCompleteCalendarDay --
            // today isn't over yet, so finalizing it now would mean a later export covering
            // the rest of today gets silently deduped away instead of adding to it.
            if (!day.isBefore(today)) continue
            byDay.getOrPut(day) { mutableListOf() }.add(avg)
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }

        val rows = byDay.map { (day, values) -> dailyMinAvgMaxRows(day, owner, "respiratory_rate", "breaths_per_min", values) }.flatten()
        return ParsedPart(rows, maxInstant)
    }

    private fun parseHrv(
        context: Context,
        file: DocumentFile,
        jsonIndex: Map<String, DocumentFile>,
        owner: String,
        cursor: Instant?,
    ): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val byDay = mutableMapOf<LocalDate, MutableList<Double>>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            // Cursor check against the row's own bucket start time, parsed straight from the
            // CSV -- deliberately before ever resolving or opening the referenced JSON file.
            // This is the actual point of the cursor: skipping a row means skipping the file
            // open entirely, not opening it and discarding the result.
            val bucketStartRaw = row.valueOf(header, "start_time")
            val bucketInstant = bucketStartRaw?.let { parseLocalDateTimeWithOffset(it, row.valueOf(header, "time_offset")) }
            if (cursor != null && bucketInstant != null && !bucketInstant.isAfter(cursor)) continue

            val binningName = row.valueOf(header, "binning_data") ?: continue
            val jsonFile = jsonIndex[binningName] ?: continue
            val subReadings = runCatching { parseHrvBinningJson(context, jsonFile) }.getOrElse {
                Log.w(TAG, "Skipped unreadable HRV binning file: $binningName", it)
                emptyList()
            }
            for ((instant, rmssd) in subReadings) {
                if (cursor != null && !instant.isAfter(cursor)) continue
                val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
                if (!day.isBefore(today)) continue
                byDay.getOrPut(day) { mutableListOf() }.add(rmssd)
                if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
            }
        }

        val rows = byDay.map { (day, values) -> dailyMinAvgMaxRows(day, owner, "heart_rate_variability_rmssd", "ms", values) }.flatten()
        return ParsedPart(rows, maxInstant)
    }

    private fun parseHrvBinningJson(context: Context, file: DocumentFile): List<Pair<Instant, Double>> {
        val text = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return emptyList()
        val array = JSONArray(text)
        val out = mutableListOf<Pair<Instant, Double>>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val startMs = obj.optLong("start_time", -1L)
            val rmssd = obj.optDouble("rmssd", Double.NaN)
            if (startMs > 0 && !rmssd.isNaN()) {
                out += Instant.ofEpochMilli(startMs) to rmssd
            }
        }
        return out
    }

    /**
     * Real per-bucket data (confirmed: ~1 bucket/hour of active wear, over a year of history) with
     * an already-computed `score` inline -- no JSON needed, unlike hrv. Dense enough, and
     * genuinely fluctuating enough through a day (unlike a slow-moving biomarker), to treat the
     * same way as heart rate: daily min/avg/max rather than one row per bucket. From a
     * functional-medicine read, the daily *peak* and the spread matter at least as much as a
     * single daily average would -- a flat "average stress" number would hide exactly the kind of
     * acute-spike pattern worth correlating against sleep/HRV dips.
     */
    private fun parseStress(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val byDay = mutableMapOf<LocalDate, MutableList<Double>>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "start_time") ?: continue
            val score = row.valueOf(header, "score")?.toDoubleOrNull() ?: continue
            val instant = parseLocalDateTimeWithOffset(startRaw, row.valueOf(header, "time_offset")) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            if (!day.isBefore(today)) continue
            byDay.getOrPut(day) { mutableListOf() }.add(score)
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }

        val rows = byDay.map { (day, values) -> dailyMinAvgMaxRows(day, owner, "stress_score", "score", values) }.flatten()
        return ParsedPart(rows, maxInstant)
    }

    /**
     * The plain (non-`.raw`) AGE export already carries a computed `score` per reading -- the
     * `.raw` file's own JSON-backed sub-readings were never needed. Real cadence is roughly once
     * a day: a deliberate spot-check via the skin sensor, not a continuously-sampled signal, so
     * this is point-in-time like weight or a scale reading -- a "daily average" would just blur
     * the actual measured value for no reason, the same logic that moved blood pressure to
     * point-in-time once its real cadence was checked. Uses Samsung's own `datauuid` as the
     * dedup key directly, rather than a derived one, since there's no second import path for this
     * metric to stay cross-compatible with (unlike blood pressure -- see [parseBloodPressure]).
     */
    private fun parseAdvancedGlycationEndproduct(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val rows = mutableListOf<CsvRow>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val dayTimeRaw = row.valueOf(header, "day_time") ?: continue
            val score = row.valueOf(header, "score")?.toDoubleOrNull() ?: continue
            val datauuid = row.valueOf(header, "datauuid") ?: continue
            // This export has no time_offset column at all -- parseLocalDateTimeWithOffset
            // falls back to the device's current zone rather than assuming UTC in that case,
            // which matters here specifically since this is the one type missing a recorded
            // offset to use instead.
            val instant = parseLocalDateTimeWithOffset(dayTimeRaw, null) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            if (!day.isBefore(today)) continue
            rows += CsvRow(instant, owner, "advanced_glycation_endproduct", formatValue(score), "score", "advanced_glycation_endproduct_$datauuid")
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }
        return ParsedPart(rows, maxInstant)
    }

    /**
     * A carotenoid/antioxidant skin-scan score, same sensor family and shape as AGE above --
     * added 2026-08-23, found while scoping a Watch 8 upgrade's export for anything new. Real
     * but currently sparse (2 readings total when this was written, the day after the watch
     * upgrade that introduced it) -- added anyway rather than waiting for more usage, since the
     * schema itself is simple and already well-understood (identical shape to AGE, which already
     * works reliably): a single scalar plus a real `datauuid`, no ambiguity to wait out. Sparse
     * data just means few rows for now, not an unreliable parser -- more accumulate automatically
     * as the feature gets used, with no code change needed later.
     */
    private fun parseAntioxidant(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val rows = mutableListOf<CsvRow>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "start_time") ?: continue
            val score = row.valueOf(header, "antioxidant")?.toDoubleOrNull() ?: continue
            val datauuid = row.valueOf(header, "datauuid") ?: continue
            val instant = parseLocalDateTimeWithOffset(startRaw, row.valueOf(header, "time_offset")) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            if (!day.isBefore(today)) continue
            rows += CsvRow(instant, owner, "antioxidant", formatValue(score), "score", "antioxidant_$datauuid")
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }
        return ParsedPart(rows, maxInstant)
    }

    /**
     * Mean arterial pressure -- added 2026-08-23, same discovery pass as antioxidant above.
     * Also sparse right now (4 readings total, and only the most recent of those actually has a
     * populated `measurement` value -- the three before it don't, apparently from before
     * Samsung's own computation for this started working reliably), but same reasoning as
     * antioxidant: the schema itself isn't ambiguous, so this is a data-volume problem that
     * fixes itself over time, not a parsing-reliability one. Rows with no `measurement` value are
     * just skipped, the same defensive pattern used everywhere else in this file for an optional
     * field -- not treated as a failure.
     */
    private fun parseMeanArterialPressure(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val rows = mutableListOf<CsvRow>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "start_time") ?: continue
            val measurement = row.valueOf(header, "measurement")?.toDoubleOrNull() ?: continue
            val datauuid = row.valueOf(header, "datauuid") ?: continue
            val instant = parseLocalDateTimeWithOffset(startRaw, row.valueOf(header, "time_offset")) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            if (!day.isBefore(today)) continue
            rows += CsvRow(instant, owner, "mean_arterial_pressure", formatValue(measurement), "mmHg", "mean_arterial_pressure_$datauuid")
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }
        return ParsedPart(rows, maxInstant)
    }

    /**
     * Recovers the real name behind a custom exercise (e.g. "GrowingHannas") -- confirmed real,
     * found while scoping this (2026-09-07): a custom exercise's name lives in a small, separate
     * reference table (`com.samsung.shealth.exercise.custom_exercise`, one row per *distinct*
     * custom exercise ever created, not per session), joined to each session by a stable
     * `custom_id` -- **not** the session's own `title` field, which turns out to be blank
     * (confirmed against 125/125 real GrowingHannas sessions), matching what Health Connect's
     * own `title` field already showed for the exact same sessions. Neither platform writes the
     * name directly onto the session record itself; both need this same kind of join.
     *
     * Only sessions with a real, matched `custom_id` produce a row here -- built-in exercise
     * types (running, walking, strength_training, ...) are already correctly labeled via Health
     * Connect's own `exerciseType` code and don't need this at all. This exists specifically for
     * the ones Health Connect can only call "other_workout" with no way to tell one custom
     * exercise from another.
     *
     * Deliberately its own metric with its own independent ID (Samsung's real per-session
     * `datauuid`), not sharing Health Connect's per-session record UUID the way `exercise_title`
     * does when it *is* actually present on the Health Connect side (see HealthConnectReader's
     * `readExercise`) -- this is a genuinely separate read pipeline (Samsung's SAF-granted
     * folder, not Health Connect's API) that has no access to the other's record IDs at all. A
     * downstream reader recovers which physical session this belongs to the same way every
     * other dual-sourced metric here already works: by matching timestamps, not a shared ID.
     */
    private fun parseExerciseTitle(context: Context, exerciseFile: DocumentFile, customExerciseFile: DocumentFile?, owner: String, cursor: Instant?): ParsedPart {
        if (customExerciseFile == null) return ParsedPart(emptyList(), null)
        val (nameHeader, nameRows) = readSamsungCsv(context, customExerciseFile)
        if (nameHeader.isEmpty()) return ParsedPart(emptyList(), null)
        val namesByCustomId = nameRows.mapNotNull { row ->
            val id = row.valueOf(nameHeader, "custom_id") ?: return@mapNotNull null
            val name = row.valueOf(nameHeader, "custom_name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to name
        }.toMap()
        if (namesByCustomId.isEmpty()) return ParsedPart(emptyList(), null)

        val (header, dataRows) = readSamsungCsv(context, exerciseFile)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val rows = mutableListOf<CsvRow>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val customId = row.valueOf(header, "custom_id") ?: continue
            val name = namesByCustomId[customId] ?: continue
            val startRaw = row.valueOf(header, "com.samsung.health.exercise.start_time") ?: continue
            val datauuid = row.valueOf(header, "com.samsung.health.exercise.datauuid") ?: continue
            // Deliberately NOT parseLocalDateTimeWithOffset here, unlike every other Samsung
            // export type handled in this file -- real bug, confirmed live 2026-09-07 against
            // the actual Health Connect session for the same real-world moment (matching to the
            // millisecond): com.samsung.health.exercise.start_time is already a UTC instant for
            // this specific record type, despite being paired with a time_offset field ("UTC+0900")
            // that looks exactly like the naive-local-time-plus-offset convention every other
            // Samsung export type actually uses. Subtracting that offset from an already-UTC
            // value was shifting every exercise_title row 9 hours *too early* -- confirmed
            // against 39 independent session pairs, not a one-off. Parsed directly as UTC here
            // instead, with nothing to convert.
            val instant = runCatching { LocalDateTime.parse(startRaw, LOCAL_DATETIME_FORMAT).toInstant(ZoneOffset.UTC) }.getOrNull() ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            if (!day.isBefore(today)) continue
            rows += CsvRow(instant, owner, "exercise_title", name, "text", "exercise_title_$datauuid")
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }
        return ParsedPart(rows, maxInstant)
    }

    /**
     * Each row points to a JSON holding a full post-exercise heart-rate curve (confirmed: roughly
     * one heart-rate sample per second for a couple of minutes after the session ends), not a
     * single value -- the clinically standard number here is HRR1 (heart-rate recovery at one
     * minute): how many bpm it dropped from the start of that curve to the sample closest to 60
     * seconds in. One value per exercise session, so point-in-time, timestamped at the recovery
     * measurement itself. A bigger drop is generally the better sign (stronger parasympathetic
     * reactivation) -- that reading is left for whoever looks at the number, not asserted here.
     */
    private fun parseExerciseHeartRateRecovery(
        context: Context,
        file: DocumentFile,
        jsonIndex: Map<String, DocumentFile>,
        owner: String,
        cursor: Instant?,
    ): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val rows = mutableListOf<CsvRow>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "start_time") ?: continue
            // Deliberately NOT parseLocalDateTimeWithOffset -- same real bug as exercise_title
            // (see its doc comment), confirmed to share the same root cause: this row's raw
            // start_time matches the parent exercise session's own raw end_time to the
            // millisecond ("recovery monitoring starts the instant the session ends"), and that
            // parent session's fields were independently confirmed already-UTC against real
            // Health Connect data. Both come from the same underlying Samsung workout-tracking
            // subsystem and evidently share the same raw-UTC convention, despite also carrying a
            // time_offset field that looks exactly like the naive-local-plus-offset convention
            // every other Samsung export type actually uses.
            val instant = runCatching { LocalDateTime.parse(startRaw, LOCAL_DATETIME_FORMAT).toInstant(ZoneOffset.UTC) }.getOrNull() ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            if (!day.isBefore(today)) continue

            val datauuid = row.valueOf(header, "datauuid") ?: continue
            val curveFileName = row.valueOf(header, "heart_rate") ?: continue
            val curveFile = jsonIndex[curveFileName] ?: continue
            val recoveryBpm = runCatching { computeHeartRateRecovery1Min(context, curveFile) }.getOrNull() ?: continue

            rows += CsvRow(instant, owner, "exercise_heart_rate_recovery_1min", formatValue(recoveryBpm), "bpm", "exercise_heart_rate_recovery_$datauuid")
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }
        return ParsedPart(rows, maxInstant)
    }

    private fun computeHeartRateRecovery1Min(context: Context, file: DocumentFile): Double? {
        val text = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return null
        val chart = org.json.JSONObject(text).optJSONArray("chart_data") ?: return null
        if (chart.length() == 0) return null

        var startHr: Double? = null
        var closestTo60s: Double? = null
        var closestDiffMs = Long.MAX_VALUE
        for (i in 0 until chart.length()) {
            val entry = chart.getJSONObject(i)
            val elapsedMs = entry.optLong("elapsed_time", -1L)
            val hr = entry.optDouble("heart_rate", Double.NaN)
            if (elapsedMs < 0 || hr.isNaN()) continue
            if (startHr == null) startHr = hr
            val diff = abs(elapsedMs - 60_000L)
            if (diff < closestDiffMs) {
                closestDiffMs = diff
                closestTo60s = hr
            }
        }
        val start = startHr ?: return null
        val at60s = closestTo60s ?: return null
        return start - at60s
    }

    /**
     * Blood pressure also shows up in this same full export (Samsung Health Monitor writes it
     * through to the account-level store this export reads from), with real inline systolic/
     * diastolic/pulse values -- no JSON indirection needed here. This is a *second* way in for a
     * metric that already has one (the Health Monitor PDF share, see
     * SamsungHealthMonitorPdfImporter), so the ID scheme here deliberately matches that importer's
     * exactly -- derived from the reading's own date and time, not this file's `datauuid` -- so
     * the same real-world reading captured through either path produces the same
     * `source_record_id` and dedupes as one reading instead of two.
     */
    private fun parseBloodPressure(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val today = LocalDate.now(ZoneId.systemDefault())
        val rows = mutableListOf<CsvRow>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "com.samsung.health.blood_pressure.start_time") ?: continue
            val systolic = row.valueOf(header, "com.samsung.health.blood_pressure.systolic")?.toDoubleOrNull() ?: continue
            val diastolic = row.valueOf(header, "com.samsung.health.blood_pressure.diastolic")?.toDoubleOrNull() ?: continue
            val pulse = row.valueOf(header, "com.samsung.health.blood_pressure.pulse")?.toDoubleOrNull()
            val instant = parseLocalDateTimeWithOffset(startRaw, row.valueOf(header, "com.samsung.health.blood_pressure.time_offset")) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            if (!day.isBefore(today)) continue

            // The ID has to reuse the reading's own recorded local wall-clock time -- matching
            // the PDF importer's scheme exactly, since that's the whole point (cross-source
            // dedup, see this function's doc) -- NOT the *processing phone's* current zone.
            // Real bug, confirmed on every Aug-2026 row from both owners: re-deriving a
            // "local-looking" time by re-zoning the already-correct UTC `instant` through
            // ZoneId.systemDefault() silently swaps in whatever zone the phone happens to be in
            // *right now* for whatever zone the reading was actually recorded in -- a double
            // conversion (local -> UTC via the recorded offset, then UTC -> "local" again via an
            // unrelated zone) that only round-trips back to the right answer if those two zones
            // happen to match, which they generally don't. Re-parsing the raw string directly
            // sidesteps zone math entirely for this part: it's already the reading's own local
            // wall-clock time, verbatim, with nothing left to convert.
            val localDateTime = LocalDateTime.parse(startRaw, LOCAL_DATETIME_FORMAT)
            val idBase = "blood_pressure_${localDateTime.toLocalDate()}T%02d%02d".format(localDateTime.hour, localDateTime.minute)
            rows += CsvRow(instant, owner, "blood_pressure_systolic", formatValue(systolic), "mmHg", "$idBase#systolic")
            rows += CsvRow(instant, owner, "blood_pressure_diastolic", formatValue(diastolic), "mmHg", "$idBase#diastolic")
            if (pulse != null) {
                rows += CsvRow(instant, owner, "blood_pressure_pulse", formatValue(pulse), "bpm", "$idBase#pulse")
            }
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }
        return ParsedPart(rows, maxInstant)
    }

    /**
     * Overnight readings (skin temperature, snoring) are bucketed to a noon-to-noon "sleep day"
     * rather than an ordinary calendar day -- otherwise a single continuous night's readings
     * routinely get split across two dates depending on what side of midnight bedtime happened
     * to fall on, the exact reason [HealthConnectReader] uses the same noon-to-noon convention
     * for `sleep_session_duration`/`sleep_stage_*`. Duplicated here rather than shared with that
     * class -- both are small, self-contained, and pulling a cross-class dependency in just for
     * this one helper isn't worth it -- but deliberately kept to the *exact same* noon-boundary
     * definition so a night's Samsung-export data and its Health-Connect-sourced sleep data land
     * on the same date.
     */
    private fun sleepDayOf(instant: Instant): LocalDate = instant.atZone(ZoneId.systemDefault()).minusHours(12).toLocalDate()

    private fun isCompleteSleepDay(day: LocalDate): Boolean = day.isBefore(sleepDayOf(Instant.now()))

    /**
     * Real per-bucket data (confirmed: roughly hourly while worn) with `temperature`/`min`/`max`
     * already computed inline -- no `binning_data` JSON needed at all, unlike HRV, since the
     * per-bucket summary is exactly what's needed here. Bucketed to a sleep day (see
     * [sleepDayOf]) rather than daily min/avg/max like the other dense metrics, per the
     * functional-medicine reasoning behind this request: skin temperature is overwhelmingly a
     * *sleep* signal (illness-onset fever, or a vasomotor/hot-flash proxy), and a night's
     * readings routinely straddle midnight, so calendar-day bucketing would split one night's
     * data across two dates.
     *
     * `baseline`/`lower_bound`/`upper_bound` are populated in the large majority of rows
     * (confirmed: ~90% in a real sample) but not universally, so `skin_temp_baseline_deviation`
     * is only emitted for buckets that actually have a baseline to compare against -- silently
     * skipped, not treated as a parse failure, for the buckets that don't. Deliberately reports
     * the night's *maximum* deviation, not an average: real spot-check requested this
     * explicitly, since an all-night average would wash out exactly the kind of brief nightly
     * spike (fever onset, a hot flash) this metric exists to catch.
     */
    private fun parseSkinTemperature(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val temps = mutableMapOf<LocalDate, MutableList<Double>>()
        val deviations = mutableMapOf<LocalDate, MutableList<Double>>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "start_time") ?: continue
            val temperature = row.valueOf(header, "temperature")?.toDoubleOrNull() ?: continue
            val instant = parseLocalDateTimeWithOffset(startRaw, row.valueOf(header, "time_offset")) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = sleepDayOf(instant)
            if (!isCompleteSleepDay(day)) continue

            temps.getOrPut(day) { mutableListOf() }.add(temperature)
            val baseline = row.valueOf(header, "baseline")?.toDoubleOrNull()
            if (baseline != null) {
                deviations.getOrPut(day) { mutableListOf() }.add(temperature - baseline)
            }
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }

        val rows = mutableListOf<CsvRow>()
        for ((day, values) in temps) {
            rows += dailyMinAvgMaxRows(day, owner, "skin_temp", "celsius", values)
            deviations[day]?.let { devs ->
                val ts = day.atStartOfDay(ZoneOffset.UTC).toInstant()
                rows += CsvRow(ts, owner, "skin_temp_baseline_deviation", formatValue(devs.max()), "celsius", "skin_temp_baseline_deviation_daily_$day")
            }
        }
        return ParsedPart(rows, maxInstant)
    }

    /**
     * Real discrete snore episodes (confirmed: precise start/end/duration per row, not just a
     * nightly total) -- summed and counted per sleep day rather than kept as individual rows,
     * matching how every other sleep metric here aggregates. `duration` is milliseconds in the
     * export; converted to minutes for consistency with every other duration-shaped metric in
     * this file (nothing else uses raw milliseconds).
     *
     * Known limitation, worth carrying into any downstream interpretation of this data: this
     * feature can't tell whose snoring it's picking up -- the watch has no mic for it, so once
     * sleep onset is detected it's the *phone's* mic that's listening, and with two phones in
     * one bed there's no per-person signature to filter cross-pickup. Treat a night's reading as
     * "something happened in this bed," not confidently attributable to whichever phone logged
     * it, unless corroborated some other way (the other person confirming, or a same-night SpO2
     * dip on that specific person's own watch).
     */
    private fun parseSnoring(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val byDay = mutableMapOf<LocalDate, MutableList<Double>>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "start_time") ?: continue
            val durationMs = row.valueOf(header, "duration")?.toDoubleOrNull() ?: continue
            val instant = parseLocalDateTimeWithOffset(startRaw, row.valueOf(header, "time_offset")) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = sleepDayOf(instant)
            if (!isCompleteSleepDay(day)) continue
            byDay.getOrPut(day) { mutableListOf() }.add(durationMs)
            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }

        val rows = mutableListOf<CsvRow>()
        for ((day, durationsMs) in byDay) {
            val ts = day.atStartOfDay(ZoneOffset.UTC).toInstant()
            val totalMinutes = durationsMs.sum() / 60_000.0
            rows += CsvRow(ts, owner, "snore_duration_min", formatValue(totalMinutes), "minutes", "snore_duration_daily_$day")
            rows += CsvRow(ts, owner, "snore_episode_count", durationsMs.size.toString(), "count", "snore_episode_count_daily_$day")
        }
        return ParsedPart(rows, maxInstant)
    }

    /**
     * Samsung's actual composite Sleep Score -- not part of the original request, found while
     * scoping the metrics above (2026-08-23): unlike `heart_health_score` (essentially empty,
     * deliberately skipped), this one is genuinely populated (confirmed: ~99% of nights across
     * 2+ years for the core fields) and is the real outcome number tying together sleep stages,
     * sleep heart rate, and breathing -- the same three inputs the underlying algorithm is
     * believed to weigh, per the household's own read on how this score is built.
     *
     * Point-in-time, one row per sleep *session* (not aggregated to one-per-night) -- a night
     * with both a nap and main sleep produces two distinct sessions here, distinguishable by
     * `sleep_type`'s raw numeric code (passed through unmapped, same treatment as an unrecognized
     * Health Connect exercise type -- Samsung's own enum for this isn't documented anywhere
     * checked). Real `datauuid` used as the dedup key, same as AGE/recovery-HR, since this has no
     * second import path to cross-dedup against.
     *
     * `deep_score`/`rem_score`/`wake_score`/`latency_score` (and a few other fields) are only
     * populated from roughly mid-2025 onward in real data -- Samsung apparently started writing
     * them partway through this account's history. Emitted whenever present rather than skipped
     * outright: reliable for current and future nights, just genuinely absent on older ones,
     * which is a real gap in the underlying data, not a parsing shortcoming to route around.
     *
     * `total_rem_duration`/`total_light_duration` here were checked against Health Connect's own
     * `sleep_stage_rem`/`sleep_stage_light` before adding, specifically to avoid a redundant
     * pair of columns -- confirmed NOT the same (Samsung's own figures ran consistently 5-15%
     * higher across a real 10-night sample), likely reflecting a different/newer staging pass
     * than whatever gets synced to Health Connect. Kept as distinct metrics for that reason, not
     * duplicates of the existing sleep_stage_* rows.
     */
    private fun parseSleepScore(context: Context, file: DocumentFile, owner: String, cursor: Instant?): ParsedPart {
        val (header, dataRows) = readSamsungCsv(context, file)
        if (header.isEmpty()) return ParsedPart(emptyList(), null)

        val rows = mutableListOf<CsvRow>()
        var maxInstant: Instant? = null
        for (row in dataRows) {
            val startRaw = row.valueOf(header, "com.samsung.health.sleep.start_time") ?: continue
            val instant = parseLocalDateTimeWithOffset(startRaw, row.valueOf(header, "com.samsung.health.sleep.time_offset")) ?: continue
            if (cursor != null && !instant.isAfter(cursor)) continue
            val day = sleepDayOf(instant)
            if (!isCompleteSleepDay(day)) continue
            val datauuid = row.valueOf(header, "com.samsung.health.sleep.datauuid") ?: continue
            val idBase = "sleep_score_$datauuid"

            fun emitScore(field: String, metric: String) {
                row.valueOf(header, field)?.toDoubleOrNull()?.let {
                    rows += CsvRow(instant, owner, metric, formatValue(it), "score", "$idBase#$metric")
                }
            }
            emitScore("sleep_score", "sleep_score")
            emitScore("mental_recovery", "sleep_mental_recovery")
            emitScore("physical_recovery", "sleep_physical_recovery")
            emitScore("deep_score", "sleep_deep_score")
            emitScore("rem_score", "sleep_rem_score")
            emitScore("wake_score", "sleep_wake_score")
            emitScore("latency_score", "sleep_latency_score")
            emitScore("nap_score", "sleep_nap_score")

            row.valueOf(header, "sleep_cycle")?.toDoubleOrNull()?.let {
                rows += CsvRow(instant, owner, "sleep_cycle_count", formatValue(it), "count", "$idBase#sleep_cycle_count")
            }
            row.valueOf(header, "movement_awakening")?.toDoubleOrNull()?.let {
                rows += CsvRow(instant, owner, "sleep_movement_awakening", formatValue(it), "count", "$idBase#sleep_movement_awakening")
            }
            // Milliseconds in the export -- converted to minutes for consistency with every
            // other duration-shaped metric in this file.
            row.valueOf(header, "sleep_latency")?.toDoubleOrNull()?.let {
                rows += CsvRow(instant, owner, "sleep_latency_min", formatValue(it / 60_000.0), "minutes", "$idBase#sleep_latency_min")
            }
            row.valueOf(header, "total_rem_duration")?.toDoubleOrNull()?.let {
                rows += CsvRow(instant, owner, "sleep_algorithm_rem_duration", formatValue(it), "minutes", "$idBase#sleep_algorithm_rem_duration")
            }
            row.valueOf(header, "total_light_duration")?.toDoubleOrNull()?.let {
                rows += CsvRow(instant, owner, "sleep_algorithm_light_duration", formatValue(it), "minutes", "$idBase#sleep_algorithm_light_duration")
            }
            row.valueOf(header, "sleep_type")?.let { type ->
                rows += CsvRow(instant, owner, "sleep_type_code", type, "code", "$idBase#sleep_type_code")
            }

            if (maxInstant == null || instant.isAfter(maxInstant)) maxInstant = instant
        }
        return ParsedPart(rows, maxInstant)
    }

    // Samsung's export CSVs open with a "com.samsung.health.<type>,<n>,<n>" identifier line
    // (with a leading BOM in at least the hrv export) before the real column header on line 2 --
    // not itself a data row. Columns are looked up by name, not fixed position: several of these
    // files have more comma-separated fields per data row than the header names (an extra empty
    // trailing field), so a name-indexed lookup tolerates that instead of assuming an exact count.
    private fun readSamsungCsv(context: Context, file: DocumentFile): Pair<List<String>, List<List<String>>> {
        context.contentResolver.openInputStream(file.uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val lines = reader.readLines()
                if (lines.size < 2) return emptyList<String>() to emptyList()
                val header = lines[1].removePrefix("﻿").split(",")
                val dataRows = lines.drop(2).filter { it.isNotBlank() }.map { it.split(",") }
                return header to dataRows
            }
        }
        return emptyList<String>() to emptyList()
    }

    private fun List<String>.valueOf(header: List<String>, name: String): String? {
        val idx = header.indexOf(name)
        return if (idx in indices) this[idx].ifEmpty { null } else null
    }

    private val LOCAL_DATETIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val UTC_OFFSET_PATTERN = Regex("""UTC([+-]\d{2})(\d{2})""")

    // Row timestamps are naive local datetime strings ("2024-07-15 13:15:00.000") plus, for most
    // (but not all -- see parseAdvancedGlycationEndproduct) metric types, a separately-recorded
    // offset ("UTC+0900") for whatever zone the device was actually in at that moment. Using that
    // recorded offset instead of the device's current zone matters if the data ever crosses a
    // travel/DST boundary relative to when it's being imported. When no offset is recorded at
    // all, this falls back to the device's current zone rather than UTC -- a naive local
    // timestamp with no zone information was never actually a UTC timestamp to begin with, and
    // assuming UTC would shift the effective calendar day for anyone not near that meridian.
    private fun parseLocalDateTimeWithOffset(raw: String, offsetRaw: String?): Instant? {
        val localDateTime = runCatching { LocalDateTime.parse(raw, LOCAL_DATETIME_FORMAT) }.getOrNull() ?: return null
        val offset = offsetRaw?.let { UTC_OFFSET_PATTERN.find(it) }
            ?.let { m -> runCatching { ZoneOffset.of("${m.groupValues[1]}:${m.groupValues[2]}") }.getOrNull() }
            ?: ZoneId.systemDefault().rules.getOffset(localDateTime)
        return localDateTime.toInstant(offset)
    }

    // Matches HealthConnectReader's own daily-aggregate shape and ID scheme exactly
    // (metric_daily_<date>#min/avg/max, local date encoded as UTC midnight) -- not duplicated
    // out of laziness: if Samsung Health ever does start publishing these to Health Connect in a
    // future OS update, the two sources produce identical IDs for the same day and dedupe
    // against each other instead of conflicting.
    private fun dailyMinAvgMaxRows(day: LocalDate, owner: String, metricPrefix: String, unit: String, values: List<Double>): List<CsvRow> {
        val ts: Instant = day.atStartOfDay(ZoneOffset.UTC).toInstant()
        val bucketId = "${metricPrefix}_daily_$day"
        return listOf(
            CsvRow(ts, owner, "${metricPrefix}_min", formatValue(values.min()), unit, "$bucketId#min"),
            CsvRow(ts, owner, "${metricPrefix}_avg", formatValue(values.average()), unit, "$bucketId#avg"),
            CsvRow(ts, owner, "${metricPrefix}_max", formatValue(values.max()), unit, "$bucketId#max"),
        )
    }

    private fun formatValue(v: Double): String = String.format(Locale.US, "%.1f", v)
}
