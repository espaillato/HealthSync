package com.espaillat.healthsync

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Parses a Samsung Health Monitor "Blood pressure" PDF export (Export -> PDF, from within the
 * Health Monitor app itself) into readings, and converts them into the same point-in-time
 * CsvRow shape HealthConnectReader uses for blood pressure -- one row per reading, no daily
 * bucketing, matching the same reasoning: real exports show 2-3 deliberate spot readings a day,
 * not the dense continuous sampling that would justify smoothing into a daily average. Health
 * Connect can never actually produce this data itself (Health Monitor's BpContentProvider is
 * locked behind a signature|privileged permission, confirmed by an actual SecurityException
 * querying it directly), so this is a manual, one-off-import counterpart to the background
 * sync, not a replacement for it.
 *
 * The export is a small, machine-generated PDF with real embedded text (confirmed against an
 * actual sample export), not a scanned image -- text is extracted directly via PDFBox, no OCR,
 * since OCR would add a misread-digit risk on top of the parsing itself that plain text
 * extraction doesn't have. Android has no built-in PDF text-extraction API (PdfRenderer only
 * rasterizes to a bitmap), which is why this dependency exists at all.
 */
object SamsungHealthMonitorPdfImporter {

    data class BpReading(
        val date: LocalDate,
        val time: LocalTime,
        val systolicMmHg: Int,
        val diastolicMmHg: Int,
        val pulseBpm: Int,
    )

    data class ParseResult(
        val readings: List<BpReading>,
        /** Lines that looked like they might be a record but didn't fully match, or dates that
         *  needed a fallback. Surfaced rather than silently dropped/guessed -- a misparsed
         *  blood-pressure value is a real failure mode, not a cosmetic one. */
        val warnings: List<String>,
    )

    // Confirmed against real PDFBox-Android output (not just a desktop text-extraction
    // library's output, which laid the same table out as five separate lines per reading --
    // PDFBox-Android instead collapses each reading to one line): a full reading line looks
    // like "Aug 7 1∶12 PM 113 72 81" or "Aug 7 1∶12 PM 113 72 81 some note text".
    private val READING_LINE = Regex(
        """^([A-Z][a-z]{2}) (\d{1,2}) (\d{1,2})[:∶](\d{2})\s*(AM|PM)\s+(\d{1,3})\s+(\d{1,3})\s+(\d{1,3})(?:\s+(.*))?$""",
        RegexOption.IGNORE_CASE
    )
    private val DATE_RANGE = Regex("""([A-Z][a-z]{2} \d{1,2}, \d{4})\s*~\s*([A-Z][a-z]{2} \d{1,2}, \d{4})""")
    private val RANGE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    fun extractText(context: Context, pdfBytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(pdfBytes).use { document ->
            return PDFTextStripper().getText(document)
        }
    }

