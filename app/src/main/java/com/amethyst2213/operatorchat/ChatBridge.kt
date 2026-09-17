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

    /**
     * Server-Sent Events stream for real-time updates. Holds the connection
     * open (up to the server's ~30s window) and returns any new messages that
     * arrive; callers loop this for continuous live updates.
     *
     * @return the messages received during this stream window (may be empty).
     */
    fun streamOnce(conversationId: Long, since: Long): List<Message> {
        val req = authed()
            .url("$baseUrl/webhooks/chat/stream?conversation=$conversationId&since=$since")
            .header("Accept", "text/event-stream")
            .get()
            .build()
        val out = mutableListOf<Message>()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: return emptyList()
            val reader = body.source()
            while (true) {
                val line = reader.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                try {
                    val json = JSONObject(line.removePrefix("data: "))
                    val arr = json.optJSONArray("messages") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        out.add(
                            Message(
                                id = m.optLong("id", 0),
                                conversationId = m.optLong("conversation_id", conversationId),
                                senderRole = m.optString("sender_role", "user"),
                                message = m.optString("message", ""),
                                createdAt = m.optString("created_at", ""),
                            )
                        )
                    }
                } catch (_: Exception) {
                    // ignore malformed keepalive/partial lines
                }
            }
        }
        return out
    }
}