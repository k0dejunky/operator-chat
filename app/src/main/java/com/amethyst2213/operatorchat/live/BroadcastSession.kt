package com.amethyst2213.operatorchat.live

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import com.amethyst2213.operatorchat.R
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.rtmp.RtmpStream

/**
 * The camera, the encoder and the RTMP connection.
 *
 * It is created and destroyed with the service, not with the Activity, so
 * a rotation does not touch the encoder at all. The preview surface is
 * attached and detached around it.
 */
class BroadcastSession(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onState(state: BroadcastState)
        fun onLog(message: String)
    }

    private var stream: RtmpStream? = null
    private var camera: Camera2Source? = null
    private var microphone: MicrophoneSource? = null
    private var previewSurface: SurfaceTexture? = null

    /** The bitrate of the broadcast. 800 kbps was visibly blocky. */
    var bitrate: Int = 2_500_000
        set(value) {
            field = value
        }

    /** The frame rate. */
    var fps: Int = 30
        set(value) {
            field = value
        }

    /**
     * The camera and the encoder.
     *
     * The old code did this on the UI thread: two MediaCodecs and the
     * camera open, which is 100-500ms of frozen screen every time the
     * operator pressed Go Live.
     */
    fun prepare(landscape: Boolean) {
        release()
        listener.onState(BroadcastState.Preparing)

        try {
            val cameraSource = Camera2Source(context)
            val micSource = MicrophoneSource()
            val rtmp = RtmpStream(context, connectChecker, cameraSource, micSource)

            val width = if (landscape) 640 else 360
            val height = if (landscape) 360 else 640
            rtmp.getGlInterface().autoHandleOrientation = true

            if (!rtmp.prepareVideo(width, height, bitrate, fps)) {
                throw IllegalStateException("This device cannot encode video.")
            }
            val audioReady = rtmp.prepareAudio(44100, true, 128 * 1000, false, false)
            if (!audioReady) {
                // The old code carried on without audio and only logged it.
                // The operator is told now, because a silent broadcast is
                // not what they pressed Go Live for.
                listener.onLog("The microphone is unavailable - broadcasting without audio.")
            }

            camera = cameraSource
            microphone = micSource
            stream = rtmp
            listener.onState(BroadcastState.Idle)
        } catch (error: Throwable) {
            Log.e(TAG, "the encoder could not be prepared", error)
            release()
            listener.onState(BroadcastState.Failed(error.message ?: "The camera could not be started."))
        }
    }

    /** Start publishing to [url]. */
    fun start(url: String) {
        val rtmp = stream ?: run {
            listener.onState(BroadcastState.Failed("The camera is not ready yet."))
            return
        }
        listener.onState(BroadcastState.Connecting)
        try {
            rtmp.startStream(url)
        } catch (error: Throwable) {
            Log.e(TAG, "the broadcast could not be started", error)
            listener.onState(BroadcastState.Failed(error.message ?: "The broadcast could not be started."))
        }
    }

    /** Stop publishing but keep the camera open for the next go. */
    fun stopStreaming() {
        try {
            stream?.stopStream()
        } catch (error: Throwable) {
            Log.e(TAG, "the broadcast did not stop cleanly", error)
        }
        listener.onState(BroadcastState.Stopped())
    }

    /**
     * Attach the preview surface.
     *
     * The preview used to live in the Activity's TextureView, inside a
     * ScrollView: scrolling destroyed the SurfaceTexture and the preview
     * never came back. The surface is attached to the encoder here, so it
     * can be attached and detached freely.
     */
    fun attachPreview(surface: SurfaceTexture, width: Int, height: Int) {
        previewSurface = surface
        try {
            stream?.startPreview(surface, width, height)
        } catch (error: Throwable) {
            Log.e(TAG, "the preview could not be attached", error)
        }
    }

    fun detachPreview() {
        previewSurface = null
        try {
            stream?.stopPreview(false)
        } catch (error: Throwable) {
            Log.e(TAG, "the preview could not be detached", error)
        }
    }

    /** Switch between the front and the back camera. */
    fun flipCamera() {
        try {
            camera?.switchCamera()
        } catch (error: Throwable) {
            Log.e(TAG, "the camera could not be switched", error)
            listener.onLog("That camera could not be switched to.")
        }
    }

    /** Mute the microphone (the video keeps going). */
    fun setMicMuted(muted: Boolean) {
        try {
            // setOnlyVideo(true) = send video only (drop the mic); false
            // restores it.
            stream?.getStreamClient()?.setOnlyVideo(muted)
        } catch (error: Throwable) {
            Log.e(TAG, "the microphone could not be muted", error)
        }
    }

    /** Freeze the picture (the broadcast keeps running). */
    fun setPaused(paused: Boolean) {
        try {
            if (paused) {
                stream?.getGlInterface()?.muteVideo()
            } else {
                stream?.getGlInterface()?.unMuteVideo()
            }
        } catch (error: Throwable) {
            Log.e(TAG, "the picture could not be paused", error)
        }
    }

    /** Release the camera, the encoders and the RTMP connection. */
    fun release() {
        try {
            stream?.stopStream()
        } catch (_: Throwable) {
        }
        try {
            stream?.release()
        } catch (_: Throwable) {
        }
        stream = null
        camera = null
        microphone = null
        previewSurface = null
    }

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            listener.onLog("Connecting…")
        }

        override fun onConnectionSuccess() {
            listener.onState(BroadcastState.Live())
        }

        override fun onConnectionFailed(reason: String) {
            // The old code swallowed this and left the screen claiming it
            // was live.
            Log.e(TAG, "the broadcast failed: $reason")
            listener.onState(BroadcastState.Failed(reason))
        }

        override fun onAuthError() {
            listener.onState(BroadcastState.Failed("The server refused the broadcast."))
        }

        override fun onAuthSuccess() {
        }

        override fun onDisconnect() {
            listener.onState(BroadcastState.Stopped("The broadcast ended."))
        }
    }

    companion object {
        private const val TAG = "BroadcastSession"
    }
}
