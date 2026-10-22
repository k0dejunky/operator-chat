package com.amethyst2213.operatorchat

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amethyst2213.operatorchat.ui.screens.InboxScreen
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The inbox: which conversations are listed.
 *
 * The inbox used to keep ONLY the conversations with an unread member
 * message, and the switch that showed the rest was unreachable - so a
 * conversation disappeared for good the moment the operator answered it,
 * and an old conversation could never be found again.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, sdk = [34])
class InboxModeTest {

    private lateinit var server: MockWebServer
    private lateinit var vm: MainViewModel

    /** One conversation that needs an answer, one that does not. */
    private val body = """
        {"ok":true,"conversations":[
          {"id":11,"user_email":"mara@example.test","username":"mara",
           "ai_mode":"operator","status":"open","updated_at":"2026-10-21 12:00:00",
           "last_message":"are you free tonight","last_sender":"user",
           "last_message_at":"2026-10-21 12:00:00","member_count":3,
           "unread_replyable":2,"member_reply_enabled":1,"can_chat":1},
          {"id":12,"user_email":"jules@example.test","username":"jules",
           "ai_mode":"operator","status":"open","updated_at":"2026-10-20 09:00:00",
           "last_message":"thanks!","last_sender":"operator",
           "last_message_at":"2026-10-20 09:00:00","member_count":1,
           "unread_replyable":0,"member_reply_enabled":1,"can_chat":1}
        ],"has_more":false,"next_cursor":null}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        }
        server.start()
        vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the unread mode lists only the conversations that need an answer`() {
        vm.connect(server.url("/gallery").toString(), "token")
        val items = awaitItems(1)
        assertEquals("only the conversation with unread messages is listed", 1, items.size)
        assertEquals(11L, items.first().id)
    }

    @Test
    fun `the all mode lists every conversation`() {
        vm.connect(server.url("/gallery").toString(), "token")
        awaitItems(1)
        vm.setAllUsers(true)
        val items = awaitItems(2)
        assertEquals("both conversations are listed", 2, items.size)
        assertTrue("the answered conversation is missing", items.any { it.id == 12L })
    }

    @Test
    fun `the mode survives a refresh`() {
        vm.connect(server.url("/gallery").toString(), "token")
        awaitItems(1)
        vm.setAllUsers(true)
        awaitItems(2)
        vm.refresh(showLoading = true)
        val items = awaitItems(2)
        assertEquals("the mode was lost on refresh", 2, items.size)
    }

    /** Let the looper run until the inbox has what the test waits for. */
    private fun awaitItems(count: Int): List<ChatBridge.Conversation> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val items = vm.state.value.items
            if (items.size >= count) return items
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return vm.state.value.items
    }
}
