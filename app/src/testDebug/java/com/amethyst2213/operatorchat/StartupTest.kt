package com.amethyst2213.operatorchat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The session spine without a server: validation, sign-out and the lock.
 * (The network paths are covered by the transport tests and the live E2E.)
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class StartupTest {

    private lateinit var app: Application
    private lateinit var vm: AppViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        SecurePrefs.clear(app)
        Passcode.clear(app)
        vm = AppViewModel(app)
    }

    @Test
    fun `empty credentials are refused before any request`() {
        vm.connect("", "")
        assertTrue(vm.state.value.error?.contains("required") == true)
        assertFalse(vm.state.value.signedIn)
    }

    @Test
    fun `an address without a scheme is refused`() {
        vm.connect("amethyst2213.com/gallery", "token")
        assertTrue(vm.state.value.error?.contains("must start with") == true)
    }

    @Test
    fun `sign out clears the session`() {
        SecurePrefs.save(app, "https://example.test/gallery", "token")
        vm.signOut(wipePasscode = false)
        assertFalse(vm.state.value.signedIn)
        assertTrue(SecurePrefs.url(app).isEmpty())
    }

    @Test
    fun `unlocking without a passcode says so`() {
        vm.unlockWithPin("1234")
        assertTrue(vm.state.value.error?.contains("No passcode") == true)
    }
}
