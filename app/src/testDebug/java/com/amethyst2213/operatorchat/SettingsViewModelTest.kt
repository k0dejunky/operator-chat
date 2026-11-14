package com.amethyst2213.operatorchat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.settings.SettingsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class SettingsViewModelTest {

    private lateinit var app: Application
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Passcode.clear(app)
        app.getSharedPreferences("operator_chat", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
        vm = SettingsViewModel(app)
    }

    @Test
    fun `a passcode can be set and removed`() {
        assertTrue(vm.setPasscode("4821"))
        assertTrue(Passcode.isSet(app))
        vm.removePasscode()
        assertFalse(Passcode.isSet(app))
    }

    @Test
    fun `quiet hours persist`() {
        vm.setQuiet(true)
        vm.setQuietStart("23:15")
        vm.setQuietEnd("07:45")
        val prefs = app.getSharedPreferences("operator_chat", android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("quiet_hours_enabled", false))
        assertEquals("23:15", prefs.getString("quiet_start", ""))
        assertEquals("07:45", prefs.getString("quiet_end", ""))
        assertEquals("23:15", vm.state.value.quietStart)
    }

    @Test
    fun `notification switch persists`() {
        vm.setNotifications(false)
        val prefs = app.getSharedPreferences("operator_chat", android.content.Context.MODE_PRIVATE)
        assertFalse(prefs.getBoolean("notify_enabled", true))
        assertFalse(vm.state.value.notifications)
    }
}
