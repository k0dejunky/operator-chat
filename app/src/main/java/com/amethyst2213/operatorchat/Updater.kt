package com.amethyst2213.operatorchat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.TextView
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Downloads the latest APK from the site and triggers the system installer,
 * so the operator can update the app in place (no sideload/uninstall first).
 *
 * The download is Bearer-authenticated and the file is verified before
 * install: its SHA-256 must match the value published by the server, and its
 * signing certificate must match the release key. A tampered or re-signed APK
 * is never offered to the installer.
 */
object Updater {

    /** SHA-256 of the release signing certificate (DER bytes), from keytool. */
    private const val EXPECTED_SIGNER_SHA256 =
        "B6881E87830BF50ABED6D3FA0FAC8DE4213714F24E0A90901FEED4A92B86F89B"

    fun downloadAndInstall(
        scope: CoroutineScope,
        context: Context,
        baseUrl: String,
        token: String,
        v: ChatBridge.AppVersion,
        status: TextView?,
    ) {
        scope.launch {
            try {
                status?.text = "Downloading v${v.latestVersion}…"
                val bridge = ChatBridge(baseUrl, token)
                val dest = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "OperatorChat-${v.latestVersion}.apk",
                )
                val file = withContext(Dispatchers.IO) {
                    bridge.downloadApk(v.latestVersion, dest)
                }
                if (file == null) {
                    status?.text = "Download failed — check connection and try again."
                    return@launch
                }

                // 1) Content check: SHA-256 must match the server-published value.
                val actualSha = withContext(Dispatchers.IO) { sha256Of(file) }
                val expectedSha = v.sha256.lowercase()
                if (expectedSha.isEmpty() || actualSha != expectedSha) {
                    file.delete()
                    status?.text = "Update rejected — checksum mismatch. Try again later."
                    return@launch
                }

                // 2) Signature check: must be signed by the release key.
                val signer = withContext(Dispatchers.IO) { signerSha256(file, context) }
                if (signer == null || signer != EXPECTED_SIGNER_SHA256) {
                    file.delete()
                    status?.text = "Update rejected — signature verification failed."
                    return@launch
                }

                status?.text = "Installing v${v.latestVersion}…"
                // On Android 8+ the installer is blocked unless the user has
                // granted "Install unknown apps" for this app. Route them to
                // that setting instead of letting the install fail silently.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    status?.text = "Allow installs from this app in Settings, then tap Update again."
                    val permIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(permIntent)
                    return@launch
                }
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

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun signerSha256(apk: File, context: Context): String? {
        return try {
            // API 28+: read the full signing certificate set (handles
            // v2/v3-only signed APKs); fall back to the legacy GET_SIGNATURES.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val info = context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                flags,
            ) ?: return null
            val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners ?: info.signatures
            } else {
                info.signatures
            }
            val sig = signers?.firstOrNull() ?: return null
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}