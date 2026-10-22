package com.amethyst2213.operatorchat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Finds the published build of the app, downloads it and hands it to the
 * system installer.
 *
 * The published version manifest and the APK are public (they are static
 * files under /assets/apk/), so an update can be found WITHOUT a token -
 * which matters, because the phone that needs the update is often the one
 * that cannot sign in any more.
 *
 * The download is verified before it is offered to the installer: its
 * SHA-256 must match the value published with the manifest, and its signing
 * certificate must be the release key. A tampered or re-signed APK is never
 * handed to the installer.
 */
object Updater {

    /** SHA-256 of the release signing certificate (DER bytes), from keytool. */
    const val EXPECTED_SIGNER_SHA256 =
        "B6881E87830BF50ABED6D3FA0FAC8DE4213714F24E0A90901FEED4A92B86F89B"

    private val client: OkHttpClient by lazy { ChatBridge.SHARED_CLIENT }

    /**
     * The newest build the site publishes.
     *
     * Returns null when the site cannot be reached, when there is no
     * manifest, when the published build cannot be verified, or when it is
     * not newer than the one that is installed.
     */
    suspend fun checkLatest(
        context: Context,
        baseUrl: String,
        token: String = "",
    ): UpdateOffer? {
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        val manifest = readManifest(base) ?: return null

        val version = manifest.optString("latestVersion", "")
        val versionCode = manifest.optLong("versionCode", 0)
        if (version.isBlank() || versionCode <= 0L) return null

        // The checksum published with the manifest. If the manifest does not
        // carry one, the authenticated endpoint is asked instead - it
        // recomputes the hash from the file on disk.
        var sha = manifest.optString("sha256", "")
        if (sha.isBlank() && token.isNotBlank()) {
            sha = ChatBridge(baseUrl, token).checkForUpdate()?.sha256 ?: ""
        }
        if (sha.isBlank()) return null

        val installed = installedVersionCode(context)
        if (installed <= 0L || versionCode <= installed) return null

        return UpdateOffer(
            version = version,
            versionCode = versionCode,
            notes = manifest.optString("changelog", ""),
            phase = UpdatePhase.AVAILABLE,
        )
    }

    /** The manifest, read from its public path. */
    private suspend fun readManifest(base: HttpUrl): JSONObject? = withContext(Dispatchers.IO) {
        val url = base.newBuilder()
            .addPathSegment("assets")
            .addPathSegment("apk")
            .addPathSegment("operator-chat-version.json")
            .build()
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                JSONObject(resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The published APK, downloaded and verified.
     *
     * The APK URL in the manifest is an absolute path (/gallery/assets/apk/...),
     * so it is resolved against the ORIGIN of the base URL - resolving it
     * against the base path would produce /gallery/gallery/assets/... .
     */
    suspend fun fetchVerifiedApk(
        context: Context,
        baseUrl: String,
        offer: UpdateOffer,
        onPhase: (UpdatePhase) -> Unit = {},
    ): File? {
        val base = baseUrl.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        val manifest = readManifest(base) ?: return null
        val rawUrl = manifest.optString("apkUrl", "")
        if (rawUrl.isBlank()) return null

        val origin = base.newBuilder().encodedPath("/").build()
        val apkUrl: HttpUrl = origin.resolve(rawUrl) ?: return null

        onPhase(UpdatePhase.DOWNLOADING)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val dest = File(dir, "OperatorChat-${offer.version}.apk")
        val file = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(apkUrl).get().build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.byteStream()?.use { ins ->
                        dest.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    dest
                }
            } catch (_: Exception) {
                null
            }
        }
        if (file == null) {
            onPhase(UpdatePhase.FAILED)
            return null
        }

        onPhase(UpdatePhase.VERIFYING)
        val actualSha = withContext(Dispatchers.IO) { sha256Of(file) }
        val expectedSha = manifest.optString("sha256", "")
        if (expectedSha.isNotBlank() && actualSha != expectedSha.lowercase()) {
            file.delete()
            onPhase(UpdatePhase.FAILED)
            return null
        }

        val signer = withContext(Dispatchers.IO) { signerSha256(context, file) }
        if (signer == null || signer != EXPECTED_SIGNER_SHA256) {
            file.delete()
            onPhase(UpdatePhase.FAILED)
            return null
        }
        return file
    }

    /**
     * Hand a verified APK to the system installer.
     *
     * Android does not let an app install itself silently: the installer
     * always asks. When "install unknown apps" has not been granted for this
     * app, the operator is taken to that setting instead of watching the
     * install fail.
     */
    fun install(context: Context, file: File, onPhase: (UpdatePhase) -> Unit = {}) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            onPhase(UpdatePhase.FAILED)
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permIntent)
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        onPhase(UpdatePhase.INSTALLING)
        context.startActivity(intent)
    }

    /** The version code of the installed build, or 0 when it cannot be read. */
    fun installedVersionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    } catch (_: Exception) {
        0L
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

    private fun signerSha256(context: Context, apk: File): String? {
        return try {
            // API 28+: read the full signing certificate set (handles
            // v2/v3-only signed APKs); fall back to the legacy GET_SIGNATURES.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
                ?: return null
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
