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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.ChatBridge
import com.amethyst2213.operatorchat.ChatRow
import com.amethyst2213.operatorchat.SendReconcile
import com.amethyst2213.operatorchat.Delivery
import com.amethyst2213.operatorchat.ui.components.EmptyState
import com.amethyst2213.operatorchat.ui.components.ErrorBanner
import com.amethyst2213.operatorchat.ui.components.StatusPill
import com.amethyst2213.operatorchat.ui.theme.Palette

/**
 * One conversation.
 *
 * The old screen was 1,198 lines of Activity that rebuilt the whole row
 * list on every incoming message, always scrolled to the bottom (the
 * "was I at the bottom" flag was never updated by scrolling), restored
 * the scroll position to the wrong row after loading history, and had no
 * per-message actions at all.
 */
@Composable
fun ThreadScreen(
    conversationId: Long,
    title: String,
    subtitle: String,
    rows: List<ChatRow>,
    draft: String,
    sending: Boolean,
    queued: Int,
    typing: Boolean,
    canReply: Boolean,
    memberReplyEnabled: Boolean,
    aiMode: String,
    pinnedIds: Set<Long>,
    searchOpen: Boolean,
    searchQuery: String,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPin: (Long) -> Unit,
    onUnpin: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onCopy: (Long) -> Unit,
    onToggleMemberReplies: (Boolean) -> Unit,
    onModeChange: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onBack: () -> Unit,
    outboxText: String?,
    outboxActionLabel: String,
    onOutboxAction: () -> Unit,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var menuFor by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }
    var showControls by rememberSaveable { mutableStateOf(false) }

    // The scroll position: the thread only follows the newest message when
    // the operator is already at the bottom. The old screen always
    // scrolled down, so reading history was impossible.
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlowAtBottom(listState, rows.size) { atBottom = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // The header: who this is, and the controls.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.SurfaceCard)
                .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to the inbox",
                        tint = Palette.TextSecondary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Palette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = if (searchOpen) "Close the search" else "Search this conversation",
                        tint = if (searchOpen) Palette.Amethyst else Palette.TextSecondary,
                    )
                }
                IconButton(onClick = { showControls = !showControls }) {
                    Icon(
                        imageVector = if (showControls) Icons.Filled.Close else Icons.Outlined.Tune,
                        contentDescription = if (showControls) {
                            "Close the conversation controls"
                        } else {
                            "Conversation controls"
                        },
                        tint = if (showControls) Palette.Amethyst else Palette.TextSecondary,
                    )
                }
            }

            if (showControls) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(
                        text = "AI: $aiMode",
                        background = Palette.SurfaceElevated,
                        foreground = Palette.TextSecondary,
                        modifier = Modifier.clickable {
                            onModeChange(if (aiMode == "operator") "retrieval" else "operator")
                        },
                    )
                    StatusPill(
                        text = if (memberReplyEnabled) "Replies on" else "Replies off",
                        background = if (memberReplyEnabled) {
                            Palette.AmethystContainer
                        } else {
                            Palette.SurfaceElevated
                        },
                        foreground = if (memberReplyEnabled) {
                            Palette.AmethystLight
                        } else {
                            Palette.TextSecondary
                        },
                        modifier = Modifier.clickable { onToggleMemberReplies(!memberReplyEnabled) },
                    )
                }
            }

            if (searchOpen) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search this conversation") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (queued > 0) {
                Spacer(Modifier.height(8.dp))
                StatusPill(
                    text = "$queued reply${if (queued == 1) "" else "ies"} waiting to be sent",
                    background = Palette.Warning.copy(alpha = 0.2f),
                    foreground = Palette.Warning,
                )
            }

            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(error)
            }
        }

        // The messages.
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.Surface),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 12.dp,
            ),
        ) {
            if (rows.isEmpty()) {
                item {
                    EmptyState(
                        title = "No messages yet",
                        message = "Say something to start the conversation.",
                    )
                }
            }

            itemsIndexed(rows) { index, row ->
                when (row.type) {
                    ChatRow.TYPE_DAY -> DaySeparator(row.dayLabel ?: "")
                    else -> MessageBubble(
                        row = row,
                        pinned = row.message?.id in pinnedIds,
                        onLongPress = { row.message?.let { menuFor = it.id } },
                        onRetry = { },
                    )
                }
            }

            if (typing) {
                item {
                    Text(
                        text = "The member is typing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

        // The outbox strip: the replies that are still queued, or the last
        // rejected one. It must stay visible - a lost reply is the one thing
        // an operator must never have to discover later.
        if (!outboxText.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.SurfaceElevated)
                    .clickable(onClick = onOutboxAction)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = outboxText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Warning,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = outboxActionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.AmethystLight,
                )
            }
        }

        // The composer.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.SurfaceCard)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(onClick = onAttach) {
                Icon(
                    imageVector = Icons.Outlined.AttachFile,
                    contentDescription = "Attach a file",
                    tint = Palette.TextSecondary,
                )
            }
            OutlinedTextField(
                value = draft,
                // The server truncates at MAX_REPLY_CHARS, so the field is
                // capped at the same length: a longer reply would be cut
                // silently on the way in.
                onValueChange = { onDraftChange(it.take(SendReconcile.MAX_REPLY_CHARS)) },
                placeholder = { Text("Reply…") },
                maxLines = 5,
                shape = RoundedCornerShape(20.dp),
                enabled = canReply,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = onSend,
                enabled = canReply && !sending && draft.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send",
                    tint = if (canReply && draft.isNotBlank()) {
                        Palette.Amethyst
                    } else {
                        Palette.TextTertiary
                    },
                )
            }
        }
    }

    // The per-message menu.
    menuFor?.let { messageId ->
        val pinned = messageId in pinnedIds
        Box {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menuFor = null },
            ) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin" else "Pin") },
                    leadingIcon = {
                        Icon(
                            imageVector = if (pinned) {
                                Icons.Filled.PushPin
                            } else {
                                Icons.Outlined.PushPin
                            },
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuFor = null
                        if (pinned) onUnpin(messageId) else onPin(messageId)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuFor = null
                        onCopy(messageId)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = Palette.Danger,
                        )
                    },
                    onClick = {
                        menuFor = null
                        confirmDelete = messageId
                    },
                )
            }
        }
    }

    // Deleting a message is permanent, so it is confirmed.
    confirmDelete?.let { messageId ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this message?") },
            text = { Text("It disappears from the conversation for both of you.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(messageId)
                }) { Text("Delete", color = Palette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Keep it") }
            },
        )
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextTertiary,
        )
    }
}

