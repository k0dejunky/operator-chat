package com.amethyst2213.operatorchat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the operator inbox as a StateFlow so the UI is lifecycle-aware and
 * never restarts work on rotation. Tracks connection state (online/offline/
 * unauthorized) and supports load-more pagination via the server cursor.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Connection { UNKNOWN, ONLINE, OFFLINE, UNAUTHORIZED }

    data class InboxState(
        val items: List<ChatBridge.Conversation> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val connection: Connection = Connection.UNKNOWN,
        val hasMore: Boolean = false,
        val statusLine: String = "Enter server URL + token, then connect.",
    )

    private val _state = MutableStateFlow(InboxState())
    val state: StateFlow<InboxState> = _state.asStateFlow()

    var allUsersMode = false
        private set

    private var query = ""
    private var nextCursor: String? = null

    var bridge: ChatBridge? = null
        private set

    fun hasCredentials(): Boolean =
        SecurePrefs.url(getApplication()).isNotEmpty() && SecurePrefs.token(getApplication()).isNotEmpty()

    fun connect(url: String, token: String) {
        SecurePrefs.save(getApplication(), url, token)
        bridge = ChatBridge(url, token)
        allUsersMode = false
        refresh(showLoading = true)
    }

    fun restore() {
        bridge = ChatBridge(SecurePrefs.url(getApplication()), SecurePrefs.token(getApplication()))
        refresh(showLoading = true)
    }

    fun setAllUsers(mode: Boolean) {
        allUsersMode = mode
        refresh(showLoading = true)
    }

    fun setQuery(q: String) {
        query = q
        refresh(showLoading = false)
    }

    fun refresh(showLoading: Boolean = false) {
        nextCursor = null
        load(showLoading, loadMore = false)
    }

    fun loadMore() {
        if (_state.value.hasMore) load(showLoading = false, loadMore = true)
    }

    fun logout() {
        SecurePrefs.clear(getApplication())
        bridge = null
        nextCursor = null
        _state.value = InboxState()
    }

    private fun load(showLoading: Boolean, loadMore: Boolean) {
        val b = bridge ?: return
        val current = _state.value
        if (current.loading) return

        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val page = b.inbox(query, if (loadMore) nextCursor else null)
                val sorted = page.items.sortedByDescending { parseEpoch(it.lastMessageAt) }
                val displayed = if (allUsersMode) {
                    val fav = sorted.filter { Favorites.isFavorite(getApplication(), it.id) }
                    val rest = sorted.filter { !Favorites.isFavorite(getApplication(), it.id) }
                    (fav + rest).distinctBy { it.id }
                } else {
                    val withUnread = sorted.filter { it.unreadReplyable > 0 }
                    val fav = withUnread.filter { Favorites.isFavorite(getApplication(), it.id) }
                    val rest = withUnread.filter { !Favorites.isFavorite(getApplication(), it.id) }
                    (fav + rest).distinctBy { it.id }
                }
                val withFav = displayed.map { it.copy(favorite = Favorites.isFavorite(getApplication(), it.id)) }
                nextCursor = page.nextCursor
                val items = if (loadMore) current.items + withFav else withFav
                val status = when {
                    items.isEmpty() && allUsersMode -> "No users yet."
                    items.isEmpty() -> "No new messages."
                    allUsersMode -> "${items.size} user(s)"
                    else -> "${items.size} user(s) with new messages"
                }
                _state.value = InboxState(
                    items = items,
                    loading = false,
                    error = null,
                    connection = Connection.ONLINE,
                    hasMore = page.hasMore,
                    statusLine = status,
                )
            } catch (e: Exception) {
                val code = (e.message ?: "").substringAfter("HTTP ").toIntOrNull() ?: 0
                val conn = if (code == 401) Connection.UNAUTHORIZED else Connection.OFFLINE
                val msg = when (conn) {
                    Connection.UNAUTHORIZED -> "Unauthorized — check the token."
                    else -> "Connection failed — check URL/token."
                }
                _state.value = _state.value.copy(
                    loading = false,
                    error = msg,
                    connection = conn,
                    statusLine = msg,
                )
            }
        }
    }

    /** Parse the server's "yyyy-MM-dd HH:mm:ss" timestamp to epoch millis. */
    private fun parseEpoch(value: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .parse(value)
                ?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}