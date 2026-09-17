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
                while (true) {
                    try {
                        val convs = bridge.inbox()
                        var newTotal = 0
                        for (c in convs) {
                            // refresh the thread to learn the newest member message id
                            val msgs = bridge.thread(c.id)
                            val newestMember = msgs.filter { it.senderRole == "user" }.maxOfOrNull { it.id } ?: 0
                            val last = seen[c.id] ?: 0L
                            if (newestMember > last) {
                                newTotal += 1
                            }
                            if (newestMember > last || last == 0L) {
                                seen[c.id] = newestMember
                            }
                        }
                        if (newTotal > 0) {
                            notifyNewMessages(newTotal)
                        }
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
            .build()
    }

    private fun notifyNewMessages(count: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_NEW, "New member messages", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ID_NEW)
            .setContentTitle("New chat message")
            .setContentText("$count conversation(s) have a new member message")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(999, n)
    }

    companion object {
        private const val CHANNEL_ID = "operator_chat_foreground"
        private const val CHANNEL_ID_NEW = "operator_chat_new"
        private const val NOTIF_ID = 1001
    }
}