package com.amethyst2213.operatorchat.live

/**
 * The state of the broadcast.
 *
 * The old screen expressed "am I live" with two booleans
 * (rtmpUrl != null and stream?.isStreaming), which is why every edge case
 * went wrong: a failed connection still showed the live controls, a
 * rotation left the screen saying "Rotating…" forever, and a stream that
 * had never started looked exactly like one that had.
 */
sealed interface BroadcastState {

    /** Nothing is running. */
    data object Idle : BroadcastState

    /** The camera and the encoder are being set up. */
    data object Preparing : BroadcastState

    /** The encoder is up and the RTMP handshake is running. */
    data object Connecting : BroadcastState

    /** On air. */
    data class Live(
        val viewers: Int = 0,
        val since: String = "",
        val paused: Boolean = false,
        val savingRecording: Boolean = false,
    ) : BroadcastState

    /** The broadcast stopped, with the reason it stopped. */
    data class Stopped(val reason: String = "") : BroadcastState

    /** Something failed, and the operator can see what. */
    data class Failed(val reason: String) : BroadcastState

    val isLive: Boolean get() = this is Live
    val isBusy: Boolean
        get() = this is Preparing || this is Connecting || this is Stopped
}
