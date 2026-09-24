package com.amethyst2213.operatorchat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Persisted outbox for operator replies. When a send fails (offline), the
 * message is stored here with its idempotency key and a WorkManager worker is
 * scheduled to deliver it with exponential backoff. The server's idempotency
 * key guarantees the retry never duplicates the message. Text-only messages
 * are stored inline; attachments are first copied to the app files dir so the
 * original cache file going stale cannot lose the pending upload.
 */
object SendQueue {

    private const val PREFS = "operator_chat"
    private const val KEY = "send_queue"
    private const val UNIQUE = "offline-send-drain"
    private const val ATTACH_DIR = "attachments"

    data class Pending(
        val conversationId: Long,
        val message: String,
        val key: String,
        val attachmentPath: String? = null,
    )

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
                    Pending(
                        o.optLong("conversation_id", 0),
                        o.optString("message", ""),
                        k,
                        o.optString("attachment_path", "").ifEmpty { null },
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(
        context: Context,
        conversationId: Long,
        message: String,
        key: String,
        attachmentPath: String? = null,
    ) {
        val list = pending(context).toMutableList()
        if (list.none { it.key == key }) {
            list.add(Pending(conversationId, message, key, attachmentPath))
            save(context, list)
        }
    }

    fun remove(context: Context, key: String) {
        val stale = pending(context).filter { it.key == key }
        save(context, pending(context).filter { it.key != key })
        // Clean up stashed attachment files that will never be sent.
        stale.forEach { p ->
            p.attachmentPath?.let { path ->
                val f = File(path)
                if (f.parentFile?.name == ATTACH_DIR) f.delete()
            }
        }
    }

    fun hasPending(context: Context): Boolean = pending(context).isNotEmpty()

    /**
     * Copy an attachment into the persistent outbox dir (survives cache
     * clears) and return its path, or null when the copy fails.
     */
    fun stashAttachment(context: Context, src: File, key: String): String? {
        return try {
            val dir = File(context.filesDir, ATTACH_DIR).apply { mkdirs() }
            val safeName = src.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(dir, "${key}_$safeName")
            src.inputStream().use { ins -> dest.outputStream().use { ous -> ins.copyTo(ous) } }
            dest.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun schedule(context: Context) {
        if (!hasPending(context)) return
        // Only attempt delivery when there is a network, so the worker does not
        // burn backoff cycles (and battery) while offline.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SendReplyWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP: never stack drain workers behind a stuck/backing-off one. The
        // periodic re-drain worker (enqueued at app start) is the safety net
        // that catches anything WorkManager eventually gives up on.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
    }

    /** Periodic re-drain safety net so a message never waits forever after
     *  WorkManager exhausts its retry budget. Enqueued once at app start. */
    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = androidx.work.PeriodicWorkRequestBuilder<SendReplyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "offline-send-drain-periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
    }

    private fun save(context: Context, list: List<Pending>) {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject()
                .put("conversation_id", it.conversationId)
                .put("message", it.message)
                .put("key", it.key)
            if (it.attachmentPath != null) o.put("attachment_path", it.attachmentPath)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}