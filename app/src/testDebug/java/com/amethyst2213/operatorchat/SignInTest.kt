package com.amethyst2213.operatorchat

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Signing in.
 *
 * The connect button read the token back out of the keystore instead of
 * using the one the operator had just typed, so on a fresh install (or after
 * a disconnect) every request went out with an empty bearer token and the
 * app could never sign in at all.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class SignInTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `the token that is typed is the token that is stored and used`() {
        val context = compose.activity
        SecurePrefs.clear(context)

        // The update check goes out to the network when the operator
        // connects. The throttle is honoured, so a recent check means no
        // request - and the test does not wait for the network.
        context.getSharedPreferences("operator_chat", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong("update_checked_at", System.currentTimeMillis())
            .apply()

        // The first field is the server address, the second the token.
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("http://127.0.0.1:9/gallery")
        fields[1].performTextInput("the-typed-token")
        compose.onNodeWithText("Connect").performClick()
        compose.waitForIdle()

        assertEquals("the typed token was not stored", "the-typed-token", SecurePrefs.token(context))
        assertEquals("the server address was not stored", "http://127.0.0.1:9/gallery", SecurePrefs.url(context))
    }
}
