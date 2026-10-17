package com.amethyst2213.operatorchat

/**
 * Expiring "member is typing" signal.
 *
 * The server re-emits `typing: true` on every stream frame while the member
 * types (their composer pings every ~3 s), so a frame is authoritative for
 * "now". The expiry exists for the gaps: a dropped stream or a member who
 * closed the tab must not leave the indicator stuck on screen.
 *
 * Pure on purpose — unit-testable without Android.
 */
class TypingPulse(private val ttlMs: Long = 7000L) {
    private var lastTrueAtMs: Long? = null

    /** Feed a server frame. Returns the indicator state right after it. */
    fun onSignal(typing: Boolean, nowMs: Long): Boolean {
        lastTrueAtMs = if (typing) nowMs else null
        return isActive(nowMs)
    }

    /** Current state; false once the last true is older than the TTL. */
    fun isActive(nowMs: Long): Boolean {
        val at = lastTrueAtMs ?: return false
        return nowMs - at <= ttlMs
    }

    fun clear() {
        lastTrueAtMs = null
    }
}
