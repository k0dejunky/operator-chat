package com.amethyst2213.operatorchat

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.amethyst2213.operatorchat.ui.screens.InboxScreen
import com.amethyst2213.operatorchat.ui.screens.LoginScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme

/**
 * Login + the operator inbox.
 *
 * The screen is Compose now. The business logic did not change: the inbox is
 * owned by a ViewModel (a StateFlow, so a rotation never restarts work), the
 * poll service keeps the badge fresh in the background, and an optional
 * biometric lock protects the operator view.
 */
class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels<MainViewModel>()

    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var resumed = false

    /** The address and the token, kept here so the screens can edit them. */
    private var serverUrl by mutableStateOf("")
    private var operatorToken by mutableStateOf("")

    /** A message for the operator (the update check, a failed search). */
    private var statusMessage by mutableStateOf<String?>(null)

    /** The next scheduled show, shown as a badge in the inbox header. */
    private var nextShowTitle by mutableStateOf<String?>(null)

    /** Is a broadcast running right now? */
    private var liveNow by mutableStateOf(false)

    /** The search text (held here so the field does not clear on recomposition). */
    private var searchQuery by mutableStateOf("")

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Refresh badges as soon as the poll service sees a new message (push). */
    private val chatEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ChatPollService.ACTION_CHAT_EVENT) {
                vm.refresh(showLoading = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedUrl = SecurePrefs.url(this)
        val savedToken = SecurePrefs.token(this)
        serverUrl = savedUrl
        operatorToken = savedToken

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        ContextCompat.registerReceiver(
            this,
            chatEventReceiver,
            IntentFilter(ChatPollService.ACTION_CHAT_EVENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            OperatorTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val connected = vm.bridge != null

                if (!connected) {
                    LoginScreen(
                        initialUrl = serverUrl,
                        initialToken = operatorToken,
                        busy = state.loading,
                        error = statusMessage ?: state.error,
                        onConnect = { url, token ->
                            statusMessage = null
                            connect(url, token)
                        },
                    )
                } else {
                    InboxScreen(
                        conversations = state.items,
                        loading = state.loading,
                        refreshing = false,
                        query = searchQuery,
                        error = statusMessage ?: state.error,
                        liveBadge = liveNow,
                        nextShowTitle = nextShowTitle,
                        onQueryChange = { query ->
                            searchQuery = query
                            searchJob?.cancel()
                            searchJob = lifecycleScope.launch {
                                delay(250)
                                vm.setQuery(query)
                            }
                        },
                        onOpen = { openChat(it) },
                        onOpenLive = { openLive() },
                        onOpenSchedule = { openSchedule() },
                        onOpenSettings = { openSettings() },
                        onRefresh = { vm.refresh(showLoading = true) },
                    )
                }
            }
        }

        if (savedUrl.isNotEmpty() && savedToken.isNotEmpty()) {
            // Restore the session behind the biometric lock when it is enabled.
            if (biometricLockEnabled()) {
                promptBiometric { restoreSession() }
            } else {
                restoreSession()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (vm.bridge != null) {
            vm.refresh(showLoading = false)
            startRefreshLoop()
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        stopRefreshLoop()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        stopRefreshLoop()
        try {
            unregisterReceiver(chatEventReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun connect(url: String, token: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty() || token.trim().isEmpty()) {
            statusMessage = "Server URL and token are required."
            return
        }
        serverUrl = clean
        operatorToken = token.trim()
        vm.connect(clean, serverToken())
        startPollService()
        startRefreshLoop()
    }

    private fun restoreSession() {
        vm.restore()
        startPollService()
        startRefreshLoop()
    }

    private fun serverToken(): String = SecurePrefs.token(this)

    private fun startPollService() {
        try {
            val si = Intent(this, ChatPollService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
        } catch (_: Exception) {
        }
    }

    private fun stopPollService() {
        try {
            stopService(Intent(this, ChatPollService::class.java))
        } catch (_: Exception) {
        }
    }

    /** Gentle background badge refresh, only while the screen is visible. */
    private fun startRefreshLoop() {
        if (refreshJob?.isActive == true || !resumed) return
        refreshJob = lifecycleScope.launch {
            while (true) {
                delay(30000)
                vm.refresh(showLoading = false)
            }
        }
    }

    private fun stopRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun openChat(c: ChatBridge.Conversation) {
        val i = Intent(this, ChatActivity::class.java)
        i.putExtra("base_url", serverUrl)
        i.putExtra("conversation_id", c.id)
        i.putExtra("username", c.username.ifEmpty { c.userEmail })
        i.putExtra("ai_mode", c.aiMode)
        i.putExtra("member_reply_enabled", c.memberReplyEnabled)
        startActivity(i)
    }

    private fun openLive() {
        startActivity(Intent(this, LiveActivity::class.java))
    }

    private fun openSchedule() {
        startActivity(Intent(this, ScheduleActivity::class.java))
    }

    private fun openSettings() {
        val i = Intent(this, SettingsActivity::class.java)
        i.putExtra("base_url", serverUrl)
        startActivity(i)
    }

    // ------------------------------------------------------------- biometric
    private fun biometricLockEnabled(): Boolean {
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        return prefs.getBoolean("biometric_lock", true)
    }

    private fun promptBiometric(onAuthenticated: () -> Unit) {
        val bm = ContextCompat.getSystemService(this, BiometricManager::class.java)
        val canAuth = bm?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        if (!canAuth) {
            // No biometrics enrolled: fall back to the numeric passcode so the
            // inbox is never silently unlocked. First use creates a passcode.
            promptPasscode(onAuthenticated)
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Operator Chat locked")
                .setSubtitle("Authenticate to view the operator inbox")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
        )
    }

    /** Numeric passcode gate used when no biometrics are enrolled. */
    private fun promptPasscode(onAuthenticated: () -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = if (Passcode.isSet(this@MainActivity)) "Enter your passcode" else "Create a 4-32 digit passcode"
        }
        val creating = !Passcode.isSet(this)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (creating) "Set a passcode" else "Operator Chat locked")
            .setMessage(
                if (creating) "This device has no biometrics enrolled. Set a passcode to protect the operator inbox."
                else "Enter your passcode to view the operator inbox."
            )
            .setView(input)
            .setPositiveButton(if (creating) "Set passcode" else "Unlock") { _, _ ->
                val pin = input.text.toString().trim()
                // PBKDF2 (120k iterations) must never run on the main thread,
                // so set/verify happen on the default pool and the dialog
                // follow-ups come back to the UI thread.
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.Default) {
                        if (creating) Passcode.set(this@MainActivity, pin)
                        else Passcode.verifyThrottled(this@MainActivity, pin)
                    }
                    if (creating) {
                        if (!ok) {
                            toast("Passcode must be 4-32 digits.")
                            promptPasscode(onAuthenticated)
                        } else {
                            onAuthenticated()
                        }
                    } else if (ok) {
                        onAuthenticated()
                    } else {
                        val lockSec = Passcode.lockedForSeconds(this@MainActivity)
                        if (lockSec > 0) {
                            val mins = (lockSec + 59) / 60
                            toast("Too many attempts. Try again in about $mins min.")
                        } else {
                            toast("Incorrect passcode.")
                        }
                        promptPasscode(onAuthenticated)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
        if (!creating) {
            builder.setNeutralButton("Forgot passcode?") { _, _ -> confirmPasscodeReset() }
        }
        builder.create().show()
    }

    /**
     * Wipes the passcode AND the saved server URL + operator token, then sends
     * the operator to Settings to enter them again. Clearing only the passcode
     * would make the lock pointless: anyone could tap "forgot" and walk into the
     * inbox, so the credentials the lock protects go with it.
     */
    private fun confirmPasscodeReset() {
        AlertDialog.Builder(this)
            .setTitle("Reset passcode?")
            .setMessage(
                "This also removes the saved server URL and operator token from " +
                    "this device. You will need to enter them again in Settings."
            )
            .setPositiveButton("Reset") { _, _ ->
                Passcode.clear(this)
                SecurePrefs.clear(this)
                toast("Passcode and credentials cleared. Re-enter them in Settings.")
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
