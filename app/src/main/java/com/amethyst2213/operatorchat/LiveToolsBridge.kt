package com.amethyst2213.operatorchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/**
 * The operator's live-show schedule and the per-message tools.
 *
 * These are separate from [ChatBridge] because they are about the SHOW
 * and about acting on the conversation, not about reading the inbox.
 */
class LiveToolsBridge(private val baseUrl: String, private val token: String) {

    /** A scheduled show. */
    data class Show(
        val id: Long,
        val title: String,
        val description: String,
        val startsAt: String,
        val startsAtUtc: String,
        val duration: Int,
        val status: String,
        val seconds: Long,
    )

    /** The operator's site configuration, from the chat config endpoint. */
    data class SiteConfig(
        val siteTimezone: String,
        val serverTime: String,
        val operatorOnline: Boolean,
        val awayMessage: String,
    )

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient
        get() = ChatBridge.SHARED_CLIENT

    private fun authed(): Request.Builder =
        Request.Builder().header("Authorization", "Bearer $token")

    private fun base(): HttpUrl =
        baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid base URL")

    private fun parseShow(obj: JSONObject): Show = Show(
        id = obj.optLong("id"),
        title = obj.optString("title"),
        description = obj.optString("description"),
        startsAt = obj.optString("startsAt"),
        startsAtUtc = obj.optString("startsAtUtc"),
        duration = obj.optInt("duration", 60),
        status = obj.optString("status"),
        seconds = obj.optLong("seconds"),
    )

    /** GET /live/shows — the shows that are still ahead. */
    suspend fun shows(): List<Show> = withContext(Dispatchers.IO) {
        val req = authed()
            .url(base().newBuilder().addPathSegment("live").addPathSegment("shows").build())
            .get()
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            resp.body?.string().orEmpty()
        }
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) return@withContext emptyList()
        val arr = json.optJSONArray("shows") ?: return@withContext emptyList()
        (0 until arr.length()).map { parseShow(arr.getJSONObject(it)) }
    }

    /**
     * POST /live/schedule — schedule a show.
     *
     * [startsAt] is a local time in the site's display timezone; the server
     * converts it to UTC.
     */
    suspend fun schedule(
        title: String,
        startsAt: String,
        durationMinutes: Int,
        description: String = "",
    ): Show? = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("title", title)
            .add("starts_at", startsAt)
            .add("duration", durationMinutes.toString())
            .apply { if (description.isNotBlank()) add("description", description) }
            .build()
        val req = authed()
            .url(base().newBuilder().addPathSegment("live").addPathSegment("schedule").build())
            .post(form)
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            resp.body?.string().orEmpty()
        }
        val json = JSONObject(body)
        if (!json.optBoolean("ok", false)) return@withContext null
        json.optJSONObject("show")?.let { parseShow(it) }
    }

    /** POST /live/schedule/cancel — cancel a scheduled show. */
    suspend fun cancelShow(id: Long): Boolean = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("id", id.toString()).build()
        val req = authed()
            .url(base().newBuilder().addPathSegment("live").addPathSegment("schedule").addPathSegment("cancel").build())
            .post(form)
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            resp.body?.string().orEmpty()
        }
        JSONObject(body).optBoolean("ok", false)
    }

    /**
     * POST /webhooks/chat/message-action — pin, unpin or delete a message.
     *
     * The app had no per-message actions at all before this.
     */
    suspend fun messageAction(
        action: String,
        messageId: Long = 0,
        conversationId: Long = 0,
    ): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("action", action)
            .put("id", messageId)
            .put("conversation_id", conversationId)
            .toString()
        val req = authed()
            .url(base().newBuilder()
                .addPathSegment("webhooks").addPathSegment("chat").addPathSegment("message-action").build())
            .post(payload.toRequestBody(jsonType))
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            resp.body?.string().orEmpty()
        }
        JSONObject(body).optBoolean("ok", false)
    }

    /** The messages the operator has pinned in a conversation. */
    suspend fun pinnedMessages(conversationId: Long): List<Long> = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("action", "pinned")
            .put("conversation_id", conversationId)
            .toString()
        val req = authed()
            .url(base().newBuilder()
                .addPathSegment("webhooks").addPathSegment("chat").addPathSegment("message-action").build())
            .post(payload.toRequestBody(jsonType))
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            resp.body?.string().orEmpty()
        }
        val json = JSONObject(body)
        val arr = json.optJSONArray("messages") ?: return@withContext emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it).optLong("id") }
    }

    /**
     * POST /webhooks/chat/block-user — block or unblock a member.
     *
     * hours = 0 means until the operator unblocks them.
     */
    suspend fun setBlocked(userId: Long, blocked: Boolean, hours: Int = 0): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("user_id", userId)
                .put("blocked", blocked)
                .put("hours", hours)
                .toString()
            val req = authed()
                .url(base().newBuilder()
                    .addPathSegment("webhooks").addPathSegment("chat").addPathSegment("block-user").build())
                .post(payload.toRequestBody(jsonType))
                .build()
            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                resp.body?.string().orEmpty()
            }
            JSONObject(body).optBoolean("ok", false)
        }

    /**
     * GET /webhooks/chat/config — the site's timezone and clock.
     *
     * The app used to read 'site_timezone' out of its preferences, but
     * nothing ever wrote it, so every timestamp it showed was in a
     * hardcoded zone.
     */
    suspend fun siteConfig(): SiteConfig = withContext(Dispatchers.IO) {
        val req = authed()
            .url(base().newBuilder()
                .addPathSegment("webhooks").addPathSegment("chat").addPathSegment("config").build())
            .get()
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext SiteConfig("UTC", "", false, "")
            resp.body?.string().orEmpty()
        }
        val json = JSONObject(body)
        SiteConfig(
            siteTimezone = json.optString("site_timezone", "UTC"),
            serverTime = json.optString("server_time"),
            operatorOnline = json.optBoolean("operator_online", false),
            awayMessage = json.optString("away_message"),
        )
    }
}
