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

    /**
     * Show every conversation, not only the ones with an unread member
     * message.
     *
     * This used to be unreachable: the flag existed, but the menu item that
     * set it was dropped in the rewrite, so the inbox silently kept only the
     * conversations with unread messages - and a conversation vanished for
     * good the moment the operator answered it. The choice is remembered.
     */
    var allUsersMode: Boolean = prefs().getBoolean(KEY_ALL_USERS, false)
        private set

    private var query = ""
    private var nextCursor: String? = null

    var bridge: ChatBridge? = null
        private set

    fun hasCredentials(): Boolean =
        SecurePrefs.url(getApplication()).isNotEmpty() && SecurePrefs.token(getApplication()).isNotEmpty()

    /**
     * Store the connection and start using it.
     *
     * The token that is handed in is the one that is stored and the one that
     * is used. The rewrite read the token back out of the keystore instead
     * of using the one the operator had just typed, so on a fresh install
     * (or after a disconnect) every request went out with an empty bearer
     * token and the app could never sign in at all.
     */
    fun connect(url: String, token: String) {
        val clean = url.trim().trimEnd('/')
        val tok = token.trim()
        if (clean.isEmpty() || tok.isEmpty()) {
            _state.value = _state.value.copy(
                error = "The server address and the token are both required.",
                connection = Connection.UNAUTHORIZED,
            )
            return
        }
        SecurePrefs.save(getApplication(), clean, tok)
        applyBridge(ChatBridge(clean, tok))
    }

    /** Re-use the stored credentials, if there are any. */
    fun restore() {
        val url = SecurePrefs.url(getApplication()).trim()
        val token = SecurePrefs.token(getApplication()).trim()
        if (url.isEmpty() || token.isEmpty()) {
            bridge = null
            return
        }
        applyBridge(ChatBridge(url, token))
    }

    /** Forget everything and go back to the sign-in screen. */
    fun logout() {
        SecurePrefs.clear(getApplication())
        bridge = null
        nextCursor = null
        _state.value = InboxState()
    }

    private fun applyBridge(b: ChatBridge) {
        bridge = b
        refresh(showLoading = true)
    }

    fun setAllUsers(mode: Boolean) {
        if (allUsersMode == mode) return
        allUsersMode = mode
        prefs().edit().putBoolean(KEY_ALL_USERS, mode).apply()
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

    private fun load(showLoading: Boolean, loadMore: Boolean) {
        val b = bridge ?: return
        val current = _state.value
        if (current.loading) return

        _state.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val page = b.inbox(query, if (loadMore) nextCursor else null)
                val sorted = page.items.sortedByDescending { sortKey(it.lastMessageAt) }
                // In "all conversations" mode every conversation the server
                // returned is listed. In the default mode only the ones with
                // an unanswered member message are - that is what the badge
                // counts.
                val shown = if (allUsersMode) {
                    sorted
                } else {
                    sorted.filter { it.unreadReplyable > 0 }
                }
                val displayed = shown.map { it.copy(favorite = Favorites.isFavorite(getApplication(), it.id)) }
                    .distinctBy { it.id }
                nextCursor = page.nextCursor
                val items = if (loadMore) current.items + displayed else displayed
                val status = when {
                    items.isEmpty() && allUsersMode -> "No conversations yet."
                    items.isEmpty() -> "No new messages."
                    allUsersMode -> "${items.size} conversation${if (items.size == 1) "" else "s"}"
                    else -> "${items.size} with new messages"
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
                    Connection.UNAUTHORIZED -> "Unauthorized - check the token."
                    else -> "Connection failed - check the URL and the token."
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

    /** Zone the server stamps its timestamps in. */
    private fun zoneId(): String = SiteZone.read(getApplication())

    /**
     * Sort by the server's timestamp.
     *
     * This used to call SimpleDateFormat().parse() with the DEVICE's default
     * timezone. The server stamps in the site timezone, so on a phone anywhere
     * else the inbox was ordered wrong (and every "2 min ago" label was off by
     * the offset). The zone now comes from the same parser the thread uses.
     */
    private fun sortKey(value: String): Long =
        ChatRows.epochMillis(value, zoneId())

    private fun prefs() =
        getApplication<Application>()
            .getSharedPreferences("operator_chat", android.content.Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALL_USERS = "inbox_all_users"
    }
}
