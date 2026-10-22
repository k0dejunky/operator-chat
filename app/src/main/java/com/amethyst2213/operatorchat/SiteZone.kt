package com.amethyst2213.operatorchat

import android.content.Context

/**
 * The timezone the server stamps its timestamps in.
 *
 * The app used to read 'site_timezone' out of its preferences, but nothing
 * ever wrote it, so every timestamp it showed was in a hardcoded zone - the
 * inbox was ordered wrongly and every "2 min ago" was off by the offset
 * whenever the phone was not in the site's timezone.
 */
object SiteZone {

    private const val KEY = "site_timezone"

    /** Remember the site's timezone. */
    fun save(context: Context, zone: String) {
        val clean = zone.trim()
        if (clean.isEmpty()) return
        try {
            context.getSharedPreferences("operator_chat", Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, clean)
                .apply()
        } catch (_: Exception) {
        }
    }

    /** The site's timezone, or the default when it was never learned. */
    fun read(context: Context): String =
        try {
            context.getSharedPreferences("operator_chat", Context.MODE_PRIVATE)
                .getString(KEY, ChatRows.DEFAULT_ZONE)
                ?.takeIf { it.isNotBlank() }
                ?: ChatRows.DEFAULT_ZONE
        } catch (_: Exception) {
            ChatRows.DEFAULT_ZONE
        }
}
