package com.amethyst2213.operatorchat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Looks for a newer build of the app while the app is not open.
 *
 * The app checks for an update when it starts, but the operator does not
 * always open it. This worker runs every six hours and puts a notification
 * up when the site publishes something newer, so the update is waiting when
 * the app is opened next.
 */
class UpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val url = SecurePrefs.url(app).trim()
        if (url.isEmpty()) return Result.success()

        val offer = try {
            Updater.checkLatest(app, url, SecurePrefs.token(app).trim())
        } catch (_: Exception) {
            null
        } ?: return Result.success()

        notify(app, offer)
        return Result.success()
    }

    private fun notify(context: Context, offer: UpdateOffer) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Operator Chat ${offer.version} is available")
            .setContentText("Open the app to install it.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "operator_chat_updates"
        private const val NOTIFICATION_ID = 3001
        private const val WORK_NAME = "operator-chat-update-check"

        /**
         * Ask for the check every six hours.
         *
         * The first run is delayed: the app already checks when it starts,
         * so the worker is only the backstop for a phone that stays closed -
         * and a job that fires the moment the app opens is work nobody asked
         * for.
         */
        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                    .setInitialDelay(15, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            } catch (_: Exception) {
                // WorkManager may not be initialised (it needs the startup
                // provider); the check on app start still runs.
            }
        }
    }
}
