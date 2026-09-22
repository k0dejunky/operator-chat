package com.amethyst2213.operatorchat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Delivers queued operator replies (text) with their idempotency keys. Returns
 * retry when anything remains unsent so WorkManager backs off and retries.
 */
class SendReplyWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val url = SecurePrefs.url(app)
        val token = SecurePrefs.token(app)
        if (url.isEmpty() || token.isEmpty()) {
            return Result.failure()
        }
        val bridge = ChatBridge(url, token)
        val pending = SendQueue.pending(app)
        var failed = false
        for (p in pending) {
            try {
                val id = bridge.reply(p.conversationId, p.message, null, p.key)
                if (id > 0) {
                    SendQueue.remove(app, p.key)
                } else {
                    failed = true
                }
            } catch (_: Exception) {
                failed = true
            }
        }
        return if (SendQueue.hasPending(app)) {
            if (failed) Result.retry() else Result.success()
        } else {
            Result.success()
        }
    }
}