package com.amethyst2213.operatorchat

import android.app.AlertDialog
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private lateinit var sendButton: Button
    private lateinit var emojiButton: Button
    private lateinit var attachButton: Button
    private lateinit var emojiBar: RecyclerView
    private lateinit var statusLabel: TextView
    private lateinit var root: View

    private var bridge: ChatBridge? = null
    private var conversationId: Long = 0
    private var latestId = 0L
    private var pendingPlaceholderId = 0L
    private val messages = mutableListOf<ChatBridge.Message>()
    private lateinit var adapter: MessageAdapter
    private var streamJob: Job? = null

    private val prefs by lazy { getSharedPreferences("operator_chat", MODE_PRIVATE) }

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
        statusLabel = findViewById(R.id.chat_status)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.chat_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        StatusBarToolbar.apply(this, toolbar)

        conversationId = intent.getLongExtra("conversation_id", 0)
        val url = intent.getStringExtra("base_url") ?: ""
        val token = intent.getStringExtra("token") ?: ""
        val username = intent.getStringExtra("username") ?: "#$conversationId"
        supportActionBar?.title = username

        // Keep the bottom reply bar above the soft keyboard (edge-to-edge on
        // targetSdk 35 means adjustResize alone is not enough).
        root = findViewById(R.id.chat_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            root.setPadding(0, 0, 0, ime + bars)
            insets
        }
        ViewCompat.requestApplyInsets(root)

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
            pickAttachment.launch("*/*")
        }

        sendButton.setOnClickListener { sendReply() }

        startStream()
    }

    override fun onDestroy() {
        streamJob?.cancel()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_chat_settings -> {
                showChatSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Per-chat settings: AI mode + notification tone + notifications on/off. */
    private fun showChatSettings() {
        val b = bridge ?: return
        val currentMode = intent.getStringExtra("ai_mode") ?: "retrieval"
        val modes = arrayOf("retrieval", "finetuned", "operator")
        val labels = arrayOf("Retrieval (AI + history)", "Finetuned (trained AI)", "Operator (manual)")
        val checked = modes.indexOf(currentMode).coerceAtLeast(0)

        val chatKey = "chat_$conversationId"
        val notifyOn = prefs.getBoolean("${chatKey}_notify", true)
        val tone = prefs.getString("${chatKey}_tone", "").orEmpty()
        val isFav = Favorites.isFavorite(this, conversationId)

        val options = arrayOf(
            "★ Favourite: ${if (isFav) "ON" else "OFF"}",
            "Chat mode: ${labels[checked]}",
            "Notifications: ${if (notifyOn) "ON" else "OFF"}",
            "Notification tone: ${if (tone.isEmpty()) "Default" else tone.substringAfterLast('/')}"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle("Chat settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        Favorites.toggle(this, conversationId)
                        showChatSettings()
                    }
                    1 -> pickMode(modes, labels, checked, b)
                    2 -> {
                        val on = !notifyOn
                        prefs.edit().putBoolean("${chatKey}_notify", on).apply()
                        showChatSettings()
                    }
                    3 -> pickChatTone(chatKey)
                }
            }
            .setPositiveButton("Done", null)
            .create()
        dialog.show()
    }

    private fun pickMode(modes: Array<String>, labels: Array<String>, checked: Int, b: ChatBridge) {
        AlertDialog.Builder(this)
            .setTitle("Chat mode")
            .setSingleChoiceItems(labels, checked) { _, which ->
                statusLabel.text = "Setting mode…"
                val mode = modes[which]
                lifecycleScope.launch {
                    val ok = b.setMode(conversationId, mode)
                    statusLabel.text = if (ok) "Mode set to ${labels[which]}" else "Mode update failed"
                    if (ok) intent.putExtra("ai_mode", mode)
                }
            }
            .setPositiveButton("Cancel", null)
            .create()
            .show()
    }

    private fun pickChatTone(chatKey: String) {
        val intent = android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Chat notification tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                Uri.parse(prefs.getString("${chatKey}_tone", null)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()))
        }
        chatToneResult.launch(intent)
    }

    private val chatToneResult = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            prefs.edit().putString("${"chat_$conversationId"}_tone", uri.toString()).apply()
            showChatSettings()
        }
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
                // Reading the thread clears its new-message state.
                try { bridge?.setRead(conversationId, latestId) } catch (_: Exception) {}
            } catch (e: Exception) {
                statusLabel.text = "Could not load thread: ${e.message}"
            }
            // Realtime stream loop.
            while (true) {
                try {
                    val new = bridge?.streamOnce(conversationId, latestId) ?: emptyList()
                    val fresh = new.filter { it.id > latestId && messages.none { m -> m.id == it.id } }
                    if (fresh.isNotEmpty()) {
                        latestId = fresh.maxOf { it.id }
                        messages.addAll(fresh)
                        adapter.notifyItemRangeInserted(messages.size - fresh.size, fresh.size)
                        recycler.scrollToPosition(messages.size - 1)
                        try { bridge?.setRead(conversationId, latestId) } catch (_: Exception) {}
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

        // Optimistic: show the sent message immediately (it will also come
        // back through the stream with the same id, which the filter skips).
        val placeholderId = --pendingPlaceholderId
        val now = "now"
        val optimistic = ChatBridge.Message(
            id = placeholderId,
            conversationId = conversationId,
            senderRole = "operator",
            message = text,
            createdAt = now,
            attachmentName = pending?.name,
            attachmentType = null,
            attachmentUrl = null,
            attachmentThumbUrl = null,
        )
        messages.add(optimistic)
        adapter.notifyItemInserted(messages.size - 1)
        recycler.scrollToPosition(messages.size - 1)
        statusLabel.text = "Sending…"

        lifecycleScope.launch {
            try {
                val id = bridge?.reply(conversationId, text, pending) ?: 0
                if (id > 0) {
                    latestId = maxOf(latestId, id)
                    // Replace the placeholder with the real message (same id).
                    val idx = messages.indexOfFirst { it.id == placeholderId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(id = id)
                        adapter.notifyItemChanged(idx)
                    }
                    statusLabel.text = "Sent"
                } else {
                    statusLabel.text = "Reply failed"
                    messages.removeAll { it.id == placeholderId }
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                statusLabel.text = "Send error: ${e.message}"
                messages.removeAll { it.id == placeholderId }
                adapter.notifyDataSetChanged()
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
            val image: android.widget.ImageView = v.findViewById(R.id.attach_image)
            val row: android.widget.LinearLayout = v.findViewById(R.id.bubble_row)
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
            h.bubble.setBackgroundResource(if (mine) R.drawable.bubble_out else R.drawable.bubble_in)
            h.bubble.setTextColor(if (mine) 0xFFFFFFFF.toInt() else 0xFF2E1065.toInt())

            // Member/AI messages hug the left; operator messages hug the right.
            val gravity = if (mine) android.view.Gravity.END else android.view.Gravity.START
            h.row.gravity = gravity
            (h.sender.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                lp.leftMargin = if (mine) 0 else 12
                lp.rightMargin = if (mine) 12 else 0
            }

            // Reset image slot each bind.
            h.image.setImageDrawable(null)
            h.image.visibility = View.GONE
            h.image.setOnClickListener(null)
            h.image.layoutParams = h.image.layoutParams.also { lp ->
                if (lp is android.widget.LinearLayout.LayoutParams) {
                    lp.gravity = gravity
                }
            }

            if (m.attachmentName != null) {
                if (m.attachmentThumbUrl != null) {
                    // Image attachment: show thumbnail, tap to open full size.
                    h.image.visibility = View.VISIBLE
                    h.image.setTag(m.id)
                    loadThumb(m, h.image)
                    h.image.setOnClickListener { openFullImage(m) }
                    h.attach.visibility = View.GONE
                } else {
                    h.attach.text = "📎 " + m.attachmentName
                    h.attach.visibility = View.VISIBLE
                }
            } else {
                h.attach.visibility = View.GONE
            }
        }
    }

    private fun loadThumb(m: ChatBridge.Message, iv: android.widget.ImageView) {
        val b = bridge ?: return
        lifecycleScope.launch {
            val f = File(cacheDir, "thumb_${m.id}.jpg")
            val dl = b.download(m.attachmentThumbUrl ?: return@launch, f)
            if (dl != null && iv.getTag() == m.id) {
                try {
                    val bmp = android.graphics.BitmapFactory.decodeFile(dl.absolutePath)
                    iv.setImageBitmap(bmp)
                } catch (_: Exception) { /* ignore decode error */ }
            }
        }
    }

    private fun openFullImage(m: ChatBridge.Message) {
        val b = bridge ?: return
        statusLabel.text = "Loading full image…"
        lifecycleScope.launch {
            val f = File(cacheDir, "full_${m.id}_" + System.currentTimeMillis() + ".img")
            val dl = b.download(m.attachmentUrl ?: return@launch, f)
            if (dl == null) {
                statusLabel.text = "Could not load image."
                return@launch
            }
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(androidx.core.content.FileProvider.getUriForFile(this@ChatActivity, "$packageName.fileprovider", dl), "image/*")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                statusLabel.text = "Live"
            } catch (e: Exception) {
                statusLabel.text = "No image viewer available."
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