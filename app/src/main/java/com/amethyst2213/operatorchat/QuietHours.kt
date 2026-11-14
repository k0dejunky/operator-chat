package com.amethyst2213.operatorchat

import android.content.Context

/**
 * The quiet hours.
 *
 * The setting is shared by every notification the operator gets, so the
 * quiet hours are read in one place: a member message and a live chat
 * message must both go quiet at the same hour.
 */
object QuietHours {

    /** True when the operator asked for silence right now. */
    fun isQuietNow(context: Context): Boolean {
        val prefs = context.getSharedPreferences("operator_chat", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("quiet_hours_enabled", false)) return false
        val start = parseMinute(prefs.getString("quiet_start", "22:00"))
        val end = parseMinute(prefs.getString("quiet_end", "08:00"))
        val now = java.util.Calendar.getInstance()
        val minutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return if (start <= end) {
            minutes in start until end
        } else {
            minutes >= start || minutes < end
        }
    }

    /** "22:30" -> 1350. Anything unreadable means the default, 22:00. */
    fun parseMinute(value: String?): Int {
        val m = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(value.orEmpty())
        if (!m.matches()) return 22 * 60
        val h = ((m.group(1) ?: "0").toIntOrNull() ?: 0) % 24
        val min = ((m.group(2) ?: "0").toIntOrNull() ?: 0) % 60
        return h * 60 + min
    }
}
