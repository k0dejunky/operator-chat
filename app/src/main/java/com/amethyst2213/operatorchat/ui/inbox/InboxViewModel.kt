package com.amethyst2213.operatorchat.ui.inbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amethyst2213.operatorchat.ChatBridge
import com.amethyst2213.operatorchat.ChatRows
import com.amethyst2213.operatorchat.Favorites
import com.amethyst2213.operatorchat.LiveToolsBridge
import com.amethyst2213.operatorchat.SecurePrefs
import com.amethyst2213.operatorchat.SiteZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The inbox: the unread queue, the full conversation list and the favourites,
 * with cursor pagination and a member search for starting a conversation.
 */
class InboxViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { NEEDS_REPLY, ALL, FAVOURITES }

    data class Ui(
        val items: List<ChatBridge.Conversation> = emptyList(),
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = false,
        val error: String? = null,
        val unauthorized: Boolean = false,
        val query: String = "",
        val mode: Mode = Mode.NEEDS_REPLY,
        val nextShow: LiveToolsBridge.Show? = null,
        val userResults: List<ChatBridge.UserResult> = emptyList(),
        val userSearching: Boolean = false,
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state.asStateFlow()

    // Read per call: Settings can change the address/token while this VM is
    // alive, and a cached bridge would keep talking to the old server.
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

    private var nextCursor: String? = null
    private var searchJob: Job? = null
    private var all: List<ChatBridge.Conversation> = emptyList()

    init {
        refresh()
        loadNextShow()
    }

    fun refresh(showLoading: Boolean = true) {
        val b = bridge ?: return
        if (_state.value.loading) return
        nextCursor = null
        _state.value = _state.value.copy(loading = showLoading, error = null)
        viewModelScope.launch {
            try {
                val page = b.inbox(_state.value.query, null)
                all = page.items
                nextCursor = page.nextCursor
                _state.value = _state.value.copy(
                    loading = false,
                    hasMore = page.hasMore,
                    error = null,
                    unauthorized = false,
                )
                publish()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun loadMore() {
        val b = bridge ?: return
        val cursor = nextCursor ?: return
        if (_state.value.loadingMore || _state.value.loading) return
        _state.value = _state.value.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = b.inbox(_state.value.query, cursor)
                all = (all + page.items).distinctBy { it.id }
                nextCursor = page.nextCursor
                _state.value = _state.value.copy(
                    loadingMore = false,
                    hasMore = page.hasMore,
                )
                publish()
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingMore = false)
                fail(e)
            }
        }
    }

    fun setMode(mode: Mode) {
        if (_state.value.mode == mode) return
        _state.value = _state.value.copy(mode = mode)
        publish()
    }

    fun setQuery(q: String) {
        if (_state.value.query == q) return
        _state.value = _state.value.copy(query = q)
        refresh(showLoading = false)
    }

    fun toggleFavorite(id: Long) {
        val now = Favorites.toggle(getApplication(), id)
        all = all.map { if (it.id == id) it.copy(favorite = now) else it }
        publish()
    }

    /** Search members by email/username (at least two characters). */
    fun searchUsers(q: String) {
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _state.value = _state.value.copy(userResults = emptyList(), userSearching = false)
            return
        }
        val b = bridge ?: return
        _state.value = _state.value.copy(userSearching = true)
        searchJob = viewModelScope.launch {
            try {
                val users = b.searchUsers(q.trim())
                _state.value = _state.value.copy(userResults = users, userSearching = false)
            } catch (_: Exception) {
                _state.value = _state.value.copy(userResults = emptyList(), userSearching = false)
            }
        }
    }

    /** Start (or find) a conversation with a member. Returns id + name. */
    fun startConversation(userId: Long, onReady: (Long, String) -> Unit) {
        val b = bridge ?: return
        viewModelScope.launch {
            try {
                val (id, username) = b.startConversation(userId)
                onReady(id, username)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Could not start the conversation.")
            }
        }
    }

    private fun loadNextShow() {
        val t = tools ?: return
        viewModelScope.launch {
            try {
                val shows = t.shows()
                _state.value = _state.value.copy(nextShow = shows.firstOrNull())
            } catch (_: Exception) {
                // a schedule is a nicety; never block the inbox on it
            }
        }
    }

    private fun publish() {
        val zone = SiteZone.read(getApplication())
        val s = _state.value
        val filtered = when (s.mode) {
            Mode.NEEDS_REPLY -> all.filter { it.unreadReplyable > 0 }
            Mode.ALL -> all
            Mode.FAVOURITES -> all.filter { Favorites.isFavorite(getApplication(), it.id) }
        }
        val stamped = filtered
            .map { it.copy(favorite = Favorites.isFavorite(getApplication(), it.id)) }
            .sortedByDescending { ChatRows.epochMillis(it.lastMessageAt, zone) }
            .distinctBy { it.id }
        _state.value = s.copy(items = stamped)
    }

    private fun fail(e: Exception) {
        val code = (e.message ?: "").substringAfter("HTTP ").toIntOrNull() ?: 0
        val unauthorized = code == 401 || code == 403
        _state.value = _state.value.copy(
            loading = false,
            loadingMore = false,
            unauthorized = unauthorized,
            error = if (unauthorized) {
                "The server refused the token. Sign in again in Settings."
            } else {
                "Connection failed. Check the server address and the connection."
            },
        )
    }
}
