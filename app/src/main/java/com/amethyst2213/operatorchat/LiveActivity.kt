package com.amethyst2213.operatorchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amethyst2213.operatorchat.live.BroadcastSession
import com.amethyst2213.operatorchat.live.BroadcastState
import com.amethyst2213.operatorchat.live.LiveBroadcastService
import com.amethyst2213.operatorchat.ui.screens.LiveChatLine
import com.amethyst2213.operatorchat.ui.screens.LiveScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

/**
 * The live console.
 *
 * The screen is Compose and the broadcast lives in a foreground service, so:
 *
 *  - the operator can switch apps, lock the phone or rotate the device and
 *    the stream keeps running (before, the encoder belonged to this
 *    Activity and died with it);
 *  - the rotation no longer tears the encoder down and rebuilds it in a
 *    400 ms window, which used to leave the screen saying "Rotating…" with
 *    a dead stream behind it;
 *  - the preview is a fixed box outside any scroll container, so scrolling
 *    can no longer destroy the surface and black out the preview.
 */
class LiveActivity : AppCompatActivity() {

    private var session: BroadcastSession? = null
    private var rtmpUrl: String? = null
    private var streamKey: String = ""
    private var micMuted = false
    private var paused = false

    private var state by mutableStateOf<BroadcastState>(BroadcastState.Idle)
    private var viewers by mutableStateOf(0)
    private var since by mutableStateOf("")
    private var chatLines by mutableStateOf<List<LiveChatLine>>(emptyList())
    private var chatDraft by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)

    private var chatJob: Job? = null
    private var stateJob: Job? = null
    private var statsJob: Job? = null
    private var latestChatId = 0L
    private var surfaceReady = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val camera = granted[Manifest.permission.CAMERA] == true
        val mic = granted[Manifest.permission.RECORD_AUDIO] == true
        if (camera) {
            prepareCamera()
            if (!mic) {
                error = "No microphone permission: the broadcast will have no sound."
            }
        } else {
            error = "The camera permission is needed to go live."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on while the console is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            OperatorTheme {
                LiveScreen(
                    state = state,
                    viewers = viewers,
                    since = since,
                    micMuted = micMuted,
                    paused = paused,
                    chat = chatLines,
                    chatDraft = chatDraft,
                    error = error,
                    onGoLive = { goLive() },
                    onStop = { stopBroadcast() },
                    onFlipCamera = { session?.flipCamera() },
                    onToggleMic = { toggleMic() },
                    onTogglePause = { togglePause() },
                    onChatDraftChange = { chatDraft = it },
                    onSendChat = { sendLiveChat() },
                    onSurfaceReady = { surface, width, height -> attachSurface(surface, width, height) },
                )
            }
        }

        LiveBroadcastService.start(this)
    }

    override fun onStart() {
        super.onStart()
        // The service is the owner of the encoder; the Activity only looks at
        // it and sends commands.
        stateJob?.cancel()
        stateJob = lifecycleScope.launch {
            LiveBroadcastService.current()?.state?.collect { live ->
                state = live
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) prepareCamera()
        startStats()
    }

    override fun onStop() {
        super.onStop()
        stopStats()
        stateJob?.cancel()
        stateJob = null
    }

    override fun onDestroy() {
        // The broadcast keeps running: the service owns it now. The Activity
        // only lets go of the preview surface.
        session?.detachPreview()
        super.onDestroy()
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Create (once) the camera + encoder and attach the preview surface. */
    private fun prepareCamera() {
        if (session != null) return
        val service = LiveBroadcastService.current() ?: run {
            LiveBroadcastService.start(this)
            return
        }
        val created = service.createSession(object : BroadcastSession.Listener {
            override fun onState(newState: BroadcastState) {
                runOnUiThread { state = newState }
            }

            override fun onLog(message: String) {
                runOnUiThread { error = message }
            }
        })
        session = created
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // The camera open + two MediaCodecs take 100-500ms: keep them off the
        // UI thread or the screen freezes when the operator presses Go Live.
        lifecycleScope.launch(Dispatchers.Default) {
            created.prepare(landscape)
        }
    }

    private fun attachSurface(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceReady = true
        session?.attachPreview(surface, width, height)
    }

    // ------------------------------------------------------------- controls

    private fun goLive() {
        if (!hasPermissions()) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            return
        }
        val base = base()
        val token = token()
        if (base.isEmpty() || token.isEmpty()) {
            error = "Sign in first."
            return
        }
        error = null
        state = BroadcastState.Preparing

        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) { startLiveSession(base, token) }
            if (started == null) {
                state = BroadcastState.Failed("Could not start the live session.")
                return@launch
            }
            rtmpUrl = started.first
            streamKey = started.second
            startLiveChat()
            session?.start(started.first)
        }
    }

    private fun stopBroadcast() {
        val base = base()
        val token = token()
        val key = streamKey

        session?.stopStreaming()
        if (key.isNotEmpty() && base.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    stopLiveSession(base, token, key)
                } catch (_: Exception) {
                }
            }
        }
        rtmpUrl = null
        streamKey = ""
        micMuted = false
        paused = false
        stopLiveChat()
        state = BroadcastState.Stopped("Stopped")
    }

    private fun toggleMic() {
        micMuted = !micMuted
        session?.setMicMuted(micMuted)
    }

    private fun togglePause() {
        paused = !paused
        session?.setPaused(paused)
        val base = base()
        val token = token()
        if (base.isEmpty() || token.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(base + (if (paused) "/live/pause" else "/live/resume"))
                    .header("Authorization", "Bearer $token")
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    // -------------------------------------------------------------- the chat

    private fun startLiveChat() {
        stopLiveChat()
        val base = base()
        val token = token()
        chatJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val msgs = withContext(Dispatchers.IO) { fetchLiveChatOnce(base, token, latestChatId) }
                    if (msgs.isNotEmpty()) {
                        withContext(Dispatchers.Main) { appendChat(msgs) }
                    }
                } catch (_: Exception) {
                    // Stream gone or a network blip: keep polling.
                }
                delay(4000)
            }
        }
    }

    private fun stopLiveChat() {
        chatJob?.cancel()
        chatJob = null
    }

    /** How many people are watching, and since when. */
    private fun startStats() {
        stopStats()
        val base = base()
        if (base.isEmpty()) return
        statsJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val status = withContext(Dispatchers.IO) { fetchStatus(base) }
                    if (status != null) {
                        viewers = status.first
                        since = status.second
                    }
                } catch (_: Exception) {
                }
                delay(5000)
            }
        }
    }

    private fun stopStats() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun appendChat(fresh: List<LiveChatMsg>) {
        val lines = chatLines.toMutableList()
        for (m in fresh) {
            if (m.id > 0 && m.id <= latestChatId) continue
            if (m.id > 0) latestChatId = m.id
            lines += LiveChatLine(
                who = if (m.senderRole == "operator") "Operator" else m.name,
                message = m.message,
                at = m.createdAt.substringAfter(' ').take(5),
                operator = m.senderRole == "operator",
            )
        }
        // Keep the list bounded: a long broadcast would otherwise grow it
        // without end.
        chatLines = if (lines.size > 150) lines.takeLast(150) else lines
    }

    private fun sendLiveChat() {
        val text = chatDraft.trim()
        if (text.isEmpty()) return
        chatDraft = ""
        val base = base()
        val token = token()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = FormBody.Builder().add("message", text).build()
                val req = Request.Builder()
                    .url("$base/live/chat/send")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        withContext(Dispatchers.Main) {
                            error = "The message could not be sent - is the stream live?"
                        }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { error = "The message could not be sent." }
            }
        }
    }

    // ------------------------------------------------------- the live session

    private data class LiveChatMsg(
        val id: Long,
        val senderRole: String,
        val name: String,
        val message: String,
        val createdAt: String,
    )

    private fun base(): String = SecurePrefs.url(this).trim().trimEnd('/')

    private fun token(): String = SecurePrefs.token(this).trim()

    private fun startLiveSession(base: String, token: String): Pair<String, String>? = try {
        val req = Request.Builder()
            .url(base + "/live/start")
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!json.optBoolean("ok", false)) return@use null
            json.optString("rtmp_url", "") to json.optString("stream_key", "")
        }
    } catch (_: Exception) {
        null
    }

    private fun stopLiveSession(base: String, token: String, streamKey: String) {
        val body = FormBody.Builder().add("stream_key", streamKey).build()
        val req = Request.Builder()
            .url(base + "/live/stop")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(req).execute().close()
    }

    /** GET /live/status - the viewer count and since when. */
    private fun fetchStatus(base: String): Pair<Int, String>? = try {
        val req = Request.Builder()
            .url(base + "/live/status")
            .header("Authorization", "Bearer " + token())
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val json = JSONObject(resp.body?.string().orEmpty())
            json.optInt("viewers", 0) to json.optString("since", "")
        }
    } catch (_: Exception) {
        null
    }

    /** One SSE read of /live/chat/stream. */
    private fun fetchLiveChatOnce(base: String, token: String, since: Long): List<LiveChatMsg> {
        val req = Request.Builder()
            .url("$base/live/chat/stream?since=$since")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val reader = resp.body?.source() ?: return emptyList()
            val out = mutableListOf<LiveChatMsg>()
            while (true) {
                val line = reader.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                try {
                    val json = JSONObject(line.removePrefix("data: "))
                    val arr = json.optJSONArray("messages") ?: org.json.JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        out.add(
                            LiveChatMsg(
                                id = m.optLong("id", 0),
                                senderRole = m.optString("sender_role", "user"),
                                name = m.optString("name", "member"),
                                message = m.optString("message", ""),
                                createdAt = m.optString("created_at", ""),
                            )
                        )
                    }
                } catch (_: Exception) {
                    // A keepalive or a partial line.
                }
            }
            return out
        }
    }

    companion object {
        private val client: OkHttpClient = ChatBridge.SHARED_CLIENT
    }
}
