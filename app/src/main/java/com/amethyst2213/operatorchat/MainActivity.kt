package com.amethyst2213.operatorchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Login + inbox. Lists every conversation; tapping one opens ChatActivity.
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
        statusLabel.text = "Loading inbox…"
        lifecycleScope.launch {
            try {
                val convs = b.inbox()
                conversations.clear()
                conversations.addAll(convs)
                inboxRecycler.adapter?.notifyDataSetChanged()
                emptyLabel.visibility = if (convs.isEmpty()) View.VISIBLE else View.GONE
                statusLabel.text = "${convs.size} conversation(s) — tap to open"
                // Background poller for notifications.
                try {
                    val si = android.content.Intent(this@MainActivity, ChatPollService::class.java)
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
        i.putExtra("user_email", c.userEmail)
        startActivity(i)
    }

    // ---------------------------------------------------------------- adapter
    inner class InboxAdapter(
        private val data: List<ChatBridge.Conversation>,
        private val onClick: (ChatBridge.Conversation) -> Unit,
    ) : RecyclerView.Adapter<InboxAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.row_title)
            val last: TextView = v.findViewById(R.id.row_last)
            val meta: TextView = v.findViewById(R.id.row_meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = layoutInflater.inflate(R.layout.row_conversation, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val c = data[position]
            h.title.text = c.userEmail.ifEmpty { "Conversation #${c.id}" }
            h.last.text = (if (c.lastSender == "user") "Member: " else "") + c.lastMessage.take(90).ifEmpty { "(no messages)" }
            h.meta.text = "${c.memberCount} member msg(s)" +
                (if (c.unreadReplyable > 0) " · ${c.unreadReplyable} need reply" else "") +
                " · mode ${c.aiMode}"
            h.itemView.setOnClickListener { onClick(c) }
        }
    }
}