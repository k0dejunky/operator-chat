package com.amethyst2213.operatorchat

import android.app.Application
import androidx.biometric.BiometricManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The session spine of the v4 app: the stored connection, the lock state,
 * the update offer and the sign-in/sign-out transitions.
 *
 * Screens never touch SecurePrefs for the URL/token directly - they ask this
 * for a bridge, so after a Settings change every screen gets the new one.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    enum class Connection { UNKNOWN, ONLINE, OFFLINE, UNAUTHORIZED }

    data class State(
        val signedIn: Boolean = false,
        val locked: Boolean = false,
        val url: String = "",
        val token: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val update: UpdateOffer? = null,
        val connection: Connection = Connection.UNKNOWN,
        val siteZone: String = "UTC",
        val biometricAvailable: Boolean = false,
        val passcodeSet: Boolean = false,
        val lockEnabled: Boolean = true,
    ) {
        val hasCredentials: Boolean get() = url.isNotBlank() && token.isNotBlank()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var bridgeCache: ChatBridge? = null

    init {
        restore()
    }

    /** The bridge for the stored credentials, or null while signed out. */
    fun bridge(): ChatBridge? {
        val s = _state.value
        if (!s.hasCredentials) return null
        return bridgeCache ?: ChatBridge(s.url, s.token).also { bridgeCache = it }
    }

    fun tools(): LiveToolsBridge? {
        val s = _state.value
        if (!s.hasCredentials) return null
        return LiveToolsBridge(s.url, s.token)
    }

    private fun refreshLocalFlags(): Pair<Boolean, Boolean> {
        val app = getApplication<Application>()
        val biometric = try {
            BiometricManager.from(app)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Throwable) {
            false
        }
        return biometric to Passcode.isSet(app)
    }

    private fun restore() {
        val app = getApplication<Application>()
        val url = SecurePrefs.url(app).trim()
        val token = SecurePrefs.token(app).trim()
        val zone = SiteZone.read(app)
        val (biometric, passcodeSet) = refreshLocalFlags()
        val prefs = app.getSharedPreferences("operator_chat", Application.MODE_PRIVATE)
        val lockEnabled = prefs.getBoolean("biometric_lock", true)
        val hasCreds = url.isNotBlank() && token.isNotBlank()

        _state.value = State(
            signedIn = hasCreds,
            locked = hasCreds && lockEnabled && (biometric || passcodeSet),
            url = url,
            token = token,
            siteZone = zone,
            biometricAvailable = biometric,
            passcodeSet = passcodeSet,
            lockEnabled = lockEnabled,
        )
        if (!hasCreds) return

        bridgeCache = ChatBridge(url, token)
        viewModelScope.launch {
            checkForUpdate()
            // Confirm the stored session once, without blocking the UI.
            try {
                val cfg = LiveToolsBridge(url, token).siteConfig()
                if (cfg.siteTimezone.isNotBlank()) {
                    SiteZone.save(app, cfg.siteTimezone)
                }
                _state.value = _state.value.copy(
                    connection = Connection.ONLINE,
                    siteZone = SiteZone.read(app),
                )
            } catch (e: Exception) {
                val code = (e.message ?: "").substringAfter("HTTP ").toIntOrNull() ?: 0
                _state.value = _state.value.copy(
                    connection = if (code == 401 || code == 403) Connection.UNAUTHORIZED else Connection.OFFLINE,
                )
            }
        }
    }

    /** Sign in with the fields the operator typed. */
    fun connect(rawUrl: String, rawToken: String) {
        val url = rawUrl.trim().trimEnd('/')
        val token = rawToken.trim()
        if (url.isEmpty() || token.isEmpty()) {
            _state.value = _state.value.copy(error = "The server address and the token are both required.")
            return
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            _state.value = _state.value.copy(error = "The server address must start with http:// or https://.")
            return
        }
        if (_state.value.busy) return

        _state.value = _state.value.copy(busy = true, error = null, url = url, token = token)
        viewModelScope.launch {
            try {
                val cfg = LiveToolsBridge(url, token).siteConfig()
                if (cfg.siteTimezone.isNotBlank()) {
                    SiteZone.save(getApplication(), cfg.siteTimezone)
                }
                SecurePrefs.save(getApplication(), url, token)
                bridgeCache = ChatBridge(url, token)
                val (biometric, passcodeSet) = refreshLocalFlags()
                _state.value = _state.value.copy(
                    signedIn = true,
                    locked = false,
                    busy = false,
                    error = null,
                    connection = Connection.ONLINE,
                    siteZone = SiteZone.read(getApplication()),
                    biometricAvailable = biometric,
                    passcodeSet = passcodeSet,
                )
                checkForUpdate()
            } catch (e: Exception) {
                val code = (e.message ?: "").substringAfter("HTTP ").toIntOrNull() ?: 0
                val message = when {
                    code == 401 || code == 403 -> "The server refused that token."
                    else -> "Could not reach the server. Check the address and the connection."
                }
                _state.value = _state.value.copy(busy = false, error = message)
            }
        }
    }

    /** A quick reachability/latency check for Settings. Returns ms or -1. */
    suspend fun testConnection(url: String, token: String): Pair<Boolean, Long> {
        val started = System.currentTimeMillis()
        return try {
            LiveToolsBridge(url.trim().trimEnd('/'), token.trim()).siteConfig()
            true to (System.currentTimeMillis() - started)
        } catch (_: Exception) {
            false to -1L
        }
    }

    fun unlockWithPin(pin: String) {
        val app = getApplication<Application>()
        if (!Passcode.isSet(app)) {
            _state.value = _state.value.copy(error = "No passcode is set.")
            return
        }
        val left = Passcode.lockedForSeconds(app)
        if (left > 0) {
            _state.value = _state.value.copy(error = "Try again in ${left}s.")
            return
        }
        if (Passcode.verifyThrottled(app, pin)) {
            Passcode.resetThrottle(app)
            _state.value = _state.value.copy(locked = false, error = null)
        } else {
            val wait = Passcode.lockedForSeconds(app)
            _state.value = _state.value.copy(
                error = if (wait > 0) "Too many attempts. Try again in ${wait}s." else "That passcode is not right.",
            )
        }
    }

    fun biometricUnlocked() {
        _state.value = _state.value.copy(locked = false, error = null)
        bridgeCache = bridgeCache ?: ChatBridge(_state.value.url, _state.value.token)
    }

    /** Wipe the session (the "Forgot passcode" path also wipes the passcode). */
    fun signOut(wipePasscode: Boolean = false) {
        val app = getApplication<Application>()
        SecurePrefs.clear(app)
        if (wipePasscode) Passcode.clear(app)
        bridgeCache = null
        val (biometric, passcodeSet) = refreshLocalFlags()
        _state.value = _state.value.copy(
            signedIn = false,
            locked = false,
            url = "",
            token = "",
            error = null,
            connection = Connection.UNKNOWN,
            biometricAvailable = biometric,
            passcodeSet = passcodeSet,
        )
    }

    fun changeLock(enabled: Boolean) {
        val app = getApplication<Application>()
        app.getSharedPreferences("operator_chat", Application.MODE_PRIVATE)
            .edit().putBoolean("biometric_lock", enabled).apply()
        val (biometric, passcodeSet) = refreshLocalFlags()
        _state.value = _state.value.copy(
            lockEnabled = enabled,
            biometricAvailable = biometric,
            passcodeSet = passcodeSet,
        )
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Rebuild the bridge after Settings saved a new address/token.
     *
     * The screens build their own bridge from SecurePrefs, so the next screen
     * they open uses the new values; this keeps the session state (and the
     * update check) in step immediately.
     */
    fun reloadFromPrefs() {
        val app = getApplication<Application>()
        val url = SecurePrefs.url(app).trim()
        val token = SecurePrefs.token(app).trim()
        bridgeCache = if (url.isNotBlank() && token.isNotBlank()) ChatBridge(url, token) else null
        _state.value = _state.value.copy(
            url = url,
            token = token,
            signedIn = url.isNotBlank() && token.isNotBlank(),
            locked = false,
            siteZone = SiteZone.read(app),
            connection = if (url.isNotBlank() && token.isNotBlank()) Connection.ONLINE else Connection.UNKNOWN,
        )
    }

    /** Re-read the local security flags (Settings sets a passcode). */
    fun refreshSecurityFlags() {
        val (biometric, passcodeSet) = refreshLocalFlags()
        val lockEnabled = getApplication<Application>()
            .getSharedPreferences("operator_chat", Application.MODE_PRIVATE)
            .getBoolean("biometric_lock", true)
        _state.value = _state.value.copy(
            biometricAvailable = biometric,
            passcodeSet = passcodeSet,
            lockEnabled = lockEnabled,
        )
    }

    fun checkForUpdate(force: Boolean = false) {
        val app = getApplication<Application>()
        val s = _state.value
        if (!s.hasCredentials) return
        if (!force) {
            val last = app.getSharedPreferences("operator_chat", Application.MODE_PRIVATE)
                .getLong("update_checked_at", 0L)
            if (System.currentTimeMillis() - last < 6 * 60 * 60 * 1000L) return
        }
        viewModelScope.launch {
            val offer = Updater.checkLatest(app, s.url, s.token)
            app.getSharedPreferences("operator_chat", Application.MODE_PRIVATE)
                .edit().putLong("update_checked_at", System.currentTimeMillis()).apply()
            if (offer != null) {
                _state.value = _state.value.copy(update = offer)
            }
        }
    }

    /** Download, verify and hand the update to the system installer. */
    fun installUpdate() {
        val app = getApplication<Application>()
        val offer = _state.value.update ?: return
        val s = _state.value
        _state.value = _state.value.copy(update = offer.copy(phase = UpdatePhase.DOWNLOADING))
        viewModelScope.launch {
            val file = Updater.fetchVerifiedApk(app, s.url, offer) { phase ->
                _state.value = _state.value.copy(update = offer.copy(phase = phase))
            }
            if (file == null) {
                _state.value = _state.value.copy(update = offer.copy(phase = UpdatePhase.FAILED))
                return@launch
            }
            Updater.install(app, file) { phase ->
                _state.value = _state.value.copy(update = offer.copy(phase = phase))
            }
        }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(update = null)
    }
}
