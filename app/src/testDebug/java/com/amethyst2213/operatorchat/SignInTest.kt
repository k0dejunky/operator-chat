package com.amethyst2213.operatorchat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Signing in.
 *
 * The connect button read the token back out of the keystore instead of
 * using the one the operator had just typed, so on a fresh install (or after
 * a disconnect) every request went out with an empty bearer token and the
 * app could never sign in at all.
 *
 * The test drives the activity directly: the Compose test harness waits for
 * the screen to be idle, and the blinking cursor of the text field never
 * lets it be.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class SignInTest {

    @Test
    fun `the token that is typed is the token that is stored and used`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SecurePrefs.clear(app)

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.setup()
            val activity = controller.get()

            // What the sign-in screen hands over: the address and the token
            // the operator typed.
            activity.connect("http://127.0.0.1:9/gallery", "the-typed-token")

            assertEquals("the typed token was not stored", "the-typed-token", SecurePrefs.token(app))
            assertEquals(
                "the server address was not stored",
                "http://127.0.0.1:9/gallery",
                SecurePrefs.url(app),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `an empty token is refused instead of signing in with nothing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        SecurePrefs.clear(app)

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        try {
            controller.setup()
            val activity = controller.get()
            activity.connect("http://127.0.0.1:9/gallery", "   ")
            assertEquals("a token was stored out of nothing", "", SecurePrefs.token(app))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
