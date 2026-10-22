package com.amethyst2213.operatorchat

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.amethyst2213.operatorchat.ui.screens.SettingsScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme
import kotlinx.coroutines.launch

/**
 * The operator's settings.
 *
 * The old screen could only LOOK at the server: the address and the token
 * were fixed when the screen opened, so a moved server or a rotated token
 * meant reinstalling the app. They are editable and saved now.
 */
class SettingsActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("operator_chat", MODE_PRIVATE) }

    private var serverUrl by mutableStateOf(SecurePrefs.url(this))
    private var operatorToken by mutableStateOf(SecurePrefs.token(this))
    private var notificationsOn by mutableStateOf(prefs.getBoolean("notify_enabled", true))
    private var biometricOn by mutableStateOf(prefs.getBoolean("biometric_lock", true))
    private var quietOn by mutableStateOf(prefs.getBoolean("quiet_hours_enabled", false))
    private var quietStart by mutableStateOf(prefs.getString("quiet_start", "22:00") ?: "22:00")
    private var quietEnd by mutableStateOf(prefs.getString("quiet_end", "08:00") ?: "08:00")
    private var toneName by mutableStateOf("Default")
    private var siteTimezone by mutableStateOf(ChatRows.DEFAULT_ZONE)
    private var status by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)

    /** A published build that is newer than the installed one. */
    private var update by mutableStateOf<UpdateOffer?>(null)

    private val ringtoneResult = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            prefs.edit().putString("notify_tone", uri.toString()).apply()
            toneName = readableName(uri.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        toneName = readableName(
            prefs.getString("notify_tone", null)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        )

        setContent {
            OperatorTheme {
                SettingsScreen(
                    serverUrl = serverUrl,
                    token = operatorToken,
                    notificationsOn = notificationsOn,
                    biometricOn = biometricOn,
                    quietOn = quietOn,
                    quietStart = quietStart,
                    quietEnd = quietEnd,
                    toneName = toneName,
                    siteTimezone = siteTimezone,
                    appVersion = appVersion(),
                    lastSync = prefs.getString("last_sync_at", null) ?: "never",
                    lastError = prefs.getString("last_error", null),
                    status = status,
                    error = error,
                    update = update,
                    onServerUrlChange = { serverUrl = it },
                    onTokenChange = { operatorToken = it },
                    onNotificationsChange = { on -> notificationsOn = on; set("notify_enabled", on) },
                    onBiometricChange = { on -> biometricOn = on; set("biometric_lock", on) },
                    onQuietChange = { on -> quietOn = on; set("quiet_hours_enabled", on) },
                    onQuietStartChange = { quietStart = it; set("quiet_start", it) },
                    onQuietEndChange = { quietEnd = it; set("quiet_end", it) },
                    onPickTone = { pickTone() },
                    onSaveConnection = { saveConnection() },
                    onPing = { pingServer() },
                    onCheckUpdate = { checkForUpdate() },
                    onInstallUpdate = { installUpdate() },
                    onDisconnect = { disconnect() },
                )
            }
        }

        // The site's timezone: the app used to read 'site_timezone' out of
        // its preferences, but nothing ever wrote it, so every timestamp it
        // showed was in a hardcoded zone.
        lifecycleScope.launch {
            siteTimezone = try {
                val zone = LiveToolsBridge(SecurePrefs.url(this@SettingsActivity), SecurePrefs.token(this@SettingsActivity))
                    .siteConfig().siteTimezone
                // Remember it: the inbox order and every timestamp use it.
                SiteZone.save(this@SettingsActivity, zone)
                zone
            } catch (_: Exception) {
                ChatRows.DEFAULT_ZONE
            }
        }
    }

    private fun set(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        restartPollService()
    }

    private fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        restartPollService()
    }

    /** Save the address and the token. */
    private fun saveConnection() {
        val url = serverUrl.trim().trimEnd('/')
        val token = operatorToken.trim()
        if (url.isEmpty() || token.isEmpty()) {
            error = "The server address and the token are both required."
            status = null
            return
        }
        SecurePrefs.save(this, url, token)
        serverUrl = url
        operatorToken = token
        error = null
        status = "Saved. The app will use these from now on."
        restartPollService()
    }

    private fun pingServer() {
        val url = serverUrl.trim().trimEnd('/')
        if (url.isEmpty()) {
            error = "Enter the server address first."
            return
        }
        status = "Pinging the server…"
        error = null
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            val v = ChatBridge(url, operatorToken.trim()).checkForUpdate()
            val ms = System.currentTimeMillis() - start
            if (v == null) {
                error = "The server did not answer."
                status = null
                prefs.edit().putString("last_error", "Ping failed").apply()
            } else {
                status = "The server answered in $ms ms."
                prefs.edit()
                    .putString(
                        "last_sync_at",
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                            .format(java.util.Date()),
                    )
                    .putString("last_error", "")
                    .apply()
            }
        }
    }

    private fun checkForUpdate() {
        val url = serverUrl.trim().trimEnd('/')
        if (url.isEmpty()) {
            error = "Enter the server address first."
            return
        }
        status = "Checking for updates..."
        error = null
        lifecycleScope.launch {
            val offer = Updater.checkLatest(this@SettingsActivity, url, operatorToken.trim())
            if (offer == null) {
                status = "This is the latest version."
                error = null
                return@launch
            }
            update = offer
            status = "Update available: v${offer.version}"
            installUpdate()
        }
    }

    /**
     * Download the offered build, verify it and hand it to the installer.
     *
     * The phase changes arrive on a worker thread, so they are moved onto the
     * main thread before they touch the Compose state.
     */
    private fun installUpdate() {
        val offer = update ?: return
        val url = serverUrl.trim().trimEnd('/')
        if (url.isEmpty()) {
            error = "Enter the server address first."
            return
        }
        lifecycleScope.launch {
            val file = Updater.fetchVerifiedApk(this@SettingsActivity, url, offer) { phase ->
                lifecycleScope.launch { update = offer.copy(phase = phase) }
            }
            if (file == null) {
                error = "The update could not be downloaded or verified."
                status = null
                return@launch
            }
            Updater.install(this@SettingsActivity, file) { phase ->
                lifecycleScope.launch { update = offer.copy(phase = phase) }
            }
        }
    }

    private fun disconnect() {
        SecurePrefs.clear(this)
        serverUrl = ""
        operatorToken = ""
        status = "Disconnected. The server and the token were forgotten."
        error = null
        restartPollService()
    }

    private fun pickTone() {
        val current = prefs.getString("notify_tone", null)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Chat notification tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current))
        }
        ringtoneResult.launch(intent)
    }

    private fun readableName(uri: String): String {
        val u = Uri.parse(uri)
        val last = u.lastPathSegment ?: return "Default"
        return last.replace("_", " ").take(40)
    }

    private fun appVersion(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "v${info.versionName} (${info.versionCode})"
    } catch (_: Exception) {
        "?"
    }

    private fun restartPollService() {
        try {
            val i = Intent(this, ChatPollService::class.java)
            stopService(i)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {
        }
    }
}
