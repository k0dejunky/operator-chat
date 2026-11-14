package com.amethyst2213.operatorchat.live

import com.amethyst2213.operatorchat.ChatBridge
import okhttp3.Request
import org.json.JSONObject

/**
 * Watches the chat of the running broadcast.
 *
 * The chat used to be read by the live screen, and the screen stops reading
 * the moment the operator leaves it: a member wrote in the chat of a stream
 * that was on air and the operator - in another app, or with the phone in
 * their pocket - was never told. The watcher lives in the foreground
 * service instead, so it keeps reading for as long as the broadcast runs.
 *
 * The server sends the messages of a five second window and then closes the
 * connection, so the watcher simply asks again with the last id it saw.
 */
class LiveChatWatcher(
    private val base: String,
    private val token: String,
    /** Where to continue from; 0 means "everything that is there". */
    since: Long = 0L,
) {
    /** The id of the newest message that was handed over. */
    @Volatile
    var latestId: Long = since
        private set

    /**
     * Read one window of the chat.
     *
     * Returns the messages that came in, oldest first. A network blip or a
     * stream that was closed early gives an empty list - the caller asks
     * again.
     */
    fun readOnce(): List<LiveChatMessage> {
        if (base.isEmpty() || token.isEmpty()) return emptyList()

        val request = Request.Builder()
            .url("$base/live/chat/stream?since=$latestId")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .get()
            .build()

        return try {
            ChatBridge.SHARED_CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val reader = response.body?.source() ?: return emptyList()
                val out = mutableListOf<LiveChatMessage>()
                while (true) {
                    val line = reader.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    try {
                        val json = JSONObject(line.removePrefix("data: "))
                        val array = json.optJSONArray("messages") ?: org.json.JSONArray()
                        for (i in 0 until array.length()) {
                            val raw = array.getJSONObject(i)
                            val id = raw.optLong("id", 0)
                            if (id > latestId) latestId = id
                            out.add(
                                LiveChatMessage(
                                    id = id,
                                    senderRole = raw.optString("sender_role", "user"),
                                    name = raw.optString("name", "member"),
                                    message = raw.optString("message", ""),
                                    createdAt = raw.optString("created_at", ""),
                                )
                            )
                        }
                    } catch (_: Exception) {
                        // A keepalive or a partial line.
                    }
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
