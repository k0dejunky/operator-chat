package com.amethyst2213.operatorchat

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.amethyst2213.operatorchat.ui.screens.ThreadScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    /** The rows the thread shows (day separators, grouping, delivery state). */
    private var rows by mutableStateOf<List<ChatRow>>(emptyList())

    /** The reply the operator is typing. */
    private var draft by mutableStateOf("")

    /** "The member is typing…" */
    private var typing by mutableStateOf(false)

    /** The outbox strip: the queued replies, or the last rejected one. */
    private var outboxText by mutableStateOf<String?>(null)
    private var outboxActionLabel by mutableStateOf("")

    /** A message for the operator (a failed send, a copied row). */
    private var notice by mutableStateOf<String?>(null)

    /** The search box in the thread header. */
    private var searchOpen by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")

    /** The ids of the pinned messages. */
    private var pinnedIds by mutableStateOf<Set<Long>>(emptySet())

    /** The AI mode of this conversation. */
    private var aiMode by mutableStateOf("operator")

    /** The member's name, for the header. */
    private var memberName by mutableStateOf("")

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

    /**
     * The idempotency key each in-flight optimistic row was sent with. Retrying
     * the SAME attempt must reuse its key (so the server can dedupe a reply the
     * network dropped after storing); a brand new message must always carry a
     * fresh one (see [resolveSent] for why reuse loses messages).
     */
    private val attemptKeys = mutableMapOf<Long, String>()

    private var streamJob: Job? = null

    /** "Member is typing…" signal with client-side expiry. */
    private val typingPulse = TypingPulse()
    private val typingHandler = Handler(Looper.getMainLooper())
    private val typingRecheck = object : Runnable {
        override fun run() {
            val active = typingPulse.isActive(SystemClock.elapsedRealtime())
            renderTyping(active)
            if (active) typingHandler.postDelayed(this, 2000L)
        }
    }

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
                // Full-res captures are downscaled + EXIF-rotated off the main
                // thread before they become the pending attachment.
                lifecycleScope.launch {
                    val ready = withContext(Dispatchers.IO) { preparedImage(file) }
                    if (ready.length() > MAX_ATTACHMENT_BYTES) {
                        ready.delete()
                        showSnack("Photo too large (max 25 MB) — try a lower resolution.")
                        return@launch
                    }
                    pendingAttachment = ready
                    showSnack("Photo ready — it will be sent with your reply.")
                }
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

        conversationId = intent.getLongExtra("conversation_id", 0)
        memberReplyEnabled = intent.getBooleanExtra("member_reply_enabled", true)
        aiMode = intent.getStringExtra("ai_mode") ?: "operator"
        memberName = intent.getStringExtra("username").orEmpty()

        draft = prefs.getString("draft_$conversationId", "").orEmpty()

        val url = intent.getStringExtra("base_url") ?: ""
        val token = intent.getStringExtra("token") ?: SecurePrefs.token(this)
        bridge = ChatBridge(url, token)

        setContent {
            OperatorTheme {
                ThreadScreen(
                    conversationId = conversationId,
                    title = memberName.ifBlank { "Conversation #$conversationId" },
                    subtitle = getString(
                        if (memberReplyEnabled) R.string.chat_replies_on else R.string.chat_replies_off
                    ),
                    rows = rows,
                    draft = draft,
                    sending = false,
                    queued = SendQueue.pendingFor(this@ChatActivity, conversationId).size,
                    typing = typing,
                    canReply = true,
                    memberReplyEnabled = memberReplyEnabled,
                    aiMode = aiMode,
                    pinnedIds = pinnedIds,
                    searchOpen = searchOpen,
                    searchQuery = searchQuery,
                    error = notice,
                    onDraftChange = { draft = it },
                    onSend = { sendReply() },
                    onAttach = { showAttachChooser() },
                    onToggleSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen) searchQuery = ""
                    },
                    onSearchQueryChange = { searchQuery = it },
                    onPin = { id -> pinMessage(id, true) },
                    onUnpin = { id -> pinMessage(id, false) },
                    onDelete = { id -> deleteMessage(id) },
                    onCopy = { id -> copyMessage(id) },
                    onToggleMemberReplies = { on -> toggleMemberReplies(on) },
                    onModeChange = { mode -> changeAiMode(mode) },
                    onLoadOlder = { loadOlder() },
                    onBack = { finish() },
                    outboxText = outboxText,
                    outboxActionLabel = outboxActionLabel,
                    onOutboxAction = { onOutboxAction() },
                )
            }
        }

        syncOutbox()
        render()
        loadPinned()
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
        typingPulse.clear()
        typingHandler.removeCallbacks(typingRecheck)
        renderTyping(false)
        super.onStop()
    }

    override fun onPause() {
        // Persist the draft once instead of a SharedPreferences write per
        // keystroke.
        prefs.edit()
            .putString("draft_$conversationId", draft)
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
        rows = ChatRows.build(messages, deliveries, siteZone)
    }

    /**
     * The thread screen owns the scroll position, so this only makes sure
     * the newest message is the one the operator sees.
     */
    private fun scrollToBottom() {
        wasAtBottom = true
    }

    private fun hideKeyboard() {
        // The Compose composer hides the keyboard itself.
    }

    private fun showSnack(message: String) {
        notice = message
    }

    private fun showSnackLong(message: String) {
        notice = message
    }

    // -------------------------------------------------------------- outbox

    /** Synthetic thread rows for queued replies, keyed by their outbox key. */
    private val outboxRows = mutableMapOf<String, Long>()

    /** Synthetic ids sit above any real server id so they sort last. */
    private var nextOutboxId = Long.MAX_VALUE - 1

    /**
     * Mirror this conversation's outbox into the UI: one QUEUED row per queued
     * reply, plus the strip above the thread ("N replies queued" — or, when the
     * queue is empty, the last rejected reply so its loss is never silent).
     *
     * A reply still represented by an optimistic row is not duplicated. A reply
     * whose entry left the queue (delivered, rejected, discarded) loses its
     * synthetic row; the stream calls this after every batch, so a delivered
     * reply swaps cleanly from its synthetic row to the server's real copy.
     */
    private fun syncOutbox() {
        val pending = SendQueue.pendingFor(this, conversationId)

        // Drop synthetic rows whose queue entry is gone.
        val liveKeys = pending.map { it.key }.toSet()
        val stale = outboxRows.filterKeys { it !in liveKeys }.values.toSet()
        if (stale.isNotEmpty()) {
            outboxRows.entries.removeAll { it.value in stale }
            messages.removeAll { it.id in stale }
            stale.forEach { deliveries.remove(it) }
        }

        // Add a row for each entry that has no on-screen row yet.
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(siteZone)
        }
        for (p in pending.sortedBy { it.at }) {
            if (outboxRows.containsKey(p.key)) continue
            val shownByOptimistic = attemptKeys.any { (pid, key) ->
                key == p.key && messages.any { it.id == pid }
            }
            if (shownByOptimistic) continue

            val id = nextOutboxId--
            outboxRows[p.key] = id
            deliveries[id] = Delivery.QUEUED
            messages += ChatBridge.Message(
                id = id,
                conversationId = conversationId,
                senderRole = "operator",
                message = p.message.ifBlank { getString(R.string.outbox_attachment) },
                createdAt = fmt.format(Date(if (p.at > 0) p.at else System.currentTimeMillis())),
                attachmentName = null,
                attachmentType = null,
                attachmentUrl = null,
                attachmentThumbUrl = null,
                expiresAt = null,
                maxViews = 0,
                viewCount = 0,
                mediaExpired = false,
            )
        }

        val drop = SendQueue.lastDrop(this)
        outboxText = when {
            pending.isNotEmpty() -> if (pending.size == 1) {
                getString(R.string.outbox_queued_one)
            } else {
                getString(R.string.outbox_queued_many, pending.size)
            }
            drop != null -> getString(R.string.outbox_dropped, preview(drop.message))
            else -> null
        }
        outboxActionLabel = getString(
            if (pending.isNotEmpty()) R.string.outbox_review else R.string.outbox_dismiss_notice
        )
        render()
    }

    /** What the strip's button does: review the queue, or dismiss the notice. */
    private fun onOutboxAction() {
        if (SendQueue.pendingFor(this, conversationId).isEmpty()) {
            SendQueue.clearDrop(this)
            syncOutbox()
        } else {
            showOutboxDialog()
        }
    }

    private fun preview(text: String): String {
        val body = text.ifBlank { getString(R.string.outbox_attachment) }
        return if (body.length > 60) body.take(60) + "…" else body
    }

    private fun showOutboxDialog() {
        val sorted = SendQueue.pendingFor(this, conversationId).sortedBy { it.at }
        val drop = SendQueue.lastDrop(this)
        if (sorted.isEmpty() && drop == null) return

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone(siteZone)
        }
        val labels = sorted.map { p ->
            val stamp = if (p.at > 0) "  (${timeFmt.format(Date(p.at))})" else ""
            preview(p.message) + stamp
        }.toTypedArray()

        val builder = MaterialAlertDialogBuilder(this).setTitle(R.string.outbox_title)
        if (labels.isNotEmpty()) {
            builder.setItems(labels) { _, which -> pendingItemActions(sorted[which]) }
        }
        if (drop != null) {
            builder.setMessage(getString(R.string.outbox_dropped, drop.reason))
        }
        builder.setNeutralButton(R.string.outbox_close, null)
        builder.show()
    }

    /** Per-item dialog: retry it now, or discard it for good. */
    private fun pendingItemActions(p: SendQueue.Pending) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone(siteZone)
        }
        val whenText = if (p.at > 0) timeFmt.format(Date(p.at)) else "—"
        MaterialAlertDialogBuilder(this)
            .setTitle(preview(p.message))
            .setMessage("Queued at $whenText")
            .setPositiveButton(R.string.outbox_retry) { _, _ ->
                SendQueue.schedule(this)
                showSnack(getString(R.string.outbox_retrying))
                syncOutbox()
            }
            .setNegativeButton(R.string.outbox_discard) { _, _ -> discardPending(p) }
            .setNeutralButton(R.string.outbox_close, null)
            .show()
    }

    private fun discardPending(p: SendQueue.Pending) {
        SendQueue.remove(this, p.key)
        // Drop the optimistic row of this session, if there is one.
        attemptKeys.entries.firstOrNull { it.value == p.key }?.let { (pid, _) ->
            attemptKeys.remove(pid)
            optimisticIds -= pid
            deliveries.remove(pid)
            messages.removeAll { it.id == pid }
        }
        // …and the synthetic row, if the queue entry was mirrored this session.
        outboxRows.remove(p.key)?.let { id ->
            messages.removeAll { it.id == id }
            deliveries.remove(id)
        }
        showSnack(getString(R.string.outbox_discarded))
        syncOutbox()
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
                optimisticIds.clear()
                deliveries.clear()
                attemptKeys.clear()
                outboxRows.clear()
                messages.addAll(hist.sortedBy { it.id })
                latestId = hist.maxOfOrNull { it.id } ?: 0L
                oldestId = hist.minOfOrNull { it.id } ?: 0L
                hasMore = hist.size >= 50
                wasAtBottom = true
                syncOutbox()
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
                    val frame = b.streamOnce(conversationId, latestId)
                    val fresh = frame.messages.filter {
                        it.id > latestId && messages.none { m -> m.id == it.id }
                    }
                    if (fresh.isNotEmpty()) collectIncoming(fresh)
                    frame.typing?.let { applyTyping(it) }
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

    /** Apply a typing frame from the stream to the indicator above the composer. */
    private fun applyTyping(typing: Boolean) {
        val active = typingPulse.onSignal(typing, SystemClock.elapsedRealtime())
        renderTyping(active)
        typingHandler.removeCallbacks(typingRecheck)
        if (active) typingHandler.postDelayed(typingRecheck, 2000L)
    }

    private fun renderTyping(active: Boolean) {
        typing = active
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
        syncOutbox()
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
                    val merged = (older + messages).distinctBy { it.id }.sortedBy { it.id }
                    messages.clear()
                    messages.addAll(merged)
                    oldestId = older.minOf { it.id }
                    hasMore = more
                    wasAtBottom = false
                    render()
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

    // ------------------------------------------------- the operator's tools

    /** The bridge for the new operator endpoints (pin, delete, block). */
    private fun tools(): LiveToolsBridge {
        val url = intent.getStringExtra("base_url") ?: SecurePrefs.url(this)
        val token = intent.getStringExtra("token") ?: SecurePrefs.token(this)
        return LiveToolsBridge(url, token)
    }

    /** The messages this operator has pinned in this conversation. */
    private fun loadPinned() {
        lifecycleScope.launch {
            try {
                pinnedIds = tools().pinnedMessages(conversationId).toSet()
            } catch (_: Exception) {
                pinnedIds = emptySet()
            }
        }
    }

    /**
     * Pin or unpin a message.
     *
     * A pinned message stays at the top of the thread: it is how the
     * operator marks the answer everybody should be able to find.
     */
    private fun pinMessage(id: Long, pinned: Boolean) {
        lifecycleScope.launch {
            val ok = try {
                tools().messageAction(if (pinned) "pin" else "unpin", id, conversationId)
            } catch (e: Exception) {
                showSnack("The message could not be pinned: ${e.message}")
                return@launch
            }
            if (ok) {
                pinnedIds = if (pinned) pinnedIds + id else pinnedIds - id
                showSnack(if (pinned) "Pinned." else "Unpinned.")
            } else {
                showSnack("The message could not be pinned.")
            }
        }
    }

    /** Delete a message (the server keeps the moderation log). */
    private fun deleteMessage(id: Long) {
        lifecycleScope.launch {
            val ok = try {
                tools().messageAction("delete", id, conversationId)
            } catch (e: Exception) {
                showSnack("The message could not be deleted: ${e.message}")
                return@launch
            }
            if (ok) {
                messages.removeAll { it.id == id }
                deliveries.remove(id)
                render()
                showSnack("Deleted.")
            } else {
                showSnack("The message could not be deleted.")
            }
        }
    }

    /** Copy a message to the clipboard. */
    private fun copyMessage(id: Long) {
        val message = messages.firstOrNull { it.id == id } ?: return
        val clip = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        clip.setPrimaryClip(android.content.ClipData.newPlainText("message", message.message))
        showSnack("Copied.")
    }

    /** Let (or stop) the member's own replies. */
    private fun toggleMemberReplies(enabled: Boolean) {
        val b = bridge ?: return
        lifecycleScope.launch {
            try {
                val ok = b.setMemberReply(conversationId, enabled)
                if (ok) {
                    memberReplyEnabled = enabled
                    showSnack(if (enabled) "The member can reply now." else "The member cannot reply now.")
                } else {
                    showSnack("That could not be changed.")
                }
            } catch (e: Exception) {
                showSnack("That could not be changed: ${e.message}")
            }
        }
    }

    /** Switch the conversation between the operator and the AI. */
    private fun changeAiMode(mode: String) {
        val b = bridge ?: return
        lifecycleScope.launch {
            try {
                val ok = b.setMode(conversationId, mode)
                if (ok) {
                    aiMode = mode
                    showSnack("AI mode: $mode")
                } else {
                    showSnack("The mode could not be changed.")
                }
            } catch (e: Exception) {
                showSnack("The mode could not be changed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------ sending

    private fun sendReply() {
        val text = draft.trim()
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

        // A FRESH key for every new message. A key persisted from an earlier
        // attempt must never be reused here: the server answers idempotently
        // with the OLD message's id, which would make the confirmation swap a
        // duplicate of the old message in place of this text (silent loss).
        // Only a retry of the same attempt reuses its key, and those live in
        // [attemptKeys] / the outbox entry.
        val idempotencyKey = SendReconcile.newKey()

        draft = ""
        prefs.edit().remove("draft_$conversationId").apply()
        pendingAttachment = null
        hideKeyboard()

        // Optimistic row. It keeps its negative id until the server confirms and
        // is dropped the moment the real message arrives on the stream.
        val placeholderId = --pendingPlaceholderId
        optimisticIds += placeholderId
        attemptKeys[placeholderId] = idempotencyKey
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
                    val outcome = resolveSent(
                        result.id, placeholderId, text, pending, expiresInMinutes, maxViews,
                    )
                    SendQueue.remove(this@ChatActivity, idempotencyKey)
                    wasAtBottom = true
                    render()
                    if (outcome == SendReconcile.Outcome.QUEUED) {
                        showSnackLong("Not confirmed by the server — queued and will be delivered automatically.")
                    } else {
                        showSnack("Sent")
                    }
                }

                is ChatBridge.ReplyResult.Rejected -> {
                    // The server will never accept this: drop the placeholder
                    // and say why, instead of queueing it forever.
                    optimisticIds -= placeholderId
                    deliveries.remove(placeholderId)
                    attemptKeys.remove(placeholderId)
                    messages.removeAll { it.id == placeholderId }
                    render()
                    showSnackLong(result.reason)
                }

                is ChatBridge.ReplyResult.Failed -> {
                    // Queue it for automatic delivery with backoff. The stash
                    // copies the file, so it goes to IO, never the main thread.
                    deliveries[placeholderId] = Delivery.QUEUED
                    val stashed = pending?.let {
                        withContext(Dispatchers.IO) {
                            SendQueue.stashAttachment(this@ChatActivity, it, idempotencyKey)
                        }
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

    /**
     * Reconciles a `Sent` result with the optimistic row [placeholderId].
     *
     * The server answers IDEMPOTENTLY when a key was used before, and that
     * answer carries the OLD message's id. Swapping it in would silently
     * replace this message with a duplicate of an earlier one, so
     * [SendReconcile.classify] only accepts the server's copy when its text
     * really is this message; on a mismatch the text goes to the outbox under a
     * FRESH key so it is delivered for real. When the copy cannot be confirmed
     * the row is dropped WITHOUT advancing latestId, so the SSE stream
     * re-fetches this id and renders the server's real copy.
     */
    private suspend fun resolveSent(
        sentId: Long,
        placeholderId: Long,
        text: String,
        attachment: File?,
        expiresInMinutes: Int,
        maxViews: Int,
    ): SendReconcile.Outcome {
        val idx = messages.indexOfFirst { it.id == placeholderId }
        var fetched: ChatBridge.Message? = null
        var fetchFailed = false
        try {
            if (sentId > 0) {
                fetched = bridge?.history(conversationId, sentId + 1, 1)?.first
                    ?.firstOrNull { it.id == sentId }
            }
        } catch (_: Exception) {
            fetchFailed = true
        }

        return when (SendReconcile.classify(sentId, fetched, fetchFailed, text)) {
            SendReconcile.Outcome.CONFIRMED -> {
                optimisticIds -= placeholderId
                deliveries.remove(placeholderId)
                attemptKeys.remove(placeholderId)
                latestId = maxOf(latestId, sentId)
                if (fetched != null) {
                    // A thread reload can have cleared the optimistic row; in
                    // that case insert the server copy instead of swapping.
                    if (idx >= 0) messages[idx] = fetched else messages += fetched
                }
                SendReconcile.Outcome.CONFIRMED
            }
            SendReconcile.Outcome.QUEUED -> {
                // The key answered for an OLDER message, so this text was never
                // stored. Deliver it for real under a fresh key.
                queueUnconfirmed(placeholderId, text, attachment, expiresInMinutes, maxViews)
                SendReconcile.Outcome.QUEUED
            }
            SendReconcile.Outcome.UNCONFIRMED -> {
                optimisticIds -= placeholderId
                deliveries.remove(placeholderId)
                attemptKeys.remove(placeholderId)
                if (idx >= 0) messages.removeAt(idx)
                SendReconcile.Outcome.UNCONFIRMED
            }
        }
    }

    /**
     * Puts [text] in the outbox under a FRESH idempotency key and keeps its row
     * visible. The row stays in [optimisticIds], so when the worker delivers it
     * and the stream carries the real message, [collectIncoming] drops the
     * placeholder by its matching text.
     */
    private suspend fun queueUnconfirmed(
        placeholderId: Long,
        text: String,
        attachment: File?,
        expiresInMinutes: Int,
        maxViews: Int,
    ) {
        val key = SendReconcile.newKey()
        // The stash copies the file into filesDir — never on the main thread.
        val stashed = attachment?.let {
            withContext(Dispatchers.IO) { SendQueue.stashAttachment(this@ChatActivity, it, key) }
        }
        SendQueue.add(this, conversationId, text, key, stashed, expiresInMinutes, maxViews)
        attemptKeys[placeholderId] = key
        deliveries[placeholderId] = Delivery.QUEUED
        SendQueue.schedule(this)
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
        // Retry the SAME attempt: reuse its key so a reply the network dropped
        // after the server stored it is deduped, not duplicated.
        val key = attemptKeys[m.id] ?: SendReconcile.newKey().also {
            attemptKeys[m.id] = it
        }
        lifecycleScope.launch {
            when (
                val result = bridge?.reply(conversationId, m.message, null, key)
                    ?: ChatBridge.ReplyResult.Failed("Not connected")
            ) {
                is ChatBridge.ReplyResult.Sent -> {
                    val outcome = resolveSent(result.id, m.id, m.message, null, 0, 0)
                    render()
                    if (outcome == SendReconcile.Outcome.QUEUED) {
                        showSnackLong("Not confirmed by the server — queued and will be delivered automatically.")
                    } else {
                        showSnack("Sent")
                    }
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
     * Copy a picked file into the cache, then shrink large photos.
     *
     * All of this is disk IO (up to 25 MB + a decode/encode pass), so it runs
     * on [Dispatchers.IO]; the size is checked BEFORE the copy (so a 2 GB pick
     * is rejected without writing it) and again DURING the copy (so a provider
     * that lies about its size still cannot fill the cache).
     */
    private fun attachFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { importAttachment(uri) }
                pendingAttachment = file
                showSnack("Attachment ready — it will be sent with your reply.")
            } catch (_: AttachmentTooLargeException) {
                showSnack("File too large (max 25 MB).")
            } catch (e: Exception) {
                showSnack("Could not read attachment: ${e.message ?: ""}")
            }
        }
    }

    private class AttachmentTooLargeException : IOException("too large")

    /** Blocking import: size-check, copy into the cache, compress large photos. */
    private fun importAttachment(uri: Uri): File {
        val resolver = contentResolver

        resolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) {
                if (c.getLong(idx) > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()
            }
        }

        val name = URLDecoder.decode(uri.lastPathSegment ?: "attachment", "UTF-8")
        var tmp = File(cacheDir, "attachment_${System.currentTimeMillis()}_${sanitize(name)}")
        var written = 0L

        resolver.openInputStream(uri)?.use { ins ->
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = ins.read(buffer)
                    if (read <= 0) break
                    written += read
                    if (written > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()
                    out.write(buffer, 0, read)
                }
            }
        } ?: run {
            tmp.delete()
            throw IOException("could not open the file")
        }

        tmp = preparedImage(tmp)
        if (tmp.length() > MAX_ATTACHMENT_BYTES) {
            tmp.delete()
            throw AttachmentTooLargeException()
        }
        return tmp
    }

    /**
     * Downscale + EXIF-rotate a large photo in place (returns a new cache file;
     * the original is deleted). Non-images and small already-optimized files
     * come back untouched. Blocking — call from a background dispatcher.
     */
    private fun preparedImage(source: File): File {
        val mime = MediaSniff.typeOf(source)
        if (!ImagePrep.shouldCompress(mime, source.length())) return source
        val dest = File(cacheDir, "attachment_${System.currentTimeMillis()}_photo.jpg")
        return if (ImagePrep.compressToJpeg(source, dest)) {
            source.delete()
            dest
        } else {
            dest.delete()
            source
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
