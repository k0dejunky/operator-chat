package com.amethyst2213.operatorchat

import android.app.Application
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter

/**
 * App-level uncaught exception handler: writes crashes to a log file in the
 * app's external files dir and keeps the process from silently dying without
 * any trace (helps diagnose device-side issues).
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
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
    }

    companion object {
        private const val TAG = "OperatorChat"
    }
}