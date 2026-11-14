package com.amethyst2213.operatorchat.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amethyst2213.operatorchat.ChatRows
import com.amethyst2213.operatorchat.LiveToolsBridge
import com.amethyst2213.operatorchat.SecurePrefs
import com.amethyst2213.operatorchat.SiteZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val shows: List<LiveToolsBridge.Show> = emptyList(),
        val loading: Boolean = true,
        val busy: Boolean = false,
        val error: String? = null,
        val siteZone: String = ChatRows.DEFAULT_ZONE,
        val now: Long = System.currentTimeMillis(),
    )

    private val _state = MutableStateFlow(Ui())
    val state: StateFlow<Ui> = _state.asStateFlow()

    private val tools: LiveToolsBridge?
        get() {
            val url = SecurePrefs.url(getApplication()).trim()
            val token = SecurePrefs.token(getApplication()).trim()
            return if (url.isBlank() || token.isBlank()) null else LiveToolsBridge(url, token)
        }

    init {
        load()
    }

    fun load() {
        val t = tools ?: run {
            _state.value = _state.value.copy(loading = false, error = "Sign in first.")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val zone = SiteZone.read(getApplication())
                val shows = t.shows()
                _state.value = _state.value.copy(
                    shows = shows,
                    loading = false,
                    error = null,
                    siteZone = zone,
                    now = System.currentTimeMillis(),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "The schedule could not be loaded.",
                )
            }
        }
    }

    fun schedule(title: String, start: String, duration: Int, description: String) {
        val t = tools ?: return
        if (title.isBlank()) {
            _state.value = _state.value.copy(error = "A title is required.")
            return
        }
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val show = t.schedule(title.trim(), start.trim(), duration.coerceIn(1, 600), description.trim())
                if (show == null) {
                    _state.value = _state.value.copy(
                        busy = false,
                        error = "The show could not be scheduled. Check the start time (site time).",
                    )
                } else {
                    _state.value = _state.value.copy(busy = false, shows = t.shows())
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, error = "The show could not be scheduled.")
            }
        }
    }

    fun cancel(id: Long) {
        val t = tools ?: return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                t.cancelShow(id)
                _state.value = _state.value.copy(busy = false, shows = t.shows())
            } catch (_: Exception) {
                _state.value = _state.value.copy(busy = false, error = "The show could not be cancelled.")
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        /** The next whole hour in the site's timezone, as yyyy-MM-dd HH:mm. */
        fun defaultStart(
            zone: String,
            now: ZonedDateTime = ZonedDateTime.now(),
        ): String {
            val z = runCatching { ZoneId.of(zone) }.getOrElse { ZoneId.of("UTC") }
            return now.withZoneSameInstant(z)
                .plusHours(1)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }
    }
}
