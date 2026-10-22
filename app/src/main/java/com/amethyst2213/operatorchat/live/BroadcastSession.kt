package com.amethyst2213.operatorchat.live

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import java.io.File
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

    /**
     * The preview surface, and its size.
     *
     * The surface usually arrives BEFORE the encoder is ready (the camera
     * takes a moment to open), so it is kept here and attached as soon as
     * there is a stream to attach it to. Otherwise the preview stayed black
     * until the operator left and came back.
     */
    private var pendingSurface: SurfaceTexture? = null
    private var pendingWidth = 0
    private var pendingHeight = 0

    /** True while the broadcast is also written to an MP4 on the device. */
    @Volatile
    private var recording = false

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
            // The surface is usually ready before the camera is; attach it
            // now that there is an encoder to attach it to.
            attachPreviewIfReady()
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
        pendingSurface = surface
        pendingWidth = width
        pendingHeight = height
        attachPreviewIfReady()
    }

    /** Attach the preview, but only when the encoder is there. */
    fun attachPreviewIfReady() {
        val surface = pendingSurface ?: return
        val rtmp = stream ?: return
        try {
            rtmp.startPreview(surface, pendingWidth, pendingHeight)
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
            stream?.stopRecord()
        } catch (_: Throwable) {
        }
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
        pendingSurface = null
        recording = false
    }

    // ------------------------------------------------------------- recording

    /**
     * Save the broadcast to an MP4 on the device, next to the RTMP push.
     *
     * The server records the stream too, but that copy lives on the box -
     * this one is the operator's own.
     */
    fun startRecording(dir: File, name: String): File? {
        val rtmp = stream ?: return null
        if (recording) return null
        return try {
            val file = File(dir, name)
            rtmp.startRecord(file.absolutePath, object : com.pedro.library.base.recording.RecordController.Listener {
                override fun onStatusChange(status: com.pedro.library.base.recording.RecordController.Status) {
                    recording = status != com.pedro.library.base.recording.RecordController.Status.STOPPED
                }

                override fun onError(e: Exception) {
                    recording = false
                }
            })
            recording = true
            file
        } catch (error: Throwable) {
            Log.e(TAG, "the recording could not be started", error)
            recording = false
            null
        }
    }

    /** Stop the recording and keep the broadcast running. */
    fun stopRecording() {
        try {
            stream?.stopRecord()
        } catch (error: Throwable) {
            Log.e(TAG, "the recording did not stop cleanly", error)
        }
        recording = false
    }

    /** True while the MP4 is being written. */
    fun isRecording(): Boolean = recording

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
