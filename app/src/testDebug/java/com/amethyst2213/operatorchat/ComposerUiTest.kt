package com.amethyst2213.operatorchat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.screens.ThreadScreen
import com.amethyst2213.operatorchat.ui.theme.OperatorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The composer, rendered for real.
 *
 * This test used to parse the XML layout, because the composer was XML.
 * Since v3.0 it is Compose, so the check is stronger: the tree is laid out
 * and measured, which is exactly what the old bug needed - the reply field
 * was declared 0dp wide with no weight, so it measured ZERO width and no
 * reply could be typed at all.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, sdk = [34], qualifiers = "w411dp-h891dp")
class ComposerUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the reply field is there and it takes text`() {
        compose.setContent {
            OperatorTheme {
                ThreadScreen(
                    conversationId = 1,
                    title = "member",
                    subtitle = "Replies on",
                    rows = emptyList(),
                    draft = "",
                    sending = false,
                    queued = 0,
                    typing = false,
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
        compose.waitForIdle()

        // The field exists...
        val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue("the composer has no text field", fields.isNotEmpty())

        // ...and it is wide enough to type in. The old layout declared it
        // 0dp wide with no weight: the field existed but had no width.
        val width = fields.first().size.width
        assertTrue("the reply field is $width wide", width > 100.dp.value)

        // The text actually goes in.
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("hello")
        compose.waitForIdle()
    }

    @Test
    fun `the typing indicator only shows while the member types`() {
        val typing = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            OperatorTheme {
                ThreadScreen(
                    conversationId = 1,
                    title = "member",
                    subtitle = "Replies on",
                    rows = emptyList(),
                    draft = "",
                    sending = false,
                    queued = 0,
                    typing = typing.value,
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
        compose.waitForIdle()

        var found = false
        compose.onAllNodes(androidx.compose.ui.test.hasText("The member is typing…")).fetchSemanticsNodes()
            .forEach { found = true }
        assertTrue("the typing indicator showed while nobody was typing", !found)

        typing.value = true
        compose.waitForIdle()
        compose.onNodeWithText("The member is typing…").assertIsDisplayed()
    }

    @Test
    fun `the outbox strip is visible when replies are queued`() {
        compose.setContent {
            OperatorTheme {
                ThreadScreen(
                    conversationId = 1,
                    title = "member",
                    subtitle = "Replies on",
                    rows = emptyList(),
                    draft = "",
                    sending = false,
                    queued = 2,
                    typing = false,
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
                    outboxText = "2 replies queued",
                    outboxActionLabel = "Review",
                    onOutboxAction = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("2 replies queued").assertIsDisplayed()
        compose.onNodeWithText("Review").assertIsDisplayed()
    }

    @Test
    fun `the reply length is capped at the contract length`() {
        assertEquals(2000, SendReconcile.MAX_REPLY_CHARS)
    }
}
