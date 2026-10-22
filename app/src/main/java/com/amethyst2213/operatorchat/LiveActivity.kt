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

    /** Is the broadcast also being saved to an MP4 on the device? */
    private var recording by mutableStateOf(false)

    /** The file the recording is written to. */
    private var recordFile by mutableStateOf<String?>(null)

    /** The operator pressed "Go live" and the camera permission was asked for. */
    private var wantToGoLive = false

    /** Why the last attempt to start a broadcast failed. */
    private var liveStartError = ""

    /** A progress note from the encoder ("Connecting...", "Starting the camera..."). */
    private var note by mutableStateOf<String?>(null)

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
            // The service may only use the camera type once the permission
            // is actually held; before that it runs with data-sync alone.
            LiveBroadcastService.current()?.promoteWithCamera()
            prepareCamera()
            if (!mic) {
                error = "No microphone permission: the broadcast will have no sound."
            }
            // The operator pressed "Go live" and was asked for the camera:
            // carry on with the broadcast instead of making them press the
            // button a second time.
            if (wantToGoLive) {
                wantToGoLive = false
                goLive()
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
                    note = note,
                    onGoLive = { goLive() },
                    onStop = { stopBroadcast() },
                    onFlipCamera = { session?.flipCamera() },
                    onToggleMic = { toggleMic() },
                    onTogglePause = { togglePause() },
                    onToggleRecord = { toggleRecording() },
                    recording = recording,
                    recordFile = recordFile,
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
            // The service is created by the system AFTER this activity's
            // onStart, so the state used to be missing on the first visit
            // and "Go live" did nothing. Wait for it instead.
            val service = LiveBroadcastService.awaitInstance() ?: return@launch
            service.state.collect { live -> state = live }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) prepareCamera()
        startStats()
    }

    /**
     * Prepare the camera and the encoder.
     *
     * The service owns the encoder, so this waits for the service to exist
     * and then asks it for a session. It used to give up when the service
     * was not there yet - which was always, on the first visit.
     */
    private fun prepareCameraAsync() {
        lifecycleScope.launch {
            val service = LiveBroadcastService.awaitInstance() ?: return@launch
            if (session == null) {
                val created = service.createSession(object : BroadcastSession.Listener {
                    override fun onState(newState: BroadcastState) {
                        runOnUiThread { state = newState }
                    }

                    override fun onLog(message: String) {
                        // A progress note, not an error: "Connecting..." in a
                        // red banner reads like a failure.
                        runOnUiThread { note = message }
                    }
                })
                session = created
                val landscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                // The camera open + two MediaCodecs take 100-500ms: keep them
                // off the UI thread or the screen freezes.
                withContext(Dispatchers.Default) { created.prepare(landscape) }
            }
            session?.attachPreviewIfReady()
        }
    }

    override fun onStop() {
        super.onStop()
        stopStats()
        stateJob?.cancel()
        stateJob = null
    }

    override fun onDestroy() {
        // A live broadcast keeps running: the service owns it now. But when
        // nothing is being broadcast the service is stopped, so the camera
        // and the microphone do not stay open in the background.
        val live = state is BroadcastState.Live || state is BroadcastState.Connecting
        if (!live) {
            session?.detachPreview()
            LiveBroadcastService.stop(this)
            session = null
        } else {
            session?.detachPreview()
        }
        super.onDestroy()
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Create (once) the camera + encoder and attach the preview surface. */
    private fun prepareCamera() {
        if (session != null) {
            session?.attachPreviewIfReady()
            return
        }
        prepareCameraAsync()
    }

    private fun attachSurface(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceReady = true
        session?.attachPreview(surface, width, height)
    }

    // ------------------------------------------------------------- controls

    private fun goLive() {
        if (!hasPermissions()) {
            // The broadcast continues by itself once the camera is allowed.
            wantToGoLive = true
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            return
        }
        wantToGoLive = false
        val base = base()
        val token = token()
        if (base.isEmpty() || token.isEmpty()) {
            error = "Sign in first."
            return
        }
        error = null
        note = null
        state = BroadcastState.Preparing

        lifecycleScope.launch {
            // The encoder may not be there yet: without it the publish was
            // a silent no-op.
            val service = LiveBroadcastService.awaitInstance()
            if (service == null) {
                state = BroadcastState.Failed("The broadcast service did not start.")
                return@launch
            }
            if (session == null) {
                val created = service.createSession(object : BroadcastSession.Listener {
                    override fun onState(newState: BroadcastState) {
                        runOnUiThread { state = newState }
                    }

                    override fun onLog(message: String) {
                        // A progress note, not an error: "Connecting..." in a
                        // red banner reads like a failure.
                        runOnUiThread { note = message }
                    }
                })
                session = created
                val landscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                withContext(Dispatchers.Default) { created.prepare(landscape) }
            }

            val started = withContext(Dispatchers.IO) { startLiveSession(base, token) }
            if (started == null) {
                state = BroadcastState.Failed(liveStartError)
                error = liveStartError
                return@launch
            }
            rtmpUrl = started.first
            streamKey = started.second
            startLiveChat()
            // Keep the CPU awake: a sleeping screen throttles the encoder
            // and the stream dies half way through.
            service.acquireWakeLock()
            session?.start(started.first)
        }
    }

    /**
     * Save the broadcast to an MP4 on the device.
     *
     * The server records the stream as well, but that copy is on the box -
     * this one is the operator's own.
     */
    private fun toggleRecording() {
        val current = session
        if (current == null) {
            error = "The camera is not ready yet."
            return
        }
        if (recording) {
            current.stopRecording()
            recording = false
            return
        }
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: filesDir
        val name = "live-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".mp4"
        val file = current.startRecording(dir, name)
        if (file == null) {
            error = "The recording could not be started."
            return
        }
        recording = true
        recordFile = file.name
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
        recording = false
        recordFile = null
        stopLiveChat()
        // The service owns the camera and the encoders: it has to go, or the
        // camera and the microphone stay open with the screen long closed.
        LiveBroadcastService.stop(this)
        session = null
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

    /**
     * Start a broadcast session on the server.
     *
     * The reason a failure failed is kept: a token that cannot broadcast
     * (the shared chat key, for instance) is refused with 403, and the
     * operator needs to be told THAT - "the live session could not be
     * started" is not an answer.
     */
    private fun startLiveSession(base: String, token: String): Pair<String, String>? = try {
        val req = Request.Builder()
            .url(base + "/live/start")
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                liveStartError = when (resp.code) {
                    403 -> "This token cannot start a broadcast. Create an operator device token " +
                        "in the admin panel (Chat) and sign in with that one."
                    401 -> "The server refused the token. Sign in again in Settings."
                    else -> "The live session could not be started (HTTP ${resp.code})."
                }
                return@use null
            }
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!json.optBoolean("ok", false)) {
                liveStartError = json.optString("error", "The live session could not be started.")
                return@use null
            }
            liveStartError = ""
            json.optString("rtmp_url", "") to json.optString("stream_key", "")
        }
    } catch (e: Exception) {
        liveStartError = "The server could not be reached: ${e.message ?: "unknown error"}"
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
