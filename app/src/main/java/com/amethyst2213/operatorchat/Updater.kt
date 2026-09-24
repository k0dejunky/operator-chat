package com.amethyst2213.operatorchat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads the latest APK from the site and triggers the system installer,
 * so the operator can update the app in place (no sideload/uninstall first).
 */
object Updater {

    fun downloadAndInstall(context: Context, baseUrl: String, v: ChatBridge.AppVersion, status: TextView?) {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            try {
                status?.text = "Downloading v${v.latestVersion}…"
                val bridge = ChatBridge(baseUrl, "")
                val apkUrl = if (v.apkUrl.startsWith("http")) v.apkUrl else bridge.resolveUrl(v.apkUrl)
                val dest = File(context.getExternalFilesDir(null) ?: context.filesDir, "OperatorChat-${v.latestVersion}.apk")
                val file = withContext(Dispatchers.IO) {
                    bridge.downloadFile(apkUrl, dest)
                }
                if (file == null) {
                    status?.text = "Download failed — check connection and try again."
                    return@launch
                }
                status?.text = "Installing v${v.latestVersion}…"
                val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                status?.text = "Update failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}