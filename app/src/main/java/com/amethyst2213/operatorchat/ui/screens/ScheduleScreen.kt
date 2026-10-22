package com.amethyst2213.operatorchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.LiveToolsBridge
import com.amethyst2213.operatorchat.ui.components.EmptyState
import com.amethyst2213.operatorchat.ui.components.ErrorBanner
import com.amethyst2213.operatorchat.ui.components.PrimaryButton
import com.amethyst2213.operatorchat.ui.components.SectionHeader
import com.amethyst2213.operatorchat.ui.components.StatusPill
import com.amethyst2213.operatorchat.ui.theme.Palette

/**
 * The live-show schedule.
 *
 * The operator schedules a show and the member's live page counts down to
 * it. The start time is typed in the site's timezone - the server converts
 * it - so the countdown is the same on every device.
 */
@Composable
fun ScheduleScreen(
    shows: List<LiveToolsBridge.Show>,
    busy: Boolean,
    error: String?,
    defaultStart: String,
    onSchedule: (title: String, start: String, duration: Int, description: String) -> Unit,
    onCancel: (Long) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf(defaultStart) }
    var duration by rememberSaveable { mutableStateOf("60") }
    var description by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.SurfaceCard)
                .padding(20.dp),
        ) {
            Text(
                text = "Schedule a show",
                style = MaterialTheme.typography.titleLarge,
                color = Palette.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Members see a countdown on the live page until the show starts. You still press Go Live when you are ready.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                placeholder = { Text("Friday night live") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = start,
                onValueChange = { start = it },
                label = { Text("Starts at") },
                supportingText = { Text("In the site's timezone, like 2026-10-24 20:00") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            Row {
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it.filter(Char::isDigit).take(3) },
                    label = { Text("Minutes") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(140.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                minLines = 2,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                ErrorBanner(error)
            }

            Spacer(Modifier.height(14.dp))

            PrimaryButton(
                text = if (busy) "Scheduling…" else "Schedule it",
                onClick = {
                    onSchedule(
                        title.trim(),
                        start.trim(),
                        duration.toIntOrNull() ?: 60,
                        description.trim(),
                    )
                },
                enabled = !busy && title.isNotBlank() && start.isNotBlank(),
                icon = Icons.Outlined.CalendarMonth,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            SectionHeader("Coming up")

            if (shows.isEmpty()) {
                EmptyState(
                    title = "Nothing scheduled",
                    message = "A show you schedule shows up here, and as a countdown on the live page.",
                    icon = Icons.Outlined.CalendarMonth,
                )
            } else {
                Column {
                    shows.forEach { show ->
                        ShowCard(show = show, onCancel = { onCancel(show.id) })
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ShowCard(show: LiveToolsBridge.Show, onCancel: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.SurfaceCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = show.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = show.startsAt,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
                if (show.description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = show.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            StatusPill(
                text = "in ${formatCountdown(show.seconds)}",
                background = Palette.AmethystContainer,
                foreground = Palette.AmethystLight,
            )
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Cancel ${show.title}",
                    tint = Palette.TextSecondary,
                )
            }
        }
    }
}

/** "2h 15m" / "30m" / "now". */
fun formatCountdown(seconds: Long): String {
    if (seconds <= 0) return "now"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
