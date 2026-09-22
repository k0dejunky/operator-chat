package com.amethyst2213.operatorchat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Persisted outbox for operator replies. When a send fails (offline), the
 * message is stored here with its idempotency key and a WorkManager worker is
 * scheduled to deliver it with exponential backoff. The server's idempotency
 * key guarantees the retry never duplicates the message.
 */
object SendQueue {

    private const val PREFS = "operator_chat"
    private const val KEY = "send_queue"
    private const val UNIQUE = "offline-send-drain"

    data class Pending(val conversationId: Long, val message: String, val key: String)

    fun pending(context: Context): List<Pending> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val k = o.optString("key", "")
                if (k.isEmpty()) {
                    null
                } else {
                    Pending(o.optLong("conversation_id", 0), o.optString("message", ""), k)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, conversationId: Long, message: String, key: String) {
        val list = pending(context).toMutableList()
        if (list.none { it.key == key }) {
            list.add(Pending(conversationId, message, key))
            save(context, list)
        }
    }

    fun remove(context: Context, key: String) {
        save(context, pending(context).filter { it.key != key })
    }

    fun hasPending(context: Context): Boolean = pending(context).isNotEmpty()

    fun schedule(context: Context) {
        if (!hasPending(context)) return
        val request = OneTimeWorkRequestBuilder<SendReplyWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun save(context: Context, list: List<Pending>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("conversation_id", it.conversationId)
                    .put("message", it.message)
                    .put("key", it.key)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}