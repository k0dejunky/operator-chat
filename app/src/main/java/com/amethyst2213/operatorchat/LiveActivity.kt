package com.amethyst2213.operatorchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Live video broadcast: captures the device camera + mic, encodes H.264/AAC and
 * pushes RTMP to the site's MediaMTX server. Opens a live session via
 * /live/start (Bearer operator token), streams, and closes it via /live/stop.
 * Orientation is handled automatically (portrait or landscape), so the stream
 * stays upright no matter how the phone is held.
 */
class LiveActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusLabel: TextView
    private lateinit var goBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var flipBtn: Button

    private var stream: RtmpStream? = null
    private var rtmpUrl: String? = null
    private var surfaceReady = false
    private var streamRequested = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live)

        surfaceView = findViewById(R.id.live_surface)
        statusLabel = findViewById(R.id.live_status)
        goBtn = findViewById(R.id.live_go)
        stopBtn = findViewById(R.id.live_stop)
        flipBtn = findViewById(R.id.live_flip)

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
            val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (missing.isEmpty()) startBroadcast() else permLauncher.launch(missing)
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

        // Ask for camera + mic up front so the preview is available before Go Live.
        val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missing.isNotEmpty()) permLauncher.launch(missing)
    }

    /** Prepare the encoder/stream once (kept alive across rotations). */
    private fun prepareStream(): RtmpStream {
        return RtmpStream(this, this).apply {
            // Any orientation: the GL pipeline keeps the stream upright.
            getGlInterface().autoHandleOrientation = true
            prepareVideo(640, 360, 800 * 1000)
            prepareAudio(32000, true, 64 * 1000)
        }
    }

    /** Create the stream + camera preview (does not broadcast). */
    private fun maybeStartPreview() {
        if (!surfaceReady || stream != null) return
        val s = prepareStream()
        stream = s
        try { s.startPreview(surfaceView) } catch (_: Exception) {}
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
        stopBtn.isEnabled = false
        goBtn.isEnabled = true
        statusLabel.text = "Stopped"
    }

    override fun onDestroy() {
        try {
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
        }
        stream = null
        super.onDestroy()
    }

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