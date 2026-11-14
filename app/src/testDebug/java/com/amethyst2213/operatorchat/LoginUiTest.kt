package com.amethyst2213.operatorchat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.login.LoginScreen
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
class LoginUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the connect form renders its fields`() {
        compose.setContent {
            StudioTheme {
                LoginScreen("", "", false, null, null, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithText("Server address").assertIsDisplayed()
        compose.onNodeWithText("Operator token").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test
    fun `connect hands over exactly what was typed`() {
        var url = ""
        var token = ""
        compose.setContent {
            StudioTheme {
                LoginScreen("", "", false, null, null, { u, t ->
                    url = u
                    token = t
                }, {}, {})
            }
        }
        compose.onNodeWithTag("login_url")
            .performTextReplacement("https://example.test/gallery")
        compose.onNodeWithTag("login_token")
            .performTextReplacement("device-token-123")
        compose.onNodeWithText("Connect").performClick()
        assertEquals("https://example.test/gallery", url)
        assertEquals("device-token-123", token)
    }

    @Test
    fun `an error is visible on the form`() {
        compose.setContent {
            StudioTheme {
                LoginScreen("", "", false, "The server refused that token.", null, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithText("The server refused that token.").assertIsDisplayed()
    }
}
