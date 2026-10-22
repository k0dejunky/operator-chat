package com.amethyst2213.operatorchat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.screens.InboxScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The inbox shows WHY it is empty.
 *
 * A rejected token or an unreachable server used to leave the operator
 * looking at an empty list with no explanation at all - the error was
 * passed to the screen and never drawn.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [34], qualifiers = "w411dp-h891dp")
class InboxScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the error is on the screen`() {
        compose.setContent {
            OperatorTheme {
                InboxScreen(
                    conversations = emptyList(),
                    loading = false,
                    refreshing = false,
                    query = "",
                    error = "Unauthorized - check the token.",
                    liveBadge = false,
                    nextShowTitle = null,
                    onQueryChange = {},
                    onOpen = {},
                    onOpenLive = {},
                    onOpenSchedule = {},
                    onOpenSettings = {},
                    onRefresh = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Unauthorized - check the token.").assertIsDisplayed()
    }

    @Test
    fun `the scope offers the conversations that were already answered`() {
        compose.setContent {
            OperatorTheme {
                InboxScreen(
                    conversations = emptyList(),
                    loading = false,
                    refreshing = false,
                    query = "",
                    error = null,
                    liveBadge = false,
                    nextShowTitle = null,
                    onQueryChange = {},
                    onOpen = {},
                    onOpenLive = {},
                    onOpenSchedule = {},
                    onOpenSettings = {},
                    onRefresh = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("All conversations").assertIsDisplayed()
        compose.onNodeWithText("Unread").assertIsDisplayed()
    }
}
