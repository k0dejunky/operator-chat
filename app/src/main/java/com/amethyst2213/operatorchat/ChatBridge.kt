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
        val username: String,
        val aiMode: String,
        val status: String,
        val lastMessage: String,
        val lastSender: String,
        val lastMessageAt: String,
        val memberCount: Int,
        val unreadReplyable: Int,
        val updatedAt: String,
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
                )
            }
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

    /** GET /assets/apk/operator-chat-version.json — latest app version. */
    suspend fun checkForUpdate(): AppVersion? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/assets/apk/operator-chat-version.json")
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val j = JSONObject(resp.body?.string().orEmpty())
                AppVersion(
                    latestVersion = j.optString("latestVersion", ""),
                    versionCode = j.optLong("versionCode", 0),
                    apkUrl = j.optString("apkUrl", ""),
                    changelog = j.optString("changelog", ""),
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
     * present so the server stores it as an attachment.
     *
     * @return the new message id, or 0 on failure.
     */
    suspend fun reply(conversationId: Long, message: String, attachment: File? = null): Long =
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
                val json = JSONObject(text)
                if (json.optBoolean("ok", false)) json.optLong("id", 0) else 0
            }
        }

    /**
     * Download an attachment (full size or thumbnail) into a temp file.
     * Returns the file, or null on failure.
     */
    suspend fun download(attachmentUrl: String, dest: File): File? = withContext(Dispatchers.IO) {
        val req = authed().url(resolveUrl(attachmentUrl)).get().build()
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
        val req = Request.Builder().url(url).get().build()
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
}