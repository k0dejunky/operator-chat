package com.amethyst2213.operatorchat

import com.amethyst2213.operatorchat.ui.screens.formatCountdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The countdown and the show schedule.
 *
 * The start time is absolute and in UTC, so every device counts down to the
 * same instant; these tests pin the formatting and the boundary cases.
 */
class ScheduleTest {

    @Test
    fun `a show in the future counts down`() {
        assertEquals("2h 5m", formatCountdown(2 * 3600 + 5 * 60))
        assertEquals("30m", formatCountdown(30 * 60))
        assertEquals("1d 3h", formatCountdown(27 * 3600))
    }

    @Test
    fun `a show that is due now says so`() {
        assertEquals("now", formatCountdown(0))
        assertEquals("now", formatCountdown(-60))
    }

    @Test
    fun `a show that is seconds away does not say zero minutes`() {
        // 90 seconds is "1m", not "0m": an operator reading "in 0m" would
        // think the show had already started.
        assertEquals("1m", formatCountdown(90))
    }

    @Test
    fun `the countdown never goes backwards`() {
        var remaining = 3_600L
        var previous = formatCountdown(remaining)
        repeat(10) {
            remaining -= 120
            val now = formatCountdown(remaining)
            assertTrue("$now should not be longer than $previous", now.length <= previous.length)
            previous = now
        }
    }
}
