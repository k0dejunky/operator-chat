package com.amethyst2213.operatorchat

import android.Manifest
import android.content.Intent
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
import kotlinx.coroutines.launch

/**
 * Login + users list. Shows only users with unread member messages (a badge
 * with the count), and provides the app navigation menu.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusLabel: TextView
    private lateinit var inboxRecycler: RecyclerView
    private lateinit var emptyLabel: TextView
    private lateinit var loadingBar: ProgressBar

    private var bridge: ChatBridge? = null
    private val conversations = mutableListOf<ChatBridge.Conversation>()

    private val prefs by lazy { getSharedPreferences("operator_chat", MODE_PRIVATE) }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setTitle("Users list")

        urlInput = findViewById(R.id.url_input)
        tokenInput = findViewById(R.id.token_input)
        connectButton = findViewById(R.id.connect_button)
        statusLabel = findViewById(R.id.status_label)
        inboxRecycler = findViewById(R.id.inbox_recycler)
        emptyLabel = findViewById(R.id.empty_label)
        loadingBar = findViewById(R.id.loading_bar)

        inboxRecycler.layoutManager = LinearLayoutManager(this)
        inboxRecycler.adapter = InboxAdapter(conversations) { openChat(it) }

        urlInput.setText(prefs.getString("url", "https://amethyst2213.com/gallery"))
        tokenInput.setText(prefs.getString("token", ""))

        connectButton.setOnClickListener { connect() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
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
                emptyLabel.visibility = View.VISIBLE
                statusLabel.text = "Logged out — enter server URL + token."
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
        loadInbox()
    }

    private fun loadInbox() {
        val b = bridge ?: run { statusLabel.text = "Connect first."; return }
        loadingBar.visibility = View.VISIBLE
        statusLabel.text = "Loading users…"
        lifecycleScope.launch {
            try {
                val convs = b.inbox()
                // Users list: only users with unread member messages appear.
                val withUnread = convs.filter { it.unreadReplyable > 0 }
                conversations.clear()
                conversations.addAll(withUnread)
                inboxRecycler.adapter?.notifyDataSetChanged()
                emptyLabel.visibility = if (withUnread.isEmpty()) View.VISIBLE else View.GONE
                statusLabel.text = if (withUnread.isEmpty()) {
                    "No new messages."
                } else {
                    "${withUnread.size} user(s) with new messages"
                }
                // Background poller for notifications.
                try {
                    val si = Intent(this@MainActivity, ChatPollService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                statusLabel.text = "Error: ${e.message ?: "connection failed"} — check URL/token."
            } finally {
                loadingBar.visibility = View.GONE
            }
        }
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
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = layoutInflater.inflate(R.layout.row_user, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = data[position]
            h.title.text = c.username.ifEmpty { c.userEmail }
            h.badge.text = c.unreadReplyable.toString()
            h.itemView.setOnClickListener { onClick(c) }
        }
    }
}