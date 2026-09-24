package com.amethyst2213.operatorchat

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

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
            return Result.success()
        }
        val bridge = ChatBridge(url, token)
        val pending = SendQueue.pending(app)
        var failed = false
        for (p in pending) {
            try {
                val file = p.attachmentPath?.let { path ->
                    File(path).takeIf { it.isFile }
                }
                val id = bridge.reply(p.conversationId, p.message, file, p.key)
                if (id > 0) {
                    SendQueue.remove(app, p.key)
                } else {
                    failed = true
                }
            } catch (_: Exception) {
                failed = true
            }
        }
        if (!SendQueue.hasPending(app)) return Result.success()

        // Cap WorkManager's retry attempts so a permanently-unsendable message
        // stops burning backoff cycles (and battery). The periodic re-drain
        // worker keeps trying in the background, so the message isn't lost.
        if (failed && runAttemptCount >= 6) {
            return Result.success()
        }
        return if (failed) Result.retry() else Result.success()
    }
}