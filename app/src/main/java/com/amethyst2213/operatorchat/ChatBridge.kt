package com.amethyst2213.operatorchat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                    userEmail = c.optString("user_email", ""),
                    username = c.optString("username", "").ifEmpty {
                        c.optString("user_email", "").substringBefore("@")
                    },
                    aiMode = c.optString("ai_mode", "retrieval"),
                    status = c.optString("status", "open"),
                    lastMessage = c.optString("last_message", ""),
                    lastSender = c.optString("last_sender", ""),
                    lastMessageAt = c.optString("last_message_at", ""),
                    memberCount = c.optInt("member_count", 0),
                    unreadReplyable = c.optInt("unread_replyable", 0),
                    updatedAt = c.optString("updated_at", ""),
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
                    email = u.optString("email", ""),
                    username = u.optString("username", "").ifEmpty { u.optString("email", "").substringBefore("@") },
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
        val req = authed()
            .url("$baseUrl/webhooks/chat/start")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!json.optBoolean("ok", false)) throw RuntimeException(json.optString("error", "Could not start conversation"))
            json.optLong("conversation_id", 0) to json.optString("username", "")
        }
    }

    /** POST /webhooks/chat/reply-toggle — enable/disable member replies. */
    suspend fun setMemberReply(conversationId: Long, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("enabled", enabled)
        val req = authed()
            .url("$baseUrl/webhooks/chat/reply-toggle")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
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
        val req = authed()
            .url("$baseUrl/webhooks/chat/mode")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string().orEmpty()).optBoolean("ok", false)
        }
    }

    /** POST /webhooks/chat/read — mark the conversation read by the operator. */
    suspend fun setRead(conversationId: Long, messageId: Long = 0): Boolean = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("conversation_id", conversationId)
            .put("message_id", messageId)
        val req = authed()
            .url("$baseUrl/webhooks/chat/read")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optBoolean("ok", false)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** GET /webhooks/chat/apk-info — latest app version + live APK sha256. */
    suspend fun checkForUpdate(): AppVersion? = withContext(Dispatchers.IO) {
        val req = authed()
            .url("$baseUrl/webhooks/chat/apk-info")
            .get()
            .build()
        try {
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
        val req = authed().url("$baseUrl/webhooks/chat/thread?conversation=$conversationId").get().build()
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
                .url("$baseUrl/webhooks/chat/history?conversation=$conversationId&before=$before&limit=$limit")
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
    suspend fun streamOnce(conversationId: Long, since: Long): List<Message> = withContext(Dispatchers.IO) {
        val req = authed()
            .url("$baseUrl/webhooks/chat/stream?conversation=$conversationId&since=$since")
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
                    val msgs = parseMessages(json.optJSONArray("messages"), conversationId)
                    if (msgs.isNotEmpty()) return@withContext msgs
                } catch (_: Exception) {
                    // ignore keepalive / partial
                }
            }
            emptyList()
        }
    }

    /**
     * Server-Sent Events stream of new member messages across all
     * conversations (used for push-style notifications). Returns as soon as
     * the first batch of events arrives; callers loop for live updates.
     */
    suspend fun eventsOnce(since: Long): List<ChatEvent> = withContext(Dispatchers.IO) {
        val req = authed()
            .url("$baseUrl/webhooks/chat/events?since=$since")
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
     * POST /webhooks/chat/reply — send an operator reply, optionally with a
     * file attachment (image / video / text). Uses multipart when a file is
     * present so the server stores it as an attachment. Upload progress is
     * reported to onProgress (0..1) when an attachment is being sent.
     *
     * @return the new message id, or 0 on failure.
     */
    suspend fun reply(
        conversationId: Long,
        message: String,
        attachment: File? = null,
        idempotencyKey: String? = null,
        onProgress: ((Float) -> Unit)? = null,
        expiresInMinutes: Int = 0,
        maxViews: Int = 0,
    ): Long = withContext(Dispatchers.IO) {
            val reqBuilder = authed().url("$baseUrl/webhooks/chat/reply")
            if (!idempotencyKey.isNullOrBlank()) reqBuilder.header("Idempotency-Key", idempotencyKey)

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

            client.newCall(reqBuilder.post(body).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = JSONObject(text)
                if (json.optBoolean("ok", false)) json.optLong("id", 0) else 0
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
        val req = authed()
            .url("$baseUrl/webhooks/chat/apk?version=$safe")
            .get()
            .build()
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
                message = m.optString("message", ""),
                createdAt = m.optString("created_at", ""),
                attachmentName = m.optString("attachment_name", "").ifEmpty { null },
                attachmentType = m.optString("attachment_type", "").ifEmpty { null },
                attachmentUrl = m.optString("attachment_url", "").ifEmpty { null },
                attachmentThumbUrl = m.optString("attachment_thumb_url", "").ifEmpty { null },
                expiresAt = m.optString("expires_at", "").ifEmpty { null },
                maxViews = m.optInt("max_views", 0),
                viewCount = m.optInt("view_count", 0),
                mediaExpired = m.optBoolean("media_expired", false),
            )
        }
    }

    private fun guessMediaType(f: File): String {
        // First sniff the content's magic bytes — phone gallery files often
        // have no recognisable extension.
        try {
            val head = f.inputStream().use { it.readBytes().take(16).toByteArray() }
            val hex = head.joinToString("") { b -> String.format("%02X", b) }
            when {
                hex.startsWith("FFD8FF") -> return "image/jpeg"
                hex.startsWith("89504E47") -> return "image/png"
                hex.startsWith("47494638") -> return "image/gif"
                hex.startsWith("52494646") && hex.length >= 24 &&
                    hex.substring(16, 20) == "57454250" -> return "image/webp" // RIFF....WEBP
                hex.length >= 24 && hex.substring(16, 20) == "66747970" -> return "video/mp4" // ....ftyp
            }
        } catch (_: Exception) {
            // fall through to extension-based guess
        }

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
    companion object {
        private val SHARED_CLIENT: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
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
