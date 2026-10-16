package com.amethyst2213.operatorchat

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amethyst2213.operatorchat.databinding.ActivityChatBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Messenger-style chat thread.
 *
 * What changed from the previous version, and why it "operated badly":
 *
 *  - the SSE loop spun with no delay whenever the server closed the stream
 *    immediately (hot CPU + radio drain). It now sleeps between attempts, backs
 *    off on errors, and is cancelled in onStop() so a backgrounded thread no
 *    longer holds the radio awake.
 *  - a null bridge made `?: emptyList()` return forever, so the loop became a
 *    100% CPU spin. The stream now refuses to start without a bridge.
 *  - the optimistic placeholder and the real message could both end up in the
 *    list when the stream delivered the server's copy before the reply
 *    returned. Placeholders are now dropped when the real message arrives.
 *  - the list re-bound every row on each poll (notifyDataSetChanged). It is a
 *    ListAdapter with DiffUtil now, so a new message animates in and the scroll
 *    position survives.
 *  - "Sending… / Uploading 42% / Sent / Live" was a permanent TextView above
 *    the list, so the screen flickered text at the operator. Status is a
 *    Snackbar now, and per-message state lives on the message itself.
 *  - the draft was written to SharedPreferences on every keystroke.
 *  - attachments were copied into the cache before their size was known, so a
 *    2 GB pick could fill the disk before being rejected.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding

    private var bridge: ChatBridge? = null
    private var conversationId: Long = 0
    private var latestId = 0L
    private var oldestId = 0L
    private var hasMore = false
    private var loadingOlder = false
    private var memberReplyEnabled = true
    private var pendingPlaceholderId = 0L
    private var streamActive = false
    private var wasAtBottom = true

    private val messages = mutableListOf<ChatBridge.Message>()

    /** Delivery state for optimistic (not yet confirmed) outgoing messages. */
    private val deliveries = mutableMapOf<Long, Delivery>()

    /** Ids of optimistic rows still waiting for the server to confirm them. */
    private val optimisticIds = mutableSetOf<Long>()

    private lateinit var adapter: MessageAdapter
    private var streamJob: Job? = null

    private val prefs by lazy { getSharedPreferences("operator_chat", MODE_PRIVATE) }

    /** Zone the server stamps its timestamps in (Settings > timezone). */
    private val siteZone: String
        get() = prefs.getString("site_timezone", ChatRows.DEFAULT_ZONE) ?: ChatRows.DEFAULT_ZONE

    private val pickAttachment =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) attachFromUri(uri)
        }

    /** Camera capture: an output Uri is supplied so the full-res photo/video is
     *  written to our cache dir (ACTION_IMAGE_CAPTURE alone returns only a
     *  small thumbnail). */
    private var cameraOutput: File? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = cameraOutput
            cameraOutput = null
            if (ok && file != null && file.exists() && file.length() > 0) {
                pendingAttachment = file
                showSnack("Photo ready — it will be sent with your reply.")
            } else {
                file?.delete()
                showSnack("Photo cancelled.")
            }
        }

    private val takeVideo =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val file = cameraOutput
            cameraOutput = null
            if (result.resultCode == Activity.RESULT_OK && file != null &&
                file.exists() && file.length() > 0
            ) {
                if (file.length() > MAX_ATTACHMENT_BYTES) {
                    file.delete()
                    showSnack("Video too large (max 25 MB) — try a shorter recording.")
                } else {
                    pendingAttachment = file
                    showSnack("Video ready — it will be sent with your reply.")
                }
            } else {
                file?.delete()
                showSnack("Video cancelled.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        conversationId = intent.getLongExtra("conversation_id", 0)
        memberReplyEnabled = intent.getBooleanExtra("member_reply_enabled", true)

        binding.replyInput.setText(prefs.getString("draft_$conversationId", "").orEmpty())

        setSupportActionBar(binding.chatToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val url = intent.getStringExtra("base_url") ?: ""
        val token = intent.getStringExtra("token") ?: SecurePrefs.token(this)
        val username = intent.getStringExtra("username")
        supportActionBar?.title = username?.ifBlank { null } ?: "Conversation #$conversationId"
        updateSubtitle()

        // Keep the composer above the soft keyboard (edge-to-edge on targetSdk
        // 35 means adjustResize alone is not enough) and pad the list so its
        // last row is never hidden behind the composer.
        ViewCompat.setOnApplyWindowInsetsListener(binding.chatRoot) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val shift = maxOf(ime, bars)
            binding.composer.translationY = -shift.toFloat()
            binding.messageRecycler.setPadding(0, 0, 0, shift)
            binding.chatEmpty.setPadding(32, 0, 32, shift)
            insets
        }
        ViewCompat.requestApplyInsets(binding.chatRoot)

        bridge = ChatBridge(url, token)

        adapter = MessageAdapter(
            onImageClick = { openFullImage(it) },
            onRetryClick = { retryMessage(it) },
        )
        binding.messageRecycler.layoutManager =
            LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.messageRecycler.adapter = adapter

        // Lazy-load older messages when the user scrolls to the top. `dy <= 0`
        // keeps the initial layout pass (which also reports position 0) from
        // immediately pulling in the previous page.
        binding.messageRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findFirstVisibleItemPosition() in 0..1 && dy <= 0) loadOlder()
                if (isAtBottom()) binding.scrollBottomFab.hide() else binding.scrollBottomFab.show()
            }
        })

        binding.sendButton.setOnClickListener { sendReply() }
        binding.attachButton.setOnClickListener { showAttachChooser() }
        binding.scrollBottomFab.setOnClickListener { scrollToBottom() }

        render()
    }

    override fun onStart() {
        super.onStart()
        // Only stream while the screen is visible: the previous version kept the
        // SSE connection open in the background.
        startStream()
    }

    override fun onStop() {
        streamActive = false
        streamJob?.cancel()
        streamJob = null
        super.onStop()
    }

    override fun onPause() {
        // Persist the draft once instead of a SharedPreferences write per
        // keystroke.
        prefs.edit()
            .putString("draft_$conversationId", binding.replyInput.text.toString())
            .apply()
        super.onPause()
    }

    override fun onDestroy() {
        pendingAttachment?.delete()
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

    // ------------------------------------------------------------ rendering

    /**
     * Rebuild the display rows and hand them to the adapter. Building the rows
     * (day separators, grouping, delivery state) lives in [ChatRows] so the
     * maths is unit-testable and the adapter stays a dumb binder.
     */
    private fun render() {
        val rows = ChatRows.build(messages, deliveries, siteZone)
        adapter.submitList(rows)
        binding.chatEmpty.isVisible = rows.isEmpty()
        binding.messageRecycler.isVisible = rows.isNotEmpty()
        if (wasAtBottom && rows.isNotEmpty()) scrollToBottom()
    }

    private fun isAtBottom(): Boolean {
        val lm = binding.messageRecycler.layoutManager as? LinearLayoutManager ?: return true
        if (adapter.itemCount == 0) return true
        return lm.findLastCompletelyVisibleItemPosition() >= adapter.itemCount - 2
    }

    private fun scrollToBottom() {
        if (adapter.itemCount == 0) return
        binding.messageRecycler.scrollToPosition(adapter.itemCount - 1)
        binding.scrollBottomFab.hide()
    }

    private fun showSnack(message: String) {
        Snackbar.make(binding.chatRoot, message, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.composer)
            .show()
    }

    private fun showSnackLong(message: String) {
        Snackbar.make(binding.chatRoot, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.composer)
            .show()
    }

    private fun updateSubtitle() {
        supportActionBar?.subtitle = getString(
            if (memberReplyEnabled) R.string.chat_replies_on else R.string.chat_replies_off
        )
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.replyInput.windowToken, 0)
    }

    // ------------------------------------------------------------ streaming

    private fun startStream() {
        val b = bridge
        if (b == null) {
            showSnackLong("No server configured for this conversation.")
            return
        }
        if (streamJob?.isActive == true) return
        streamActive = true

        streamJob = lifecycleScope.launch {
            try {
                val hist = b.thread(conversationId)
                messages.clear()
                messages.addAll(hist.sortedBy { it.id })
                latestId = hist.maxOfOrNull { it.id } ?: 0L
                oldestId = hist.minOfOrNull { it.id } ?: 0L
                hasMore = hist.size >= 50
                wasAtBottom = true
                render()
                scrollToBottom()
                // Deliver any queued offline replies now that we are connected.
                SendQueue.schedule(this@ChatActivity)
                b.setRead(conversationId, latestId)
            } catch (e: Exception) {
                showSnackLong("Could not load thread: ${e.message ?: "unknown error"}")
            }

            var backoffMs = 1500L
            while (streamActive) {
                try {
                    val incoming = b.streamOnce(conversationId, latestId)
                    val fresh = incoming.filter {
                        it.id > latestId && messages.none { m -> m.id == it.id }
                    }
                    if (fresh.isNotEmpty()) collectIncoming(fresh)
                    backoffMs = 1500L
                    // The server closes each SSE connection per poll, so this
                    // delay is what stops the old 100% CPU spin.
                    delay(500)
                } catch (e: Exception) {
                    // Exponential backoff with jitter so an outage doesn't
                    // hammer the server or the radio forever.
                    delay(backoffMs + (0..1000).random())
                    backoffMs = minOf(backoffMs * 2, 30000L)
                }
            }
        }
    }

    /**
     * Fold a batch of streamed messages into the list.
     *
     * Any optimistic row the server has now confirmed (same sender + same text)
     * is dropped first, so a message can never appear twice when the stream
     * wins the race against the reply response.
     */
    private fun collectIncoming(fresh: List<ChatBridge.Message>) {
        val confirmed = fresh.filter { real ->
            optimisticIds.any { pid ->
                messages.firstOrNull { it.id == pid }?.let { opt ->
                    opt.senderRole.equals(real.senderRole, true) && opt.message == real.message
                } ?: false
            }
        }

        if (confirmed.isNotEmpty()) {
            optimisticIds.removeAll { pid ->
                val opt = messages.firstOrNull { it.id == pid } ?: return@removeAll false
                confirmed.any {
                    it.senderRole.equals(opt.senderRole, true) && it.message == opt.message
                }
            }
            optimisticIds.forEach { pid -> deliveries.remove(pid) }
            messages.removeAll { m -> m.id < 0 && optimisticIds.none { it == m.id } }
        }

        latestId = maxOf(latestId, fresh.maxOf { it.id })
        messages.addAll(fresh.sortedBy { it.id })
        render()
        lifecycleScope.launch { bridge?.setRead(conversationId, latestId) }
    }

    /** Load the previous batch of older messages and prepend them. */
    private fun loadOlder() {
        if (loadingOlder || !hasMore || oldestId <= 0) return
        loadingOlder = true
        val b = bridge ?: run { loadingOlder = false; return }
        lifecycleScope.launch {
            try {
                val (older, more) = b.history(conversationId, oldestId, 50)
                if (older.isNotEmpty()) {
                    val lm = binding.messageRecycler.layoutManager as? LinearLayoutManager
                    val pos = lm?.findFirstVisibleItemPosition() ?: 0
                    val offset = lm?.findViewByPosition(pos)?.top ?: 0

                    val merged = (older + messages).distinctBy { it.id }.sortedBy { it.id }
                    messages.clear()
                    messages.addAll(merged)
                    oldestId = older.minOf { it.id }
                    hasMore = more
                    wasAtBottom = false
                    render()
                    binding.messageRecycler.post { lm?.scrollToPositionWithOffset(pos + 1, offset) }
                } else {
                    hasMore = more
                }
            } catch (_: Exception) {
                // transient; allow a retry on the next scroll to the top
            } finally {
                loadingOlder = false
            }
        }
    }

    // ------------------------------------------------------------ sending

    private fun sendReply() {
        val text = binding.replyInput.text.toString().trim()
        val pending = pendingAttachment
        if (text.isEmpty() && pending == null) return
        if (pending != null && pending.length() > MAX_ATTACHMENT_BYTES) {
            showSnack("Attachment too large (max 25 MB).")
            return
        }
        if (pending != null) {
            showExpiryDialog { minutes, views -> doSend(text, pending, minutes, views) }
        } else {
            doSend(text, null, 0, 0)
        }
    }

    /** Offer a time / view limit for the attached media before sending. */
    private fun showExpiryDialog(after: (Int, Int) -> Unit) {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.dialog_media_expiry, null, false)
        val durationGroup = view.findViewById<RadioGroup>(R.id.expiry_duration)
        val viewsGroup = view.findViewById<RadioGroup>(R.id.expiry_views)

        // Each button carries its value in android:tag, so the choice never
        // depends on a view id matching a number.
        val chosen = intArrayOf(0, 0)
        val pick: (RadioGroup, Int) -> Unit = { group, checkedId ->
            val tag = group.findViewById<android.widget.RadioButton>(checkedId)?.tag?.toString()
            val value = tag?.toIntOrNull() ?: 0
            if (group === durationGroup) chosen[0] = value else chosen[1] = value
        }
        durationGroup.setOnCheckedChangeListener { group, checkedId -> pick(group, checkedId) }
        viewsGroup.setOnCheckedChangeListener { group, checkedId -> pick(group, checkedId) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Expire this media?")
            .setView(view)
            .setPositiveButton("Send") { _, _ -> after(chosen[0], chosen[1]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doSend(text: String, pending: File?, expiresInMinutes: Int, maxViews: Int) {
        val b = bridge ?: run { showSnack("Not connected."); return }

        val idempotencyKey = prefs.getString("pending_reply_key_$conversationId", null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("pending_reply_key_$conversationId", it).apply()
            }

        binding.replyInput.setText("")
        prefs.edit().remove("draft_$conversationId").apply()
        pendingAttachment = null
        hideKeyboard()

        // Optimistic row. It keeps its negative id until the server confirms and
        // is dropped the moment the real message arrives on the stream.
        val placeholderId = --pendingPlaceholderId
        optimisticIds += placeholderId
        deliveries[placeholderId] = Delivery.SENDING
        messages += ChatBridge.Message(
            id = placeholderId,
            conversationId = conversationId,
            senderRole = "operator",
            message = text,
            createdAt = "",
            attachmentName = pending?.name,
            attachmentType = null,
            attachmentUrl = null,
            attachmentThumbUrl = null,
            expiresAt = null,
            maxViews = maxViews,
            viewCount = 0,
            mediaExpired = false,
        )
        wasAtBottom = true
        render()
        scrollToBottom()

        lifecycleScope.launch {
            when (
                val result = b.reply(
                    conversationId, text, pending, idempotencyKey,
                    onProgress = { p ->
                        runOnUiThread {
                            if (pending != null) showSnack("Uploading ${(p * 100).toInt()}%…")
                        }
                    },
                    expiresInMinutes = expiresInMinutes,
                    maxViews = maxViews,
                )
            ) {
                is ChatBridge.ReplyResult.Sent -> {
                    optimisticIds -= placeholderId
                    deliveries.remove(placeholderId)
                    latestId = maxOf(latestId, result.id)
                    // Swap the placeholder for the server's copy so it carries
                    // the real timestamp and attachment URLs. One targeted
                    // history call (not a full thread reload) fetches just it.
                    if (result.id > 0) {
                        val (one, _) = b.history(conversationId, result.id + 1, 1)
                        val real = one.firstOrNull { it.id == result.id }
                        val idx = messages.indexOfFirst { it.id == placeholderId }
                        if (real != null && idx >= 0) {
                            messages[idx] = real
                        } else if (idx >= 0) {
                            messages.removeAt(idx)
                        }
                        SendQueue.remove(this@ChatActivity, idempotencyKey)
                        prefs.edit().remove("pending_reply_key_$conversationId").apply()
                    } else {
                        messages.removeAll { it.id == placeholderId }
                    }
                    wasAtBottom = true
                    render()
                    showSnack("Sent")
                }

                is ChatBridge.ReplyResult.Rejected -> {
                    // The server will never accept this: drop the placeholder
                    // and say why, instead of queueing it forever.
                    optimisticIds -= placeholderId
                    deliveries.remove(placeholderId)
                    messages.removeAll { it.id == placeholderId }
                    prefs.edit().remove("pending_reply_key_$conversationId").apply()
                    render()
                    showSnackLong(result.reason)
                }

                is ChatBridge.ReplyResult.Failed -> {
                    // Queue it for automatic delivery with backoff.
                    deliveries[placeholderId] = Delivery.QUEUED
                    val stashed = pending?.let {
                        SendQueue.stashAttachment(this@ChatActivity, it, idempotencyKey)
                    }
                    SendQueue.add(
                        this@ChatActivity, conversationId, text, idempotencyKey,
                        stashed, expiresInMinutes, maxViews,
                    )
                    render()
                    showSnackLong("Not sent — queued and will be delivered automatically.")
                }
            }
        }
    }

    /** Retry an optimistic message from its meta line. */
    private fun retryMessage(m: ChatBridge.Message) {
        if (m.id > 0) return // a real, delivered message has nothing to retry
        if (!optimisticIds.contains(m.id)) {
            // It lives in the offline outbox: let the worker have another go.
            SendQueue.schedule(this)
            showSnack("Retrying queued messages…")
            return
        }
        deliveries[m.id] = Delivery.SENDING
        render()
        val key = prefs.getString("pending_reply_key_$conversationId", null)
            ?: UUID.randomUUID().toString()
        lifecycleScope.launch {
            when (
                val result = bridge?.reply(conversationId, m.message, null, key)
                    ?: ChatBridge.ReplyResult.Failed("Not connected")
            ) {
                is ChatBridge.ReplyResult.Sent -> {
                    optimisticIds -= m.id
                    deliveries.remove(m.id)
                    messages.removeAll { it.id == m.id }
                    render()
                    showSnack("Sent")
                }
                is ChatBridge.ReplyResult.Rejected -> {
                    deliveries[m.id] = Delivery.FAILED
                    render()
                    showSnackLong(result.reason)
                }
                is ChatBridge.ReplyResult.Failed -> {
                    deliveries[m.id] = Delivery.QUEUED
                    render()
                    showSnackLong("Still can't reach the server — left in the queue.")
                }
            }
        }
    }

    private var pendingAttachment: File? = null

    /** Chooser for the attach button: camera photo, camera video, or a file. */
    private fun showAttachChooser() {
        val options = listOf(
            AttachOption(R.drawable.ic_camera, getString(R.string.attach_photo)) {
                launchCamera(isVideo = false)
            },
            AttachOption(R.drawable.ic_video, getString(R.string.attach_video)) {
                launchCamera(isVideo = true)
            },
            AttachOption(R.drawable.ic_folder, getString(R.string.attach_file)) {
                pickAttachment.launch("*/*")
            },
        )

        val listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
        }
        listView.adapter = object : ArrayAdapter<AttachOption>(
            this, R.layout.row_attach_option, R.id.attach_label, options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.row_attach_option, parent, false)
                val opt = getItem(position) ?: return v
                v.findViewById<ImageView>(R.id.attach_icon).setImageResource(opt.icon)
                v.findViewById<TextView>(R.id.attach_label).text = opt.label
                return v
            }
        }
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            options[position].action()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.attach_title)
            .setView(listView)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private data class AttachOption(val icon: Int, val label: String, val action: () -> Unit)

    /** Launch the system camera app writing a full-size capture to our cache
     *  dir via a FileProvider Uri. */
    private fun launchCamera(isVideo: Boolean) {
        val intent = if (isVideo) {
            Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        } else {
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        }
        val output = newCameraFile(isVideo)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", output)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        if (isVideo) {
            // Keep recordings bounded so they stay under the 25 MB upload cap.
            intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            cameraOutput = output
            if (isVideo) takeVideo.launch(intent) else takePicture.launch(uri)
        } catch (e: Exception) {
            cameraOutput = null
            output.delete()
            showSnack("Camera unavailable: ${e.message ?: ""}")
        }
    }

    private fun newCameraFile(isVideo: Boolean): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = if (isVideo) "video_$stamp.mp4" else "photo_$stamp.jpg"
        return File(cacheDir, name)
    }

    /**
     * Copy a picked file into the cache.
     *
     * The size is checked BEFORE the copy (so a 2 GB pick is rejected without
     * writing it) and again DURING the copy (so a provider that lies about its
     * size still cannot fill the cache).
     */
    private fun attachFromUri(uri: Uri) {
        try {
            val resolver = contentResolver

            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                    val size = c.getLong(idx)
                    if (size > MAX_ATTACHMENT_BYTES) {
                        showSnack("File too large (max 25 MB).")
                        return
                    }
                }
            }

            val name = URLDecoder.decode(uri.lastPathSegment ?: "attachment", "UTF-8")
            val tmp = File(cacheDir, "attachment_${System.currentTimeMillis()}_${sanitize(name)}")
            var written = 0L

            resolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = ins.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > MAX_ATTACHMENT_BYTES) throw IOException("too large")
                        out.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("could not open the file")

            if (tmp.length() > MAX_ATTACHMENT_BYTES) {
                tmp.delete()
                showSnack("File too large (max 25 MB).")
                return
            }

            pendingAttachment = tmp
            showSnack("Attachment ready — it will be sent with your reply.")
        } catch (e: Exception) {
            showSnack("Could not read attachment: ${e.message ?: ""}")
        }
    }

    private fun sanitize(n: String): String = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    // ------------------------------------------------------------ settings

    /** Per-chat settings: AI mode + member replies + notifications. */
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

        data class Row(val label: String, val action: () -> Unit)

        val rows = listOf(
            Row(
                getString(if (isFav) R.string.settings_fav_on else R.string.settings_fav_off)
            ) {
                Favorites.toggle(this, conversationId)
                showChatSettings()
            },
            Row("Chat mode: ${labels[checked]}") { pickMode(modes, labels, checked, b) },
            Row(
                getString(
                    if (memberReplyEnabled) R.string.settings_replies_on else R.string.settings_replies_off
                )
            ) {
                val target = !memberReplyEnabled
                lifecycleScope.launch {
                    val ok = b.setMemberReply(conversationId, target)
                    if (ok) {
                        memberReplyEnabled = target
                        intent.putExtra("member_reply_enabled", target)
                        updateSubtitle()
                    }
                    showSnack(
                        when {
                            !ok -> "Could not update the reply toggle"
                            target -> "Member replies enabled"
                            else -> "Member replies disabled"
                        }
                    )
                }
            },
            Row(
                getString(
                    if (notifyOn) R.string.settings_notify_on else R.string.settings_notify_off
                )
            ) {
                val on = !notifyOn
                prefs.edit().putBoolean("${chatKey}_notify", on).apply()
                showChatSettings()
            },
            Row(
                getString(
                    if (tone.isEmpty()) R.string.settings_tone_default else R.string.settings_tone_set
                )
            ) { pickChatTone(chatKey) },
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Chat settings")
            .setItems(rows.map { it.label }.toTypedArray()) { _, which -> rows[which].action() }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun pickMode(modes: Array<String>, labels: Array<String>, checked: Int, b: ChatBridge) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Chat mode")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val mode = modes[which]
                lifecycleScope.launch {
                    val ok = b.setMode(conversationId, mode)
                    showSnack(if (ok) "Mode set to ${labels[which]}" else "Mode update failed")
                    if (ok) intent.putExtra("ai_mode", mode)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickChatTone(chatKey: String) {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Chat notification tone")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                Uri.parse(
                    prefs.getString("${chatKey}_tone", null)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION).toString()
                )
            )
        }
        chatToneResult.launch(intent)
    }

    private val chatToneResult = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        if (uri != null) {
            prefs.edit().putString("chat_${conversationId}_tone", uri.toString()).apply()
            showSnack("Notification tone set.")
        }
    }

    // ------------------------------------------------------------ media

    /** Open a full-size attachment in the viewer. */
    private fun openFullImage(m: ChatBridge.Message) {
        val b = bridge ?: return
        lifecycleScope.launch {
            showSnack("Loading…")
            val f = File(cacheDir, "full_${m.id}_${System.currentTimeMillis()}.img")
            val dl = b.download(m.attachmentUrl ?: return@launch, f)
            if (dl == null) {
                showSnack("Could not load the attachment.")
                return@launch
            }
            startActivity(
                Intent(this@ChatActivity, MediaViewerActivity::class.java)
                    .putExtra("path", dl.absolutePath)
            )
        }
    }

    private companion object {
        const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
    }
}
