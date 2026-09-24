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
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Login + users list. Shows only users with unread member messages (favourites
 * pinned to the top), with a menu to view all users. The inbox is owned by a
 * ViewModel (StateFlow) so rotation never restarts work, and an optional
 * biometric lock protects the operator view.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var loginContainer: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var inboxRecycler: RecyclerView
    private lateinit var emptyLabel: TextView
    private lateinit var loadingBar: ProgressBar
    private lateinit var searchInput: EditText
    private lateinit var loadMoreButton: Button

    private val vm: MainViewModel by viewModels<MainViewModel>()

    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var resumed = false

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
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle("Users list")
        StatusBarToolbar.apply(this, toolbar)

        urlInput = findViewById(R.id.url_input)
        tokenInput = findViewById(R.id.token_input)
        connectButton = findViewById(R.id.connect_button)
        loginContainer = findViewById(R.id.login_container)
        statusLabel = findViewById(R.id.status_label)
        inboxRecycler = findViewById(R.id.inbox_recycler)
        emptyLabel = findViewById(R.id.empty_label)
        loadingBar = findViewById(R.id.loading_bar)
        searchInput = findViewById(R.id.search_input)
        loadMoreButton = findViewById(R.id.load_more_button)
        loadMoreButton.setOnClickListener { vm.loadMore() }

        inboxRecycler.layoutManager = LinearLayoutManager(this)
        inboxRecycler.adapter = InboxAdapter { openChat(it) }

        connectButton.setOnClickListener { connect() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(250)
                    vm.setQuery(searchInput.text.toString().trim())
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val savedUrl = SecurePrefs.url(this)
        val savedToken = SecurePrefs.token(this)
        if (savedUrl.isNotEmpty() && savedToken.isNotEmpty()) {
            urlInput.setText(savedUrl)
            tokenInput.setText(savedToken)
            // Restore the session behind the biometric lock when it is enabled.
            if (biometricLockEnabled()) {
                promptBiometric { restoreSession() }
            } else {
                restoreSession()
            }
        }

        ContextCompat.registerReceiver(this, chatEventReceiver,
            IntentFilter(ChatPollService.ACTION_CHAT_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { render(it) }
            }
        }
    }

    private fun render(state: MainViewModel.InboxState) {
        val loggedIn = vm.bridge != null
        loginContainer.visibility = if (loggedIn) View.GONE else View.VISIBLE
        if (loggedIn) {
            (inboxRecycler.adapter as? InboxAdapter)?.submitList(state.items)
            loadMoreButton.visibility = if (state.hasMore) View.VISIBLE else View.GONE
            emptyLabel.visibility = if (state.items.isEmpty()) View.VISIBLE else View.GONE
            loadingBar.visibility = if (state.loading) View.VISIBLE else View.GONE
            statusLabel.text = when {
                state.loading -> if (vm.allUsersMode) "Loading all users…" else "Loading users…"
                state.error != null -> state.error!!
                else -> state.statusLine
            }
        } else {
            (inboxRecycler.adapter as? InboxAdapter)?.submitList(emptyList())
            statusLabel.text = state.statusLine
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
        try { unregisterReceiver(chatEventReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_all_users -> {
                vm.setAllUsers(true)
                supportActionBar?.setTitle("All users")
                true
            }
            R.id.action_unread -> {
                vm.setAllUsers(false)
                supportActionBar?.setTitle("Users list")
                true
            }
            R.id.action_new_chat -> {
                showNewChatDialog()
                true
            }
            R.id.action_settings -> {
                openSettings()
                true
            }
            R.id.action_update -> {
                checkForUpdate()
                true
            }
            R.id.action_logout -> {
                vm.logout()
                stopRefreshLoop()
                stopPollService()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openSettings() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        val i = Intent(this, SettingsActivity::class.java)
        i.putExtra("base_url", url)
        startActivity(i)
    }

    private fun checkForUpdate() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        if (url.isEmpty()) {
            statusLabel.text = "Enter the server URL first."
            return
        }
        statusLabel.text = "Checking for updates…"
        val b = ChatBridge(url, SecurePrefs.token(this))
        lifecycleScope.launch {
            val v = b.checkForUpdate()
            if (v == null) {
                statusLabel.text = "Could not reach the update server."
                return@launch
            }
            val current = packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            if (v.versionCode > current) {
                Updater.downloadAndInstall(lifecycleScope, this@MainActivity, url, SecurePrefs.token(this@MainActivity), v, statusLabel)
            } else {
                statusLabel.text = "You're on the latest version (${v.latestVersion})."
            }
        }
    }

    private fun connect() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        if (url.isEmpty() || token.isEmpty()) {
            statusLabel.text = "Server URL and token are required."
            return
        }
        vm.connect(url, token)
        startPollService()
        startRefreshLoop()
    }

    private fun restoreSession() {
        vm.restore()
        startPollService()
        startRefreshLoop()
    }

    private fun startPollService() {
        try {
            val si = Intent(this, ChatPollService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
        } catch (_: Exception) {}
    }

    private fun stopPollService() {
        try {
            stopService(Intent(this, ChatPollService::class.java))
        } catch (_: Exception) {}
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
        i.putExtra("base_url", urlInput.text.toString().trim().trimEnd('/'))
        i.putExtra("conversation_id", c.id)
        i.putExtra("username", c.username.ifEmpty { c.userEmail })
        i.putExtra("ai_mode", c.aiMode)
        i.putExtra("member_reply_enabled", c.memberReplyEnabled)
        startActivity(i)
    }

    /** Open a conversation by id (used after starting a chat with a user). */
    private fun openChatById(conversationId: Long, username: String) {
        val i = Intent(this, ChatActivity::class.java)
        i.putExtra("base_url", urlInput.text.toString().trim().trimEnd('/'))
        i.putExtra("conversation_id", conversationId)
        i.putExtra("username", username)
        startActivity(i)
    }

    /** Search users and start a new conversation with any of them. */
    private fun showNewChatDialog() {
        val b = vm.bridge ?: run { statusLabel.text = "Connect first."; return }

        val search = EditText(this).apply {
            hint = "Search users by email…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val list = ListView(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        search.setPadding(pad, pad, pad, pad)
        list.setPadding(0, 0, 0, 0)
        val results = mutableListOf<ChatBridge.UserResult>()
        val adapter = object : ArrayAdapter<ChatBridge.UserResult>(this, android.R.layout.simple_list_item_2, android.R.id.text1, results) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val u = getItem(position) ?: return v
                val t1 = v.findViewById<TextView>(android.R.id.text1)
                val t2 = v.findViewById<TextView>(android.R.id.text2)
                t1?.text = u.username
                t2?.text = u.email + (if (u.canChat) " · chat enabled" else " · no chat plan")
                return v
            }
        }
        list.adapter = adapter

        var dialogSearchJob: Job? = null
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                dialogSearchJob?.cancel()
                val q = s?.toString()?.trim().orEmpty()
                if (q.length < 2) { results.clear(); adapter.notifyDataSetChanged(); return }
                dialogSearchJob = lifecycleScope.launch {
                    try {
                        val found = b.searchUsers(q)
                        results.clear()
                        results.addAll(found)
                        adapter.notifyDataSetChanged()
                    } catch (_: Exception) {
                        statusLabel.text = "User search failed — check connection."
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        list.setOnItemClickListener { _, _, position, _ ->
            val user = adapter.getItem(position) ?: return@setOnItemClickListener
            statusLabel.text = "Opening chat with ${user.username}…"
            lifecycleScope.launch {
                try {
                    val (cid, username) = b.startConversation(user.id)
                    if (cid > 0) openChatById(cid, username.ifEmpty { user.username })
                } catch (e: Exception) {
                    statusLabel.text = "Could not start chat: ${e.message}"
                }
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        AlertDialog.Builder(this)
            .setTitle("Message any user")
            .setView(content)
            .setNegativeButton("Close", null)
            .create()
            .show()
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
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = if (Passcode.isSet(this@MainActivity)) "Enter your passcode" else "Create a 4-32 digit passcode"
        }
        val creating = !Passcode.isSet(this)

        AlertDialog.Builder(this)
            .setTitle(if (creating) "Set a passcode" else "Operator Chat locked")
            .setMessage(
                if (creating) "This device has no biometrics enrolled. Set a passcode to protect the operator inbox."
                else "Enter your passcode to view the operator inbox."
            )
            .setView(input)
            .setPositiveButton(if (creating) "Set passcode" else "Unlock") { _, _ ->
                val pin = input.text.toString().trim()
                if (creating) {
                    if (!Passcode.set(this, pin)) {
                        toast("Passcode must be 4-32 digits.")
                        promptPasscode(onAuthenticated)
                    } else {
                        onAuthenticated()
                    }
                } else if (Passcode.verify(this, pin)) {
                    onAuthenticated()
                } else {
                    toast("Incorrect passcode.")
                    promptPasscode(onAuthenticated)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .show()
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- adapter
    inner class InboxAdapter(
        private val onClick: (ChatBridge.Conversation) -> Unit,
    ) : ListAdapter<ChatBridge.Conversation, InboxAdapter.Holder>(INBOX_DIFF) {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.row_title)
            val badge: TextView = v.findViewById(R.id.row_badge)
            val star: TextView = v.findViewById(R.id.row_star)
            val preview: TextView = v.findViewById(R.id.row_preview)
            val avatar: TextView = v.findViewById(R.id.row_avatar)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = layoutInflater.inflate(R.layout.row_user, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = getItem(position)
            h.title.text = c.username.ifEmpty { c.userEmail }
            val name = c.username.ifEmpty { c.userEmail }
            h.avatar.text = name.trim().firstOrNull()?.uppercase() ?: "?"
            h.star.text = if (c.favorite) "★" else "☆"
            h.star.setOnClickListener {
                Favorites.toggle(this@MainActivity, c.id)
                vm.refresh(showLoading = false)
            }
            if (vm.allUsersMode) {
                h.badge.visibility = if (c.unreadReplyable > 0) View.VISIBLE else View.GONE
                if (c.unreadReplyable > 0) h.badge.text = c.unreadReplyable.toString()
                h.preview.visibility = View.VISIBLE
                h.preview.text = (if (c.lastSender == "user") "Member: " else "") +
                    c.lastMessage.take(60).ifEmpty { "(no messages)" }
            } else {
                h.badge.visibility = View.VISIBLE
                h.badge.text = c.unreadReplyable.toString()
                h.preview.visibility = View.GONE
            }
            h.itemView.setOnClickListener { onClick(c) }
        }
    }

    private object INBOX_DIFF : DiffUtil.ItemCallback<ChatBridge.Conversation>() {
        override fun areItemsTheSame(a: ChatBridge.Conversation, b: ChatBridge.Conversation): Boolean =
            a.id == b.id

        override fun areContentsTheSame(a: ChatBridge.Conversation, b: ChatBridge.Conversation): Boolean =
            a.id == b.id
                && a.username == b.username
                && a.userEmail == b.userEmail
                && a.lastMessage == b.lastMessage
                && a.lastSender == b.lastSender
                && a.lastMessageAt == b.lastMessageAt
                && a.unreadReplyable == b.unreadReplyable
                && a.memberReplyEnabled == b.memberReplyEnabled
                && a.canChat == b.canChat
                && a.favorite == b.favorite
    }
}