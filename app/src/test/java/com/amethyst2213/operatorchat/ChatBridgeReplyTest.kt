package com.amethyst2213.operatorchat

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Covers the reply contract that used to swallow the server's reason, and the
 * media-type sniffing that used to read a whole attachment into memory.
 */
class ChatBridgeReplyTest {

    private lateinit var server: okhttp3.mockwebserver.MockWebServer
    private lateinit var bridge: ChatBridge

    @Before
    fun setUp() {
        server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        bridge = ChatBridge("http://localhost:${server.port}/gallery", "test-token")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a successful reply reports the new message id`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"id":4242,"conversation_id":7}""")
        )

        val result = bridge.reply(7, "hello")

        assertTrue(result is ChatBridge.ReplyResult.Sent)
        assertEquals(4242L, (result as ChatBridge.ReplyResult.Sent).id)
    }

    @Test
    fun `a server rejection carries its reason`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"ok":false,"error":"Member replies are disabled for this conversation."}""")
        )

        val result = bridge.reply(7, "hello")

        assertTrue(
            "expected a Rejected, got $result",
            result is ChatBridge.ReplyResult.Rejected,
        )
        assertEquals(
            "Member replies are disabled for this conversation.",
            (result as ChatBridge.ReplyResult.Rejected).reason,
        )
    }

    @Test
    fun `a network failure is a Failure not a rejection`() = runBlocking {
        // Nothing enqueued: MockWebServer answers with a protocol error, and a
        // mid-flight shutdown is what the retry path has to survive.
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))

        val result = bridge.reply(7, "hello")

        assertTrue(
            "a transport problem must not be reported as a rejection",
            result is ChatBridge.ReplyResult.Failed,
        )
        assertTrue((result as ChatBridge.ReplyResult.Failed).reason.isNotBlank())
    }

    @Test
    fun `an error body that is not json still reports something useful`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("<html>gateway blew up</html>")
        )

        val result = bridge.reply(7, "hello")

        assertTrue(result is ChatBridge.ReplyResult.Rejected)
        val reason = (result as ChatBridge.ReplyResult.Rejected).reason
        assertTrue("reason should mention the HTTP status: $reason", reason.contains("500"))
    }

    @Test
    fun `an attachment reply is sent as multipart with the expiry fields`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":9}"""))
        val file = File.createTempFile("attach", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x01))
            deleteOnExit()
        }

        val result = bridge.reply(
            conversationId = 7,
            message = "look",
            attachment = file,
            idempotencyKey = "key-123",
            expiresInMinutes = 30,
            maxViews = 3,
        )

        assertTrue(result is ChatBridge.ReplyResult.Sent)
        val recorded = server.takeRequest() ?: error("no request")
        assertEquals("key-123", recorded.getHeader("Idempotency-Key"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"conversation_id\""))
        assertTrue(body.contains("expires_in_minutes"))
        assertTrue(body.contains("30"))
        assertTrue(body.contains("max_views"))
    }

    @Test
    fun `a jpeg with no recognisable extension is still sniffed as jpeg`() = runBlocking {
        // A file the picker renamed: the magic bytes are the only signal.
        val file = File.createTempFile("blob", "").apply {
            writeBytes(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
                    ByteArray(64)
            )
            deleteOnExit()
        }
        // Reflect into the private sniffer so the fix stays covered: it must
        // read only the header, so a huge file costs a 16 byte read.
        val method = ChatBridge::class.java.getDeclaredMethod("guessMediaType", File::class.java)
        method.isAccessible = true
        val type = method.invoke(bridge, file) as String

        assertEquals("image/jpeg", type)
    }

    @Test
    fun `an unknown file falls back to octet stream`() = runBlocking {
        val file = File.createTempFile("mystery", ".xyzzy").apply {
            writeBytes(ByteArray(32) { 0x11 })
            deleteOnExit()
        }
        val method = ChatBridge::class.java.getDeclaredMethod("guessMediaType", File::class.java)
        method.isAccessible = true
        val type = method.invoke(bridge, file) as String

        assertEquals("application/octet-stream", type)
    }
}
