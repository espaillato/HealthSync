package com.espaillat.healthsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class MergeRowsTest {
    private val plusNine = ZoneOffset.ofHours(9)

    /** A daily row's timestamp is always its own ID's date, exactly as in real use. */
    private fun row(id: String, value: String, replace: Boolean = false, metric: String = "steps"): CsvRow {
        val day = id.substringAfter("_daily_")
        return CsvRow(Instant.parse("${day}T00:00:00Z"), "Sam", metric, value, "count", id, replaceExisting = replace)
    }

    private val header = CsvRow.HEADER
    private val existing = "$header\n" +
        "2026-09-19T00:00:00+09:00,Sam,steps,30000,count,steps_daily_2026-09-19\n" +
        "2026-09-20T00:00:00+09:00,Sam,steps,10000,count,steps_daily_2026-09-20\n"

    private fun merge(text: String, vararg rows: CsvRow) = mergeRows(text, rows.toList(), plusNine)

    @Test fun plainDuplicateIsDropped() {
        val r = merge(existing, row("steps_daily_2026-09-19", "12000"))
        assertEquals(0, r.added); assertEquals(0, r.replaced); assertFalse(r.changed); assertEquals(existing, r.text)
    }

    @Test fun replaceFlaggedRowCorrectsValueInPlace() {
        val r = merge(existing, row("steps_daily_2026-09-19", "12000", replace = true))
        assertEquals(0, r.added); assertEquals(1, r.replaced); assertTrue(r.changed)
        assertEquals(existing.replace("30000", "12000"), r.text)
    }

    @Test fun replaceWithSameNumberLeavesFileAlone() {
        val r = merge(existing, row("steps_daily_2026-09-20", "10000.0", replace = true))
        assertEquals(0, r.replaced); assertFalse(r.changed); assertEquals(existing, r.text)
    }

    @Test fun newRowsAreAppendedAndReplacementsStillApplied() {
        val r = merge(existing, row("steps_daily_2026-09-19", "12000", replace = true), row("steps_daily_2026-09-21", "5000"))
        assertEquals(1, r.added); assertEquals(1, r.replaced)
        assertEquals(existing.replace("30000", "12000") + "2026-09-21T00:00:00+09:00,Sam,steps,5000,count,steps_daily_2026-09-21\n", r.text)
    }

    @Test fun emptyFileGetsRowsAfterTheHeader() {
        val r = merge("$header\n", row("steps_daily_2026-09-21", "5"))
        assertEquals("$header\n2026-09-21T00:00:00+09:00,Sam,steps,5,count,steps_daily_2026-09-21\n", r.text)
    }

    @Test fun fileWithoutTrailingNewlineStillAppendsCleanly() {
        val r = merge(existing.trimEnd('\n'), row("steps_daily_2026-09-21", "5"))
        assertEquals(existing + "2026-09-21T00:00:00+09:00,Sam,steps,5,count,steps_daily_2026-09-21\n", r.text)
    }

    @Test fun replaceForAnIdNotOnDriveIsJustAnInsert() {
        val r = merge(existing, row("steps_daily_2026-09-22", "7", replace = true))
        assertEquals(1, r.added); assertEquals(0, r.replaced)
    }

    @Test fun stagedLineRoundTripKeepsTheReplaceFlagAndInstant() {
        val flagged = row("steps_daily_2026-09-19", "12000", replace = true)
        assertEquals(flagged, CsvRow.fromCsvLine(flagged.toStagedLine()))
        val plain = row("steps_daily_2026-09-19", "12000")
        assertEquals(plain, CsvRow.fromCsvLine(plain.toStagedLine()))
        val instantRow = CsvRow(Instant.parse("2026-08-07T03:53:18.250Z"), "Sam", "blood_pressure_systolic", "123.0", "mmHg", "abc#systolic")
        assertEquals(instantRow, CsvRow.fromCsvLine(instantRow.toStagedLine()))
        assertNull(CsvRow.fromCsvLine("a,b,c"))
    }

    @Test fun replaceFlagNeverLeaksIntoTheDriveLine() {
        assertEquals(6, row("steps_daily_2026-09-19", "1", replace = true).toCsvLine(plusNine).split(",").size)
    }

    // ---- timestamp format ----

    @Test fun dailyRowIsLocalMidnightOfItsDate() {
        assertEquals("2026-09-20T00:00:00+09:00", row("steps_daily_2026-09-20", "1").localTimestamp(plusNine))
        // a negative-offset zone must still land on the same date, not the day before
        assertEquals("2026-09-20T00:00:00-05:00", row("steps_daily_2026-09-20", "1").localTimestamp(ZoneOffset.ofHours(-5)))
    }

    @Test fun realInstantIsConvertedToLocalWholeSeconds() {
        val r = CsvRow(Instant.parse("2026-08-07T03:53:18.670Z"), "Sam", "blood_pressure_systolic", "123.0", "mmHg", "abc#systolic")
        assertEquals("2026-08-07T12:53:18+09:00", r.localTimestamp(plusNine))
    }

    @Test fun rowIsFiledUnderItsLocalYear() {
        val r = CsvRow(Instant.parse("2026-12-31T20:00:00Z"), "Sam", "weight", "70.0", "kg", "uuid1")
        assertEquals(2027, r.fileYear(plusNine)) // 2027-01-01 05:00 local, though still 2026 in UTC
        assertEquals(2026, row("steps_daily_2026-12-31", "1").fileYear(plusNine))
    }

    // ---- legacy conversion ----

    private val legacy = CsvRow.LEGACY_HEADER + "\n" +
        "2026-09-19T00:00:00Z,Sam,steps,12000,count,steps_daily_2026-09-19\n" +
        "2026-08-06T18:53:18.670Z,Sam,blood_pressure_pulse,90.0,bpm,blood_pressure_2026-08-07T1253#pulse\n"

    @Test fun legacyFileIsConvertedOnceAndIsIdempotent() {
        val converted = migrateLegacyTimestamps(legacy, plusNine)
        assertEquals(
            CsvRow.HEADER + "\n" +
                "2026-09-19T00:00:00+09:00,Sam,steps,12000,count,steps_daily_2026-09-19\n" +
                "2026-08-07T03:53:18+09:00,Sam,blood_pressure_pulse,90.0,bpm,blood_pressure_2026-08-07T1253#pulse\n",
            converted,
        )
        assertEquals(converted, migrateLegacyTimestamps(converted, plusNine))
    }

    @Test fun mergeConvertsALegacyFileEvenWhenThereIsNothingToAdd() {
        val r = mergeRows(legacy, listOf(row("steps_daily_2026-09-19", "12000")), plusNine)
        assertEquals(0, r.added); assertTrue(r.changed)
        assertTrue(r.text.startsWith(CsvRow.HEADER + "\n2026-09-19T00:00:00+09:00"))
    }

    @Test fun mergeAppendsNewFormatRowsToAConvertedLegacyFile() {
        val r = mergeRows(legacy, listOf(row("steps_daily_2026-09-21", "5")), plusNine)
        assertEquals(1, r.added)
        assertFalse(r.text.contains("Z,"))
        assertTrue(r.text.endsWith("2026-09-21T00:00:00+09:00,Sam,steps,5,count,steps_daily_2026-09-21\n"))
    }

    @Test fun emptyOrHeaderlessTextIsNotTreatedAsLegacy() {
        assertEquals("", migrateLegacyTimestamps("", plusNine))
        assertEquals(existing, migrateLegacyTimestamps(existing, plusNine))
    }
}
