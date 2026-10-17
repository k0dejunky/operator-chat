package com.amethyst2213.operatorchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the reply-idempotency rules.
 *
 * v2.47 and earlier reused ONE idempotency key per conversation. After any
 * failed send, the next message therefore carried a key the server had already
 * seen, the idempotent answer returned the OLD message's id, and the UI swapped
 * a duplicate of the old message in place of the new text - silently losing it.
 * These tests pin the rule that makes that impossible: a `Sent` result is only
 * accepted when the server's copy really is the text that was sent.
 */
class SendReconcileTest {

    private fun msg(id: Long, text: String) = ChatBridge.Message(
        id = id,
        conversationId = 7,
        senderRole = "operator",
        message = text,
        createdAt = "2026-10-16 12:00:00",
        attachmentName = null,
        attachmentType = null,
        attachmentUrl = null,
        attachmentThumbUrl = null,
        expiresAt = null,
        maxViews = 0,
        viewCount = 0,
        mediaExpired = false,
    )

    // ---------------------------------------------------------------- sameText

    @Test
    fun `identical text is the same message`() {
        assertTrue(SendReconcile.sameText("hello there", "hello there"))
    }

    @Test
    fun `the server's trim and truncation still count as the same message`() {
        // Server: mb_substr(trim($message), 0, 2000)
        assertTrue(SendReconcile.sameText("hello", "  hello  "))

        val long = "a".repeat(2500)
        assertTrue(SendReconcile.sameText(long.take(SendReconcile.MAX_REPLY_CHARS), long))
    }

    @Test
    fun `different text is not the same message`() {
        assertFalse(SendReconcile.sameText("the old reply", "the new reply"))
        assertFalse(SendReconcile.sameText("", "anything"))
    }

    // --------------------------------------------------------------- classify

    @Test
    fun `a matching server copy is confirmed`() {
        assertEquals(
            SendReconcile.Outcome.CONFIRMED,
            SendReconcile.classify(42, msg(42, "hi"), fetchFailed = false, sentText = "hi"),
        )
    }

    @Test
    fun `an idempotent answer carrying an older message is queued, never swapped`() {
        // The exact production bug: we sent "second", the key was already used
        // for "first", so the server answers with the OLD message's id.
        assertEquals(
            SendReconcile.Outcome.QUEUED,
            SendReconcile.classify(41, msg(41, "first"), fetchFailed = false, sentText = "second"),
        )
    }

    @Test
    fun `a failed history fetch is unconfirmed, not a loss`() {
        assertEquals(
            SendReconcile.Outcome.UNCONFIRMED,
            SendReconcile.classify(42, null, fetchFailed = true, sentText = "hi"),
        )
    }

    @Test
    fun `a missing server copy is unconfirmed`() {
        assertEquals(
            SendReconcile.Outcome.UNCONFIRMED,
            SendReconcile.classify(42, null, fetchFailed = false, sentText = "hi"),
        )
    }

    @Test
    fun `a zero id is never confirmed`() {
        assertEquals(
            SendReconcile.Outcome.UNCONFIRMED,
            SendReconcile.classify(0, null, fetchFailed = false, sentText = "hi"),
        )
    }

    // ---------------------------------------------------------------- newKey

    @Test
    fun `new sends always get fresh keys`() {
        val a = SendReconcile.newKey()
        val b = SendReconcile.newKey()
        assertNotEquals(a, b)
        assertTrue(a.isNotBlank() && b.isNotBlank())
    }

    @Test
    fun `keys satisfy the server's format check`() {
        // ChatBridgeController: /\A[A-Za-z0-9._:-]{8,128}\z/
        val key = SendReconcile.newKey()
        assertTrue(key.length in 8..128)
        assertTrue(key.all { it.isLetterOrDigit() || it in "._:-" })
    }
}
