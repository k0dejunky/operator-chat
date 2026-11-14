package com.amethyst2213.operatorchat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.inbox.InboxScreen
import com.amethyst2213.operatorchat.ui.inbox.InboxViewModel
import com.amethyst2213.operatorchat.ui.theme.StudioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [34], qualifiers = "w411dp-h891dp")
class InboxUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun conversation(id: Long, name: String, unread: Int) = ChatBridge.Conversation(
        id = id,
        userEmail = "$name@example.test",
        username = name,
        aiMode = "operator",
        status = "open",
        lastMessage = "Hello from $name",
        lastSender = "user",
        lastMessageAt = "2026-11-14 10:00:00",
        memberCount = 1,
        unreadReplyable = unread,
        updatedAt = "2026-11-14 10:00:00",
    )

    @androidx.compose.runtime.Composable
    private fun Screen(
        state: InboxViewModel.Ui,
        onMode: (InboxViewModel.Mode) -> Unit = {},
    ) {
        StudioTheme {
            InboxScreen(
                state = state,
                update = null,
                onRefresh = {},
                onLoadMore = {},
                onMode = onMode,
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

    @Test
    fun `the unread queue names the member and the last message`() {
        compose.setContent {
            Screen(
                InboxViewModel.Ui(
                    items = listOf(conversation(1, "mara_lane", 3), conversation(2, "jules_88", 1)),
                ),
            )
        }
        compose.onNodeWithText("mara_lane").assertIsDisplayed()
        compose.onNodeWithText("Hello from mara_lane").assertIsDisplayed()
        compose.onNodeWithText("NEEDS A REPLY").assertIsDisplayed()
    }

    @Test
    fun `the filters are real buttons`() {
        var mode: InboxViewModel.Mode? = null
        compose.setContent { Screen(InboxViewModel.Ui(items = emptyList()), onMode = { mode = it }) }
        compose.onNodeWithText("All").performClick()
        assertEquals(InboxViewModel.Mode.ALL, mode)
    }

    @Test
    fun `an empty queue says what to do next`() {
        compose.setContent { Screen(InboxViewModel.Ui(items = emptyList())) }
        compose.onNodeWithText("Nothing needs a reply").assertIsDisplayed()
    }
}
