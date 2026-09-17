package com.amethyst2213.operatorchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the chat bridge contract. Talks only to the site webhooks —
 * no AI logic, no API keys on device.
 */
class ChatBridge(private val baseUrl: String, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authed(): Request.Builder = Request.Builder()
        .header("Authorization", "Bearer $token")

    data class Conversation(
        val id: Long,
        val userEmail: String,
        val aiMode: String,
        val status: String,
        val lastMessage: String,
        val lastSender: String,
        val memberCount: Int,
        val unreadReplyable: Int,
        val updatedAt: String,
    )

    data class Message(
        val id: Long,
        val conversationId: Long,
        val senderRole: String,
        val message: String,
        val createdAt: String,
    )

    /** GET /webhooks/chat/inbox — all conversations (no chat id needed). */
    suspend fun inbox(): List<Conversation> = withContext(Dispatchers.IO) {
        val req = authed().url("$baseUrl/webhooks/chat/inbox").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("conversations") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Conversation(
                    id = c.optLong("id", 0),
                    userEmail = c.optString("user_email", ""),
                    aiMode = c.optString("ai_mode", "retrieval"),
                    status = c.optString("status", "open"),
                    lastMessage = c.optString("last_message", ""),
                    lastSender = c.optString("last_sender", ""),
                    memberCount = c.optInt("member_count", 0),
                    unreadReplyable = c.optInt("unread_replyable", 0),
                    updatedAt = c.optString("updated_at", ""),
                )
            }
        }
    }

    /** GET /webhooks/chat/thread?conversation=ID — full message history. */
    suspend fun thread(conversationId: Long): List<Message> = withContext(Dispatchers.IO) {
        val req = authed().url("$baseUrl/webhooks/chat/thread?conversation=$conversationId").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("messages") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                Message(
                    id = m.optLong("id", 0),
                    conversationId = m.optLong("conversation_id", conversationId),
                    senderRole = m.optString("sender_role", "user"),
                    message = m.optString("message", ""),
                    createdAt = m.optString("created_at", ""),
                )
            }
        }
    }

    /** POST /webhooks/chat/reply — send an operator reply (harvested into training data). */
    suspend fun reply(conversationId: Long, message: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("message", message)
            .put("sender_role", "operator")
        val req = authed()
            .url("$baseUrl/webhooks/chat/reply")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            JSONObject(body).optBoolean("ok", false)
        }
    }
}