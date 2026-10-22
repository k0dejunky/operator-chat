package com.amethyst2213.operatorchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sensors
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.ChatBridge
import com.amethyst2213.operatorchat.ui.components.EmptyState
import com.amethyst2213.operatorchat.ui.components.InitialAvatar
import com.amethyst2213.operatorchat.ui.components.LoadingRow
import com.amethyst2213.operatorchat.ui.components.SectionHeader
import com.amethyst2213.operatorchat.ui.components.StatusPill
import com.amethyst2213.operatorchat.ui.components.avatarColorFor
import com.amethyst2213.operatorchat.ui.theme.Palette

/**
 * The operator's inbox: every conversation, newest activity first.
 *
 * The old list was a plain RecyclerView with a search field that fired a
 * request on every keystroke (and could not cancel the one in flight), a
 * refresh on every chat event, and a favourite lookup that re-parsed its
 * JSON five times per row.
 */
@Composable
fun InboxScreen(
    conversations: List<ChatBridge.Conversation>,
    loading: Boolean,
    refreshing: Boolean,
    query: String,
    error: String?,
    liveBadge: Boolean,
    nextShowTitle: String?,
    onQueryChange: (String) -> Unit,
    onOpen: (ChatBridge.Conversation) -> Unit,
    onOpenLive: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(InboxFilter.ALL) }

    val visible = conversations.filter { conversation ->
        when (filter) {
            InboxFilter.NEEDS_REPLY -> conversation.unreadReplyable > 0
            InboxFilter.FAVOURITES -> conversation.favorite
            else -> true
        }
    }

    val needsReply = visible.filter { it.unreadReplyable > 0 }
    val rest = visible.filter { it.unreadReplyable <= 0 }

    Column(modifier = Modifier.fillMaxSize()) {
        // The top bar: what the operator can reach, and whether anything is
        // happening right now.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.SurfaceCard)
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Inbox",
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.TextPrimary,
                )
                Text(
                    text = if (conversations.isEmpty()) {
                        "No conversations yet"
                    } else {
                        "${conversations.size} conversation${if (conversations.size == 1) "" else "s"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            }

            if (liveBadge) {
                StatusPill(
                    text = "LIVE",
                    background = Palette.Live,
                    foreground = Palette.White,
                    pulsing = true,
                )
                Spacer(Modifier.width(4.dp))
            }

            IconButton(onClick = onOpenLive) {
                Icon(
                    imageVector = Icons.Outlined.Sensors,
                    contentDescription = "Go live",
                    tint = if (liveBadge) Palette.Live else Palette.TextSecondary,
                )
            }
            IconButton(onClick = onOpenSchedule) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = "Schedule a show",
                    tint = Palette.TextSecondary,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = Palette.TextSecondary,
                )
            }
        }

        // The search field and the filters.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search members") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InboxFilter.entries.forEach { option ->
                    val selected = filter == option
                    StatusPill(
                        text = option.label,
                        background = if (selected) Palette.Amethyst else Palette.SurfaceElevated,
                        foreground = if (selected) Palette.White else Palette.TextSecondary,
                        modifier = Modifier.clickable { filter = option },
                    )
                }
            }
            if (!nextShowTitle.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                StatusPill(
                    text = "Next: $nextShowTitle",
                    background = Palette.AmethystContainer,
                    foreground = Palette.AmethystLight,
                )
            }
        }

        when {
            loading && conversations.isEmpty() -> LoadingRow()
            visible.isEmpty() -> EmptyState(
                title = if (query.isBlank()) "Nothing here yet" else "No matches",
                message = if (query.isBlank()) {
                    "Member conversations show up here as soon as somebody writes."
                } else {
                    "No conversation matches \"$query\"."
                },
                icon = Icons.Outlined.ChatBubbleOutline,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                if (needsReply.isNotEmpty()) {
                    item { SectionHeader("Needs a reply") }
                    items(needsReply, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onOpen = { onOpen(conversation) },
                        )
                    }
                }
                if (rest.isNotEmpty()) {
                    item { SectionHeader(if (needsReply.isEmpty()) "Conversations" else "Everything else") }
                    items(rest, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            onOpen = { onOpen(conversation) },
                        )
                    }
                }
            }
        }
    }
}

/** The inbox filters. */
enum class InboxFilter(val label: String) {
    ALL("All"),
    NEEDS_REPLY("Needs a reply"),
    FAVOURITES("Favourites"),
}

@Composable
private fun ConversationCard(
    conversation: ChatBridge.Conversation,
    onOpen: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.SurfaceCard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                InitialAvatar(
                    name = conversation.username.ifBlank { conversation.userEmail },
                    size = 46.dp,
                )
                if (conversation.favorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Favourite",
                        tint = Palette.Warning,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(16.dp)
                            .background(Palette.SurfaceCard, RoundedCornerShape(999.dp)),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.username.ifBlank { conversation.userEmail },
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = conversation.lastMessage.ifBlank { "No messages yet" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (conversation.unreadReplyable > 0) {
                    StatusPill(
                        text = conversation.unreadReplyable.toString(),
                        background = Palette.Amethyst,
                        foreground = Palette.White,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                // The mode is only worth a badge when it is NOT the
                // default: a pill on every single row is noise.
                if (conversation.aiMode.isNotBlank() && conversation.aiMode != "operator") {
                    StatusPill(
                        text = conversation.aiMode,
                        background = Palette.AmethystContainer,
                        foreground = Palette.AmethystLight,
                    )
                }
            }
        }
    }
}
