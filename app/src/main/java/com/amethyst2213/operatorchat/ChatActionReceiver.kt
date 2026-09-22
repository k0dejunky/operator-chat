package com.amethyst2213.operatorchat

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Handles actions attached to new-message notifications: Mute, Mark read, and
 * Direct Reply (reads the RemoteInput text, queues it offline-safe, and
 * delivers it automatically).
 */
class ChatActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getLongExtra("conversation_id", 0)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            ACTION_MUTE -> {
                context.getSharedPreferences("operator_chat", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("chat_${conversationId}_notify", false)
                    .apply()
                nm.cancel((1000 + conversationId).toInt())
            }

            ACTION_MARK_READ -> {
                nm.cancel((1000 + conversationId).toInt())
                markRead(context, conversationId)
            }

            ACTION_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence("reply_text")?.toString()?.trim().orEmpty()
                if (replyText.isNotEmpty()) {
                    val key = UUID.randomUUID().toString()
                    SendQueue.add(context, conversationId, replyText, key)
                    SendQueue.schedule(context)
                }
                nm.cancel((1000 + conversationId).toInt())
            }
        }
    }

    private fun markRead(context: Context, conversationId: Long) {
        val url = SecurePrefs.url(context)
        val token = SecurePrefs.token(context)
        if (url.isEmpty() || token.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ChatBridge(url, token).setRead(conversationId)
            } catch (_: Exception) {
                // best-effort
            }
        }
    }

    companion object {
        const val ACTION_MUTE = "com.amethyst2213.operatorchat.action.MUTE"
        const val ACTION_MARK_READ = "com.amethyst2213.operatorchat.action.MARK_READ"
        const val ACTION_REPLY = "com.amethyst2213.operatorchat.action.REPLY"
        const val EXTRA_REPLY_TEXT = "reply_text"
    }
}