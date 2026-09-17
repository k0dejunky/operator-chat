package com.amethyst2213.operatorchat

import android.Manifest
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Login + users list. Shows only users with unread member messages (favourites
 * pinned to the top), with a menu to view all users (most recent 25, unique).
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

    private var bridge: ChatBridge? = null
    private val conversations = mutableListOf<ChatBridge.Conversation>()
    private var allUsersMode = false
    private var refreshJob: Job? = null

    private val prefs by lazy { getSharedPreferences("operator_chat", MODE_PRIVATE) }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Refresh badges as soon as the poll service sees a new message (push). */
    private val chatEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ChatPollService.ACTION_CHAT_EVENT && bridge != null) {
                loadInbox(showLoading = false)
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

        inboxRecycler.layoutManager = LinearLayoutManager(this)
        inboxRecycler.adapter = InboxAdapter(conversations) { openChat(it) }

        connectButton.setOnClickListener { connect() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val savedUrl = prefs.getString("url", "") ?: ""
        val savedToken = prefs.getString("token", "") ?: ""
        if (savedUrl.isNotEmpty() && savedToken.isNotEmpty()) {
            urlInput.setText(savedUrl)
            tokenInput.setText(savedToken)
            bridge = ChatBridge(savedUrl, savedToken)
            setLoggedIn(true)
            loadInbox()
        }

        registerReceiver(chatEventReceiver, IntentFilter(ChatPollService.ACTION_CHAT_EVENT))
    }

    override fun onResume() {
        super.onResume()
        // Refresh when returning from a chat: reading/reply clears unread.
        if (bridge != null) loadInbox(showLoading = false)
    }

    override fun onDestroy() {
        stopRefreshLoop()
        try { unregisterReceiver(chatEventReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun setLoggedIn(loggedIn: Boolean) {
        loginContainer.visibility = if (loggedIn) View.GONE else View.VISIBLE
        if (loggedIn) statusLabel.text = "Loading users…"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_all_users -> {
                allUsersMode = true
                supportActionBar?.setTitle("All users")
                loadInbox()
                true
            }
            R.id.action_unread -> {
                allUsersMode = false
                supportActionBar?.setTitle("Users list")
                loadInbox()
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
                prefs.edit().remove("url").remove("token").apply()
                bridge = null
                conversations.clear()
                inboxRecycler.adapter?.notifyDataSetChanged()
                emptyLabel.visibility = View.GONE
                setLoggedIn(false)
                stopRefreshLoop()
                statusLabel.text = "Logged out — enter server URL + token."
                try {
                    stopService(Intent(this, ChatPollService::class.java))
                } catch (_: Exception) {}
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
        val b = ChatBridge(url, prefs.getString("token", "") ?: "")
        lifecycleScope.launch {
            val v = b.checkForUpdate()
            if (v == null) {
                statusLabel.text = "Could not reach the update server."
                return@launch
            }
            val current = packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            if (v.versionCode > current) {
                Updater.downloadAndInstall(this@MainActivity, url, v, statusLabel)
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
        prefs.edit().putString("url", url).putString("token", token).apply()
        bridge = ChatBridge(url, token)
        setLoggedIn(true)
        loadInbox()
    }

    private fun loadInbox(showLoading: Boolean = true) {
        val b = bridge ?: run { statusLabel.text = "Connect first."; return }
        if (showLoading) {
            loadingBar.visibility = View.VISIBLE
            statusLabel.text = if (allUsersMode) "Loading all users…" else "Loading users…"
        }
        lifecycleScope.launch {
            try {
                val convs = b.inbox()
                val sorted = convs.sortedByDescending { it.lastMessageAt }
                val displayed = if (allUsersMode) {
                    // Most recent 25 unique users, favourites first.
                    val fav = sorted.filter { Favorites.isFavorite(this@MainActivity, it.id) }
                    val rest = sorted.filter { !Favorites.isFavorite(this@MainActivity, it.id) }
                    (fav + rest).distinctBy { it.id }.take(25)
                } else {
                    // Unread users; favourites pinned to the top.
                    val withUnread = sorted.filter { it.unreadReplyable > 0 }
                    val fav = withUnread.filter { Favorites.isFavorite(this@MainActivity, it.id) }
                    val rest = withUnread.filter { !Favorites.isFavorite(this@MainActivity, it.id) }
                    (fav + rest).distinctBy { it.id }
                }
                conversations.clear()
                conversations.addAll(displayed)
                inboxRecycler.adapter?.notifyDataSetChanged()
                emptyLabel.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
                statusLabel.text = when {
                    displayed.isEmpty() && allUsersMode -> "No users yet."
                    displayed.isEmpty() -> "No new messages."
                    allUsersMode -> "Recent ${displayed.size} user(s)"
                    else -> "${displayed.size} user(s) with new messages"
                }
                startRefreshLoop()
                try {
                    val si = Intent(this@MainActivity, ChatPollService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                if (showLoading) {
                    statusLabel.text = "Error: ${e.message ?: "connection failed"} — check URL/token."
                }
            } finally {
                if (showLoading) {
                    loadingBar.visibility = View.GONE
                }
            }
        }
    }

    /** Poll the inbox every few seconds so new-message badges appear live. */
    private fun startRefreshLoop() {
        if (refreshJob?.isActive == true) return
        refreshJob = lifecycleScope.launch {
            while (true) {
                delay(5000)
                loadInbox(showLoading = false)
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
        i.putExtra("token", tokenInput.text.toString().trim())
        i.putExtra("conversation_id", c.id)
        i.putExtra("username", c.username.ifEmpty { c.userEmail })
        i.putExtra("ai_mode", c.aiMode)
        startActivity(i)
    }

    // ---------------------------------------------------------------- adapter
    inner class InboxAdapter(
        private val data: List<ChatBridge.Conversation>,
        private val onClick: (ChatBridge.Conversation) -> Unit,
    ) : RecyclerView.Adapter<InboxAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.row_title)
            val badge: TextView = v.findViewById(R.id.row_badge)
            val star: TextView = v.findViewById(R.id.row_star)
            val preview: TextView = v.findViewById(R.id.row_preview)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = layoutInflater.inflate(R.layout.row_user, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = data[position]
            h.title.text = c.username.ifEmpty { c.userEmail }
            h.star.text = if (Favorites.isFavorite(this@MainActivity, c.id)) "★" else "☆"
            h.star.setOnClickListener {
                Favorites.toggle(this@MainActivity, c.id)
                loadInbox()
            }
            if (allUsersMode) {
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
}