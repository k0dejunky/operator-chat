package com.amethyst2213.operatorchat

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles actions attached to new-message notifications (e.g. "Mute").
 * Exported=false: only the app can trigger it.
 */
class ChatActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getLongExtra("conversation_id", 0)
        when (intent.action) {
            ACTION_MUTE -> {
                context.getSharedPreferences("operator_chat", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("chat_${conversationId}_notify", false)
                    .apply()
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel((1000 + conversationId).toInt())
            }
        }
    }

    companion object {
        const val ACTION_MUTE = "com.amethyst2213.operatorchat.action.MUTE"
    }
}