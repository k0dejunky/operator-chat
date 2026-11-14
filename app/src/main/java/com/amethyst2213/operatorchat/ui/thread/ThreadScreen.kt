package com.amethyst2213.operatorchat.ui.thread

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Reply
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.amethyst2213.operatorchat.ChatBridge
import com.amethyst2213.operatorchat.ChatRow
import com.amethyst2213.operatorchat.ChatRows
import com.amethyst2213.operatorchat.Delivery
import com.amethyst2213.operatorchat.SiteZone
import com.amethyst2213.operatorchat.SendQueue
import com.amethyst2213.operatorchat.ui.components.EmptyState
import com.amethyst2213.operatorchat.ui.components.ErrorStrip
import com.amethyst2213.operatorchat.ui.components.GhostButton
import com.amethyst2213.operatorchat.ui.components.IconAction
import com.amethyst2213.operatorchat.ui.components.InitialAvatar
import com.amethyst2213.operatorchat.ui.components.SectionLabel
import com.amethyst2213.operatorchat.ui.components.StudioPill
import com.amethyst2213.operatorchat.ui.components.TypingDots
import com.amethyst2213.operatorchat.ui.theme.Studio
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(
    vm: ThreadViewModel,
    state: ThreadViewModel.Ui,
    onBack: () -> Unit,
    onOpenMedia: (String) -> Unit,
) {
    val context = LocalContext.current
    val zone = SiteZone.read(context)
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var actionMessage by remember { mutableStateOf<ChatBridge.Message?>(null) }
    var confirmDelete by remember { mutableStateOf<ChatBridge.Message?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showOutbox by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var expires by remember { mutableStateOf(0) }
    var maxViews by remember { mutableStateOf(0) }

    val shownMessages = remember(state.messages, filter) {
        if (filter.isBlank()) state.messages
        else state.messages.filter {
            it.message.contains(filter, ignoreCase = true) ||
                (it.attachmentName ?: "").contains(filter, ignoreCase = true)
        }
    }
    val rows = remember(shownMessages, state.deliveries, zone) {
        ChatRows.build(shownMessages, state.deliveries, zone)
    }

    var stick by remember { mutableStateOf(true) }
    LaunchedEffect(listState, rows.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> stick = last >= rows.size - 3 }
    }
    LaunchedEffect(rows.size) {
        if (stick && rows.isNotEmpty()) {
            listState.animateScrollToItem(rows.size - 1)
        }
    }
    LaunchedEffect(listState.firstVisibleItemIndex, state.hasMore) {
        if (state.hasMore && listState.firstVisibleItemIndex <= 1 && !state.loadingOlder) {
            vm.loadOlder()
        }
    }
    LaunchedEffect(state.snack) {
        state.snack?.let {
            snackbar.showSnackbar(it)
            vm.dismissSnack()
        }
    }
    LaunchedEffect(vm) {
        vm.mediaPath.collect { path -> onOpenMedia(path) }
    }

    val photoFile = remember { File(context.cacheDir, "camera").apply { mkdirs() } }
    var cameraTarget by remember { mutableStateOf<File?>(null) }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraTarget
        if (ok && file != null && file.isFile) vm.attachFile(file)
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val file = cameraTarget
        if (ok && file != null && file.isFile) vm.attachFile(file)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.prepareAttachment(uri)
    }
    val toneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            vm.setTone(uri?.toString())
        }
    }

    fun launchCamera(video: Boolean) {
        val file = File(photoFile, if (video) "video_${System.currentTimeMillis()}.mp4" else "photo_${System.currentTimeMillis()}.jpg")
        cameraTarget = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        if (video) videoLauncher.launch(uri) else photoLauncher.launch(uri)
    }

    Scaffold(
        containerColor = Studio.Ink,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAction(Icons.Outlined.Close, "Back", onBack)
                    InitialAvatar(state.name, 36.dp)
                    Spacer(Modifier.size(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.name.ifBlank { "Member" },
                            style = MaterialTheme.typography.titleMedium,
                            color = Studio.Ivory,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(if (state.replies) "Replies on" else "Replies off")
                                append(" · ")
                                append(
                                    when (state.aiMode) {
                                        "operator" -> "You answer"
                                        "finetuned" -> "Fine-tuned AI"
                                        else -> "Retrieval AI"
                                    },
                                )
                                if (state.connectionLost) append(" · reconnecting")
                            },
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                            color = if (state.connectionLost) Studio.Coral else Studio.Dim,
                        )
                    }
                    IconAction(Icons.Outlined.Search, "Search", { searching = !searching })
                    IconAction(Icons.Outlined.MoreVert, "Conversation settings", { showSettings = true })
                }
                if (searching) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search this conversation", color = Studio.Dim) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
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
                        IconAction(Icons.Outlined.Close, "Close search", {
                            searching = false
                            filter = ""
                        })
                    }
                }
            }
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                if (!state.error.isNullOrBlank()) {
                    ErrorStrip(state.error, onRetry = { vm.reload() })
                }
                if (state.outboxCount > 0 || state.outboxNotice != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Studio.SurfaceHigh)
                            .clickable {
                                if (state.outboxCount > 0) showOutbox = true else vm.dismissOutboxNotice()
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (state.outboxCount > 0) {
                                if (state.outboxCount == 1) "1 reply queued — tap to review"
                                else "${state.outboxCount} replies queued — tap to review"
                            } else {
                                "Could not send: ${state.outboxNotice?.take(50)}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Studio.Amber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (state.outboxCount > 0) "Review" else "Dismiss",
                            style = MaterialTheme.typography.labelSmall,
                            color = Studio.Amber,
                        )
                    }
                }
                if (state.typing) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TypingDots()
                        Text("typing", style = MaterialTheme.typography.labelSmall, color = Studio.Muted)
                    }
                }
                if (state.uploading >= 0) {
                    Text(
                        "Uploading ${state.uploading}%…",
                        style = MaterialTheme.typography.labelSmall,
                        color = Studio.Amber,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }

                state.pending?.let { pending ->
                    Row(
                        Modifier
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Studio.Surface)
                            .border(1.dp, Studio.Hairline, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            if (pending.image) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile,
                            null,
                            tint = Studio.Amethyst,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                pending.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = Studio.Ivory,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                readableSize(pending.bytes) + " · expires " +
                                    when (expires) {
                                        0 -> "never"
                                        60 -> "1 hour"
                                        1440 -> "24 hours"
                                        else -> "$expires min"
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                color = Studio.Dim,
                            )
                        }
                        IconAction(Icons.Outlined.Close, "Remove attachment", { vm.clearAttachment() })
                    }
                    Row(
                        Modifier.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StudioPill("Keep", selected = expires == 0, onClick = { expires = 0 })
                        StudioPill("1h", selected = expires == 60, onClick = { expires = 60 })
                        StudioPill("24h", selected = expires == 1440, onClick = { expires = 1440 })
                        StudioPill(
                            if (maxViews == 0) "∞ views" else "$maxViews views",
                            selected = true,
                            onClick = { maxViews = when (maxViews) { 0 -> 1; 1 -> 3; else -> 0 } },
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    IconAction(Icons.Outlined.AttachFile, "Attach", { showAttach = true }, tint = Studio.Muted, size = 22.dp)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { if (it.length <= 2000) draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Reply…", color = Studio.Dim) },
                        maxLines = 4,
                        shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (draft.isNotBlank() || state.pending != null) {
                                vm.send(draft, expires, maxViews)
                                draft = ""
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Studio.Hairline,
                            unfocusedBorderColor = Studio.Hairline,
                            focusedContainerColor = Studio.Surface,
                            unfocusedContainerColor = Studio.Surface,
                            cursorColor = Studio.Amber,
                            focusedTextColor = Studio.Ivory,
                            unfocusedTextColor = Studio.Ivory,
                        ),
                    )
                    val canSend = (draft.isNotBlank() || state.pending != null) && !state.sending
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(if (canSend) Studio.Amber else Studio.SurfaceHigh)
                            .clickable(enabled = canSend) {
                                vm.send(draft, expires, maxViews)
                                draft = ""
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Send,
                            contentDescription = "Send",
                            tint = if (canSend) Studio.OnAmber else Studio.Dim,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.loadingOlder) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Loading older…", style = MaterialTheme.typography.bodySmall, color = Studio.Dim)
                    }
                }
            }
            if (state.loading && rows.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(60.dp),
                        contentAlignment = Alignment.Center,
                    ) { androidx.compose.material3.CircularProgressIndicator(color = Studio.Amber, strokeWidth = 2.dp) }
                }
            } else if (rows.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Reply,
                        title = if (filter.isBlank()) "No messages yet" else "Nothing matches",
                        body = if (filter.isBlank()) "Write the first reply below." else "Try a different search.",
                    )
                }
            }
            items(rows, key = { it.key }) { row ->
                when (row.type) {
                    ChatRow.TYPE_DAY -> DayDivider(row.dayLabel ?: "")
                    else -> MessageBubble(
                        row = row,
                        pinned = row.message?.id?.let { state.pinned.contains(it) } == true,
                        onLongPress = { row.message?.let { actionMessage = it } },
                        onRetry = { row.message?.let { vm.retry(it) } },
                        onOpenAttachment = { row.message?.let { vm.openAttachment(it) } },
                    )
                }
            }
        }
    }

    actionMessage?.let { message ->
        ModalBottomSheet(
            onDismissRequest = { actionMessage = null },
            containerColor = Studio.Surface,
            dragHandle = null,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    message.message.ifBlank { message.attachmentName ?: "Message" }.take(120),
                    style = MaterialTheme.typography.titleSmall,
                    color = Studio.Muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                SheetRow(
                    icon = Icons.Outlined.PushPin,
                    label = if (state.pinned.contains(message.id)) "Unpin message" else "Pin message",
                ) {
                    vm.setPinned(message.id, !state.pinned.contains(message.id))
                    actionMessage = null
                }
                SheetRow(icon = Icons.Outlined.ContentCopy, label = "Copy text") {
                    clipboard.setText(AnnotatedString(message.message))
                    actionMessage = null
                }
                SheetRow(icon = Icons.Outlined.Delete, label = "Delete message", danger = true) {
                    actionMessage = null
                    confirmDelete = message
                }
            }
        }
    }

    confirmDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Studio.Surface,
            title = { Text("Delete this message?", color = Studio.Ivory) },
            text = { Text("It disappears for the member too.", color = Studio.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMessage(message.id)
                    confirmDelete = null
                }) { Text("Delete", color = Studio.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = Studio.Muted) }
            },
        )
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor = Studio.Surface,
            dragHandle = null,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Conversation", style = MaterialTheme.typography.headlineSmall, color = Studio.Ivory)
                ToggleRow(
                    "Favourite",
                    "Pin this conversation to Favourites.",
                    state.favourite,
                ) { vm.toggleFavourite() }
                ToggleRow(
                    "Member replies",
                    "Let the member write back in this conversation.",
                    state.replies,
                ) { vm.setReplies(!state.replies) }
                ToggleRow(
                    "Notifications",
                    "Notify me about new messages here.",
                    state.notifications,
                ) { vm.setNotifications(!state.notifications) }
                SectionLabel("Who answers", Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioPill("You", selected = state.aiMode == "operator", onClick = { vm.setMode("operator") })
                    StudioPill("Retrieval", selected = state.aiMode == "retrieval", onClick = { vm.setMode("retrieval") })
                    StudioPill("Fine-tuned", selected = state.aiMode == "finetuned", onClick = { vm.setMode("finetuned") })
                }
                SheetRow(icon = Icons.Outlined.Notifications, label = "Notification tone") {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                        putExtra(
                            RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            state.tone?.let { Uri.parse(it) },
                        )
                    }
                    toneLauncher.launch(intent)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showAttach) {
        ModalBottomSheet(
            onDismissRequest = { showAttach = false },
            containerColor = Studio.Surface,
            dragHandle = null,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Attach", style = MaterialTheme.typography.headlineSmall, color = Studio.Ivory)
                SheetRow(icon = Icons.Outlined.Image, label = "Take a photo") {
                    showAttach = false
                    launchCamera(false)
                }
                SheetRow(icon = Icons.Outlined.Videocam, label = "Record a video") {
                    showAttach = false
                    launchCamera(true)
                }
                SheetRow(icon = Icons.Outlined.InsertDriveFile, label = "Choose a file") {
                    showAttach = false
                    fileLauncher.launch("*/*")
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }

    if (showOutbox) {
        val items = vm.outboxItems()
        ModalBottomSheet(
            onDismissRequest = { showOutbox = false },
            containerColor = Studio.Surface,
            dragHandle = null,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Queued replies", style = MaterialTheme.typography.headlineSmall, color = Studio.Ivory)
                if (items.isEmpty()) {
                    Text("Nothing is queued.", style = MaterialTheme.typography.bodyMedium, color = Studio.Muted)
                }
                items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            item.message.ifBlank { "(attachment)" }.take(60),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Studio.Ivory,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { vm.retryOutbox() }) { Text("Retry", color = Studio.Amber) }
                        TextButton(onClick = { vm.discardOutbox(item.key) }) { Text("Discard", color = Studio.Coral) }
                    }
                }
                TextButton(onClick = { showOutbox = false }) { Text("Close", color = Studio.Muted) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    row: ChatRow,
    pinned: Boolean,
    onLongPress: () -> Unit,
    onRetry: () -> Unit,
    onOpenAttachment: () -> Unit,
) {
    val message = row.message ?: return
    val outgoing = row.type == ChatRow.TYPE_OUTGOING
    val delivery = row.delivery
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = if (outgoing) 18.dp else if (row.grouped) 6.dp else 4.dp,
                        topEnd = if (outgoing) if (row.grouped) 6.dp else 4.dp else 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 18.dp,
                    ),
                )
                .background(if (outgoing) Studio.Amber.copy(alpha = 0.13f) else Studio.SurfaceHigh)
                .border(
                    1.dp,
                    if (outgoing) Studio.Amber.copy(alpha = 0.24f) else Studio.Hairline,
                    RoundedCornerShape(18.dp),
                )
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (!outgoing && row.showHeader) {
                Text(
                    message.attachmentName?.let { "Member" } ?: "Member",
                    style = MaterialTheme.typography.labelSmall,
                    color = Studio.Amethyst,
                )
                Spacer(Modifier.height(2.dp))
            }
            if (message.message.isNotBlank()) {
                Text(
                    message.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Studio.Ivory,
                )
            }
            if (!message.attachmentName.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Studio.Ink.copy(alpha = 0.35f))
                        .clickable(enabled = !message.mediaExpired, onClick = onOpenAttachment)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        if ((message.attachmentType ?: "").startsWith("video/")) Icons.Outlined.Videocam
                        else Icons.Outlined.InsertDriveFile,
                        null,
                        tint = Studio.Ivory,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (message.mediaExpired) "Media expired" else message.attachmentName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Studio.Ivory,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (pinned) {
                    Icon(Icons.Outlined.PushPin, "Pinned", tint = Studio.Amber, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    when (delivery) {
                        Delivery.SENDING -> "sending…"
                        Delivery.QUEUED -> "queued"
                        Delivery.FAILED -> "failed — tap to retry"
                        Delivery.SENT -> row.timeLabel
                    },
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                    color = when (delivery) {
                        Delivery.QUEUED -> Studio.Amber
                        Delivery.FAILED -> Studio.Coral
                        else -> Studio.Dim
                    },
                    modifier = if (delivery == Delivery.FAILED) Modifier.clickable(onClick = onRetry) else Modifier,
                )
                if (outgoing && delivery == Delivery.SENT) {
                    Spacer(Modifier.size(4.dp))
                    Text("✓", style = MaterialTheme.typography.labelSmall, color = Studio.Amber)
                }
            }
        }
    }
}

@Composable
private fun DayDivider(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Studio.Hairline),
        )
        Text(
            label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall,
            color = Studio.Dim,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Studio.Hairline),
        )
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (danger) Studio.Coral else Studio.Muted, modifier = Modifier.size(19.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) Studio.Coral else Studio.Ivory,
        )
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Studio.Ivory)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Studio.Dim)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onChange() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Studio.OnAmber,
                checkedTrackColor = Studio.Amber,
                uncheckedThumbColor = Studio.Muted,
                uncheckedTrackColor = Studio.SurfaceHigh,
            ),
        )
    }
}

private fun readableSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
