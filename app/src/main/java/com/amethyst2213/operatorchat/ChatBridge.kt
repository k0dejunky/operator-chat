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
 * Thin client for the chat bridge contract (docs/chat-android-contract.md).
 * Talks only to the site webhooks — no AI logic, no API keys on device.
 */
class ChatBridge(private val baseUrl: String, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun authed(): Request.Builder = Request.Builder()
        .header("Authorization", "Bearer $token")

    data class Config(
        val conversationId: Long,
        val aiMode: String,
        val status: String,
        val pending: Int,
    )

    data class Message(
        val id: Long,
        val conversationId: Long,
        val senderRole: String,
        val message: String,
    )

    /** GET /webhooks/chat/config?conversation=ID — AI/Live mode + pending count. */
    suspend fun config(conversationId: Long): Config = withContext(Dispatchers.IO) {
        val req = authed()
            .url("$baseUrl/webhooks/chat/config?conversation=$conversationId")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            Config(
                conversationId = json.optLong("conversation", 0),
                aiMode = json.optString("ai_mode", "retrieval"),
                status = json.optString("status", "closed"),
                pending = json.optInt("pending", 0),
            )
        }
    }

    /** GET /webhooks/chat/pending?conversation=ID — member messages awaiting a reply. */
    suspend fun pending(conversationId: Long): List<Message> = withContext(Dispatchers.IO) {
        val req = authed()
            .url("$baseUrl/webhooks/chat/pending?conversation=$conversationId")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val arr = json.optJSONArray("messages") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                Message(
                    id = m.optLong("id", 0),
                    conversationId = m.optLong("conversation_id", conversationId),
                    senderRole = m.optString("sender_role", "user"),
                    message = m.optString("message", ""),
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