@Composable
private fun MessageBubble(
    row: ChatRow,
    pinned: Boolean,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
) {
    val outgoing = row.isOutgoing
    val bubble = if (outgoing) Palette.Amethyst else Palette.SurfaceCard
    val text = if (outgoing) Palette.White else Palette.TextPrimary

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (outgoing) 16.dp else if (row.grouped) 4.dp else 16.dp,
                        bottomEnd = if (outgoing) if (row.grouped) 4.dp else 16.dp else 16.dp,
                    )
                )
                .background(bubble)
                .clickable(onClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 9.dp)
                .widthIn(max = 320.dp),
        ) {
            if (row.showHeader) {
                Text(
                    text = if (outgoing) "You" else "Member",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outgoing) Palette.White.copy(alpha = 0.75f) else Palette.TextTertiary,
                )
            }
            row.message?.message?.takeIf { it.isNotBlank() }?.let { body ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = text,
                )
            }
            row.message?.attachmentName?.let { name ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "📎 $name",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outgoing) Palette.White.copy(alpha = 0.85f) else Palette.TextSecondary,
                    modifier = Modifier.semantics {
                        contentDescription = "Attachment: $name"
                    },
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = if (outgoing) Palette.White else Palette.Warning,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = row.timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outgoing) Palette.White.copy(alpha = 0.7f) else Palette.TextTertiary,
                )
                if (outgoing) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (row.delivery) {
                            Delivery.SENDING -> "Sending…"
                            Delivery.QUEUED -> "Queued"
                            Delivery.FAILED -> "Failed — tap to retry"
                            Delivery.SENT -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.delivery == Delivery.FAILED) {
                            Palette.Danger
                        } else {
                            Palette.White.copy(alpha = 0.7f)
                        },
                        modifier = if (row.delivery == Delivery.FAILED) {
                            Modifier.clickable(onClick = onRetry)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
    }
}

/** Whether the list is scrolled to the bottom. */
private suspend fun snapshotFlowAtBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
    rowCount: Int,
    onChange: (Boolean) -> Unit,
) {
    androidx.compose.runtime.snapshotFlow {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= rowCount - 2
    }.collect { onChange(it) }
}
