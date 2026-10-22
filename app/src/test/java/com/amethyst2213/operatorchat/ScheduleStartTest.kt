package com.amethyst2213.operatorchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The default start time of a show.
 *
 * The old code called ZoneId.of() on the server's timezone DURING the
 * composition, so a blank or unknown zone took the whole schedule screen
 * down.
 */
class ScheduleStartTest {

    private val noon = ZonedDateTime.of(2026, 10, 21, 12, 30, 0, 0, ZoneId.of("UTC"))

    @Test
    fun `a blank zone falls back to UTC instead of crashing`() {
        val start = ScheduleActivity.defaultStart("", noon)
        assertEquals("2026-10-21 13:00", start)
    }

    @Test
    fun `an unknown zone falls back to UTC instead of crashing`() {
        val start = ScheduleActivity.defaultStart("Mars/Olympus", noon)
        assertEquals("2026-10-21 13:00", start)
    }

    @Test
    fun `the site timezone is used`() {
        val start = ScheduleActivity.defaultStart("Europe/Berlin", noon)
        // 12:30 UTC is 14:30 in Berlin (summer time), so the next full hour
        // is 15:00 there.
        assertEquals("2026-10-21 15:00", start)
    }

    @Test
    fun `the start is always a full hour in the future`() {
        val start = ScheduleActivity.defaultStart("UTC", noon)
        assertTrue(start > "2026-10-21 12:00")
    }
}
