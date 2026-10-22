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
import android.widget.EditText
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.amethyst2213.operatorchat.ui.screens.InboxScreen
import com.amethyst2213.operatorchat.ui.screens.LoginScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** A published build that is newer than the installed one. */
    private var updateOffer by mutableStateOf<UpdateOffer?>(null)

    /**
     * The connection that is actually in use.
     *
     * The settings screen can change the address and the token while this
     * activity is in the background, so the applied connection is compared
     * with the stored one every time the screen comes back.
     */
    private var appliedUrl by mutableStateOf("")
    private var appliedToken by mutableStateOf("")

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
                // A rejected token is not a connection: the operator has to
                // sign in again, and the inbox must not sit there empty
                // pretending everything is fine.
                val connected = vm.bridge != null &&
                    state.connection != MainViewModel.Connection.UNAUTHORIZED

                if (!connected) {
                    LoginScreen(
                        initialUrl = serverUrl,
                        initialToken = operatorToken,
                        busy = state.loading,
                        error = statusMessage ?: state.error,
                        update = updateOffer,
                        onInstallUpdate = { installUpdate() },
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
                        allConversations = vm.allUsersMode,
                        hasMore = state.hasMore,
                        update = updateOffer,
                        onInstallUpdate = { installUpdate() },
                        onAllConversationsChange = { all -> vm.setAllUsers(all) },
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
                        onLoadMore = { vm.loadMore() },
                        onNewChat = { showNewChatDialog() },
                    )
                }
            }
        }

        // The update check does not need a session: the published manifest
        // is public, so a phone that cannot sign in still gets the fix.
        checkForUpdate()
        UpdateWorker.schedule(this)

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
        // The settings screen can change the connection while this activity
        // is in the background. Pick the change up instead of keeping a
        // bridge that points at the old server (or, worse, at nothing).
        val storedUrl = SecurePrefs.url(this).trim()
        val storedToken = SecurePrefs.token(this).trim()
        if (storedUrl != appliedUrl || storedToken != appliedToken) {
            if (storedUrl.isEmpty() || storedToken.isEmpty()) {
                appliedUrl = ""
                appliedToken = ""
                vm.logout()
                stopPollService()
            } else {
                serverUrl = storedUrl
                operatorToken = storedToken
                restoreSession()
            }
        }
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

    /**
     * Sign in with the address and the token the operator typed.
     *
     * The token used to be read back out of the keystore instead of being
     * taken from the field, so a fresh install could never sign in at all.
     */
    private fun connect(url: String, token: String) {
        val clean = url.trim().trimEnd('/')
        val tok = token.trim()
        if (clean.isEmpty() || tok.isEmpty()) {
            statusMessage = "The server address and the token are both required."
            return
        }
        serverUrl = clean
        operatorToken = tok
        appliedUrl = clean
        appliedToken = tok
        statusMessage = null
        vm.connect(clean, tok)
        learnSiteZone()
        startPollService()
        startRefreshLoop()
    }

    /**
     * Learn the site's timezone.
     *
     * The app used to read it out of its preferences, but nothing ever wrote
     * it - so the inbox was ordered by a hardcoded zone and every
     * "2 min ago" was off by the offset.
     */
    private fun learnSiteZone() {
        val url = SecurePrefs.url(this).trim()
        val token = SecurePrefs.token(this).trim()
        if (url.isEmpty() || token.isEmpty()) return
        lifecycleScope.launch {
            try {
                val zone = LiveToolsBridge(url, token).siteConfig().siteTimezone
                SiteZone.save(this@MainActivity, zone)
            } catch (_: Exception) {
                // The default zone is used until the server answers.
            }
        }
    }

    private fun restoreSession() {
        val url = SecurePrefs.url(this).trim()
        val token = SecurePrefs.token(this).trim()
        if (url.isEmpty() || token.isEmpty()) {
            return
        }
        appliedUrl = url
        appliedToken = token
        serverUrl = url
        operatorToken = token
        vm.restore()
        learnSiteZone()
        startPollService()
        startRefreshLoop()
    }

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

    // ------------------------------------------------------------- updates

    /**
     * Look for a newer build of the app.
     *
     * The check runs on every start but at most once every six hours (the
     * timestamp is kept in the preferences), so it never turns into a poll
     * of the site.
     */
    private fun checkForUpdate(force: Boolean = false) {
        val url = SecurePrefs.url(this).trim()
        if (url.isEmpty()) return
        val prefs = getSharedPreferences("operator_chat", MODE_PRIVATE)
        val last = prefs.getLong("update_checked_at", 0L)
        val now = System.currentTimeMillis()
        if (!force && last > 0L && now - last < CHECK_INTERVAL_MS) return
        prefs.edit().putLong("update_checked_at", now).apply()

        lifecycleScope.launch {
            val offer = try {
                Updater.checkLatest(this@MainActivity, url, SecurePrefs.token(this@MainActivity).trim())
            } catch (_: Exception) {
                null
            }
            if (offer != null) updateOffer = offer
        }
    }

    /** Download the offered build, verify it and hand it to the installer. */
    private fun installUpdate() {
        val offer = updateOffer ?: return
        val url = SecurePrefs.url(this).trim()
        if (url.isEmpty()) {
            statusMessage = "The server address is not set."
            return
        }
        lifecycleScope.launch {
            val file = Updater.fetchVerifiedApk(this@MainActivity, url, offer) { phase ->
                lifecycleScope.launch { updateOffer = offer.copy(phase = phase) }
            }
            if (file == null) {
                updateOffer = offer.copy(phase = UpdatePhase.FAILED)
                statusMessage = "The update could not be downloaded or verified."
                return@launch
            }
            Updater.install(this@MainActivity, file) { phase ->
                lifecycleScope.launch { updateOffer = offer.copy(phase = phase) }
            }
        }
    }

    // ------------------------------------------------------------- new chat

    /**
     * Message any member.
     *
     * This action was in the v2.50 overflow menu and was dropped in the
     * rewrite, so the operator could only ever answer somebody who had
     * written first. The conversation is opened (or created) with the
     * member that is picked.
     */
    private fun showNewChatDialog() {
        val bridge = vm.bridge ?: run {
            statusMessage = "Sign in first."
            return
        }
        val query = EditText(this).apply {
            hint = "Search members by email..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val results = mutableListOf<ChatBridge.UserResult>()
        val list = android.widget.ListView(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        query.setPadding(pad, pad, pad, pad)
        list.setPadding(0, 0, 0, 0)
        val adapter = object : android.widget.ArrayAdapter<ChatBridge.UserResult>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, results,
        ) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                val u = getItem(position) ?: return v
                val t1 = v.findViewById<android.widget.TextView>(android.R.id.text1)
                val t2 = v.findViewById<android.widget.TextView>(android.R.id.text2)
                t1?.text = u.username
                t2?.text = u.email + (if (u.canChat) " - chat enabled" else " - no chat plan")
                return v
            }
        }
        list.adapter = adapter

        // The dialog is created below; the list needs a handle on it to
        // close itself once a member has been picked.
        var dialog: AlertDialog? = null

        var searchJob: Job? = null
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                searchJob?.cancel()
                val q = s?.toString()?.trim().orEmpty()
                if (q.length < 2) {
                    results.clear()
                    adapter.notifyDataSetChanged()
                    return
                }
                searchJob = lifecycleScope.launch {
                    try {
                        val found = bridge.searchUsers(q)
                        results.clear()
                        results.addAll(found)
                        adapter.notifyDataSetChanged()
                    } catch (_: Exception) {
                        statusMessage = "The member search failed - check the connection."
                    }
                }
            }

            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val user = adapter.getItem(position) ?: return@setOnItemClickListener
            lifecycleScope.launch {
                try {
                    val (cid, username) = bridge.startConversation(user.id)
                    if (cid > 0L) {
                        dialog?.dismiss()
                        openChat(
                            ChatBridge.Conversation(
                                id = cid,
                                userEmail = user.email,
                                username = username.ifEmpty { user.username },
                                aiMode = "operator",
                                status = "open",
                                lastMessage = "",
                                lastSender = "",
                                lastMessageAt = "",
                                memberCount = 0,
                                unreadReplyable = 0,
                                updatedAt = "",
                            ),
                        )
                    }
                } catch (e: Exception) {
                    statusMessage = "Could not start the conversation: ${e.message ?: "unknown error"}"
                }
            }
        }

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(query)
            addView(list, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Message any member")
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
        dialog?.show()
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
     * would make the lock pointless: anyone could tap "forgot" and walk into
     * the inbox, so the credentials the lock protects go with it.
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
                appliedUrl = ""
                appliedToken = ""
                vm.logout()
                stopPollService()
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

    companion object {
        /** How often the app looks for a new build. */
        private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
