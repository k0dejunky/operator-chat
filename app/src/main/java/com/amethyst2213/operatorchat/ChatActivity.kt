package com.amethyst2213.operatorchat

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder

/**
 * Messenger-style chat thread. Bottom-fixed reply bar with emoji + attachment
 * support; realtime via the SSE stream. Opens from MainActivity so every
 * conversation is its own clean screen (no cross-conversation state leaks).
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var emojiButton: ImageButton
    private lateinit var attachButton: ImageButton
    private lateinit var emojiBar: RecyclerView
    private lateinit var titleLabel: TextView
    private lateinit var statusLabel: TextView

    private var bridge: ChatBridge? = null
    private var conversationId: Long = 0
    private var latestId = 0L
    private val messages = mutableListOf<ChatBridge.Message>()
    private lateinit var adapter: MessageAdapter
    private var streamJob: Job? = null

    private val pickAttachment = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) attachFromUri(uri)
    }

    private val emojis = listOf("😀", "😍", "😘", "❤️", "🔥", "🥵", "😈", "💋", "👅", "😉",
        "👍", "🙈", "💦", "🤤", "✨", "😊", "😈", "🖤", "🌹", "💯")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        recycler = findViewById(R.id.message_recycler)
        input = findViewById(R.id.reply_input)
        sendButton = findViewById(R.id.send_button)
        emojiButton = findViewById(R.id.emoji_button)
        attachButton = findViewById(R.id.attach_button)
        emojiBar = findViewById(R.id.emoji_bar)
        titleLabel = findViewById(R.id.thread_title)
        statusLabel = findViewById(R.id.chat_status)

        conversationId = intent.getLongExtra("conversation_id", 0)
        val url = intent.getStringExtra("base_url") ?: ""
        val token = intent.getStringExtra("token") ?: ""
        titleLabel.text = "Chat with ${intent.getStringExtra("user_email") ?: "#$conversationId"}"

        bridge = ChatBridge(url, token)

        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = MessageAdapter(messages)
        recycler.adapter = adapter

        // Emoji quick-bar
        emojiBar.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        emojiBar.adapter = EmojiAdapter(emojis) { emoji ->
            input.append(emoji)
            input.setSelection(input.text.length)
        }
        emojiBar.visibility = View.GONE
        emojiButton.setOnClickListener {
            emojiBar.visibility = if (emojiBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        attachButton.setOnClickListener {
            pickAttachment.launch("image/*|video/*|text/plain|application/pdf")
        }

        sendButton.setOnClickListener { sendReply() }

        startStream()
    }

    override fun onDestroy() {
        streamJob?.cancel()
        super.onDestroy()
    }

    private fun startStream() {
        streamJob?.cancel()
        streamJob = lifecycleScope.launch {
            try {
                // Initial full load.
                val hist = bridge?.thread(conversationId) ?: emptyList()
                messages.clear()
                messages.addAll(hist)
                latestId = hist.maxOfOrNull { it.id } ?: 0L
                adapter.notifyDataSetChanged()
                statusLabel.text = "Live"
            } catch (e: Exception) {
                statusLabel.text = "Could not load thread: ${e.message}"
            }
            // Realtime stream loop.
            while (true) {
                try {
                    val new = bridge?.streamOnce(conversationId, latestId) ?: emptyList()
                    val fresh = new.filter { it.id > latestId }
                    if (fresh.isNotEmpty()) {
                        latestId = fresh.maxOf { it.id }
                        messages.addAll(fresh)
                        adapter.notifyItemRangeInserted(messages.size - fresh.size, fresh.size)
                        recycler.scrollToPosition(messages.size - 1)
                    }
                } catch (e: Exception) {
                    statusLabel.text = "Reconnecting…"
                    delay(1500)
                }
            }
        }
    }

    private fun sendReply() {
        val text = input.text.toString().trim()
        val pending = pendingAttachment
        if (text.isEmpty() && pending == null) return
        input.setText("")
        pendingAttachment = null
        lifecycleScope.launch {
            try {
                val ok = bridge?.reply(conversationId, text, pending) ?: false
                statusLabel.text = if (ok) "Sent" else "Reply failed"
                if (ok) { /* the stream will deliver the message back */ }
            } catch (e: Exception) {
                statusLabel.text = "Send error: ${e.message}"
            }
        }
    }

    private var pendingAttachment: File? = null

    private fun attachFromUri(uri: Uri) {
        try {
            val resolver = contentResolver
            val displayName = resolver.getType(uri)?.let { t ->
                // fall back to a generic name; use the last path segment if available
                uri.lastPathSegment ?: "attachment"
            } ?: "attachment"
            val name = URLDecoder.decode(uri.lastPathSegment ?: "attachment", "UTF-8")
            val tmp = File(cacheDir, "attachment_" + System.currentTimeMillis() + "_" + sanitize(name))
            resolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { ous -> ins.copyTo(ous) }
            }
            pendingAttachment = tmp
            statusLabel.text = "Attachment ready: ${sanitize(name)} (will send with your reply)"
        } catch (e: Exception) {
            statusLabel.text = "Could not read attachment: ${e.message}"
        }
    }

    private fun sanitize(n: String): String = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    // ------------------------------------------------------------ adapters
    inner class MessageAdapter(private val data: List<ChatBridge.Message>) :
        RecyclerView.Adapter<MessageAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val bubble: TextView = v.findViewById(R.id.bubble)
            val sender: TextView = v.findViewById(R.id.sender_label)
            val attach: TextView = v.findViewById(R.id.attach_label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = data.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val m = data[position]
            val mine = m.senderRole == "operator"
            h.sender.text = when (m.senderRole) {
                "user" -> "Member"
                "operator" -> "You"
                else -> "AI"
            }
            h.bubble.text = m.message.ifEmpty { "" }
            h.bubble.setBackgroundColor(if (mine) 0xFF9333EA.toInt() else 0xFFFDF2F8.toInt())
            h.bubble.setTextColor(if (mine) 0xFFFFFFFF.toInt() else 0xFF4A044E.toInt())
            if (m.attachmentName != null) {
                h.attach.text = "📎 " + m.attachmentName
                h.attach.visibility = View.VISIBLE
            } else {
                h.attach.visibility = View.GONE
            }
        }
    }

    inner class EmojiAdapter(
        private val list: List<String>,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<EmojiAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val btn: TextView = v.findViewById(R.id.emoji_item)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_emoji, parent, false)
            return Holder(v)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            h.btn.text = list[position]
            h.btn.setOnClickListener { onClick(list[position]) }
        }
    }
}