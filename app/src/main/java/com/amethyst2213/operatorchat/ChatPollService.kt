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
import kotlinx.coroutines.launch

/**
 * Background poller that alerts the operator when any conversation needs a
 * reply. Uses the inbox endpoint (no chat id needed) and tracks a per-
 * conversation high-water mark to notify only on new member messages.
 */
class ChatPollService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Operator chat running"))
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        val url = prefs.getString("url", "")?.trim()?.trimEnd('/') ?: ""
        val token = prefs.getString("token", "")?.trim() ?: ""

        job?.cancel()
        if (url.isNotEmpty() && token.isNotEmpty()) {
            val bridge = ChatBridge(url, token)
            job = scope.launch {
                // high-water mark per conversation: id -> last seen member message id
                val seen = mutableMapOf<Long, Long>()
                // First pass primes the high-water marks without notifying, so
                // pre-existing unread messages don't trigger alerts on start.
                var primed = false
                while (true) {
                    try {
                        val convs = bridge.inbox()
                        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
                        val globalOn = prefs.getBoolean("notify_enabled", true)
                        val defaultTone = prefs.getString("notify_tone", null)
                        for (c in convs) {
                            val chatKey = "chat_${c.id}"
                            if (!prefs.getBoolean("${chatKey}_notify", true)) continue

                            val msgs = bridge.thread(c.id)
                            val newestMember = msgs.filter { it.senderRole == "user" }.maxOfOrNull { it.id } ?: 0
                            val last = seen[c.id] ?: 0L

                            // Only notify for genuinely new member messages, and
                            // only after the initial high-water mark is primed.
                            if (globalOn && primed && newestMember > last && newestMember > 0) {
                                val tone = prefs.getString("${chatKey}_tone", null) ?: defaultTone
                                notifyNewMessage(c, tone)
                            }
                            if (newestMember > last || last == 0L) {
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
        }
        return START_STICKY
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
            .setContentIntent(openAppIntent())
        if (tone != null) {
            builder.setSound(android.net.Uri.parse(tone))
        }
        NotificationManagerCompat.from(this).notify((1000 + c.id).toInt(), builder.build())
    }

    companion object {
        private const val CHANNEL_ID = "operator_chat_foreground"
        private const val CHANNEL_ID_NEW = "operator_chat_new"
        private const val NOTIF_ID = 1001
    }
}