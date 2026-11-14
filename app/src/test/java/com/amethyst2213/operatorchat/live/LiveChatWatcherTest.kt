package com.amethyst2213.operatorchat.live

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The watcher of the live chat.
 *
 * The operator was never told that a member wrote in the chat of a stream
 * that was on air: the chat was read by the live screen, and the screen
 * stops reading the moment the operator leaves it. The watcher now lives in
 * the service that outlives the screen, so it has to keep its place in the
 * stream - otherwise every reconnect would announce the whole chat again.
 */
class LiveChatWatcherTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    @Test
    fun `the messages of a window are handed over`() {
        server.enqueue(
            sse(
                "data: {\"messages\":[" +
                    "{\"id\":7,\"sender_role\":\"user\",\"name\":\"sam\",\"message\":\"hi\",\"created_at\":\"2026-10-24 10:00:00\"}," +
                    "{\"id\":8,\"sender_role\":\"operator\",\"name\":\"Operator\",\"message\":\"hello\",\"created_at\":\"2026-10-24 10:00:01\"}" +
                    "],\"latestId\":8}\n\n"
            )
        )

        val watcher = LiveChatWatcher(server.url("/").toString().trimEnd('/'), "token")
        val messages = watcher.readOnce()

        assertEquals(2, messages.size)
        assertEquals(7L, messages[0].id)
        assertEquals("sam", messages[0].name)
        assertEquals("hi", messages[0].message)
        assertEquals("user", messages[0].senderRole)
        assertEquals("operator", messages[1].senderRole)
    }

    @Test
    fun `the watcher continues after the newest message it saw`() {
        server.enqueue(
            sse(
                "data: {\"messages\":[" +
                    "{\"id\":11,\"sender_role\":\"user\",\"name\":\"sam\",\"message\":\"one\",\"created_at\":\"\"}" +
                    "],\"latestId\":11}\n\n"
            )
        )

        val watcher = LiveChatWatcher(server.url("/").toString().trimEnd('/'), "token")
        watcher.readOnce()
        assertEquals(11L, watcher.latestId)

        val first = server.takeRequest()
        assertTrue(
            "expected since=0, got ${first.path}",
            first.path?.contains("since=0") == true
        )

        // The next request must ask for what comes after the message it saw.
        server.enqueue(sse("data: {\"messages\":[],\"latestId\":11}\n\n"))
        watcher.readOnce()
        val second = server.takeRequest()
        assertTrue(
            "expected since=11, got ${second.path}",
            second.path?.contains("since=11") == true
        )
    }

    @Test
    fun `a watcher that starts later does not announce the whole chat again`() {
        server.enqueue(sse("data: {\"messages\":[],\"latestId\":99}\n\n"))

        val watcher = LiveChatWatcher(
            server.url("/").toString().trimEnd('/'),
            "token",
            since = 99L,
        )
        watcher.readOnce()

        val request = server.takeRequest()
        assertTrue(
            "expected since=99, got ${request.path}",
            request.path?.contains("since=99") == true
        )
    }

    @Test
    fun `a broken stream gives no messages and does not throw`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val watcher = LiveChatWatcher(server.url("/").toString().trimEnd('/'), "token")
        assertEquals(0, watcher.readOnce().size)
    }
}
