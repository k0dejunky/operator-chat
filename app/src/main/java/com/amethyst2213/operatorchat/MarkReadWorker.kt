package com.amethyst2213.operatorchat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Durable notification action: mark a conversation read with retry. */
class MarkReadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cid = inputData.getLong("conversation_id", 0L)
        val url = SecurePrefs.url(applicationContext)
        val token = SecurePrefs.token(applicationContext)
        if (cid <= 0 || url.isEmpty() || token.isEmpty()) return Result.failure()
        return try {
            if (ChatBridge(url, token).setRead(cid)) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
