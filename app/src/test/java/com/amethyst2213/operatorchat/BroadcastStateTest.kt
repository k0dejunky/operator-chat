package com.amethyst2213.operatorchat

import com.amethyst2213.operatorchat.live.BroadcastState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broadcast state machine.
 *
 * The old screen showed a "Live" label whatever happened: a failed
 * connection, a dropped stream and a deliberate stop all looked the same,
 * and the operator had no way to tell a dead broadcast from a live one.
 * These tests pin the states the screen can be in.
 */
class BroadcastStateTest {

    @Test
    fun `only a live broadcast counts as live`() {
        assertTrue(BroadcastState.Live().isLive)
        assertFalse(BroadcastState.Idle.isLive)
        assertFalse(BroadcastState.Preparing.isLive)
        assertFalse(BroadcastState.Connecting.isLive)
        assertFalse(BroadcastState.Stopped().isLive)
        assertFalse(BroadcastState.Failed("nope").isLive)
    }

    @Test
    fun `a failure carries the reason the operator has to read`() {
        val state = BroadcastState.Failed("The server refused the broadcast.")
        assertTrue(state.reason.contains("refused"))
    }

    @Test
    fun `a stop carries its reason`() {
        assertEquals("Stopped", BroadcastState.Stopped("Stopped").reason)
        assertEquals("", BroadcastState.Stopped().reason)
    }
}
