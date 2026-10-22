package com.amethyst2213.operatorchat

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The JSON nulls.
 *
 * org.json's optString(name, fallback) only uses the fallback when the key
 * is MISSING - a key that is present with the value null comes back as the
 * four-letter string "null". The server sends null for every message
 * without an attachment, so the thread showed a paperclip with the word
 * "null" next to it, and a conversation without messages showed "null" as
 * its last message.
 */
class JsonNullTest {

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

    @Test
    fun `a null attachment is not the string null`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"ok":true,"conversation":1,"messages":[
                  {"id":7,"sender_role":"user","message":"hello",
                   "attachment_name":null,"attachment_type":null,"attachment_url":null,
                   "created_at":"2026-10-21 12:00:00","max_views":0,"view_count":0}
                ]}
                """.trimIndent(),
            ),
        )
        val messages = runBlocking { ChatBridge(server.url("/gallery").toString(), "t").thread(1) }
        assertEquals(1, messages.size)
        assertNull("the attachment name was the string null", messages.first().attachmentName)
        assertEquals("hello", messages.first().message)
    }

    @Test
    fun `a null last message is not the string null`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"ok":true,"conversations":[
                  {"id":3,"user_email":"mara@example.test","username":"mara",
                   "ai_mode":"operator","status":"open","updated_at":"2026-10-21 12:00:00",
                   "last_message":null,"last_sender":null,"last_message_at":null,
                   "member_count":0,"unread_replyable":0}
                ],"has_more":false}
                """.trimIndent(),
            ),
        )
        val page = runBlocking { ChatBridge(server.url("/gallery").toString(), "t").inbox() }
        assertEquals(1, page.items.size)
        assertEquals("the last message was the string null", "", page.items.first().lastMessage)
    }
}
