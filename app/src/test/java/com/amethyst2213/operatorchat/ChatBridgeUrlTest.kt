package com.amethyst2213.operatorchat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A request that cannot even be built must fail the request, not the app.
 *
 * The URL was built OUTSIDE the try/catch, so a malformed server address
 * threw an IllegalArgumentException that escaped the coroutine - and an
 * exception that escapes a coroutine kills the whole process. The operator
 * saw the app disappear when they answered a message.
 */
class ChatBridgeUrlTest {

    private val broken = ChatBridge("not a url", "token")

    @Test
    fun `a malformed address fails the reply instead of throwing`() {
        val result = runBlocking { broken.reply(1, "hello") }
        assertTrue("expected a failure, got $result", result is ChatBridge.ReplyResult.Failed)
    }

    @Test
    fun `a malformed address fails the toggles instead of throwing`() {
        runBlocking {
            assertFalse(broken.setMode(1, "operator"))
            assertFalse(broken.setMemberReply(1, true))
            assertFalse(broken.setRead(1, 2))
        }
    }

    @Test
    fun `a malformed address fails the history instead of throwing`() {
        val error = runCatching { runBlocking { broken.thread(1) } }.exceptionOrNull()
        assertTrue("expected an error, got $error", error is IllegalArgumentException)
    }

    @Test
    fun `an empty address is refused before anything is sent`() {
        val result = runBlocking { ChatBridge("", "token").reply(1, "hello") }
        assertTrue("expected a failure, got $result", result is ChatBridge.ReplyResult.Failed)
    }
}
