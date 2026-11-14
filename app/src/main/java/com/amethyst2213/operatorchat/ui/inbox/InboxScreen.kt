package com.amethyst2213.operatorchat.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amethyst2213.operatorchat.ChatBridge
import com.amethyst2213.operatorchat.ChatRows
import com.amethyst2213.operatorchat.SiteZone
import com.amethyst2213.operatorchat.UpdateOffer
import com.amethyst2213.operatorchat.ui.components.EmptyState
import com.amethyst2213.operatorchat.ui.components.ErrorStrip
import com.amethyst2213.operatorchat.ui.components.IconAction
import com.amethyst2213.operatorchat.ui.components.InitialAvatar
import com.amethyst2213.operatorchat.ui.components.SectionLabel
import com.amethyst2213.operatorchat.ui.components.StudioCard
import com.amethyst2213.operatorchat.ui.components.StudioPill
import com.amethyst2213.operatorchat.ui.components.StudioTopBar
import com.amethyst2213.operatorchat.ui.components.UpdateStrip
import com.amethyst2213.operatorchat.ui.theme.Studio
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@Composable
fun InboxScreen(
    state: InboxViewModel.Ui,
    update: UpdateOffer?,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onMode: (InboxViewModel.Mode) -> Unit,
    onQuery: (String) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpen: (ChatBridge.Conversation) -> Unit,
    onSearchUsers: (String) -> Unit,
    onStartConversation: (Long) -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
) {
    var query by remember { mutableStateOf(state.query) }
    var newChat by remember { mutableStateOf(false) }
    var userQuery by remember { mutableStateOf("") }
    val zone = SiteZone.read(androidx.compose.ui.platform.LocalContext.current)

    LaunchedEffect(query) {
        delay(250)
        if (query != state.query) onQuery(query)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                StudioTopBar(
                    title = "Studio",
                    subtitle = when (state.items.size) {
                        0 -> "No conversations"
                        1 -> "1 conversation"
                        else -> "${state.items.size} conversations"
                    },
                    actions = {
                        IconAction(Icons.Outlined.EditNote, "New chat", { newChat = true })
                        IconAction(Icons.Outlined.Refresh, "Refresh", onRefresh)
                    },
                )
            }

            if (update != null) {
                item {
                    UpdateStrip(update, onInstall = onInstallUpdate, onDismiss = onDismissUpdate)
                }
            }

            if (state.nextShow != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val show = state.nextShow
                        StudioPill(
                            text = "Next: ${show.title} · ${countdown(show.seconds)}",
                            leading = {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Studio.Amethyst),
                                )
                            },
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search members", color = Studio.Dim) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = Studio.Dim)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Studio.Amber,
                        unfocusedBorderColor = Studio.Hairline,
                        focusedContainerColor = Studio.Surface,
                        unfocusedContainerColor = Studio.Surface,
                        cursorColor = Studio.Amber,
                        focusedTextColor = Studio.Ivory,
                        unfocusedTextColor = Studio.Ivory,
                    ),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioPill(
                        "Needs reply",
                        selected = state.mode == InboxViewModel.Mode.NEEDS_REPLY,
                        onClick = { onMode(InboxViewModel.Mode.NEEDS_REPLY) },
                    )
                    StudioPill(
                        "All",
                        selected = state.mode == InboxViewModel.Mode.ALL,
                        onClick = { onMode(InboxViewModel.Mode.ALL) },
                    )
                    StudioPill(
                        "Favourites",
                        selected = state.mode == InboxViewModel.Mode.FAVOURITES,
                        onClick = { onMode(InboxViewModel.Mode.FAVOURITES) },
                    )
                }
            }

            if (!state.error.isNullOrBlank()) {
                item { ErrorStrip(state.error, onRetry = onRefresh) }
            }

            if (state.loading && state.items.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = Studio.Amber, strokeWidth = 2.dp) }
                }
            }

            if (!state.loading && state.items.isEmpty() && state.error.isNullOrBlank()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.ChatBubbleOutline,
                        title = when (state.mode) {
                            InboxViewModel.Mode.FAVOURITES -> "No favourites yet"
                            InboxViewModel.Mode.ALL -> "No conversations yet"
                            else -> "Nothing needs a reply"
                        },
                        body = when (state.mode) {
                            InboxViewModel.Mode.FAVOURITES -> "Star a conversation from its settings or the inbox to keep it here."
                            InboxViewModel.Mode.ALL -> "When a member writes, the conversation appears here."
                            else -> "You are all caught up. Switch to All to see every conversation."
                        },
                    )
                }
            }

            if (state.mode == InboxViewModel.Mode.NEEDS_REPLY && state.items.isNotEmpty()) {
                item { SectionLabel("Needs a reply", Modifier.padding(top = 6.dp)) }
            }
            items(state.items, key = { it.id }) { conversation ->
                ConversationCard(
                    conversation = conversation,
                    zone = zone,
                    onClick = { onOpen(conversation) },
                    onFavorite = { onToggleFavorite(conversation.id) },
                )
            }

            if (state.hasMore) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onLoadMore, enabled = !state.loadingMore) {
                            Text(
                                if (state.loadingMore) "Loading…" else "Load more",
                                color = Studio.Amber,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }

    if (newChat) {
        NewChatDialog(
            results = state.userResults,
            searching = state.userSearching,
            query = userQuery,
            onQuery = { userQuery = it; onSearchUsers(it) },
            onPick = { userId ->
                newChat = false
                onStartConversation(userId)
            },
            onDismiss = { newChat = false },
        )
    }
}

