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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Live video broadcast: captures the device camera + mic, encodes H.264/AAC and
 * pushes RTMP to the site's MediaMTX server. Opens a live session via
 * /live/start (Bearer operator token), streams, and closes it via /live/stop.
 */
class LiveActivity : AppCompatActivity(), ConnectChecker {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusLabel: TextView
    private lateinit var goBtn: Button
    private lateinit var stopBtn: Button

    private var stream: RtmpStream? = null
    private var rtmpUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val ok = perms[Manifest.permission.CAMERA] == true &&
                perms[Manifest.permission.RECORD_AUDIO] == true
            if (ok) startBroadcast() else {
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

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // If the preview disappears while streaming, keep the stream
                // alive (the encoder holds its own surface).
            }
        })

        goBtn.setOnClickListener {
            val missing = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (missing.isEmpty()) startBroadcast() else permLauncher.launch(missing)
        }

        stopBtn.setOnClickListener { stopBroadcast() }
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
            stream = RtmpStream(this@LiveActivity, this@LiveActivity).apply {
                prepareVideo(640, 360, 800 * 1000)
                prepareAudio(32000, true, 64 * 1000)
                startPreview(surfaceView)
                startStream(started.first)
            }
            stopBtn.isEnabled = true
            statusLabel.text = "Live — " + started.first.substringBefore('?')
        }
    }

    private fun stopBroadcast() {
        try {
            stream?.stopStream()
            stream?.release()
        } catch (_: Exception) {
        }
        stream = null
        rtmpUrl?.let {
            val base = SecurePrefs.url(this).trim().trimEnd('/')
            val token = SecurePrefs.token(this).trim()
            CoroutineScope(Dispatchers.IO).launch {
                try { stopLiveSession(base, token) } catch (_: Exception) {}
            }
        }
        rtmpUrl = null
        stopBtn.isEnabled = false
        goBtn.isEnabled = true
        statusLabel.text = "Stopped"
    }

    override fun onDestroy() {
        stopBroadcast()
        super.onDestroy()
    }

    private fun startLiveSession(base: String, token: String): Pair<String, String>? = try {
        val url = base + "/live/start"
        val req = Request.Builder()
            .url(url)
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

    private fun stopLiveSession(base: String, token: String) {
        val req = Request.Builder()
            .url(base + "/live/stop")
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().close()
    }

    // ConnectChecker callbacks (called on the stream's thread).
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {
        runOnUiThread { statusLabel.text = "Live" }
    }
    override fun onConnectionFailed(reason: String) {
        runOnUiThread { statusLabel.text = "Stream failed: $reason" }
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