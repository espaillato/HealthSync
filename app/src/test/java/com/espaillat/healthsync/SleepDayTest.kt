package com.espaillat.healthsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class SleepDayTest {
    private val zone = ZoneOffset.ofHours(9)
    private fun at(local: String): Instant = LocalDateTime.parse(local).toInstant(zone)
    private fun session(start: String, end: String) = at(start) to at(end)

    @Test fun noonIsTheBoundary() {
        assertEquals(LocalDate.of(2026, 9, 23), sleepDayOf(at("2026-09-24T11:59:59"), zone))
        assertEquals(LocalDate.of(2026, 9, 24), sleepDayOf(at("2026-09-24T12:00:00"), zone))
    }

    /** The real sessions Health Connect held for one phone on 2026-09-20 .. 09-25 (see docs). */
    private val realSessions = listOf(
        session("2026-09-20T03:30:00", "2026-09-20T11:00:30"),
        session("2026-09-21T03:58:00", "2026-09-21T10:57:30"),
        session("2026-09-22T03:56:00", "2026-09-22T09:34:00"),
        session("2026-09-23T01:30:00", "2026-09-23T11:03:00"),
        session("2026-09-23T22:29:00", "2026-09-23T23:43:00"),
        session("2026-09-24T03:33:00", "2026-09-24T13:19:00"),   // ends after noon
        session("2026-09-24T19:54:00", "2026-09-24T21:25:00"),
        session("2026-09-25T02:15:00", "2026-09-25T09:15:30"),
    )

    @Test fun aNightThatEndsAfterNoonStaysWithTheNightItStartedIn() {
        val byDay = sleepMinutesByDay(realSessions, zone)
        // 09-23 = the evening nap (74) + the night that began 03:33 on 09-24 and ran to 13:19 (586)
        assertEquals(74L + 586L, byDay[LocalDate.of(2026, 9, 23)])
        // 09-24 = the evening nap (91) + the night that began 02:15 on 09-25 (420) -- not 1,097
        assertEquals(91L + 420L, byDay[LocalDate.of(2026, 9, 24)])
    }

    @Test fun nightsThatEndBeforeNoonAreUnchangedFromTheOldEndBasedBucketing() {
        val byDay = sleepMinutesByDay(realSessions, zone)
        assertEquals(450L, byDay[LocalDate.of(2026, 9, 19)])
        assertEquals(419L, byDay[LocalDate.of(2026, 9, 20)])
        assertEquals(338L, byDay[LocalDate.of(2026, 9, 21)])
        assertEquals(573L, byDay[LocalDate.of(2026, 9, 22)])
    }

    @Test fun endBasedBucketingWouldHaveMergedTwoNights() {
        // documents the bug this replaced: by END time 09-24 collects all three sessions
        val endBased = realSessions.groupBy { sleepDayOf(it.second, zone) }
            .mapValues { (_, v) -> v.sumOf { java.time.Duration.between(it.first, it.second).toMinutes() } }
        assertEquals(586L + 91L + 420L, endBased[LocalDate.of(2026, 9, 24)])
        assertFalse(endBased.containsKey(LocalDate.of(2026, 9, 25)))
    }
}
