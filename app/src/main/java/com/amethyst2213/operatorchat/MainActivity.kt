package com.amethyst2213.operatorchat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusLabel: TextView

    // Inbox view
    private lateinit var inboxLayout: LinearLayout
    private lateinit var refreshButton: Button

    // Thread view
    private lateinit var threadLayout: LinearLayout
    private lateinit var threadTitle: TextView
    private lateinit var backButton: Button
    private lateinit var replyInput: EditText
    private lateinit var replyButton: Button

    private lateinit var rootScroll: ScrollView

    private var bridge: ChatBridge? = null
    private var pollJob: Job? = null
    private var currentConv: Long = 0
    private var lastMessageId = 0L

    private val prefs by lazy { getSharedPreferences("operator_chat", MODE_PRIVATE) }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.url_input)
        tokenInput = findViewById(R.id.token_input)
        connectButton = findViewById(R.id.connect_button)
        statusLabel = findViewById(R.id.status_label)
        inboxLayout = findViewById(R.id.inbox_layout)
        refreshButton = findViewById(R.id.refresh_button)
        threadLayout = findViewById(R.id.thread_layout)
        threadTitle = findViewById(R.id.thread_title)
        backButton = findViewById(R.id.back_button)
        replyInput = findViewById(R.id.reply_input)
        replyButton = findViewById(R.id.reply_button)
        rootScroll = findViewById(R.id.root_scroll)

        urlInput.setText(prefs.getString("url", "https://amethyst2213.com/gallery"))
        tokenInput.setText(prefs.getString("token", ""))

        connectButton.setOnClickListener { connect() }
        refreshButton.setOnClickListener { loadInbox() }
        backButton.setOnClickListener { showInbox() }
        replyButton.setOnClickListener { sendReply() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private fun connect() {
        val url = urlInput.text.toString().trim().trimEnd('/')
        val token = tokenInput.text.toString().trim()
        if (url.isEmpty() || token.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), "Server URL and token are required.", Snackbar.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("url", url).putString("token", token).apply()
        bridge = ChatBridge(url, token)
        // Start the background poller for new-message notifications.
        try {
            val si = android.content.Intent(this, ChatPollService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(si)
            } else {
                startService(si)
            }
        } catch (e: Exception) {
            // Foreground service start can fail on some OEM builds; the app
            // still works for active use — just no background notifications.
            Log.w("OperatorChat", "service start failed", e)
        }
        statusLabel.text = "Connected — loading inbox…"
        loadInbox()
    }

    private fun showInbox() {
        pollJob?.cancel()
        currentConv = 0
        inboxLayout.visibility = View.VISIBLE
        threadLayout.visibility = View.GONE
    }

    private fun loadInbox() {
        val b = bridge ?: run { statusLabel.text = "Connect first."; return }
        inboxLayout.removeAllViews()
        statusLabel.text = "Loading inbox…"
        lifecycleScope.launch {
            try {
                val convs = b.inbox()
                if (convs.isEmpty()) {
                    val empty = TextView(this@MainActivity).apply {
                        text = "No conversations yet."
                        setPadding(16, 16, 16, 16)
                    }
                    inboxLayout.addView(empty)
                } else {
                    convs.forEach { c -> inboxLayout.addView(buildRow(c)) }
                }
                statusLabel.text = "${convs.size} conversation(s)"
            } catch (e: Exception) {
                statusLabel.text = "Error: ${e.message ?: "connection failed"} — check URL/token and try again."
            }
        }
    }

    private fun buildRow(c: ChatBridge.Conversation): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(0xFFFDF2F8.toInt())
            isClickable = true
            setOnClickListener { openThread(c.id, c.userEmail) }
        }
        val title = TextView(this).apply {
            text = c.userEmail.ifEmpty { "Conversation #${c.id}" }
            setTextColor(0xFF4A044E.toInt())
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        }
        val last = TextView(this).apply {
            text = (if (c.lastSender == "user") "Member: " else "") + (c.lastMessage.take(80).ifEmpty { "(no messages)" })
            setTextColor(0xFF6B21A8.toInt())
            textSize = 13f
            maxLines = 1
        }
        val meta = TextView(this).apply {
            val unread = c.unreadReplyable
            text = "${c.memberCount} member msg(s)" + (if (unread > 0) " · $unread need reply" else "") + " · mode ${c.aiMode}"
            setTextColor(0xFF9333EA.toInt())
            textSize = 11f
        }
        row.addView(title)
        row.addView(last)
        row.addView(meta)
        return row
    }

    private fun openThread(convId: Long, email: String) {
        currentConv = convId
        lastMessageId = 0L
        threadTitle.text = "Chat with $email"
        threadLayout.visibility = View.VISIBLE
        inboxLayout.visibility = View.GONE
        statusLabel.text = "Loading thread…"
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            // Initial full load, then stream for realtime updates.
            try {
                loadThreadOnce()
            } catch (e: Exception) {
                statusLabel.text = "Thread error: ${e.message ?: "connection"} — retrying…"
            }
            while (true) {
                try {
                    streamThread()
                } catch (e: Exception) {
                    statusLabel.text = "Stream error: ${e.message ?: "connection"} — reconnecting…"
                    delay(1500)
                }
            }
        }
    }

    /** Realtime: block on the SSE stream and render new messages as they arrive. */
    private suspend fun streamThread() {
        val b = bridge ?: return
        val msgs = b.streamOnce(currentConv, lastMessageId)
        if (msgs.isNotEmpty()) {
            val sv = rootScroll
            val atBottom = sv.getChildAt(0)?.let { root ->
                root.bottom - (sv.scrollY + sv.height) < 60
            } ?: true
            msgs.forEach { m ->
                if (m.id > lastMessageId) appendMessage(m)
                if (m.id > lastMessageId) lastMessageId = m.id
            }
            if (atBottom) {
                sv.post { sv.fullScroll(View.FOCUS_DOWN) }
            }
            statusLabel.text = "Live"
        }
    }

    private suspend fun loadThreadOnce() {
        val b = bridge ?: return
        val msgs = b.thread(currentConv)
        if (msgs.isEmpty()) {
            statusLabel.text = "No messages in this thread."
            return
        }
        val newOnes = msgs.filter { it.id > lastMessageId }
        if (newOnes.isNotEmpty()) {
            val sv = rootScroll
            val atBottom = sv.getChildAt(0)?.let { root ->
                root.bottom - (sv.scrollY + sv.height) < 60
            } ?: true
            newOnes.forEach { appendMessage(it) }
            if (atBottom) {
                sv.post { sv.fullScroll(View.FOCUS_DOWN) }
            }
            lastMessageId = msgs.maxOf { it.id }
            statusLabel.text = "Live"
        }
    }

    private fun appendMessage(m: ChatBridge.Message) {
        val bubble = TextView(this).apply {
            text = when (m.senderRole) {
                "user" -> "Member:\n${m.message}"
                "operator" -> "You:\n${m.message}"
                else -> "AI:\n${m.message}"
            }
            textSize = 14f
            setPadding(12, 10, 12, 10)
        }
        if (m.senderRole == "user") {
            bubble.setBackgroundColor(0xFFFDF2F8.toInt())
            bubble.setTextColor(0xFF4A044E.toInt())
        } else {
            bubble.setBackgroundColor(0xFF9333EA.toInt())
            bubble.setTextColor(0xFFFFFFFF.toInt())
        }
        threadLayout.addView(bubble)
    }

    private fun sendReply() {
        val b = bridge ?: return
        val text = replyInput.text.toString().trim()
        if (text.isEmpty()) return
        replyInput.setText("")
        lifecycleScope.launch {
            try {
                val ok = b.reply(currentConv, text)
                statusLabel.text = if (ok) "Reply sent" else "Reply failed"
                if (ok) loadThreadOnce()
            } catch (e: Exception) {
                statusLabel.text = "Send error: ${e.message}"
            }
        }
    }
}