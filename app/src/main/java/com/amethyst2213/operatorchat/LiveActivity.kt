package com.amethyst2213.operatorchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.SurfaceHolder
import android.view.SurfaceView
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

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusLabel: TextView
    private lateinit var goBtn: Button
    private lateinit var stopBtn: Button
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

        surfaceView = findViewById(R.id.live_surface)
        statusLabel = findViewById(R.id.live_status)
        goBtn = findViewById(R.id.live_go)
        stopBtn = findViewById(R.id.live_stop)
        flipBtn = findViewById(R.id.live_flip)
        chatList = findViewById(R.id.live_chat_list)
        chatScroll = findViewById(R.id.live_chat_scroll)
        chatInput = findViewById(R.id.live_chat_input)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                maybeStartPreview()
                maybeStartStreaming()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })

        goBtn.setOnClickListener {
            if (hasPermissions()) startBroadcast() else permLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }

        stopBtn.setOnClickListener { stopBroadcast() }

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
            // Follow the phone's physical orientation (portrait or landscape).
            getGlInterface().autoHandleOrientation = true
            prepareVideo(640, 360, 800 * 1000)
            prepareAudio(32000, true, 64 * 1000)
        }
    }

    /** Create the stream + camera preview (does not broadcast). */
    private fun maybeStartPreview() {
        if (!surfaceReady || stream != null || !hasPermissions()) return
        val s = prepareStream()
        stream = s
        try {
            s.startPreview(surfaceView)
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
            stopBtn.isEnabled = true
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
        stopLiveChat()
        stopBtn.isEnabled = false
        goBtn.isEnabled = true
        statusLabel.text = "Stopped"
    }

    override fun onDestroy() {
        stopLiveChat()
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
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        runOnUiThread { statusLabel.text = "Live" }
    }
    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            statusLabel.text = "Stream failed: $reason"
            stopBtn.isEnabled = false
            goBtn.isEnabled = true
        }
    }
    override fun onDisconnect() {
        runOnUiThread {
            stopBtn.isEnabled = false
            goBtn.isEnabled = true
            statusLabel.text = "Disconnected"
        }
    }
    override fun onAuthError() {
        runOnUiThread { statusLabel.text = "Stream auth error" }
    }
    override fun onAuthSuccess() {
        runOnUiThread { statusLabel.text = "Authenticated" }
    }
}