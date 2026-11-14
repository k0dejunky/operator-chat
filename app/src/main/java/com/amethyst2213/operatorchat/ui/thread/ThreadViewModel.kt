package com.amethyst2213.operatorchat.ui.thread

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amethyst2213.operatorchat.ChatBridge
import com.amethyst2213.operatorchat.ChatRow
import com.amethyst2213.operatorchat.Delivery
import com.amethyst2213.operatorchat.Favorites
import com.amethyst2213.operatorchat.ImagePrep
import com.amethyst2213.operatorchat.LiveToolsBridge
import com.amethyst2213.operatorchat.MediaSniff
import com.amethyst2213.operatorchat.SecurePrefs
import com.amethyst2213.operatorchat.SendQueue
import com.amethyst2213.operatorchat.SendReconcile
import com.amethyst2213.operatorchat.SiteZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One conversation. Loads the latest messages, streams new ones, sends with
 * idempotency + an offline outbox, and owns the per-message actions.
 */
class ThreadViewModel(
    app: Application,
    val conversationId: Long,
    initialName: String,
    initialMode: String,
    initialReplies: Boolean,
) : AndroidViewModel(app) {

    data class Pending(
        val path: String,
        val name: String,
        val mime: String,
        val bytes: Long,
        val image: Boolean,
    )

    data class Ui(
        val messages: List<ChatBridge.Message> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val typing: Boolean = false,
        val hasMore: Boolean = false,
        val loadingOlder: Boolean = false,
        val name: String = "",
        val aiMode: String = "operator",
        val replies: Boolean = true,
        val favourite: Boolean = false,
        val notifications: Boolean = true,
        val tone: String? = null,
        val pending: Pending? = null,
        val outboxCount: Int = 0,
        val outboxNotice: String? = null,
        val sending: Boolean = false,
        val uploading: Int = -1,
        val pinned: Set<Long> = emptySet(),
        val snack: String? = null,
        val connectionLost: Boolean = false,
        val deliveries: Map<Long, Delivery> = emptyMap(),
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state.asStateFlow()

    private val media = MutableSharedFlow<String>(extraBufferCapacity = 2)
    /** A local file ready for MediaViewerActivity. */
    val mediaPath: SharedFlow<String> = media.asSharedFlow()

    private val bridge: ChatBridge?
        get() {
            val url = SecurePrefs.url(getApplication()).trim()
            val token = SecurePrefs.token(getApplication()).trim()
            return if (url.isBlank() || token.isBlank()) null else ChatBridge(url, token)
        }
    private val tools: LiveToolsBridge?
        get() {
            val url = SecurePrefs.url(getApplication()).trim()
            val token = SecurePrefs.token(getApplication()).trim()
            return if (url.isBlank() || token.isBlank()) null else LiveToolsBridge(url, token)
        }

    private var latestId = 0L
    private var oldestId = 0L
    private var streamJob: Job? = null
    private var started = false
    private var nextPlaceholder = -1L
    private var nextOutboxId = Long.MAX_VALUE - 1L

    private val deliveries = mutableMapOf<Long, Delivery>()
    private val optimisticIds = mutableSetOf<Long>()
    private val attemptKeys = mutableMapOf<Long, String>()
    private val outboxRows = mutableMapOf<String, Long>()

    private val prefs
        get() = getApplication<Application>()
            .getSharedPreferences("operator_chat", Context.MODE_PRIVATE)

    init {
        _state.value = _state.value.copy(
            name = initialName,
            aiMode = initialMode,
            replies = initialReplies,
            favourite = Favorites.isFavorite(getApplication(), conversationId),
            notifications = prefs.getBoolean("chat_${conversationId}_notify", true),
            tone = prefs.getString("chat_${conversationId}_tone", null),
        )
        load()
        loadPinned()
    }

    fun start() {
        if (started) return
        started = true
        streamJob = viewModelScope.launch {
            var backoff = 1500L
            while (true) {
                val b = bridge ?: break
                try {
                    val frame = b.streamOnce(conversationId, latestId)
                    collectIncoming(frame.messages.filter { it.id > latestId })
                    _state.value = _state.value.copy(typing = frame.typing == true, connectionLost = false)
                    backoff = 1500L
                    delay(600)
                } catch (_: Exception) {
                    _state.value = _state.value.copy(typing = false, connectionLost = true)
                    delay(backoff + (0..1000).random())
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        started = false
    }

    private fun load() {
        val b = bridge ?: run {
            _state.value = _state.value.copy(loading = false, error = "No connection configured.")
            return
        }
        viewModelScope.launch {
            try {
                val messages = b.thread(conversationId).sortedBy { it.id }
                latestId = messages.maxOfOrNull { it.id } ?: 0L
                oldestId = messages.minOfOrNull { it.id } ?: 0L
                deliveries.clear()
                optimisticIds.clear()
                attemptKeys.clear()
                outboxRows.clear()
                _state.value = _state.value.copy(
                    messages = messages,
                    loading = false,
                    error = null,
                    hasMore = messages.size >= 50,
                    connectionLost = false,
                )
                syncOutbox()
                b.setRead(conversationId, latestId)
                SendQueue.schedule(getApplication())
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "Could not load the conversation. ${e.message ?: ""}".trim(),
                )
            }
        }
    }

    fun reload() {
        _state.value = _state.value.copy(loading = true, error = null)
        load()
    }

    fun loadOlder() {
        val s = _state.value
        if (s.loadingOlder || !s.hasMore || oldestId <= 0) return
        val b = bridge ?: return
        _state.value = s.copy(loadingOlder = true)
        viewModelScope.launch {
            try {
                val (older, more) = b.history(conversationId, oldestId, 50)
                if (older.isNotEmpty()) {
                    oldestId = older.minOf { it.id }
                }
                _state.value = _state.value.copy(
                    messages = (_state.value.messages + older).distinctBy { it.id }.sortedBy { it.id },
                    loadingOlder = false,
                    hasMore = more,
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(loadingOlder = false)
            }
        }
    }

    /** Send the composer's text (and the pending attachment, if any). */
    fun send(text: String, expiresInMinutes: Int = 0, maxViews: Int = 0) {
        val b = bridge ?: return
        val attachment = _state.value.pending?.let { File(it.path) }
        if (text.isBlank() && attachment == null) return

        val key = SendReconcile.newKey()
        val placeholderId = nextPlaceholder--
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(SiteZone.read(getApplication()))
        }.format(Date())

        optimisticIds += placeholderId
        attemptKeys[placeholderId] = key
        deliveries[placeholderId] = Delivery.SENDING
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatBridge.Message(
                id = placeholderId,
                conversationId = conversationId,
                senderRole = "operator",
                message = text,
                createdAt = now,
                attachmentName = attachment?.name,
                attachmentType = null,
                attachmentUrl = null,
                attachmentThumbUrl = null,
                expiresAt = null,
                maxViews = maxViews,
                viewCount = 0,
                mediaExpired = false,
            ),
            pending = null,
            sending = true,
            uploading = if (attachment != null) 0 else -1,
            snack = null,
            deliveries = deliveries.toMap(),
        )

        viewModelScope.launch {
            val result = b.reply(
                conversationId = conversationId,
                message = text,
                attachment = attachment,
                idempotencyKey = key,
                onProgress = { p ->
                    _state.value = _state.value.copy(uploading = (p * 100).toInt())
                },
                expiresInMinutes = expiresInMinutes,
                maxViews = maxViews,
            )
            _state.value = _state.value.copy(sending = false, uploading = -1)
            when (result) {
                is ChatBridge.ReplyResult.Sent -> {
                    val outcome = resolveSent(result.id, placeholderId, text, attachment)
                    SendQueue.remove(getApplication(), key)
                    when (outcome) {
                        SendReconcile.Outcome.CONFIRMED -> setSnack("Sent")
                        SendReconcile.Outcome.QUEUED -> setSnack("Not confirmed — queued and will be delivered.")
                        SendReconcile.Outcome.UNCONFIRMED -> setSnack("Waiting for the server copy…")
                    }
                }
                is ChatBridge.ReplyResult.Rejected -> {
                    optimisticIds -= placeholderId
                    attemptKeys.remove(placeholderId)
                    deliveries.remove(placeholderId)
                    _state.value = _state.value.copy(
                        messages = _state.value.messages.filterNot { it.id == placeholderId },
                        snack = result.reason,
                        deliveries = deliveries.toMap(),
                    )
                }
                is ChatBridge.ReplyResult.Failed -> {
                    queue(placeholderId, text, attachment, key, 0, 0)
                    setSnack("Not sent — queued and will be delivered automatically.")
                }
            }
            syncOutbox()
        }
    }

    private suspend fun resolveSent(
        sentId: Long,
        placeholderId: Long,
        text: String,
        attachment: File?,
    ): SendReconcile.Outcome {
        val b = bridge ?: return SendReconcile.Outcome.UNCONFIRMED
        var fetched: ChatBridge.Message? = null
        var failed = false
        try {
            if (sentId > 0) {
                fetched = b.history(conversationId, sentId + 1, 1).first.firstOrNull { it.id == sentId }
            }
        } catch (_: Exception) {
            failed = true
        }
        return when (val outcome = SendReconcile.classify(sentId, fetched, failed, text)) {
            SendReconcile.Outcome.CONFIRMED -> {
                optimisticIds -= placeholderId
                attemptKeys.remove(placeholderId)
                deliveries.remove(placeholderId)
                latestId = maxOf(latestId, sentId)
                if (fetched != null) {
                    val list = _state.value.messages.toMutableList()
                    val index = list.indexOfFirst { it.id == placeholderId }
                    if (index >= 0) list[index] = fetched else list += fetched
                    _state.value = _state.value.copy(messages = list.sortedBy { it.id })
                }
                outcome
            }
            SendReconcile.Outcome.QUEUED -> {
                queue(placeholderId, text, attachment, SendReconcile.newKey(), 0, 0)
                outcome
            }
            SendReconcile.Outcome.UNCONFIRMED -> {
                optimisticIds -= placeholderId
                attemptKeys.remove(placeholderId)
                deliveries.remove(placeholderId)
                _state.value = _state.value.copy(
                    messages = _state.value.messages.filterNot { it.id == placeholderId },
                )
                outcome
            }
        }
    }

    private suspend fun queue(
        placeholderId: Long,
        text: String,
        attachment: File?,
        key: String,
        expiresInMinutes: Int,
        maxViews: Int,
    ) {
        val stashed = attachment?.let {
            withContext(Dispatchers.IO) { SendQueue.stashAttachment(getApplication(), it, key) }
        }
        SendQueue.add(getApplication(), conversationId, text, key, stashed, expiresInMinutes, maxViews)
        attemptKeys[placeholderId] = key
        deliveries[placeholderId] = Delivery.QUEUED
        SendQueue.schedule(getApplication())
    }

    private fun collectIncoming(fresh: List<ChatBridge.Message>) {
        if (fresh.isEmpty()) return
        // Drop optimistic rows the server has now confirmed by text, so a
        // message can never appear twice when the stream wins the race.
        val confirmed = fresh.filter { real ->
            optimisticIds.any { pid ->
                _state.value.messages.firstOrNull { it.id == pid }?.let { opt ->
                    opt.senderRole.equals(real.senderRole, true) && opt.message == real.message
                } ?: false
            }
        }
        if (confirmed.isNotEmpty()) {
            optimisticIds.removeAll { pid ->
                val opt = _state.value.messages.firstOrNull { it.id == pid } ?: return@removeAll false
                confirmed.any { it.senderRole.equals(opt.senderRole, true) && it.message == opt.message }
            }
            optimisticIds.forEach { deliveries.remove(it) }
        }
        latestId = maxOf(latestId, fresh.maxOf { it.id })
        val merged = (_state.value.messages.filter { it.id > 0 } + fresh)
            .distinctBy { it.id }
            .sortedBy { it.id }
        val stillOptimistic = _state.value.messages.filter {
            it.id < 0 && optimisticIds.contains(it.id)
        }
        _state.value = _state.value.copy(
            messages = (merged + stillOptimistic).sortedBy { it.id },
        )
        syncOutbox()
        viewModelScope.launch { bridge?.setRead(conversationId, latestId) }
    }

    /**
     * Mirror the offline outbox into the thread: one synthetic row per queued
     * reply, kept until its entry leaves the queue.
     */
    private fun syncOutbox() {
        val pending = SendQueue.pendingFor(getApplication(), conversationId)
        val liveKeys = pending.map { it.key }.toSet()
        val staleIds = outboxRows.filterKeys { it !in liveKeys }.values.toSet()
        var messages = _state.value.messages.filterNot { staleIds.contains(it.id) }
        outboxRows.keys.retainAll(liveKeys)
        staleIds.forEach { deliveries.remove(it) }

        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(SiteZone.read(getApplication()))
        }
        for (p in pending.sortedBy { it.at }) {
            if (outboxRows.containsKey(p.key)) continue
            val id = nextOutboxId--
            outboxRows[p.key] = id
            deliveries[id] = Delivery.QUEUED
            messages += ChatBridge.Message(
                id = id,
                conversationId = conversationId,
                senderRole = "operator",
                message = p.message.ifBlank { "(attachment)" },
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
        val drop = SendQueue.lastDrop(getApplication())
        _state.value = _state.value.copy(
            messages = messages.sortedBy { it.id },
            outboxCount = pending.size,
            outboxNotice = if (pending.isEmpty() && drop != null) drop.message else null,
            deliveries = deliveries.toMap(),
        )
    }

    fun retry(message: ChatBridge.Message) {
        if (message.id >= 0) return
        if (attemptKeys.containsKey(message.id)) {
            SendQueue.schedule(getApplication())
            setSnack("Retrying queued replies…")
            return
        }
        SendQueue.schedule(getApplication())
        setSnack("Retrying queued replies…")
    }

    fun retryOutbox() {
        SendQueue.schedule(getApplication())
        setSnack("Retrying queued replies…")
    }

    fun dismissOutboxNotice() {
        SendQueue.clearDrop(getApplication())
        syncOutbox()
    }

    fun discardOutbox(key: String) {
        SendQueue.remove(getApplication(), key)
        syncOutbox()
    }

    fun outboxItems(): List<SendQueue.Pending> =
        SendQueue.pendingFor(getApplication(), conversationId).sortedBy { it.at }

    fun prepareAttachment(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val resolver = app.contentResolver
            var name = "attachment"
            var size = -1L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
            if (size > MAX_BYTES) {
                setSnack("That file is larger than 25 MB.")
                return@launch
            }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "attachment" }
            val dir = File(app.cacheDir, "outgoing").apply { mkdirs() }
            val dest = File(dir, "${System.currentTimeMillis()}_$safe")
            val copied = try {
                resolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                if (dest.isFile && dest.length() > 0) dest else null
            } catch (_: Exception) {
                null
            }
            if (copied == null) {
                setSnack("That file could not be read.")
                return@launch
            }
            if (copied.length() > MAX_BYTES) {
                copied.delete()
                setSnack("That file is larger than 25 MB.")
                return@launch
            }
            val mime = MediaSniff.typeOf(copied)
            val isImage = mime.startsWith("image/")
            var finalFile = copied
            if (isImage && ImagePrep.shouldCompress(mime, copied.length())) {
                val jpeg = File(dir, "${copied.nameWithoutExtension}_prepared.jpg")
                if (ImagePrep.compressToJpeg(copied, jpeg) && jpeg.isFile) {
                    copied.delete()
                    finalFile = jpeg
                }
            }
            val finalMime = if (finalFile.name.endsWith(".jpg")) "image/jpeg" else mime
            _state.value = _state.value.copy(
                pending = Pending(
                    path = finalFile.absolutePath,
                    name = if (finalFile == copied) name else name.replaceAfterLast('.', "jpg"),
                    mime = finalMime,
                    bytes = finalFile.length(),
                    image = finalMime.startsWith("image/"),
                ),
            )
        }
    }

    /** A file the camera just wrote (already in cache/external cache). */
    fun attachFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val mime = MediaSniff.typeOf(file)
            _state.value = _state.value.copy(
                pending = Pending(
                    path = file.absolutePath,
                    name = file.name,
                    mime = mime,
                    bytes = file.length(),
                    image = mime.startsWith("image/"),
                ),
            )
        }
    }

    fun clearAttachment() {
        _state.value = _state.value.copy(pending = null)
    }

    fun setMode(mode: String) {
        val b = bridge ?: return
        _state.value = _state.value.copy(aiMode = mode)
        viewModelScope.launch { b.setMode(conversationId, mode) }
    }

    fun setReplies(enabled: Boolean) {
        val b = bridge ?: return
        _state.value = _state.value.copy(replies = enabled)
        viewModelScope.launch { b.setMemberReply(conversationId, enabled) }
    }

    fun toggleFavourite() {
        val on = Favorites.toggle(getApplication(), conversationId)
        _state.value = _state.value.copy(favourite = on)
    }

    fun setNotifications(enabled: Boolean) {
        prefs.edit().putBoolean("chat_${conversationId}_notify", enabled).apply()
        _state.value = _state.value.copy(notifications = enabled)
    }

    fun setTone(uri: String?) {
        prefs.edit().apply {
            if (uri.isNullOrBlank()) remove("chat_${conversationId}_tone")
            else putString("chat_${conversationId}_tone", uri)
        }.apply()
        _state.value = _state.value.copy(tone = uri)
    }

    fun loadPinned() {
        val t = tools ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(pinned = t.pinnedMessages(conversationId).toSet())
            } catch (_: Exception) {
            }
        }
    }

    fun setPinned(messageId: Long, pinned: Boolean) {
        val t = tools ?: return
        viewModelScope.launch {
            if (t.messageAction(if (pinned) "pin" else "unpin", messageId, conversationId)) {
                _state.value = _state.value.copy(
                    pinned = if (pinned) _state.value.pinned + messageId else _state.value.pinned - messageId,
                )
            } else {
                setSnack("The message could not be pinned.")
            }
        }
    }

    fun deleteMessage(messageId: Long) {
        val t = tools ?: return
        viewModelScope.launch {
            if (t.messageAction("delete", messageId, conversationId)) {
                _state.value = _state.value.copy(
                    messages = _state.value.messages.filterNot { it.id == messageId },
                    pinned = _state.value.pinned - messageId,
                )
            } else {
                setSnack("The message could not be deleted.")
            }
        }
    }

    /** Download an attachment and hand its local path to the viewer. */
    fun openAttachment(message: ChatBridge.Message) {
        val url = message.attachmentUrl ?: return
        val b = bridge ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(getApplication<Application>().cacheDir, "incoming").apply { mkdirs() }
            val safe = (message.attachmentName ?: "attachment")
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val dest = File(dir, "${message.id}_$safe")
            if (!dest.isFile || dest.length() == 0L) {
                val file = b.download(url, dest)
                if (file == null) {
                    setSnack("The attachment could not be downloaded.")
                    return@launch
                }
            }
            media.emit(dest.absolutePath)
        }
    }

    fun dismissSnack() {
        _state.value = _state.value.copy(snack = null)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun setSnack(text: String) {
        _state.value = _state.value.copy(snack = text)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        const val MAX_BYTES = 25L * 1024L * 1024L
    }
}
