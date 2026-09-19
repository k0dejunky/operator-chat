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
    private lateinit var searchInput: EditText

    private var bridge: ChatBridge? = null
    private val conversations = mutableListOf<ChatBridge.Conversation>()
    private var allUsersMode = false
    private var refreshJob: Job? = null
    private var inboxJob: Job? = null
    private var searchJob: Job? = null

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
        searchInput = findViewById(R.id.search_input)

        inboxRecycler.layoutManager = LinearLayoutManager(this)
        inboxRecycler.adapter = InboxAdapter(conversations) { openChat(it) }

        connectButton.setOnClickListener { connect() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(250)
                    if (bridge != null) loadInbox(showLoading = false)
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
            bridge = ChatBridge(savedUrl, savedToken)
            setLoggedIn(true)
            loadInbox()
        }

        ContextCompat.registerReceiver(this, chatEventReceiver,
            IntentFilter(ChatPollService.ACTION_CHAT_EVENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        // Refresh when returning from a chat: reading/reply clears unread.
        if (bridge != null) loadInbox(showLoading = false)
    }

    override fun onDestroy() {
        inboxJob?.cancel()
        searchJob?.cancel()
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
                SecurePrefs.clear(this)
                searchInput.setText("")
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
        val b = ChatBridge(url, SecurePrefs.token(this))
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
        SecurePrefs.save(this, url, token)
        bridge = ChatBridge(url, token)
        setLoggedIn(true)
        loadInbox()
    }

    private fun loadInbox(showLoading: Boolean = true) {
        val b = bridge ?: run { statusLabel.text = "Connect first."; return }
        inboxJob?.cancel()
        if (showLoading) {
            loadingBar.visibility = View.VISIBLE
            statusLabel.text = if (allUsersMode) "Loading all users…" else "Loading users…"
        }
        inboxJob = lifecycleScope.launch {
            try {
                val convs = b.inbox(searchInput.text.toString().trim())
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
        val b = bridge ?: run { statusLabel.text = "Connect first."; return }

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

        var searchJob: Job? = null
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val q = s?.toString()?.trim().orEmpty()
                if (q.length < 2) { results.clear(); adapter.notifyDataSetChanged(); return }
                searchJob = lifecycleScope.launch {
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
