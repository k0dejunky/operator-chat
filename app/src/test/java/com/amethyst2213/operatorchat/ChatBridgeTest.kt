package com.amethyst2213.operatorchat

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM tests for the bridge data layer using a mock HTTP server. */
class ChatBridgeTest {

    private lateinit var server: MockWebServer
    private lateinit var bridge: ChatBridge

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        bridge = ChatBridge("http://localhost:${server.port}/gallery", "test-token")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun inboxParsesPageAndCursor() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"conversations":[{"id":1,"user_email":"a@x.com","username":"a","ai_mode":"retrieval","status":"open","last_message":"hi","last_sender":"user","last_message_at":"2026-09-01 10:00:00","member_count":1,"unread_replyable":2,"updated_at":"2026-09-01 10:00:00","member_reply_enabled":1,"can_chat":1}],"has_more":true,"next_cursor":"abc"}"""
            )
        )
        val page = bridge.inbox("q", null, 50)
        assertEquals(1, page.items.size)
        assertEquals(2, page.items[0].unreadReplyable)
        assertTrue(page.items[0].canChat)
        assertTrue(page.items[0].memberReplyEnabled)
        assertTrue(page.hasMore)
        assertEquals("abc", page.nextCursor)
    }

    @Test
    fun usersSearchParses() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"users":[{"id":7,"email":"b@x.com","username":"b","can_chat":0,"conversation_id":null,"member_reply_enabled":1}]}"""
            )
        )
        val users = bridge.searchUsers("b")
        assertEquals(1, users.size)
        assertEquals(7L, users[0].id)
        assertEquals(null, users[0].conversationId)
        assertTrue(users[0].memberReplyEnabled)
    }

    @Test
    fun unauthorizedThrows() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try {
            bridge.inbox()
            assertTrue("should have thrown", false)
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("401"))
        }
    }

    @Test
    fun startConversationParsesId() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"conversation_id":42,"username":"b","member_reply_enabled":1,"can_chat":0}"""
            )
        )
        val (cid, username) = bridge.startConversation(7)
        assertEquals(42L, cid)
        assertEquals("b", username)
    }

    @Test
    fun apkInfoParsesSha256() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"latestVersion":"2.26","versionCode":37,"apkUrl":"/gallery/assets/apk/OperatorChat-v2.26.apk","changelog":"hardened updates","sha256":"abc123def456"}"""
            )
        )
        val v = bridge.checkForUpdate()
        assertEquals("2.26", v?.latestVersion)
        assertEquals(37L, v?.versionCode)
        assertEquals("abc123def456", v?.sha256)
    }

    @Test
    fun apkDownloadIsAuthenticated() = runBlocking {
        val apkBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x01)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().apply { write(apkBytes) })
        )
        val dest = java.io.File.createTempFile("upd", ".apk")
        try {
            val f = bridge.downloadApk("2.26", dest)
            assertEquals(dest, f)
            val recorded = server.takeRequest() ?: error("no request")
            assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
            assertTrue(recorded.path!!.contains("/webhooks/chat/apk?version=2.26"))
        } finally {
            dest.delete()
        }
    }
}