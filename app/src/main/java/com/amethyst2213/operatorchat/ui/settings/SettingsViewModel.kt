package com.amethyst2213.operatorchat.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amethyst2213.operatorchat.ChatPollService
import com.amethyst2213.operatorchat.ChatRows
import com.amethyst2213.operatorchat.LiveToolsBridge
import com.amethyst2213.operatorchat.Passcode
import com.amethyst2213.operatorchat.SecurePrefs
import com.amethyst2213.operatorchat.SiteZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val url: String = "",
        val token: String = "",
        val notifications: Boolean = true,
        val quiet: Boolean = false,
        val quietStart: String = "22:00",
        val quietEnd: String = "08:00",
        val toneName: String = "Default",
        val siteZone: String = ChatRows.DEFAULT_ZONE,
        val status: String? = null,
        val error: String? = null,
        val lastSync: String = "never",
        val lastError: String? = null,
        val passcodeSet: Boolean = false,
        val busy: Boolean = false,
        val version: String = "?",
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state.asStateFlow()

    private val prefs get() = getApplication<Application>()
        .getSharedPreferences("operator_chat", Context.MODE_PRIVATE)

    init {
        val app = getApplication<Application>()
        val tone = prefs.getString("notify_tone", null)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        _state.value = Ui(
            url = SecurePrefs.url(app),
            token = SecurePrefs.token(app),
            notifications = prefs.getBoolean("notify_enabled", true),
            quiet = prefs.getBoolean("quiet_hours_enabled", false),
            quietStart = prefs.getString("quiet_start", "22:00") ?: "22:00",
            quietEnd = prefs.getString("quiet_end", "08:00") ?: "08:00",
            toneName = readableName(tone),
            siteZone = SiteZone.read(app),
            lastSync = prefs.getString("last_sync_at", null) ?: "never",
            lastError = prefs.getString("last_error", null)?.ifBlank { null },
            passcodeSet = Passcode.isSet(app),
            version = versionName(app),
        )
    }

    fun setUrl(value: String) {
        _state.value = _state.value.copy(url = value, error = null)
    }

    fun setToken(value: String) {
        _state.value = _state.value.copy(token = value, error = null)
    }

    fun saveConnection(onSaved: () -> Unit) {
        val url = _state.value.url.trim().trimEnd('/')
        val token = _state.value.token.trim()
        if (url.isEmpty() || token.isEmpty()) {
            _state.value = _state.value.copy(error = "The server address and the token are both required.")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            _state.value = _state.value.copy(error = "The address must start with http:// or https://.")
            return
        }
        SecurePrefs.save(getApplication(), url, token)
        _state.value = _state.value.copy(
            url = url,
            token = token,
            status = "Saved. The app will use these from now on.",
            error = null,
            busy = true,
        )
        viewModelScope.launch {
            try {
                val cfg = LiveToolsBridge(url, token).siteConfig()
                if (cfg.siteTimezone.isNotBlank()) SiteZone.save(getApplication(), cfg.siteTimezone)
                _state.value = _state.value.copy(
                    busy = false,
                    siteZone = SiteZone.read(getApplication()),
                    status = "Saved and verified. The server answered.",
                )
                restartPollService()
                onSaved()
            } catch (_: Exception) {
                // Saved anyway: the operator may be fixing a token that is
                // issued in a moment. The error says the check failed.
                _state.value = _state.value.copy(
                    busy = false,
                    status = "Saved, but the server did not answer yet.",
                    error = null,
                )
                restartPollService()
                onSaved()
            }
        }
    }

    fun testConnection() {
        val url = _state.value.url.trim().trimEnd('/')
        val token = _state.value.token.trim()
        if (url.isEmpty()) {
            _state.value = _state.value.copy(error = "Enter the server address first.")
            return
        }
        _state.value = _state.value.copy(busy = true, status = "Talking to the server…", error = null)
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            try {
                LiveToolsBridge(url, token).siteConfig()
                val ms = System.currentTimeMillis() - started
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                prefs.edit().putString("last_sync_at", stamp).putString("last_error", "").apply()
                _state.value = _state.value.copy(
                    busy = false,
                    status = "The server answered in $ms ms.",
                    lastSync = stamp,
                    lastError = null,
                )
            } catch (e: Exception) {
                prefs.edit().putString("last_error", "The server did not answer").apply()
                _state.value = _state.value.copy(
                    busy = false,
                    status = null,
                    error = "The server did not answer. Check the address, the connection and the token.",
                    lastError = "The server did not answer",
                )
            }
        }
    }

    fun setNotifications(enabled: Boolean) {
        prefs.edit().putBoolean("notify_enabled", enabled).apply()
        _state.value = _state.value.copy(notifications = enabled)
        restartPollService()
    }

    fun setQuiet(enabled: Boolean) {
        prefs.edit().putBoolean("quiet_hours_enabled", enabled).apply()
        _state.value = _state.value.copy(quiet = enabled)
        restartPollService()
    }

    fun setQuietStart(value: String) {
        val clean = value.take(5)
        prefs.edit().putString("quiet_start", clean).apply()
        _state.value = _state.value.copy(quietStart = clean)
        restartPollService()
    }

    fun setQuietEnd(value: String) {
        val clean = value.take(5)
        prefs.edit().putString("quiet_end", clean).apply()
        _state.value = _state.value.copy(quietEnd = clean)
        restartPollService()
    }

    fun setTone(uri: String?) {
        val target = uri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
        prefs.edit().putString("notify_tone", target).apply()
        _state.value = _state.value.copy(toneName = readableName(target))
        restartPollService()
    }

    fun setPasscode(pin: String): Boolean {
        val ok = Passcode.set(getApplication(), pin)
        _state.value = _state.value.copy(
            passcodeSet = Passcode.isSet(getApplication()),
            status = if (ok) "Passcode set." else "The passcode must be 4 to 32 digits.",
            error = null,
        )
        return ok
    }

    fun removePasscode() {
        Passcode.clear(getApplication())
        _state.value = _state.value.copy(passcodeSet = false, status = "Passcode removed.", error = null)
    }

    fun disconnect(onDone: () -> Unit) {
        SecurePrefs.clear(getApplication())
        _state.value = _state.value.copy(url = "", token = "")
        restartPollService()
        onDone()
    }

    fun clearStatus() {
        _state.value = _state.value.copy(status = null, error = null)
    }

    private fun restartPollService() {
        val app = getApplication<Application>()
        try {
            val intent = Intent(app, ChatPollService::class.java)
            app.stopService(intent)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        } catch (_: Exception) {
        }
    }

    private fun readableName(uri: String): String {
        val path = Uri.parse(uri).lastPathSegment ?: return "Default"
        return path.replace("_", " ").take(40)
    }

    private fun versionName(app: Application): String = try {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        "v${info.versionName} (${info.versionCode})"
    } catch (_: Exception) {
        "?"
    }
}