@Composable
private fun ConversationCard(
    conversation: ChatBridge.Conversation,
    zone: String,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    val unread = conversation.unreadReplyable > 0
    val name = conversation.username.ifBlank { conversation.userEmail }
    StudioCard(onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InitialAvatar(name, 46.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (unread) Studio.Ivory else Studio.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conversation.favorite) {
                        Icon(
                            Icons.Outlined.Star,
                            contentDescription = "Favourite",
                            tint = Studio.Amber,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable(onClick = onFavorite),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    conversation.lastMessage.ifBlank { "No messages yet" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread) Studio.Ivory.copy(alpha = 0.85f) else Studio.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val epoch = ChatRows.epochMillis(conversation.lastMessageAt, zone)
                Text(
                    relativeTime(epoch),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
                    color = Studio.Dim,
                )
                if (unread) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(Studio.Amber)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            conversation.unreadReplyable.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                            color = Studio.OnAmber,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NewChatDialog(
    results: List<ChatBridge.UserResult>,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Studio.Surface,
        titleContentColor = Studio.Ivory,
        textContentColor = Studio.Muted,
        title = { Text("Message a member", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Email or username", color = Studio.Dim) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Studio.Dim) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Studio.Amber,
                        unfocusedBorderColor = Studio.Hairline,
                        focusedContainerColor = Studio.InkSoft,
                        unfocusedContainerColor = Studio.InkSoft,
                        cursorColor = Studio.Amber,
                        focusedTextColor = Studio.Ivory,
                        unfocusedTextColor = Studio.Ivory,
                    ),
                )
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Studio.Amber,
                        strokeWidth = 2.dp,
                    )
                }
                LazyColumn(Modifier.height(260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(results, key = { it.id }) { user ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = user.canChat) { onPick(user.id) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            InitialAvatar(user.username.ifBlank { user.email }, 34.dp)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    user.username.ifBlank { user.email },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (user.canChat) Studio.Ivory else Studio.Dim,
                                )
                                Text(
                                    if (user.canChat) user.email else "No chat range",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Studio.Dim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Studio.Amber) }
        },
    )
}

private fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val diff = System.currentTimeMillis() - epochMs
    if (diff < 0) return "now"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            .format(java.util.Date(epochMs))
    }
}

private fun countdown(seconds: Long): String {
    if (seconds <= 0) return "now"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}
