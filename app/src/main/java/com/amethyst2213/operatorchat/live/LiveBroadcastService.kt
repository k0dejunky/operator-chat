package com.amethyst2213.operatorchat.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.amethyst2213.operatorchat.R
import com.amethyst2213.operatorchat.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        instance = this
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
                session?.release()
                session = null
                _state.value = BroadcastState.Stopped("Stopped")
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
        session?.release()
        session = null
        _state.value = BroadcastState.Idle
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Move the service into the foreground so the camera may keep running. */
    private fun startForegroundCompat() {
        val notification = buildNotification("Preparing the camera…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

        /**
         * The running service.
         *
         * The service is not bound (there is no IPC here), so the Activity
         * reaches it through this reference. It is set in onCreate and
         * cleared in onDestroy.
         */
        @Volatile
        private var instance: LiveBroadcastService? = null

        fun current(): LiveBroadcastService? = instance

        private const val CHANNEL_ID = "live_broadcast"
        private const val NOTIFICATION_ID = 2001

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
            context.startService(intent)
        }
    }
}

/** Color to ARGB, for the notification. */
private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt(),
    )
