package com.amethyst2213.operatorchat.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.LiveToolsBridge
import com.amethyst2213.operatorchat.ui.components.EmptyState
import com.amethyst2213.operatorchat.ui.components.ErrorStrip
import com.amethyst2213.operatorchat.ui.components.GhostButton
import com.amethyst2213.operatorchat.ui.components.IconAction
import com.amethyst2213.operatorchat.ui.components.PrimaryButton
import com.amethyst2213.operatorchat.ui.components.SectionLabel
import com.amethyst2213.operatorchat.ui.components.StudioCard
import com.amethyst2213.operatorchat.ui.components.StudioPill
import com.amethyst2213.operatorchat.ui.components.StudioTopBar
import com.amethyst2213.operatorchat.ui.theme.Studio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    state: ScheduleViewModel.Ui,
    onRefresh: () -> Unit,
    onSchedule: (String, String, Int, String) -> Unit,
    onCancel: (Long) -> Unit,
    onClearError: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        StudioTopBar(
            title = "Shows",
            subtitle = if (state.shows.isEmpty()) "Nothing scheduled" else "${state.shows.size} upcoming",
            actions = { IconAction(Icons.Outlined.Add, "Schedule a show", { creating = true }) },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.error.isNullOrBlank()) {
                item { ErrorStrip(state.error, onRetry = { onClearError(); onRefresh() }) }
            }
            if (state.loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Studio.Amber, strokeWidth = 2.dp)
                    }
                }
            } else if (state.shows.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Event,
                        title = "No shows scheduled",
                        body = "A scheduled show puts a countdown on the member's live page. You still go live by hand.",
                        action = {
                            PrimaryButton(
                                "Schedule the next show",
                                onClick = { creating = true },
                                fill = false,
                            )
                        },
                    )
                }
            } else {
                item { SectionLabel("Coming up", Modifier.padding(top = 6.dp)) }
                items(state.shows, key = { it.id }) { show ->
                    ShowCard(show = show, onCancel = { onCancel(show.id) })
                }
            }
            item { Spacer(Modifier.height(100.dp)) }
        }
    }

    if (creating) {
        NewShowSheet(
            siteZone = state.siteZone,
            busy = state.busy,
            onDismiss = { creating = false },
            onCreate = { title, start, duration, description ->
                creating = false
                onSchedule(title, start, duration, description)
            },
        )
    }
}

@Composable
private fun ShowCard(show: LiveToolsBridge.Show, onCancel: () -> Unit) {
    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    show.title.ifBlank { "Untitled show" },
                    style = MaterialTheme.typography.titleLarge,
                    color = Studio.Ivory,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    show.startsAt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Studio.Muted,
                )
                if (show.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        show.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Studio.Dim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconAction(Icons.Outlined.DeleteOutline, "Cancel show", onCancel, tint = Studio.Coral)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioPill(
                text = if (show.seconds <= 0) "now" else "in " + formatCountdown(show.seconds),
                leading = {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Studio.Amethyst),
                    )
                },
            )
            StudioPill("${show.duration} min")
            if (show.status.isNotBlank()) StudioPill(show.status)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewShowSheet(
    siteZone: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String, Int, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(ScheduleViewModel.defaultStart(siteZone)) }
    var duration by remember { mutableStateOf("60") }
    var description by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Studio.Surface,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Schedule a show", style = MaterialTheme.typography.headlineSmall, color = Studio.Ivory)
            Text(
                "Times are in the site timezone (${siteZone}).",
                style = MaterialTheme.typography.bodySmall,
                color = Studio.Dim,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 160) title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { start = it },
                    label = { Text("Starts at (site time)") },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                )
                OutlinedTextField(
                    value = duration,
                    onValueChange = { value -> duration = value.filter { it.isDigit() }.take(3) },
                    label = { Text("Minutes") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                )
            }
            OutlinedTextField(
                value = description,
                onValueChange = { if (it.length <= 1000) description = it },
                label = { Text("Description (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
            )
            PrimaryButton(
                text = "Schedule it",
                onClick = {
                    onCreate(title, start, duration.toIntOrNull() ?: 60, description)
                },
                loading = busy,
                enabled = title.isNotBlank() && !busy,
            )
            GhostButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Studio.Amber,
    unfocusedBorderColor = Studio.Hairline,
    focusedLabelColor = Studio.Amber,
    unfocusedLabelColor = Studio.Dim,
    focusedContainerColor = Studio.InkSoft,
    unfocusedContainerColor = Studio.InkSoft,
    cursorColor = Studio.Amber,
    focusedTextColor = Studio.Ivory,
    unfocusedTextColor = Studio.Ivory,
)

/**
 * A countdown without the "in" prefix. 90 seconds is "1m" (never "0m"),
 * because "in 0m" reads like the show already started.
 */
fun formatCountdown(seconds: Long): String {
    if (seconds <= 0) return "now"
    var minutes = seconds / 60
    if (minutes <= 0) minutes = 1
    val d = minutes / (24 * 60)
    val h = (minutes % (24 * 60)) / 60
    val m = minutes % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}
