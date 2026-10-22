package com.amethyst2213.operatorchat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.screens.InboxScreen
import com.amethyst2213.operatorchat.ui.screens.LiveChatLine
import com.amethyst2213.operatorchat.ui.screens.LiveScreen
import com.amethyst2213.operatorchat.ui.screens.LoginScreen
import com.amethyst2213.operatorchat.ui.screens.ScheduleScreen
import com.amethyst2213.operatorchat.ui.screens.SettingsScreen
import com.amethyst2213.operatorchat.ui.screens.ThreadScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme
import com.amethyst2213.operatorchat.live.BroadcastState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every screen and writes a PNG, so the layout can actually be
 * LOOKED at instead of guessed at.
 *
 * The images land in build/screenshots/. They are not a test: the test
 * only fails when a screen cannot be rendered at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    // One test per screen: the Compose test rule allows exactly one
    // setContent() per test.
    @Test
    fun `the login screen renders`() { renderLogin() }

    @Test
    fun `the inbox renders`() { renderInbox() }

    @Test
    fun `the thread renders`() { renderThread() }

    @Test
    fun `the live console renders`() { renderLive() }

    @Test
    fun `the schedule renders`() { renderSchedule() }

    @Test
    fun `the settings render`() { renderSettings() }

    private fun shot(name: String) {
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().root
        val view = (root as? ViewRootForTest)?.view ?: return
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun renderLogin() {
        compose.setContent {
            OperatorTheme {
                LoginScreen(
                    initialUrl = "https://amethyst2213.com/gallery",
                    initialToken = "operator-token-here",
                    busy = false,
                    error = null,
                    onConnect = { _, _ -> },
                )
            }
        }
        shot("01-login")
    }

    private fun renderInbox() {
        compose.setContent {
            OperatorTheme {
                InboxScreen(
                    conversations = listOf(
                        conversation(1, "mara_lane", "mara@example.test", "Are you free tonight? I have the new set ready.", 3),
                        conversation(2, "jules_88", "jules@example.test", "Thanks for the tip, it worked!", 1),
                        conversation(3, "kit", "kit@example.test", "Sent you the gallery link.", 0),
                    ),
                    loading = false,
                    refreshing = false,
                    query = "",
                    error = null,
                    liveBadge = true,
                    nextShowTitle = "Friday night live",
                    onQueryChange = {},
                    onOpen = {},
                    onOpenLive = {},
                    onOpenSchedule = {},
                    onOpenSettings = {},
                    onRefresh = {},
                )
            }
        }
        shot("02-inbox")
    }

    private fun renderThread() {
        compose.setContent {
            OperatorTheme {
                ThreadScreen(
                    conversationId = 1,
                    title = "mara_lane",
                    subtitle = "Replies on",
                    rows = emptyList(),
                    draft = "Yes - I am free at eight.",
                    sending = false,
                    queued = 0,
                    typing = true,
                    canReply = true,
                    memberReplyEnabled = true,
                    aiMode = "operator",
                    pinnedIds = emptySet(),
                    searchOpen = false,
                    searchQuery = "",
                    error = null,
                    onDraftChange = {},
                    onSend = {},
                    onAttach = {},
                    onToggleSearch = {},
                    onSearchQueryChange = {},
                    onPin = {},
                    onUnpin = {},
                    onDelete = {},
                    onCopy = {},
                    onToggleMemberReplies = {},
                    onModeChange = {},
                    onLoadOlder = {},
                    onBack = {},
                    outboxText = null,
                    outboxActionLabel = "",
                    onOutboxAction = {},
                )
            }
        }
        shot("03-thread")
    }

    private fun renderLive() {
        compose.setContent {
            OperatorTheme {
                LiveScreen(
                    state = BroadcastState.Live(),
                    viewers = 128,
                    since = "21:14",
                    micMuted = false,
                    paused = false,
                    chat = listOf(
                        LiveChatLine("mara_lane", "Hello!", "21:15", false),
                        LiveChatLine("Operator", "Welcome everyone.", "21:15", true),
                        LiveChatLine("jules_88", "The sound is a bit low", "21:16", false),
                    ),
                    chatDraft = "",
                    error = null,
                    onGoLive = {},
                    onStop = {},
                    onFlipCamera = {},
                    onToggleMic = {},
                    onTogglePause = {},
                    onChatDraftChange = {},
                    onSendChat = {},
                    onSurfaceReady = { _, _, _ -> },
                    // A real TextureView cannot be drawn off-device, so the
                    // preview is a placeholder here.
                    preview = {
                        androidx.compose.foundation.layout.Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(androidx.compose.ui.graphics.Color(0xFF101018)),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            androidx.compose.material3.Text(
                                text = "camera preview",
                                color = androidx.compose.ui.graphics.Color(0xFF6E6E7C),
                            )
                        }
                    },
                )
            }
        }
        shot("04-live")
    }

    private fun renderSchedule() {
        compose.setContent {
            OperatorTheme {
                ScheduleScreen(
                    shows = listOf(
                        LiveToolsBridge.Show(
                            id = 1,
                            title = "Friday night live",
                            description = "New set, and the members' picks.",
                            startsAt = "2026-10-24 20:00",
                            startsAtUtc = "2026-10-25T02:00:00Z",
                            duration = 90,
                            status = "scheduled",
                            seconds = 7_200,
                        ),
                    ),
                    busy = false,
                    error = null,
                    defaultStart = "2026-10-24 20:00",
                    onSchedule = { _, _, _, _ -> },
                    onCancel = {},
                )
            }
        }
        shot("05-schedule")
    }

    private fun renderSettings() {
        compose.setContent {
            OperatorTheme {
                SettingsScreen(
                    serverUrl = "https://amethyst2213.com/gallery",
                    token = "operator-token-here",
                    notificationsOn = true,
                    biometricOn = true,
                    quietOn = true,
                    quietStart = "22:00",
                    quietEnd = "08:00",
                    toneName = "Default",
                    siteTimezone = "America/Denver",
                    appVersion = "v3.0 (62)",
                    lastSync = "2026-10-21 12:04:33",
                    lastError = null,
                    status = "The server answered in 182 ms.",
                    error = null,
                    onServerUrlChange = {},
                    onTokenChange = {},
                    onNotificationsChange = {},
                    onBiometricChange = {},
                    onQuietChange = {},
                    onQuietStartChange = {},
                    onQuietEndChange = {},
                    onPickTone = {},
                    onSaveConnection = {},
                    onPing = {},
                    onCheckUpdate = {},
                    onDisconnect = {},
                )
            }
        }
        shot("06-settings")
    }

    private fun conversation(
        id: Long,
        username: String,
        email: String,
        last: String,
        unread: Int,
    ) = ChatBridge.Conversation(
        id = id,
        userEmail = email,
        username = username,
        aiMode = "operator",
        status = "active",
        lastMessage = last,
        lastSender = "user",
        lastMessageAt = "2026-10-21T12:0$id:00Z",
        memberCount = 1,
        unreadReplyable = unread,
        updatedAt = "2026-10-21T12:0$id:00Z",
        memberReplyEnabled = true,
        canChat = true,
        favorite = id == 1L,
    )
}
