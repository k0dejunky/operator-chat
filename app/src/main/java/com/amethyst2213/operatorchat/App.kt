package com.amethyst2213.operatorchat

import android.app.Application
import android.os.Environment
import android.util.Log
import coil.Coil
import coil.ImageLoader
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileWriter

/**
 * App-level setup: an uncaught exception handler (writes crashes to a log
 * file) and an authenticated Coil image loader so attachment thumbnails are
 * fetched with the bridge Bearer token and cached in memory + disk.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        pruneTemporaryCache()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e(TAG, "Uncaught exception", throwable)
                val dir = getExternalFilesDir(null) ?: filesDir
                val f = File(dir, "crash.log")
                FileWriter(f, true).use { w ->
                    w.write("\n[${System.currentTimeMillis()}] ${throwable}\n")
                    throwable.stackTrace?.forEach { w.write("\t$it\n") }
                }
            } catch (_: Exception) {
                // nothing else we can do
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        // Coil loader that attaches the operator token to every media request
        // (attachments are Bearer-protected) and caches thumbnails on disk.
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = SecurePrefs.token(this@App)
                val builder = chain.request().newBuilder()
                if (token.isNotEmpty()) {
                    builder.header("Authorization", "Bearer $token")
                }
                chain.proceed(builder.build())
            }
            .build()

        Coil.setImageLoader(
            ImageLoader.Builder(this).okHttpClient(client).build()
        )
    }

    private fun pruneTemporaryCache() {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        cacheDir.listFiles()?.forEach { file ->
            if ((file.name.startsWith("thumb_") || file.name.startsWith("full_") || file.name.startsWith("attachment_"))
                && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    companion object {
        private const val TAG = "OperatorChat"
    }
}