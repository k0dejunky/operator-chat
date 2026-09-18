package com.amethyst2213.operatorchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background notifier for the operator. Primary path: a Server-Sent Events
 * stream (/webhooks/chat/events) pushes new member messages the moment they
 * arrive, so notifications are near-instant. Fallback: a 10s inbox poller
 * catches anything the stream missed. Both share a per-conversation
 * high-water mark so nothing notifies twice.
 */
class ChatPollService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Operator chat running"))
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        val url = SecurePrefs.url(this).trim().trimEnd('/')
        val token = SecurePrefs.token(this).trim()

        job?.cancel()
        if (url.isNotEmpty() && token.isNotEmpty()) {
            val bridge = ChatBridge(url, token)
            val seen = mutableMapOf<Long, Long>() // conversation -> last seen member message id
            job = scope.launch {
                launch { eventsPump(bridge, seen) }   // primary: SSE push
                launch { pollFallback(bridge, seen) } // fallback: 10s poll
            }
        }
        return START_STICKY
    }

    /** Primary: hold the SSE events stream open; notify as member messages arrive. */
    private suspend fun eventsPump(bridge: ChatBridge, seen: MutableMap<Long, Long>) {
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        var since = prefs.getLong("events_since", 0L)
        var primed = since > 0L
        while (scope.isActive) {
            try {
                val events = bridge.eventsOnce(since)
                if (events.isNotEmpty()) {
                    if (!primed) {
                        // First batch just primes the high-water marks.
                        events.forEach { seen[it.conversationId] = it.id }
                        since = events.maxOf { it.id }
                        prefs.edit().putLong("events_since", since).apply()
                        primed = true
                        continue
                    }
                    events.forEach { ev ->
                        val last = seen[ev.conversationId] ?: 0L
                        if (ev.id > last) {
                            seen[ev.conversationId] = ev.id
                            if (notifyEnabledFor(ev.conversationId)) {
                                notifyNewEvent(ev)
                                broadcastRefresh()
                            }
                        }
                    }
                    since = maxOf(since, events.maxOf { it.id })
                    prefs.edit().putLong("events_since", since).apply()
                }
            } catch (_: Exception) {
                // stream dropped/reconnect; the poller covers the gap
                delay(3000)
            }
        }
    }

    /** Fallback: poll the inbox every 10s in case the stream was disconnected. */
    private suspend fun pollFallback(bridge: ChatBridge, seen: MutableMap<Long, Long>) {
        var primed = false
        while (scope.isActive) {
            try {
                val convs = bridge.inbox()
                for (c in convs) {
                    if (!notifyEnabledFor(c.id)) continue
                    val msgs = bridge.thread(c.id)
                    val newestMember = msgs.filter { it.senderRole == "user" }.maxOfOrNull { it.id } ?: 0
                    val last = seen[c.id] ?: 0L
                    if (primed && newestMember > last && newestMember > 0) {
                        seen[c.id] = newestMember
                        val tone = toneFor(c.id)
                        notifyNewMessage(c, tone)
                        broadcastRefresh()
                    } else if (newestMember > last || last == 0L) {
                        seen[c.id] = newestMember
                    }
                }
                primed = true
            } catch (_: Exception) {
                // transient; retry
            }
            delay(10000)
        }
    }

    private fun notifyEnabledFor(conversationId: Long): Boolean {
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        if (!prefs.getBoolean("notify_enabled", true)) return false
        return prefs.getBoolean("chat_${conversationId}_notify", true)
    }

    private fun toneFor(conversationId: Long): String? {
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        return prefs.getString("chat_${conversationId}_tone", null)
            ?: prefs.getString("notify_tone", null)
    }

    /** Tell MainActivity to refresh badges immediately. */
    private fun broadcastRefresh() {
        sendBroadcast(Intent(ACTION_CHAT_EVENT))
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): android.app.Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Operator chat", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Operator Chat")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun openAppIntent(): android.app.PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return android.app.PendingIntent.getActivity(this, 0, i,
            if (Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
    }

    private fun notifyNewEvent(ev: ChatBridge.ChatEvent) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_NEW, "New member messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val name = ev.username.ifEmpty { "member" }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_NEW)
            .setContentTitle("New message from $name")
            .setContentText(ev.message.take(120))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(ev.conversationId))
        val tone = toneFor(ev.conversationId)
        if (tone != null) {
            builder.setSound(android.net.Uri.parse(tone))
        }
        NotificationManagerCompat.from(this).notify((1000 + ev.conversationId).toInt(), builder.build())
    }

    private fun notifyNewMessage(c: ChatBridge.Conversation, tone: String?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_NEW, "New member messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val name = c.username.ifEmpty { c.userEmail }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID_NEW)
            .setContentTitle("New message from $name")
            .setContentText("${c.unreadReplyable} new message(s) — tap to open")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(c.id))
        if (tone != null) {
            builder.setSound(android.net.Uri.parse(tone))
        }
        NotificationManagerCompat.from(this).notify((1000 + c.id).toInt(), builder.build())
    }

    private fun openChatIntent(conversationId: Long): android.app.PendingIntent {
        val i = Intent(this, ChatActivity::class.java).apply {
            putExtra("base_url", SecurePrefs.url(this@ChatPollService))
            putExtra("conversation_id", conversationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return android.app.PendingIntent.getActivity(
            this,
            conversationId.toInt(),
            i,
            if (Build.VERSION.SDK_INT >= 23) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "operator_chat_foreground"
        private const val CHANNEL_ID_NEW = "operator_chat_new"
        private const val NOTIF_ID = 1001
        const val ACTION_CHAT_EVENT = "com.amethyst2213.operatorchat.CHAT_EVENT"
    }
}
