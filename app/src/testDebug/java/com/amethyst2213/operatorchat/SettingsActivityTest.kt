package com.amethyst2213.operatorchat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * The settings screen opens.
 *
 * Its field initialisers read the stored connection with `SecurePrefs.url(this)`
 * - and an initialiser runs while the object is still being constructed, when
 * `this` is not yet a usable context. The screen therefore crashed the moment
 * it was opened:
 *
 *   Unable to instantiate activity SettingsActivity:
 *   java.lang.NullPointerException: ... on a null object reference
 *     at SecurePrefs.read ... at SettingsActivity.<init>
 *
 * The emulator found it; no unit test could, because the activity is never
 * constructed there.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class SettingsActivityTest {

    @Test
    fun `the settings screen opens without crashing`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        try {
            controller.setup()
            assertNotNull("the activity was not created", controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
