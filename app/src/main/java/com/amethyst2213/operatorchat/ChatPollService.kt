package com.amethyst2213.operatorchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlin.random.Random

/**
 * Background notifier for the operator. Primary path: a Server-Sent Events
 * stream (/webhooks/chat/events) pushes new member messages the moment they
 * arrive, so notifications are near-instant. Fallback: a low-frequency,
 * adaptive inbox poller that only runs while the stream is unhealthy and
 * backs off (30s → 5min) to stay battery-friendly. In Doze/battery-saver it
 * idles instead of polling, and metered networks get the slowest interval.
 * Both share a per-conversation high-water mark so nothing notifies twice.
 */
class ChatPollService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    /** True when the SSE stream delivered a batch successfully recently. */
    @Volatile
    private var streamHealthy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Operator chat running"))
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        val url = SecurePrefs.url(this).trim().trimEnd('/')
        val token = SecurePrefs.token(this).trim()

        job?.cancel()
        if (url.isEmpty() || token.isEmpty()) {
            // No credentials (logged out, or a system restart with cleared
            // prefs): stop the foreground service instead of idling forever
            // with a persistent notification.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val bridge = ChatBridge(url, token)
        val seen = mutableMapOf<Long, Long>() // conversation -> last seen member message id
        job = scope.launch {
            launch { eventsPump(bridge, seen) }   // primary: SSE push
            launch { pollFallback(bridge, seen) } // fallback: 10s poll
        }
        return START_STICKY
    }

    /** Primary: hold the SSE events stream open; notify as member messages arrive. */
    private suspend fun eventsPump(bridge: ChatBridge, seen: MutableMap<Long, Long>) {
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        var since = prefs.getLong("events_since", 0L)
        var primed = since > 0L
        var backoffMs = 3000L
        while (scope.isActive) {
            try {
                val events = bridge.eventsOnce(since)
                streamHealthy = true
                backoffMs = 3000L
                if (events.isNotEmpty()) {
                    if (!primed) {
                        // First batch: prime the high-water marks so the
                        // operator is not spammed with pre-install history. If
                        // a message in that batch is genuinely fresh (created
                        // within the last 2 minutes), it is still surfaced —
                        // otherwise it would be swallowed silently.
                        val freshNow = events.any { recentEvent(it) }
                        events.forEach {
                            seen[it.conversationId] = it.id
                            if (freshNow) notifyFresh(it)
                        }
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
                // Stream dropped/reconnect: back off with jitter so the radio
                // is not hammered; the adaptive fallback covers the gap.
                streamHealthy = false
                delay(backoffMs + Random.nextLong(0, 2000))
                backoffMs = minOf(backoffMs * 2, 30000L)
            }
        }
    }

    /**
     * Fallback: only polls while the SSE stream is unhealthy, starting at 30s
     * and backing off to 5min. While the stream is healthy (or the device is
     * Dozing / on a metered network) it idles so it costs almost nothing.
     */
    private suspend fun pollFallback(bridge: ChatBridge, seen: MutableMap<Long, Long>) {
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        var primed = prefs.getLong("events_since", 0L) > 0L
        var backoffMs = 30000L
        while (scope.isActive) {
            // Healthy stream pushes live; nothing to catch up.
            if (streamHealthy || isPowerConstrained()) {
                delay(300000L)
                continue
            }
            val interval = if (isMetered()) 300000L else backoffMs
            try {
                // Walk every inbox page (up to 200 rows) so conversations past
                // the first 50 can still notify when the stream is down.
                var page = bridge.inbox()
                var seenRows = 0
                while (seenRows < 200 && page.items.isNotEmpty()) {
                    for (c in page.items) {
                        if (!notifyEnabledFor(c.id)) continue
                        if (c.unreadReplyable <= 0 && primed) continue
                        // Fetch the thread only for conversations that changed.
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
                    seenRows += page.items.size
                    page = bridge.inbox(cursor = page.nextCursor)
                }
                primed = true
                backoffMs = 30000L
            } catch (_: Exception) {
                backoffMs = minOf(backoffMs * 2, 300000L)
            }
            delay(interval + Random.nextLong(0, 5000))
        }
    }

    /** True when the device is Dozing or in battery-saver (skip polling). */
    private fun isPowerConstrained(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode || (Build.VERSION.SDK_INT >= 23 && pm.isDeviceIdleMode)
    }

    /** True when the active network is metered (use the slowest interval). */
    private fun isMetered(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
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

    /** True during the configured quiet hours (silent notifications). */
    private fun quietNow(): Boolean {
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        if (!prefs.getBoolean("quiet_hours_enabled", false)) return false
        val start = parseMinute(prefs.getString("quiet_start", "22:00"))
        val end = parseMinute(prefs.getString("quiet_end", "08:00"))
        val now = java.util.Calendar.getInstance()
        val m = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        return if (start <= end) m in start until end else m >= start || m < end
    }

    private fun parseMinute(value: String?): Int {
        val m = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(value.orEmpty())
        return if (m.matches()) {
            val h = ((m.group(1) ?: "0").toIntOrNull() ?: 0) % 24
            val min = ((m.group(2) ?: "0").toIntOrNull() ?: 0) % 60
            h * 60 + min
        } else 22 * 60
    }

    private fun actionPendingIntent(action: String, conversationId: Long): android.app.PendingIntent {
        val i = Intent(this, ChatActionReceiver::class.java).apply {
            this.action = action
            putExtra("conversation_id", conversationId)
        }
        return android.app.PendingIntent.getBroadcast(
            this,
            (conversationId + action.hashCode()).toInt(),
            i,
            if (Build.VERSION.SDK_INT >= 23) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            },
        )
    }

    private fun replyAction(conversationId: Long): NotificationCompat.Action {
        val pi = actionPendingIntent(ChatActionReceiver.ACTION_REPLY, conversationId)
        val remoteInput = androidx.core.app.RemoteInput.Builder(ChatActionReceiver.EXTRA_REPLY_TEXT)
            .setLabel("Reply")
            .build()
        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            pi,
        ).addRemoteInput(remoteInput).build()
    }

    private fun mutePendingIntent(conversationId: Long): android.app.PendingIntent =
        actionPendingIntent(ChatActionReceiver.ACTION_MUTE, conversationId)

    private fun markReadPendingIntent(conversationId: Long): android.app.PendingIntent =
        actionPendingIntent(ChatActionReceiver.ACTION_MARK_READ, conversationId)

    /** Tell MainActivity to refresh badges immediately. */
    private fun broadcastRefresh() {
        sendBroadcast(Intent(ACTION_CHAT_EVENT))
    }

    override fun onDestroy() {
        job?.cancel()
        scope.coroutineContext.cancel()
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

    /** True when an event's created_at is within the last 2 minutes. */
    private fun recentEvent(ev: ChatBridge.ChatEvent): Boolean {
        return try {
            val at = java.time.Instant.parse(ev.createdAt)
            java.time.Duration.between(at, java.time.Instant.now()).seconds in 0..120
        } catch (_: Exception) {
            false
        }
    }

    /** Notify a single fresh event during the priming batch. */
    private fun notifyFresh(ev: ChatBridge.ChatEvent) {
        if (!notifyEnabledFor(ev.conversationId)) return
        notifyNewEvent(ev)
        broadcastRefresh()
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
        if (tone != null && !quietNow()) {
            builder.setSound(android.net.Uri.parse(tone))
        }
        builder.setGroup("operator_chat")
            .addAction(replyAction(ev.conversationId))
            .addAction(0, "Mark read", markReadPendingIntent(ev.conversationId))
            .addAction(0, "Mute", mutePendingIntent(ev.conversationId))
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
        if (tone != null && !quietNow()) {
            builder.setSound(android.net.Uri.parse(tone))
        }
        builder.setGroup("operator_chat")
            .addAction(replyAction(c.id))
            .addAction(0, "Mark read", markReadPendingIntent(c.id))
            .addAction(0, "Mute", mutePendingIntent(c.id))
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
