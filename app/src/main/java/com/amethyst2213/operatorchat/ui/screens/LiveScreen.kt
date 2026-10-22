package com.amethyst2213.operatorchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.live.BroadcastState
import com.amethyst2213.operatorchat.ui.components.DangerButton
import com.amethyst2213.operatorchat.ui.components.ErrorBanner
import com.amethyst2213.operatorchat.ui.components.LiveBadge
import com.amethyst2213.operatorchat.ui.components.StatusPill
import com.amethyst2213.operatorchat.ui.theme.Palette

/**
 * The live console.
 *
 * The old screen was a preview on top, five buttons under it and a chat
 * list that grew a TextView per message. Everything that could go wrong
 * did: the stream died with the app, a rotation killed it silently, and
 * a failed connection still looked like a live one.
 */
@Composable
fun LiveScreen(
    state: BroadcastState,
    viewers: Int,
    since: String,
    micMuted: Boolean,
    paused: Boolean,
    chat: List<LiveChatLine>,
    chatDraft: String,
    error: String?,
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleMic: () -> Unit,
    onTogglePause: () -> Unit,
    onToggleRecord: () -> Unit = {},
    recording: Boolean = false,
    recordFile: String? = null,
    onChatDraftChange: (String) -> Unit,
    onSendChat: () -> Unit,
    onSurfaceReady: (android.graphics.SurfaceTexture, Int, Int) -> Unit,
    // The camera preview. It is a parameter so the screen can be rendered
    // (and looked at) without a real camera: a TextureView cannot be
    // drawn off-device.
    preview: @Composable () -> Unit = { CameraPreview(onSurfaceReady) },
) {
    val listState = rememberLazyListState()

    // The chat follows the newest message, but only while the operator is
    // already at the bottom.
    var atBottom by remember { mutableStateOf(true) }
    LaunchedEffect(chat.size, atBottom) {
        if (atBottom && chat.isNotEmpty()) {
            listState.animateScrollToItem(chat.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        // The header: the state, plainly.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Palette.SurfaceCard)
                .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Live",
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.TextPrimary,
                )
                Text(
                    text = when (state) {
                        is BroadcastState.Live -> "$viewers watching" + if (since.isNotBlank()) " · since $since" else ""
                        is BroadcastState.Preparing -> "Starting the camera…"
                        is BroadcastState.Connecting -> "Connecting to the server…"
                        is BroadcastState.Stopped -> state.reason.ifBlank { "Stopped" }
                        is BroadcastState.Failed -> state.reason
                        BroadcastState.Idle -> "Not live"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is BroadcastState.Failed) Palette.Danger else Palette.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (recording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.FiberManualRecord,
                        contentDescription = "Recording",
                        tint = Palette.Live,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "REC",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.Live,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            LiveBadge(
                live = state.isLive,
                paused = paused,
            )
        }

        if (!error.isNullOrBlank()) {
            ErrorBanner(error, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }

        // The preview. It is a fixed box: the old one was inside a
        // ScrollView, and scrolling destroyed the surface for good.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .background(Color.Black)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            preview()
        }

        // The controls.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (state) {
                is BroadcastState.Live -> {
                    IconButton(onClick = onToggleMic) {
                        Icon(
                            imageVector = if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (micMuted) "Unmute the microphone" else "Mute the microphone",
                            tint = if (micMuted) Palette.Warning else Palette.TextPrimary,
                        )
                    }
                    IconButton(onClick = onTogglePause) {
                        Icon(
                            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (paused) "Resume the picture" else "Pause the picture",
                            tint = if (paused) Palette.Warning else Palette.TextPrimary,
                        )
                    }
                    IconButton(onClick = onToggleRecord) {
                        Icon(
                            imageVector = Icons.Filled.FiberManualRecord,
                            contentDescription = if (recording) {
                                "Stop the recording"
                            } else {
                                "Record the broadcast on this phone"
                            },
                            tint = if (recording) Palette.Live else Palette.TextPrimary,
                        )
                    }
                    IconButton(onClick = onFlipCamera) {
                        Icon(
                            imageVector = Icons.Filled.Cameraswitch,
                            contentDescription = "Switch camera",
                            tint = Palette.TextPrimary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    DangerButton(
                        text = "Stop",
                        onClick = onStop,
                        icon = Icons.Filled.Stop,
                    )
                }

                else -> {
                    IconButton(onClick = onFlipCamera) {
                        Icon(
                            imageVector = Icons.Outlined.Cameraswitch,
                            contentDescription = "Switch camera",
                            tint = Palette.TextSecondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    StatusPill(
                        text = when (state) {
                            is BroadcastState.Preparing -> "Preparing…"
                            is BroadcastState.Connecting -> "Connecting…"
                            is BroadcastState.Failed -> "Try again"
                            else -> "Go live"
                        },
                        background = if (state is BroadcastState.Failed) {
                            Palette.Amethyst
                        } else {
                            Palette.SurfaceElevated
                        },
                        foreground = if (state is BroadcastState.Failed) {
                            Palette.White
                        } else {
                            Palette.TextSecondary
                        },
                        modifier = Modifier.clickable(
                            enabled = state !is BroadcastState.Preparing && state !is BroadcastState.Connecting,
                            onClick = onGoLive,
                        ),
                    )
                }
            }
        }

        if (recording && !recordFile.isNullOrBlank()) {
            Text(
                text = "Saving to $recordFile",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        // The live chat.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Live chat",
                style = MaterialTheme.typography.titleSmall,
                color = Palette.TextSecondary,
            )
            Spacer(Modifier.width(8.dp))
            StatusPill(
                text = "${chat.size}",
                background = Palette.SurfaceElevated,
                foreground = Palette.TextSecondary,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            items(chat) { line ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = line.who,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (line.operator) Palette.AmethystLight else Palette.TextSecondary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = line.at,
                            style = MaterialTheme.typography.labelSmall,
                            color = Palette.TextTertiary,
                        )
                    }
                    Text(
                        text = line.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextPrimary,
                    )
                }
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
            OutlinedTextField(
                value = chatDraft,
                onValueChange = onChatDraftChange,
                placeholder = { Text("Say something…") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onSendChat, enabled = chatDraft.isNotBlank()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send",
                    tint = if (chatDraft.isNotBlank()) Palette.Amethyst else Palette.TextTertiary,
                )
            }
        }
    }
}

/**
 * The camera preview.
 *
 * A TextureView, and NOT a SurfaceView: the operator has to be able to
 * read the chat that sits under the preview, and a SurfaceView punches a
 * hole through the window.
 */
@Composable
private fun CameraPreview(onSurfaceReady: (android.graphics.SurfaceTexture, Int, Int) -> Unit) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context ->
            android.view.TextureView(context).apply {
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        onSurfaceReady(s, w, h)
                    }

                    override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, w: Int, h: Int) {}

                    override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean = true

                    override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** One line of the live chat. */
data class LiveChatLine(
    val who: String,
    val message: String,
    val at: String,
    val operator: Boolean,
)
