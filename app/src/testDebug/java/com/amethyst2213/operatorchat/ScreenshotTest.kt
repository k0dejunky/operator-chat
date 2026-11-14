package com.amethyst2213.operatorchat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.inbox.InboxScreen
import com.amethyst2213.operatorchat.ui.inbox.InboxViewModel
import com.amethyst2213.operatorchat.ui.login.LoginScreen
import com.amethyst2213.operatorchat.ui.schedule.ScheduleScreen
import com.amethyst2213.operatorchat.ui.schedule.ScheduleViewModel
import com.amethyst2213.operatorchat.ui.settings.SettingsScreen
import com.amethyst2213.operatorchat.ui.settings.SettingsViewModel
import com.amethyst2213.operatorchat.ui.theme.StudioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the v4 screens and writes a PNG of each, so the layout can be
 * LOOKED at. Not an assertion suite: it fails only if a screen cannot render.
 * Images land in app/build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Test
    fun `the login screen renders`() {
        compose.setContent {
            StudioTheme {
                LoginScreen("", "", false, null, null, { _, _ -> }, {}, {})
            }
        }
        shot("01-login")
    }

    @Test
    fun `the inbox renders`() {
        val items = listOf(
            conversation(1, "mara_lane", "Are you free tonight? I have the new set ready.", 3, true),
            conversation(2, "jules_88", "Thanks for the tip, it worked!", 1, false),
            conversation(3, "kit", "Sent you the gallery link.", 0, false),
        )
        compose.setContent {
            StudioTheme {
                InboxScreen(
                    state = InboxViewModel.Ui(items = items, mode = InboxViewModel.Mode.NEEDS_REPLY),
                    update = null,
                    onRefresh = {},
                    onLoadMore = {},
                    onMode = {},
                    onQuery = {},
                    onToggleFavorite = {},
                    onOpen = {},
                    onSearchUsers = {},
                    onStartConversation = {},
                    onInstallUpdate = {},
                    onDismissUpdate = {},
                )
            }
        }
        shot("02-inbox")
    }

    @Test
    fun `the schedule renders`() {
        compose.setContent {
            StudioTheme {
                ScheduleScreen(
                    state = ScheduleViewModel.Ui(
                        loading = false,
                        shows = listOf(
                            show(1, "Friday night live", "2026-11-20 20:00", 5 * 86400L),
                            show(2, "Sunday morning set", "2026-11-22 10:00", 7 * 86400L),
                        ),
                    ),
                    onRefresh = {},
                    onSchedule = { _, _, _, _ -> },
                    onCancel = {},
                    onClearError = {},
                )
            }
        }
        shot("03-schedule")
    }

    @Test
    fun `the settings render`() {
        compose.setContent {
            StudioTheme {
                SettingsScreen(
                    state = SettingsViewModel.Ui(
                        url = "https://amethyst2213.com/gallery",
                        token = "••••••••",
                        notifications = true,
                        quiet = true,
                        quietStart = "22:00",
                        quietEnd = "08:00",
                        toneName = "Aurora",
                        siteZone = "America/Denver",
                        status = "The server answered in 84 ms.",
                        lastSync = "2026-11-14 09:12:00",
                        passcodeSet = true,
                        version = "v4.0 (70)",
                    ),
                    update = null,
                    lockEnabled = true,
                    biometricAvailable = true,
                    onUrl = {},
                    onToken = {},
                    onSave = {},
                    onTest = {},
                    onNotifications = {},
                    onQuiet = {},
                    onQuietStart = {},
                    onQuietEnd = {},
                    onTone = {},
                    onLockChanged = {},
                    onSetPasscode = { true },
                    onRemovePasscode = {},
                    onCheckUpdate = {},
                    onInstallUpdate = {},
                    onDismissUpdate = {},
                    onDisconnect = {},
                    onClearStatus = {},
                )
            }
        }
        shot("04-settings")
    }

    private fun conversation(
        id: Long,
        name: String,
        last: String,
        unread: Int,
        favourite: Boolean,
    ): ChatBridge.Conversation = ChatBridge.Conversation(
        id = id,
        userEmail = "$name@example.test",
        username = name,
        aiMode = "operator",
        status = "open",
        lastMessage = last,
        lastSender = "user",
        lastMessageAt = "2026-11-14 09:20:00",
        memberCount = 1,
        unreadReplyable = unread,
        updatedAt = "2026-11-14 09:20:00",
        favorite = favourite,
    )

    private fun show(id: Long, title: String, start: String, seconds: Long) = LiveToolsBridge.Show(
        id = id,
        title = title,
        description = "",
        startsAt = start,
        startsAtUtc = start,
        duration = 90,
        status = "scheduled",
        seconds = seconds,
    )

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
}
