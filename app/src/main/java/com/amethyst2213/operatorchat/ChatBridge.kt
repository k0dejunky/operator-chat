package com.amethyst2213.operatorchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Thin client for the chat bridge contract. Talks only to the site webhooks —
 * no AI logic, no API keys on device.
 */
class ChatBridge(private val baseUrl: String, private val token: String) {

    private val client: OkHttpClient
        get() = SHARED_CLIENT

    private fun authed(): Request.Builder = Request.Builder()
        .header("Authorization", "Bearer $token")

    /**
     * A text field, or null when the server sent a JSON null.
     *
     * org.json's optString(name, fallback) only uses the fallback when the
     * key is MISSING - a key that is present with the value null comes back
     * as the four-letter string "null". The server sends null for every
     * message without an attachment, so the thread showed a paperclip with
     * the word "null" next to it.
     */
    private fun JSONObject.textOrNull(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key, "").trim()
        return value.ifEmpty { null }
    }

    /**
     * The URL of a path on the site.
     *
     * The base URL is the site's base path (https://host/gallery), so the
     * path is added to it. A malformed base URL throws here - and every
     * call is inside a try, because an exception that escapes a coroutine
     * kills the whole app.
     */
    private fun siteUrl(path: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid server address")
        val builder = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    /**
     * Whether a raw URL (from server data) points at the configured origin.
     * Relative paths are always same-origin; absolute URLs must match the
     * base URL's scheme + host, so the operator token is never sent anywhere
     * else (e.g. a CDN or compromised attachment_url/apkUrl).
     */
    private fun sameOrigin(raw: String): Boolean {
        val base = baseUrl.toHttpUrlOrNull() ?: return false
        val target = raw.toHttpUrlOrNull() ?: return true
        return target.host == base.host && target.scheme == base.scheme
    }

    data class Conversation(
        val id: Long,
        val userEmail: String,
        val username: String,
        val aiMode: String,
        val status: String,
        val lastMessage: String,
        val lastSender: String,
        val lastMessageAt: String,
        val memberCount: Int,
        val unreadReplyable: Int,
        val updatedAt: String,
        val memberReplyEnabled: Boolean = true,
        val canChat: Boolean = false,
        val favorite: Boolean = false,
    )

    data class UserResult(
        val id: Long,
        val email: String,
        val username: String,
        val canChat: Boolean,
        val conversationId: Long?,
        val memberReplyEnabled: Boolean,
    )

    /** One page of the inbox, with a cursor for the next page. */
    data class InboxPage(
        val items: List<Conversation>,
        val hasMore: Boolean = false,
        val nextCursor: String? = null,
    )

    data class ChatEvent(
        val id: Long,
        val conversationId: Long,
        val userId: Long,
        val username: String,
        val message: String,
        val createdAt: String,
    )

    data class AppVersion(
        val latestVersion: String,
        val versionCode: Long,
        val apkUrl: String,
        val changelog: String,
        val sha256: String = "",
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
        val attachmentThumbUrl: String?,
        val expiresAt: String?,
        val maxViews: Int,
        val viewCount: Int,
        val mediaExpired: Boolean,
    )

    /**
     * One SSE frame from the conversation stream. [typing] is the member's
     * composer state; null when the payload carried no typing field (an older
     * server), so the app never has to guess.
     */
    data class StreamFrame(
        val messages: List<Message>,
        val typing: Boolean?,
    )

    /** GET /webhooks/chat/inbox — a page of conversations with a next-cursor. */
    suspend fun inbox(query: String = "", cursor: String? = null, limit: Int = 50): InboxPage = withContext(Dispatchers.IO) {
        val base = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid base URL")
        val url = base.newBuilder()
            .addPathSegment("webhooks").addPathSegment("chat").addPathSegment("inbox")
            .addQueryParameter("limit", limit.toString())
            .apply { if (query.isNotBlank()) addQueryParameter("q", query) }
            .apply { if (!cursor.isNullOrBlank()) addQueryParameter("cursor", cursor) }
            .build()
        val req = authed().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("conversations") ?: JSONArray()
            val items = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Conversation(
                    id = c.optLong("id", 0),
                    userEmail = c.textOrNull("user_email") ?: "",
                    username = c.textOrNull("username")
                        ?: (c.textOrNull("user_email") ?: "").substringBefore("@"),
                    aiMode = c.textOrNull("ai_mode") ?: "retrieval",
                    status = c.textOrNull("status") ?: "open",
                    lastMessage = c.textOrNull("last_message") ?: "",
                    lastSender = c.textOrNull("last_sender") ?: "",
                    lastMessageAt = c.textOrNull("last_message_at") ?: "",
                    memberCount = c.optInt("member_count", 0),
                    unreadReplyable = c.optInt("unread_replyable", 0),
                    updatedAt = c.textOrNull("updated_at") ?: "",
                    memberReplyEnabled = c.optInt("member_reply_enabled", 1) == 1,
                    canChat = c.optInt("can_chat", 0) == 1,
                )
            }
            InboxPage(
                items = items,
                hasMore = json.optBoolean("has_more", false),
                nextCursor = if (json.isNull("next_cursor")) null else json.optString("next_cursor", null),
            )
        }
    }

    /** GET /webhooks/chat/users?q= — search users the operator can message. */
    suspend fun searchUsers(query: String): List<UserResult> = withContext(Dispatchers.IO) {
        val base = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid base URL")
        val url = base.newBuilder()
            .addPathSegment("webhooks").addPathSegment("chat").addPathSegment("users")
            .addQueryParameter("q", query)
            .build()
        val req = authed().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("users") ?: JSONArray()
            (0 until arr.length()).map { i ->
                val u = arr.getJSONObject(i)
                UserResult(
                    id = u.optLong("id", 0),
                    email = u.textOrNull("email") ?: "",
                    username = u.textOrNull("username")
                        ?: (u.textOrNull("email") ?: "").substringBefore("@"),
                    canChat = u.optInt("can_chat", 0) == 1,
                    conversationId = if (u.isNull("conversation_id") || u.optLong("conversation_id", 0) == 0L) null else u.optLong("conversation_id"),
                    memberReplyEnabled = u.optInt("member_reply_enabled", 1) == 1,
                )
            }
        }
    }

    /** POST /webhooks/chat/start — open (or reuse) a user's conversation. */
    suspend fun startConversation(userId: Long): Pair<Long, String> = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("user_id", userId)
        try {
            val req = authed()
                .url(siteUrl("webhooks/chat/start"))
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!json.optBoolean("ok", false)) throw RuntimeException(json.optString("error", "Could not start conversation"))
                json.optLong("conversation_id", 0) to json.optString("username", "")
            }
        } catch (e: Exception) {
            throw RuntimeException(e.message ?: "Could not start the conversation", e)
        }
    }

    /** POST /webhooks/chat/reply-toggle — enable/disable member replies. */
    suspend fun setMemberReply(conversationId: Long, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("enabled", enabled)
        try {
            val req = authed()
                .url(siteUrl("webhooks/chat/reply-toggle"))
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optBoolean("ok", false)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** POST /webhooks/chat/mode — change a conversation's AI mode. */
    suspend fun setMode(conversationId: Long, mode: String): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("ai_mode", mode)
        try {
            val req = authed()
                .url(siteUrl("webhooks/chat/mode"))
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optBoolean("ok", false)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** POST /webhooks/chat/read — mark the conversation read by the operator. */
    suspend fun setRead(conversationId: Long, messageId: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("message_id", messageId)
        try {
            val req = authed()
                .url(siteUrl("webhooks/chat/read"))
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optBoolean("ok", false)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** GET /webhooks/chat/apk-info — latest app version + live APK sha256. */
    suspend fun checkForUpdate(): AppVersion? = withContext(Dispatchers.IO) {
        try {
            val req = authed()
                .url(siteUrl("webhooks/chat/apk-info"))
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val j = JSONObject(resp.body?.string().orEmpty())
                if (!j.optBoolean("ok", false)) return@use null
                AppVersion(
                    latestVersion = j.optString("latestVersion", ""),
                    versionCode = j.optLong("versionCode", 0),
                    apkUrl = j.optString("apkUrl", ""),
                    changelog = j.optString("changelog", ""),
                    sha256 = j.optString("sha256", ""),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** GET /webhooks/chat/thread?conversation=ID — most recent 50 messages. */
    suspend fun thread(conversationId: Long): List<Message> = withContext(Dispatchers.IO) {
        val req = authed()
            .url(siteUrl("webhooks/chat/thread", listOf("conversation" to conversationId.toString())))
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            parseMessages(json.optJSONArray("messages"), conversationId)
        }
    }

    /**
     * GET /webhooks/chat/history — batch of older messages (id < before),
     * oldest-first. Returns a pair of (messages, hasMore).
     */
    suspend fun history(conversationId: Long, before: Long, limit: Int = 50): Pair<List<Message>, Boolean> =
        withContext(Dispatchers.IO) {
            val req = authed()
                .url(siteUrl("webhooks/chat/history", listOf(
                    "conversation" to conversationId.toString(),
                    "before" to before.toString(),
                    "limit" to limit.toString(),
                )))
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                val json = JSONObject(resp.body?.string().orEmpty())
                Pair(
                    parseMessages(json.optJSONArray("messages"), conversationId),
                    json.optBoolean("has_more", false),
                )
            }
        }

    /**
     * Server-Sent Events stream for real-time updates. Returns as soon as the
     * first batch of messages arrives (so new messages display immediately
     * instead of waiting for the 30s stream window to close); callers loop
     * for continuous live updates.
     */
    suspend fun streamOnce(conversationId: Long, since: Long): StreamFrame = withContext(Dispatchers.IO) {
        val req = authed()
            .url(siteUrl("webhooks/chat/stream", listOf(
                "conversation" to conversationId.toString(),
                "since" to since.toString(),
            )))
            .header("Accept", "text/event-stream")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val reader = resp.body?.source() ?: return@withContext StreamFrame(emptyList(), null)
            while (true) {
                val line = reader.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                try {
                    val json = JSONObject(line.removePrefix("data: "))
                    val msgs = parseMessages(json.optJSONArray("messages"), conversationId)
                    val typing = if (json.has("typing")) json.optBoolean("typing", false) else null
                    // Return on a message batch OR a typing frame; a frame with
                    // neither is a keepalive-shaped payload we can ignore.
                    if (msgs.isNotEmpty() || typing != null) {
                        return@withContext StreamFrame(msgs, typing)
                    }
                } catch (_: Exception) {
                    // ignore keepalive / partial
                }
            }
            StreamFrame(emptyList(), null)
        }
    }

    /**
     * Server-Sent Events stream of new member messages across all
     * conversations (used for push-style notifications). Returns as soon as
     * the first batch of events arrives; callers loop for live updates.
     */
    suspend fun eventsOnce(since: Long): List<ChatEvent> = withContext(Dispatchers.IO) {
        val req = authed()
            .url(siteUrl("webhooks/chat/events", listOf("since" to since.toString())))
            .header("Accept", "text/event-stream")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val reader = resp.body?.source() ?: return@withContext emptyList()
            while (true) {
                val line = reader.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                try {
                    val json = JSONObject(line.removePrefix("data: "))
                    val arr = json.optJSONArray("events") ?: JSONArray()
                    if (arr.length() == 0) continue
                    val out = (0 until arr.length()).map { i ->
                        val e = arr.getJSONObject(i)
                        ChatEvent(
                            id = e.optLong("id", 0),
                            conversationId = e.optLong("conversation_id", 0),
                            userId = e.optLong("user_id", 0),
                            username = e.optString("username", ""),
                            message = e.optString("message", ""),
                            createdAt = e.optString("created_at", ""),
                        )
                    }
                    return@withContext out
                } catch (_: Exception) {
                    // ignore keepalive / partial
                }
            }
            emptyList()
        }
    }

    /**
     * Result of a reply attempt.
     *
     * `reply()` used to return 0 for both a network failure and a server-side
     * rejection, so the operator had no way to tell why a message did not go
     * out. [Message] is the new id on success, or the reason it failed.
     */
    sealed class ReplyResult {
        data class Sent(val id: Long) : ReplyResult()
        data class Rejected(val reason: String) : ReplyResult()
        data class Failed(val reason: String) : ReplyResult()
    }

    /**
     * POST /webhooks/chat/reply — send an operator reply, optionally with a
     * file attachment (image / video / text). Uses multipart when a file is
     * present so the server stores it as an attachment. Upload progress is
     * reported to onProgress (0..1) when an attachment is being sent.
     */
    suspend fun reply(
        conversationId: Long,
        message: String,
        attachment: File? = null,
        idempotencyKey: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        expiresInMinutes: Int = 0,
        maxViews: Int = 0,
    ): ReplyResult = withContext(Dispatchers.IO) {
            val body: RequestBody
            if (attachment != null) {
                val mediaType = guessMediaType(attachment).toMediaTypeOrNull()
                val partBody = ProgressRequestBody(attachment.asRequestBody(mediaType), onProgress)
                val mb = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("conversation_id", conversationId.toString())
                    .addFormDataPart("message", message)
                    .addFormDataPart("sender_role", "operator")
                    .addFormDataPart("attachment", attachment.name, partBody)
                if (expiresInMinutes > 0) mb.addFormDataPart("expires_in_minutes", expiresInMinutes.toString())
                if (maxViews > 0) mb.addFormDataPart("max_views", maxViews.toString())
                body = mb.build()
            } else {
                val payload = JSONObject()
                    .put("conversation_id", conversationId)
                    .put("message", message)
                    .put("sender_role", "operator")
                body = payload.toString().toRequestBody("application/json".toMediaType())
            }

            try {
                // The URL is built INSIDE the try: a malformed base URL
                // throws, and an exception that escapes a coroutine kills
                // the app instead of failing the reply.
                val reqBuilder = authed().url(siteUrl("webhooks/chat/reply"))
                if (!idempotencyKey.isNullOrBlank()) reqBuilder.header("Idempotency-Key", idempotencyKey)
                client.newCall(reqBuilder.post(body).build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = try { JSONObject(text) } catch (_: JSONException) { JSONObject() }
                    if (json.optBoolean("ok", false)) {
                        ReplyResult.Sent(json.optLong("id", 0))
                    } else {
                        // The server tells us why (banned word, closed thread,
                        // over-size attachment). Surface it rather than a bare
                        // "failed".
                        val why = json.optString("error", "").ifBlank {
                            "Server rejected the reply (HTTP ${resp.code})"
                        }
                        ReplyResult.Rejected(why)
                    }
                }
            } catch (e: Exception) {
                ReplyResult.Failed(e.message ?: "Could not reach the server")
            }
        }

    /** Reports upload progress (0..1) while a body is written. */
    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val onProgress: ((Float) -> Unit)?,
    ) : RequestBody() {
        override fun contentType(): okhttp3.MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun isOneShot(): Boolean = delegate.isOneShot()

        override fun writeTo(sink: okio.BufferedSink) {
            val total = contentLength()
            var sent = 0L
            val counting = object : okio.ForwardingSink(sink) {
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    if (total > 0) {
                        onProgress?.invoke((sent.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            val buffered = counting.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    /**
     * Download an attachment (full size or thumbnail) into a temp file.
     * Returns the file, or null on failure.
     */
    suspend fun download(attachmentUrl: String, dest: File): File? = withContext(Dispatchers.IO) {
        val resolved = resolveUrl(attachmentUrl)
        val builder = authed().url(resolved)
        if (!sameOrigin(attachmentUrl)) builder.removeHeader("Authorization")
        val req = builder.build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.byteStream()?.use { ins ->
                    dest.outputStream().use { ous -> ins.copyTo(ous) }
                }
                dest
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download the published APK for a version via the authenticated webhook.
     * Returns the file, or null on failure.
     */
    suspend fun downloadApk(version: String, dest: File): File? = withContext(Dispatchers.IO) {
        val safe = version.replace(Regex("[^0-9.]"), "")
        try {
            val req = authed()
                .url(siteUrl("webhooks/chat/apk", listOf("version" to safe)))
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.byteStream()?.use { ins ->
                    dest.outputStream().use { ous -> ins.copyTo(ous) }
                }
                dest
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download a full (absolute) URL into a file. Used for the APK update.
     */
    suspend fun downloadFile(url: String, dest: File): File? = withContext(Dispatchers.IO) {
        val builder = authed().url(url)
        if (!sameOrigin(url)) builder.removeHeader("Authorization")
        val req = builder.build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.byteStream()?.use { ins ->
                    dest.outputStream().use { ous -> ins.copyTo(ous) }
                }
                dest
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolve a site-relative path (e.g. "/gallery/webhooks/chat/attachment?message=58")
     * against the configured base URL, avoiding a duplicated base path. The
     * base URL is "https://host/gallery" and the server hands back paths that
     * already include the /gallery prefix, so we derive scheme+host from the
     * base and append the path as-is.
     */
    fun resolveUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        val base = baseUrl.trimEnd('/')
        val slash = base.indexOf('/', base.indexOf("://") + 3)
        val origin = if (slash > 0) base.substring(0, slash) else base
        val joined = if (path.startsWith("/")) path else "/" + path
        return origin + joined
    }

    private fun parseMessages(arr: JSONArray?, conversationId: Long): List<Message> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            Message(
                id = m.optLong("id", 0),
                conversationId = m.optLong("conversation_id", conversationId),
                senderRole = m.optString("sender_role", "user"),
                message = m.textOrNull("message") ?: "",
                createdAt = m.textOrNull("created_at") ?: "",
                attachmentName = m.textOrNull("attachment_name"),
                attachmentType = m.textOrNull("attachment_type"),
                attachmentUrl = m.textOrNull("attachment_url"),
                attachmentThumbUrl = m.textOrNull("attachment_thumb_url"),
                expiresAt = m.textOrNull("expires_at"),
                maxViews = m.optInt("max_views", 0),
                viewCount = m.optInt("view_count", 0),
                mediaExpired = m.optBoolean("media_expired", false),
            )
        }
    }

    private fun guessMediaType(f: File): String {
        // Sniffing lives in MediaSniff so the media viewer uses the same rules;
        // it reads only the first bytes (the old code read the whole file).
        return MediaSniff.typeOf(f)
    }
    companion object {
        /**
         * The one client for every call to the site: timeouts, connection pool
         * and certificate pinning. Live streaming and the live-chat poller use
         * it too, so no call ever bypasses the pins.
         */
        internal val SHARED_CLIENT: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                // Without a write timeout a stalled upload never fails: the
                // composer sat on "Sending…" forever with no way out.
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                // A whole call (connect + write + read) must always have a
                // ceiling. SSE reads reset the read timeout per line, so a 5
                // minute cap still leaves the 30s stream window untouched while
                // guaranteeing a wedged connection eventually gives up.
                .callTimeout(5, TimeUnit.MINUTES)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))

            // Certificate pinning for the production host: the leaf SPKI plus
            // the SSL.com intermediate (so a leaf renewal keeps working). Pins
            // only ever apply to amethyst2213.com; other hosts use the system
            // trust store.
            builder.certificatePinner(
                CertificatePinner.Builder()
                    .add(
                        "amethyst2213.com",
                        "sha256/Oaafy95ec+V4ZtMk9bvRXi8wVKgVsmewsupQt1//+/E=",
                        "sha256/0FKBVxnyd4Jq8v8ST+3sjO+WoB7PLBEpSdixbHE1L4g=",
                    )
                    .build()
            )

            builder.build()
        }
    }
}