    fun parse(rawText: String): ParseResult {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val warnings = mutableListOf<String>()

        // Per-row dates have no year ("Aug 7") -- resolve it from the report's own date-range
        // header ("Aug 7, 2026 ~ Aug 8, 2026") rather than assuming "this year", since an export
        // could easily be for a past period. If the range spans two different years, match each
        // row's month/day against whichever year in that span actually lands inside the stated
        // range, rather than guessing "the current year" for everything.
        val rangeMatch = DATE_RANGE.find(rawText)
        val rangeStart = rangeMatch?.groupValues?.get(1)
            ?.let { runCatching { LocalDate.parse(it, RANGE_DATE_FORMAT) }.getOrNull() }
        val rangeEnd = rangeMatch?.groupValues?.get(2)
            ?.let { runCatching { LocalDate.parse(it, RANGE_DATE_FORMAT) }.getOrNull() }

        if (rangeStart == null || rangeEnd == null) {
            warnings += "Couldn't find/parse the report's date-range header -- falling back to " +
                "the current year for every row. Double-check the dates below before uploading."
        }

        fun resolveYear(month: Int, day: Int): Int {
            if (rangeStart != null && rangeEnd != null) {
                for (year in rangeStart.year..rangeEnd.year) {
                    val candidate = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
                    if (!candidate.isBefore(rangeStart) && !candidate.isAfter(rangeEnd)) return year
                }
                warnings += "A row dated $month/$day didn't fall inside the report's stated " +
                    "range ($rangeStart ~ $rangeEnd) for any year in that span -- used " +
                    "${rangeEnd.year}, but this needs a manual check."
                return rangeEnd.year
            }
            return LocalDate.now().year
        }

        val readings = mutableListOf<BpReading>()
        for (line in lines) {
            val match = READING_LINE.matchEntire(line) ?: continue

            val month = Month.entries.firstOrNull {
                it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).equals(match.groupValues[1], ignoreCase = true)
            }
            val day = match.groupValues[2].toIntOrNull()
            var hour = match.groupValues[3].toIntOrNull()
            val minute = match.groupValues[4].toIntOrNull()
            val systolic = match.groupValues[6].toIntOrNull()
            val diastolic = match.groupValues[7].toIntOrNull()
            val pulse = match.groupValues[8].toIntOrNull()

            if (month == null || day == null || hour == null || minute == null ||
                systolic == null || diastolic == null || pulse == null
            ) {
                warnings += "Line matched the reading pattern but a field failed to parse: \"$line\""
                continue
            }

            // Wide, deliberately generous sanity bounds -- not a clinical judgment about what's
            // a "normal" reading (a real, unusually high or low reading should still upload),
            // just a guard against a genuine parsing error producing an obviously-not-a-real-
            // blood-pressure-value number that would otherwise get silently aggregated in as if
            // it were real.
            if (systolic !in 40..350 || diastolic !in 20..250 || pulse !in 20..280) {
                warnings += "Line matched the reading pattern but produced an implausible " +
                    "value ($systolic/$diastolic mmHg, pulse $pulse) -- skipped rather than " +
                    "trusted: \"$line\""
                continue
            }

            val isPm = match.groupValues[5].equals("PM", ignoreCase = true)
            if (isPm && hour != 12) hour += 12
            if (!isPm && hour == 12) hour = 0

            val year = resolveYear(month.value, day)
            readings += BpReading(LocalDate.of(year, month, day), LocalTime.of(hour, minute), systolic, diastolic, pulse)
        }

        return ParseResult(readings.sortedWith(compareBy({ it.date }, { it.time })), warnings)
    }

    /**
     * One row per reading, no daily bucketing -- matches HealthConnectReader's own
     * `blood_pressure_systolic`/`blood_pressure_diastolic` point-in-time treatment exactly, plus
     * a `blood_pressure_pulse` series of its own (deliberately not folded into the general
     * heart-rate metric: a pulse taken during a BP measurement isn't the same clinical context as
     * continuous or exercise heart rate, and blending the two would misrepresent both).
     *
     * No completeness/"today" filtering needed here, unlike this app's daily-aggregated metrics
     * -- each reading is its own row with its own real timestamp and its own ID, so there's no
     * "incomplete bucket" to protect against finalizing too early. The `source_record_id` is
     * synthesized from the reading's own date+time (minute precision, matching what the PDF
     * actually contains) rather than a Health Connect record UUID, since there isn't one here --
     * still deterministic per reading, so a re-export covering the same reading produces the
     * same ID and gets deduped the same way.
     */
    fun toCsvRows(readings: List<BpReading>, owner: String): List<CsvRow> {
        val zone = ZoneId.systemDefault()
        return readings.flatMap { r ->
            val ts = ZonedDateTime.of(r.date, r.time, zone).toInstant()
            val idBase = "blood_pressure_${r.date}T%02d%02d".format(r.time.hour, r.time.minute)
            listOf(
                CsvRow(ts, owner, "blood_pressure_systolic", formatValue(r.systolicMmHg.toDouble()), "mmHg", "$idBase#systolic"),
                CsvRow(ts, owner, "blood_pressure_diastolic", formatValue(r.diastolicMmHg.toDouble()), "mmHg", "$idBase#diastolic"),
                CsvRow(ts, owner, "blood_pressure_pulse", formatValue(r.pulseBpm.toDouble()), "bpm", "$idBase#pulse"),
            )
        }
    }

    private fun formatValue(v: Double): String = String.format(Locale.US, "%.1f", v)
}
