package com.amethyst2213.operatorchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typing indicator must appear as soon as a `typing: true` frame lands,
 * survive the ~3 s gaps between the server's re-emissions, and disappear
 * within one TTL when the frames stop (dropped stream, closed tab).
 */
class TypingPulseTest {

    @Test
    fun `true activates and the ttl bounds the shown time`() {
        val pulse = TypingPulse(ttlMs = 7000L)
        assertTrue(pulse.onSignal(true, nowMs = 1000L))
        assertTrue("gaps inside the ttl stay on", pulse.isActive(7999L))
        assertTrue("the boundary is included", pulse.isActive(8000L))
        assertFalse("older than ttl turns off", pulse.isActive(8001L))
    }

    @Test
    fun `false clears immediately`() {
        val pulse = TypingPulse()
        assertTrue(pulse.onSignal(true, 0L))
        assertFalse(pulse.onSignal(false, 100L))
        assertFalse(pulse.isActive(100L))
    }

    @Test
    fun `re-emitted trues keep the indicator alive`() {
        val pulse = TypingPulse(ttlMs = 7000L)
        pulse.onSignal(true, 0L)
        pulse.onSignal(true, 5000L)
        assertTrue("last true was 6s ago", pulse.isActive(11000L))
        assertFalse("13s after the last true", pulse.isActive(12001L))
    }

    @Test
    fun `clear wipes the state`() {
        val pulse = TypingPulse()
        pulse.onSignal(true, 0L)
        pulse.clear()
        assertFalse(pulse.isActive(0L))
        assertFalse(pulse.isActive(1L))
    }

    @Test
    fun `a signal that never came stays inactive`() {
        val pulse = TypingPulse()
        assertFalse(pulse.isActive(0L))
        assertFalse(pulse.isActive(Long.MAX_VALUE / 2))
    }
}
