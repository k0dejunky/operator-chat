package com.amethyst2213.operatorchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin client for the chat bridge contract. Talks only to the site webhooks —
 * no AI logic, no API keys on device.
 */
class ChatBridge(private val baseUrl: String, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
        val attachmentName: String?,
        val attachmentType: String?,
        val attachmentUrl: String?,
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
            parseMessages(json.optJSONArray("messages"), conversationId)
        }
    }

    /**
     * Server-Sent Events stream for real-time updates. Holds the connection
     * open and returns any new messages that arrive; callers loop for live.
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
            val reader = resp.body?.source() ?: return emptyList()
            while (true) {
                val line = reader.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                try {
                    val json = JSONObject(line.removePrefix("data: "))
                    out.addAll(parseMessages(json.optJSONArray("messages"), conversationId))
                } catch (_: Exception) {
                    // ignore keepalive / partial
                }
            }
        }
        return out
    }

    /**
     * POST /webhooks/chat/reply — send an operator reply, optionally with a
     * file attachment (image / video / text). Uses multipart when a file is
     * present so the server stores it as an attachment.
     */
    suspend fun reply(conversationId: Long, message: String, attachment: File? = null): Boolean =
        withContext(Dispatchers.IO) {
            val reqBuilder = authed().url("$baseUrl/webhooks/chat/reply")

            val body: RequestBody
            if (attachment != null) {
                val mediaType = guessMediaType(attachment).toMediaTypeOrNull()
                val partBody = attachment.asRequestBody(mediaType)
                body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("conversation_id", conversationId.toString())
                    .addFormDataPart("message", message)
                    .addFormDataPart("sender_role", "operator")
                    .addFormDataPart("attachment", attachment.name, partBody)
                    .build()
            } else {
                val payload = JSONObject()
                    .put("conversation_id", conversationId)
                    .put("message", message)
                    .put("sender_role", "operator")
                body = payload.toString().toRequestBody("application/json".toMediaType())
            }

            client.newCall(reqBuilder.post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                JSONObject(text).optBoolean("ok", false)
            }
        }

    private fun parseMessages(arr: JSONArray?, conversationId: Long): List<Message> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Message(
                id = m.optLong("id", 0),
                conversationId = m.optLong("conversation_id", conversationId),
                senderRole = m.optString("sender_role", "user"),
                message = m.optString("message", ""),
                createdAt = m.optString("created_at", ""),
                attachmentName = m.optString("attachment_name", "").ifEmpty { null },
                attachmentType = m.optString("attachment_type", "").ifEmpty { null },
                attachmentUrl = m.optString("attachment_url", "").ifEmpty { null },
            )
        }
    }

    private fun guessMediaType(f: File): String {
        val name = f.name.lowercase()
        return when {
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".webm") -> "video/webm"
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}