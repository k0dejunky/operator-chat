package com.amethyst2213.operatorchat

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.amethyst2213.operatorchat.ui.screens.ScheduleScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme
import kotlinx.coroutines.launch

/**
 * The live-show schedule.
 *
 * A scheduled show puts a countdown on the member's live page. The operator
 * still presses Go Live by hand: the schedule is a promise to the members,
 * not a camera trigger.
 */
class ScheduleActivity : AppCompatActivity() {

    private var shows by mutableStateOf<List<LiveToolsBridge.Show>>(emptyList())
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    /** The site's timezone (the start time is typed in the site's timezone). */
    private var siteTimezone by mutableStateOf(ChatRows.DEFAULT_ZONE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = SecurePrefs.url(this)
        val token = SecurePrefs.token(this)
        val tools = LiveToolsBridge(url, token)

        setContent {
            OperatorTheme {
                ScheduleScreen(
                    shows = shows,
                    busy = busy,
                    error = error,
                    defaultStart = defaultStart(),
                    onSchedule = { title, start, duration, description ->
                        schedule(tools, title, start, duration, description)
                    },
                    onCancel = { id -> cancel(tools, id) },
                )
            }
        }

        load(tools)
        lifecycleScope.launch {
            siteTimezone = try {
                tools.siteConfig().siteTimezone
            } catch (_: Exception) {
                ChatRows.DEFAULT_ZONE
            }
        }
    }

    /** The site's timezone, and the next whole hour, as the default start. */
    private fun defaultStart(): String {
        val zone = siteTimezone
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of(zone))
        val start = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        return start.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    private fun load(tools: LiveToolsBridge) {
        lifecycleScope.launch {
            try {
                shows = tools.shows()
                error = null
            } catch (e: Exception) {
                error = "The schedule could not be loaded: ${e.message}"
            }
        }
    }

    private fun schedule(
        tools: LiveToolsBridge,
        title: String,
        start: String,
        duration: Int,
        description: String,
    ) {
        busy = true
        error = null
        lifecycleScope.launch {
            try {
                val show = tools.schedule(title, start, duration, description)
                if (show == null) {
                    error = "The show could not be scheduled. Check the start time."
                } else {
                    shows = tools.shows()
                }
            } catch (e: Exception) {
                error = "The show could not be scheduled: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private fun cancel(tools: LiveToolsBridge, id: Long) {
        busy = true
        lifecycleScope.launch {
            try {
                tools.cancelShow(id)
                shows = tools.shows()
            } catch (e: Exception) {
                error = "The show could not be cancelled: ${e.message}"
            } finally {
                busy = false
            }
        }
    }
}
