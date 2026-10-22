package com.amethyst2213.operatorchat.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.amethyst2213.operatorchat.R
import com.amethyst2213.operatorchat.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The broadcast service: it owns the camera, the encoder and the RTMP
 * connection.
 *
 * The old design kept all of that inside the LiveActivity, which meant:
 *
 *  - the stream died the moment the operator switched apps or the screen
 *    locked, because there was no foreground service holding it;
 *  - a rotation released the encoder and rebuilt it from scratch, in a
 *    single 400ms window - if the surface was not ready in time, the
 *    broadcast was silently dead while the screen still said "Rotating…";
 *  - the preview TextureView lived inside a ScrollView, so scrolling
 *    destroyed the surface and the preview went black for good.
 *
 * Here the encoder outlives the Activity. The Activity attaches a preview
 * surface to the encoder and detaches it when it goes away; the broadcast
 * itself carries on.
 */
class LiveBroadcastService : Service() {

    private val _state = MutableStateFlow<BroadcastState>(BroadcastState.Idle)
    val state: StateFlow<BroadcastState> = _state.asStateFlow()

    /** The encoder. It lives here so it survives the Activity. */
    var session: BroadcastSession? = null
        private set

    /**
     * The wake lock.
     *
     * Without it the screen sleeps during a broadcast, the CPU is throttled
     * and the stream dies halfway through. It is held only while the
     * broadcast is actually running.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
        _ready.value = this
    }

    /**
     * Create the camera + encoder.
     *
     * The service owns it, so the encoder survives the Activity. The
     * Activity attaches a preview surface to it and detaches it again.
     */
    fun createSession(listener: BroadcastSession.Listener): BroadcastSession {
        session?.release()
        val created = BroadcastSession(this, listener)
        session = created
        return created
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundCompat()
            }
        }
        // The broadcast is not restarted automatically: the operator
        // decides when to go live.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        if (instance === this) instance = null
        if (_ready.value === this) _ready.value = null
        super.onDestroy()
    }

    /** Release the camera, the encoders and the wake lock. */
    private fun stopEverything() {
        session?.release()
        session = null
        releaseWakeLock()
        _state.value = BroadcastState.Idle
    }

    /**
     * Move the service into the foreground so the camera may keep running.
     *
     * The type is decided by the permissions that are ACTUALLY granted: on
     * Android 14+ a service that is promoted with the camera or the
     * microphone type without holding that permission throws a
     * SecurityException, and the service was started before the operator had
     * ever been asked for the camera.
     */
    private fun startForegroundCompat() {
        val notification = buildNotification("Preparing the camera…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    allowedForegroundTypes(),
                )
            } catch (error: Throwable) {
                // The camera type is refused when the permission is missing;
                // the broadcast still works with the data-sync type alone.
                Log.w(TAG, "could not promote the service with the camera type", error)
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } catch (fallback: Throwable) {
                    Log.e(TAG, "could not promote the service at all", fallback)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Re-promote the service after the operator granted the camera or the
     * microphone, so the broadcast may use them.
     */
    fun promoteWithCamera() {
        startForegroundCompat()
    }

    /** The foreground service types this app is allowed to use right now. */
    private fun allowedForegroundTypes(): Int = foregroundTypes(
        hasPermission(Manifest.permission.CAMERA),
        hasPermission(Manifest.permission.RECORD_AUDIO),
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Keep the CPU awake while the broadcast is running. */
    fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "operatorchat:live")
                .apply { setReferenceCounted(false) }
            wakeLock?.acquire(MAX_WAKE_LOCK_MS)
        } catch (_: Throwable) {
            // A device without a working power manager still broadcasts.
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    /** Update the ongoing notification as the broadcast progresses. */
    fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, Class.forName("com.amethyst2213.operatorchat.LiveActivity")),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setColor(Palette.Amethyst.toArgb())
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live broadcast",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a live broadcast is running."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.amethyst2213.operatorchat.STOP_BROADCAST"

        private const val TAG = "LiveBroadcastService"

        /** A broadcast is not a 4-hour affair; the lock has a ceiling anyway. */
        private const val MAX_WAKE_LOCK_MS = 6L * 60 * 60 * 1000

        /**
         * The running service.
         *
         * The service is not bound (there is no IPC here), so the Activity
         * reaches it through this reference. It is set in onCreate and
         * cleared in onDestroy.
         */
        @Volatile
        private var instance: LiveBroadcastService? = null

        /**
         * The service, as a flow.
         *
         * The Activity used to read the static instance in onStart, but the
         * service's onCreate runs AFTER the activity's onStart - so on the
         * first visit there was no service yet, the state was never shown
         * and "Go live" did nothing. The flow lets the Activity wait for it.
         */
        private val _ready = MutableStateFlow<LiveBroadcastService?>(null)

        fun current(): LiveBroadcastService? = instance

        /** Wait for the service to be there (it is created by the system). */
        suspend fun awaitInstance(timeoutMs: Long = 5000): LiveBroadcastService? =
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                _ready.filterNotNull().first()
            }

        private const val CHANNEL_ID = "live_broadcast"
        private const val NOTIFICATION_ID = 2001

        /**
         * The foreground service types the app may be promoted with.
         *
         * On Android 14+ a service that is promoted with the camera or the
         * microphone type while the permission is NOT held throws a
         * SecurityException, and the service was started before the
         * operator had ever been asked for the camera - so opening the live
         * screen could crash the app. The data-sync type is always allowed.
         */
        fun foregroundTypes(cameraGranted: Boolean, micGranted: Boolean): Int {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (cameraGranted) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (micGranted) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            return type
        }

        fun start(context: Context) {
            val intent = Intent(context, LiveBroadcastService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveBroadcastService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}

/** Color to ARGB, for the notification. */
private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (blue * 255).toInt(),
        (green * 255).toInt(),
    )
