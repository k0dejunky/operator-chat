package com.amethyst2213.operatorchat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Live video broadcast: captures the device camera + mic, encodes H.264/AAC and
 * pushes RTMP to the site's MediaMTX server. Opens a live session via
 * /live/start (Bearer operator token), streams, and closes it via /live/stop.
 * The camera preview runs as soon as the screen opens, a Flip button switches
 * front/back camera, the stream follows the phone's orientation (portrait or
 * landscape), and the viewers' live group chat is shown so the operator can
 * read and reply while broadcasting.
 */
class LiveActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var textureView: TextureView
    private lateinit var statusLabel: TextView
    private lateinit var goBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var pauseBtn: Button
    private lateinit var flipBtn: Button
    private lateinit var chatList: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatInput: EditText

    private var stream: RtmpStream? = null
    private var rtmpUrl: String? = null
    private var surfaceReady = false
    private var streamRequested = false
    private var chatJob: Job? = null
    private var latestChatId = 0L
    private var micMuted = false
    private var paused = false
    private var rotating = false
    private var audioReady = true
    private var wakeLock: PowerManager.WakeLock? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val ok = perms[Manifest.permission.CAMERA] == true &&
                perms[Manifest.permission.RECORD_AUDIO] == true
            if (ok) {
                maybeStartPreview()
            } else {
                statusLabel.text = "Camera + mic permission required to go live."
                Toast.makeText(this, "Camera + mic permission required.", Toast.LENGTH_LONG).show()
            }
        }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)

        // Keep the screen on while the broadcast screen is open, so the device
        // never sleeps and drops the stream.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textureView = findViewById(R.id.live_surface)
        statusLabel = findViewById(R.id.live_status)
        goBtn = findViewById(R.id.live_go)
        stopBtn = findViewById(R.id.live_stop)
        muteBtn = findViewById(R.id.live_mute)
        pauseBtn = findViewById(R.id.live_pause)
        flipBtn = findViewById(R.id.live_flip)
        chatList = findViewById(R.id.live_chat_list)
        chatScroll = findViewById(R.id.live_chat_scroll)
        chatInput = findViewById(R.id.live_chat_input)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceReady = true
                maybeStartPreview()
                maybeStartStreaming()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                surfaceReady = false
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        goBtn.setOnClickListener {
            if (hasPermissions()) startBroadcast() else permLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }

        stopBtn.setOnClickListener { stopBroadcast() }

        muteBtn.setOnClickListener { toggleMicMute() }
        pauseBtn.setOnClickListener { togglePause() }

        flipBtn.setOnClickListener {
            val s = stream
            if (s == null) {
                Toast.makeText(this, "Waiting for the camera…", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val vs = s.videoSource
                if (vs is com.pedro.encoder.input.sources.video.Camera1Source) vs.switchCamera()
                else if (vs is com.pedro.encoder.input.sources.video.Camera2Source) vs.switchCamera()
            } catch (_: Exception) {}
        }

        findViewById<Button>(R.id.live_chat_send).setOnClickListener { sendLiveChat() }

        // Ask for camera + mic up front so the preview is available before Go Live.
        if (!hasPermissions()) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    /** Prepare the encoder/stream once (kept alive across rotations). */
    private fun prepareStream(): RtmpStream {
        return RtmpStream(this, this).apply {
            // Encode in the phone's current physical orientation so the stream
            // keeps the correct aspect ratio: portrait -> 360x640, landscape ->
            // 640x360. autoHandleOrientation keeps the content upright.
            getGlInterface().autoHandleOrientation = true
            val portrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
            prepareVideo(if (portrait) 360 else 640, if (portrait) 640 else 360, 800 * 1000)
            // AAC stereo 44.1k/128k is the most widely supported mic capture
            // config. prepareAudio returns false when the mic/encoder can't be
            // initialized (then the stream is video-only), so surface it.
            audioReady = prepareAudio(44100, true, 128 * 1000, false, false)
            if (!audioReady) {
                android.util.Log.e("LiveActivity", "prepareAudio failed - broadcasting without audio")
            }
        }
    }

    /** When the phone is rotated mid-broadcast, restart the encoder at the new
     *  orientation so the stream keeps the correct aspect ratio. The new stream
     *  is created only once the TextureView has recreated its surface texture
     *  for the new orientation (onSurfaceTextureAvailable) - attaching it to
     *  the old, about-to-be-replaced surface renders the preview offset. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val s = stream ?: return
        val url = rtmpUrl ?: return
        if (!s.isStreaming) return
        rotating = true
        try {
            s.stopStream()
            s.release()
        } catch (_: Exception) {
        }
        stream = null
        streamRequested = false
        statusLabel.text = "Rotating…"
        // Fallback: if the surface texture isn't recreated (orientation change
        // didn't resize the view), restart once the layout settles.
        textureView.postDelayed({
            if (stream == null && surfaceReady && rtmpUrl != null) {
                rotating = false
                maybeStartPreview()
                maybeStartStreaming()
            }
        }, 400)
    }

    /** Create the stream + camera preview (does not broadcast). */
    private fun maybeStartPreview() {
        if (!surfaceReady || stream != null || !hasPermissions()) return
        val s = prepareStream()
        stream = s
        try {
            s.startPreview(textureView)
        } catch (_: Exception) {
            // Camera not openable yet (e.g. permission dialog still up): drop
            // the stream so a later call can retry the preview.
            stream = null
        }
    }

    private fun startBroadcast() {
        val base = SecurePrefs.url(this).trim().trimEnd('/')
        val token = SecurePrefs.token(this).trim()
        if (base.isEmpty() || token.isEmpty()) {
            statusLabel.text = "Sign in first."
            return
        }

        goBtn.isEnabled = false
        statusLabel.text = "Starting session…"

        CoroutineScope(Dispatchers.Main).launch {
            val started = withContext(Dispatchers.IO) { startLiveSession(base, token) }
            if (started == null) {
                goBtn.isEnabled = true
                statusLabel.text = "Could not start live session — check connection and token."
                Toast.makeText(this@LiveActivity, "Could not start live session.", Toast.LENGTH_LONG).show()
                return@launch
            }

            rtmpUrl = started.first
            setStreamingUi(true)
            acquireWakeLock()
            if (!audioReady) {
                statusLabel.text = "Live (no audio - mic unavailable)"
                Toast.makeText(this@LiveActivity, "Mic could not be initialized - streaming video only.", Toast.LENGTH_LONG).show()
            }
            startLiveChat()
            maybeStartStreaming()
        }
    }

    /** Start the RTMP broadcast once the URL, surface and preview are ready. */
    private fun maybeStartStreaming() {
        val url = rtmpUrl ?: return
        if (!surfaceReady) return
        maybeStartPreview()
        val s = stream ?: return
        if (s.isStreaming) return

        streamRequested = true
        statusLabel.text = "Connecting…"
        s.startStream(url)
        // Re-apply mute/pause after a reconnect (e.g. a rotation restart).
        applyStreamState(s)
    }

    /** Re-apply the operator's mic-mute / pause to a (re)started stream. */
    private fun applyStreamState(s: RtmpStream) {
        try {
            if (micMuted) s.getStreamClient().setOnlyVideo(true)
            if (paused) {
                s.getGlInterface().muteVideo()
                s.getStreamClient().setOnlyVideo(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun stopBroadcast() {
        // Stop the encoder/RTMP but keep the camera preview so the operator can
        // reframe before the next broadcast.
        try { stream?.stopStream() } catch (_: Exception) {}
        streamRequested = false

        val base = SecurePrefs.url(this).trim().trimEnd('/')
        val token = SecurePrefs.token(this).trim()
        val key = rtmpUrl?.substringAfterLast('/') ?: ""
        rtmpUrl?.let {
            CoroutineScope(Dispatchers.IO).launch {
                try { stopLiveSession(base, token, key) } catch (_: Exception) {}
            }
        }
        rtmpUrl = null
        rotating = false
        micMuted = false
        paused = false
        muteBtn.text = "Mute mic"
        pauseBtn.text = "Pause"
        releaseWakeLock()
        stopLiveChat()
        setStreamingUi(false)
        statusLabel.text = "Stopped"
    }

    /** Hold a partial wake lock so the CPU/network stays alive while streaming
     *  (guards against the device dozing even if the screen is turned off). */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "operatorchat:live")
                .apply { acquire(2 * 60 * 60 * 1000L) }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    /** Mute/unmute the microphone without stopping the stream. */
    private fun toggleMicMute() {
        val s = stream ?: return
        micMuted = !micMuted
        try {
            // setOnlyVideo(true) = send video only (drop the mic); false restores it.
            s.getStreamClient().setOnlyVideo(micMuted)
        } catch (_: Exception) {
        }
        muteBtn.text = if (micMuted) "Unmute mic" else "Mute mic"
        statusLabel.text = if (micMuted) "Mic muted" else "Live"
    }

    /** Pause/resume the broadcast without ending it (viewers see a message). */
    private fun togglePause() {
        val s = stream ?: return
        paused = !paused
        try {
            // Pause: mute the video feed + drop audio so nothing sensitive
            // streams while the model steps away; the site shows a message.
            s.getGlInterface().let {
                if (paused) it.muteVideo() else it.unMuteVideo()
            }
            s.getStreamClient().setOnlyVideo(paused)
        } catch (_: Exception) {
        }
        pauseBtn.text = if (paused) "Resume" else "Pause"
        statusLabel.text = if (paused) "Paused — viewers see a message" else "Live"
        val base = SecurePrefs.url(this).trim().trimEnd('/')
        val token = SecurePrefs.token(this).trim()
        if (base.isEmpty() || token.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
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

    override fun onDestroy() {
        stopLiveChat()
        releaseWakeLock()
        try {
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
        }
        stream = null
        super.onDestroy()
    }

    // ---- Live session API -------------------------------------------------

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
        // Tell the server which stream ended so it can finalize the recording.
        // streamKey is captured by the caller *before* rtmpUrl is cleared.
        val body = okhttp3.FormBody.Builder().add("stream_key", streamKey).build()
        val req = Request.Builder()
            .url(base + "/live/stop")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        client.newCall(req).execute().close()
    }

    // ---- Live group chat --------------------------------------------------

    private data class LiveChatMsg(
        val id: Long,
        val senderRole: String,
        val name: String,
        val message: String,
        val createdAt: String,
    )

    private fun startLiveChat() {
        stopLiveChat()
        val base = SecurePrefs.url(this).trim().trimEnd('/')
        val token = SecurePrefs.token(this).trim()
        chatJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val msgs = withContext(Dispatchers.IO) {
                        fetchLiveChatOnce(base, token, latestChatId)
                    }
                    msgs.forEach { appendChat(it) }
                } catch (_: Exception) {
                    // Stream gone or network blip: keep polling.
                }
                delay(4000)
            }
        }
    }

    private fun stopLiveChat() {
        chatJob?.cancel()
        chatJob = null
    }

    /** One SSE read of /live/chat/stream — returns whatever arrived in the
     *  server's short window (the server ends the response after ~5s). */
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
                    val arr = json.optJSONArray("messages") ?: JSONArray()
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
                    // ignore keepalive / partial lines
                }
            }
            return out
        }
    }

    private fun appendChat(m: LiveChatMsg) {
        if (m.id > 0 && m.id <= latestChatId) return
        if (m.id > 0) latestChatId = m.id
        runOnUiThread {
            val tv = TextView(this).apply {
                val who = if (m.senderRole == "operator") "Operator" else m.name
                text = "$who: ${m.message}"
                textSize = 13f
                setTextColor(if (m.senderRole == "operator") 0xFFFF6060.toInt() else 0xFFEDE7F6.toInt())
                setPadding(0, 0, 0, 6)
            }
            chatList.addView(tv)
            while (chatList.childCount > 150) chatList.removeViewAt(0)
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun sendLiveChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        chatInput.setText("")
        val base = SecurePrefs.url(this).trim().trimEnd('/')
        val token = SecurePrefs.token(this).trim()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = okhttp3.FormBody.Builder().add("message", text).build()
                val req = Request.Builder()
                    .url("$base/live/chat/send")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@LiveActivity, "Could not send — no live stream?", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    Toast.makeText(this@LiveActivity, "Could not send message.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ConnectChecker callbacks (called on the stream's thread).
    override fun onConnectionStarted(url: String) {
        runOnUiThread { if (rotating) statusLabel.text = "Reconnecting…" }
    }
    override fun onConnectionSuccess() {
        rotating = false
        runOnUiThread {
            setStreamingUi(true)
            statusLabel.text = "Live"
        }
    }
    override fun onConnectionFailed(reason: String) {
        rotating = false
        runOnUiThread {
            setStreamingUi(false)
            statusLabel.text = "Stream failed: $reason"
        }
    }
    override fun onDisconnect() {
        runOnUiThread {
            // A clean stop during a rotation restart is expected - the reconnect
            // restores the live UI. Don't flip to the stopped state for it.
            if (rotating) {
                statusLabel.text = "Rotating…"
                return@runOnUiThread
            }
            setStreamingUi(false)
            statusLabel.text = "Disconnected"
        }
    }
    override fun onAuthError() {
        runOnUiThread { statusLabel.text = "Stream auth error" }
    }
    override fun onAuthSuccess() {
        runOnUiThread { statusLabel.text = "Authenticated" }
    }

    /** Set the broadcast controls to match the live/stopped state. */
    private fun setStreamingUi(live: Boolean) {
        stopBtn.isEnabled = live
        muteBtn.isEnabled = live
        pauseBtn.isEnabled = live
        goBtn.isEnabled = !live
    }
}