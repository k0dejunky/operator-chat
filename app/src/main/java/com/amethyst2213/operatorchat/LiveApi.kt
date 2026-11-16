package com.amethyst2213.operatorchat

import com.amethyst2213.operatorchat.live.LiveChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The live-broadcast HTTP contract: session start/stop, pause, viewer stats
 * and the live chat. Kept out of the ViewModel so it can be unit-tested and
 * reused by the watcher inside the broadcast service.
 */
object LiveApi {

    data class Started(val rtmpUrl: String, val streamKey: String)
    data class Failure(val message: String)

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client get() = ChatBridge.SHARED_CLIENT

    /** POST /live/start. Returns Started or Failure with a readable reason. */
    suspend fun start(base: String, token: String): Any = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(base.trimEnd('/') + "/live/start")
                .header("Authorization", "Bearer $token")
                .post("".toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Failure(
                        when (resp.code) {
                            403 -> "This token cannot start a broadcast. Create an operator device token in the admin panel and sign in with that one."
                            401 -> "The server refused the token. Sign in again in Settings."
                            else -> "The live session could not be started (HTTP ${resp.code})."
                        },
                    )
                }
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!json.optBoolean("ok", false)) {
                    return@withContext Failure(
                        json.optString("error", "The live session could not be started."),
                    )
                }
                Started(json.optString("rtmp_url"), json.optString("stream_key"))
            }
        } catch (e: Exception) {
            Failure("The server could not be reached: ${e.message ?: "unknown error"}")
        }
    }

    /** POST /live/stop (form stream_key). Fire and forget. */
    suspend fun stop(base: String, token: String, streamKey: String) = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder().add("stream_key", streamKey).build()
            val req = Request.Builder()
                .url(base.trimEnd('/') + "/live/stop")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    /** POST /live/pause or /live/resume. */
    suspend fun setPaused(base: String, token: String, paused: Boolean) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(base.trimEnd('/') + if (paused) "/live/pause" else "/live/resume")
                .header("Authorization", "Bearer $token")
                .post("".toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    /** GET /live/status. */
    suspend fun status(base: String, token: String): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(base.trimEnd('/') + "/live/status")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optInt("viewers", 0) to json.optString("since", "")
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One SSE window of /live/chat/stream.
     *
     * The server closes the window after ~5 seconds; the caller loops. A
     * line that is not a data frame (a keepalive `: ping`) is ignored.
     */
    suspend fun chatOnce(base: String, token: String, since: Long): List<LiveChatMessage> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(base.trimEnd('/') + "/live/chat/stream?since=$since")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "text/event-stream")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList()
                    val reader = resp.body?.source() ?: return@withContext emptyList()
                    val out = mutableListOf<LiveChatMessage>()
                    while (true) {
                        val line = reader.readUtf8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        try {
                            val json = JSONObject(line.removePrefix("data: "))
                            val arr: JSONArray = json.optJSONArray("messages") ?: JSONArray()
                            for (i in 0 until arr.length()) {
                                val m = arr.getJSONObject(i)
                                out.add(
                                    LiveChatMessage(
                                        id = m.optLong("id", 0),
                                        senderRole = m.optString("sender_role", "user"),
                                        name = m.optString("name", "member"),
                                        message = m.optString("message", ""),
                                        createdAt = m.optString("created_at", ""),
                                    ),
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
                    out
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** One immediate read of the live chat since an id. */
    data class ChatPoll(val messages: List<LiveChatMessage>, val latestId: Long)

    suspend fun chatPoll(base: String, token: String, since: Long): ChatPoll? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(base.trimEnd('/') + "/live/chat/poll?since=$since")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val json = JSONObject(resp.body?.string().orEmpty())
                    if (!json.optBoolean("ok", false)) return@withContext null
                    val arr = json.optJSONArray("messages") ?: JSONArray()
                    val messages = (0 until arr.length()).map { i ->
                        val m = arr.getJSONObject(i)
                        LiveChatMessage(
                            id = m.optLong("id", 0),
                            senderRole = m.optString("sender_role", "user"),
                            name = m.optString("name", "member"),
                            message = m.optString("message", ""),
                            createdAt = m.optString("created_at", ""),
                        )
                    }
                    ChatPoll(messages, json.optLong("latestId", 0))
                }
            } catch (_: Exception) {
                null
            }
        }

    /** POST /live/chat/send (form message). */
    suspend fun sendChat(base: String, token: String, message: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder().add("message", message).build()
                val req = Request.Builder()
                    .url(base.trimEnd('/') + "/live/chat/send")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { it.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }
}
