package com.amethyst2213.operatorchat.ui.live

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amethyst2213.operatorchat.live.BroadcastState
import com.amethyst2213.operatorchat.live.LiveChatMessage
import com.amethyst2213.operatorchat.ui.components.DangerButton
import com.amethyst2213.operatorchat.ui.components.ErrorStrip
import com.amethyst2213.operatorchat.ui.components.IconAction
import com.amethyst2213.operatorchat.ui.components.LivePill
import com.amethyst2213.operatorchat.ui.components.PrimaryButton
import com.amethyst2213.operatorchat.ui.components.StudioCard
import com.amethyst2213.operatorchat.ui.components.StudioTopBar
import com.amethyst2213.operatorchat.ui.theme.Studio

@Composable
fun LiveScreen(vm: LiveViewModel, state: LiveViewModel.Ui) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var pendingGo by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // With the keyboard open there is no room for a 16:9 preview, a control
    // row and the chat on a phone: the chat and the composer used to end up
    // underneath the keyboard. The keyboard collapses the preview; focus
    // alone must NOT keep it collapsed (the field keeps focus after the IME
    // closes, which hid the Go live/Stop controls).
    val compact = rememberKeyboardOpen()

    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val camera = result[Manifest.permission.CAMERA] == true
        vm.permissionsChanged(camera)
        if (pendingGo && camera) {
            pendingGo = false
            vm.goLive()
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> vm.setChatVisible(true)
                Lifecycle.Event.ON_STOP -> vm.setChatVisible(false)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            vm.onScreenLeft()
        }
    }
    LaunchedEffect(cameraGranted) {
        if (cameraGranted) vm.permissionsChanged(true)
    }
    LaunchedEffect(state.chat.size) {
        if (state.chat.isNotEmpty()) listState.animateScrollToItem(state.chat.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        StudioTopBar(
            title = "Live",
            subtitle = when (val s = state.state) {
                is BroadcastState.Live -> "${state.viewers} watching · ${if (state.paused) "paused" else "on air"}"
                BroadcastState.Preparing -> "Starting the camera…"
                BroadcastState.Connecting -> "Connecting to the server…"
                is BroadcastState.Failed -> "Not live"
                is BroadcastState.Stopped -> "Stopped"
                else -> "Not live"
            },
            actions = { if (state.state is BroadcastState.Live) LivePill() },
        )

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!compact) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Studio.InkSoft),
            ) {
                CameraPreview(
                    onSurface = { texture, width, height -> vm.attachPreview(texture, width, height) },
                )
                if (cameraGranted && state.state is BroadcastState.Idle) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Studio.Ink.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "The camera preview starts when you go live.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Studio.Muted,
                        )
                    }
                }
                if (!cameraGranted) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.Sensors, null, tint = Studio.Dim, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Camera permission is needed to go live.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Studio.Muted,
                        )
                    }
                }
                if (state.state is BroadcastState.Live) {
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                    ) { LivePill(if (state.paused) "PAUSED" else "LIVE") }
                }
                if (state.recording) {
                    Row(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Studio.Ink.copy(alpha = 0.7f))
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Studio.Coral),
                        )
                        Text("REC", style = MaterialTheme.typography.labelSmall, color = Studio.Ivory)
                    }
                }
                if (state.paused) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Studio.Ink.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Viewers see a frozen frame",
                            style = MaterialTheme.typography.titleSmall,
                            color = Studio.Ivory,
                        )
                    }
                }
            }

            if (!state.startError.isNullOrBlank()) {
                ErrorStrip(state.startError, onRetry = { vm.goLive() })
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.state.isLive) {
                    DangerButton(
                        text = "Stop broadcast",
                        onClick = { vm.stop() },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Stop,
                    )
                } else {
                    PrimaryButton(
                        text = when {
                            state.state is BroadcastState.Preparing -> "Preparing…"
                            state.state is BroadcastState.Connecting -> "Connecting…"
                            state.state is BroadcastState.Failed -> "Try again"
                            else -> "Go live"
                        },
                        onClick = {
                            if (!cameraGranted) {
                                pendingGo = true
                                permissionLauncher.launch(
                                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                                )
                            } else {
                                vm.goLive()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        loading = state.state is BroadcastState.Preparing ||
                            state.state is BroadcastState.Connecting,
                        icon = Icons.Outlined.Sensors,
                    )
                }
                if (state.state.isLive) {
                    IconAction(
                        if (state.micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                        if (state.micMuted) "Unmute" else "Mute",
                        { vm.toggleMute() },
                        tint = if (state.micMuted) Studio.Coral else Studio.Ivory,
                    )
                    IconAction(
                        if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                        if (state.paused) "Resume" else "Pause",
                        { vm.togglePause() },
                        tint = Studio.Ivory,
                    )
                    IconAction(Icons.Outlined.FlipCameraAndroid, "Flip camera", { vm.flipCamera() }, tint = Studio.Ivory)
                    IconAction(
                        Icons.Outlined.FiberManualRecord,
                        if (state.recording) "Stop recording" else "Record",
                        { vm.toggleRecording() },
                        tint = if (state.recording) Studio.Coral else Studio.Ivory,
                    )
                }
            }

            }
            if (!state.note.isNullOrBlank() && !state.state.isLive) {
                Text(
                    state.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Muted,
                )
            }
            if (!micGranted && cameraGranted) {
                Text(
                    "No microphone permission: the broadcast will have no sound.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Amber,
                )
            }

            Text(
                "Live chat",
                style = MaterialTheme.typography.labelSmall,
                color = Studio.Dim,
                modifier = Modifier.padding(top = 4.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.chat, key = { it.id }) { message -> LiveChatRow(message) }
                if (state.chat.isEmpty()) {
                    item {
                        Text(
                            "Chat messages from members appear here while you are live.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Studio.Dim,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            if (!state.chatError.isNullOrBlank()) {
                Text(state.chatError, style = MaterialTheme.typography.bodySmall, color = Studio.Coral)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 500) draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (state.state.isLive) "Message the viewers…" else "Go live to chat",
                            color = Studio.Dim,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (state.state.isLive && draft.isNotBlank()) {
                            vm.sendChat(draft)
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
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (state.state.isLive && draft.isNotBlank()) Studio.Amber else Studio.SurfaceHigh)
                        .clickable(enabled = state.state.isLive && draft.isNotBlank()) {
                            vm.sendChat(draft)
                            draft = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Send,
                        "Send",
                        tint = if (state.state.isLive && draft.isNotBlank()) Studio.OnAmber else Studio.Dim,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/**
 * Whether the keyboard is covering the lower part of the window.
 *
 * `WindowInsets.ime` reports 0 on this activity: with
 * `decorFitsSystemWindows(true)` and `adjustResize` the system RESIZES the
 * window instead of dispatching an IME inset. The reliable signal is the
 * activity view getting shorter than the screen.
 */
@Composable
private fun rememberKeyboardOpen(): Boolean {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    var heightPx by remember { mutableStateOf(view.height) }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener { heightPx = view.height }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    val heightDp = with(density) { heightPx.toDp().value }
    return heightDp > 0f && heightDp < screenHeightDp * 0.8f
}

@Composable
private fun CameraPreview(onSurface: (SurfaceTexture, Int, Int) -> Unit) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        onSurface(surface, width, height)
                    }
                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun LiveChatRow(message: LiveChatMessage) {
    val operator = message.senderRole == "operator"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (operator) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(if (operator) Studio.Amber.copy(alpha = 0.13f) else Studio.SurfaceHigh)
                .padding(horizontal = 11.dp, vertical = 7.dp)
                .widthIn(max = 280.dp),
        ) {
            if (!operator) {
                Text(
                    message.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Studio.Amethyst,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                message.message,
                style = MaterialTheme.typography.bodyMedium,
                color = Studio.Ivory,
            )
        }
    }
}
