package com.amethyst2213.operatorchat

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Favourite users persisted on-device (per operator). Stored as a JSON array
 * of conversation ids; favourites are pinned to the top of the users list.
 */
object Favorites {

    private const val PREFS = "operator_chat"
    private const val KEY = "favorites"

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(c: Context): List<Long> {
        val raw = prefs(c).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isFavorite(c: Context, conversationId: Long): Boolean =
        list(c).contains(conversationId)

    fun toggle(c: Context, conversationId: Long): Boolean {
        val current = list(c).toMutableList()
        val on = if (current.contains(conversationId)) {
            current.remove(conversationId); false
        } else {
            current.add(conversationId); true
        }
        val arr = JSONArray(current)
        prefs(c).edit().putString(KEY, arr.toString()).apply()
        return on
    }
}