package com.amethyst2213.operatorchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * JVM tests for the thread row model: day separators, sender grouping, the
 * optimistic-placeholder rules and the timezone the server stamps in.
 *
 * These cover the logic the old adapter did inline with notifyDataSetChanged,
 * which is what made the thread feel unpredictable.
 */
class ChatRowsTest {

    private val zone = "America/Denver"

    private fun msg(
        id: Long,
        role: String,
        text: String,
        at: String,
        conversationId: Long = 1,
    ) = ChatBridge.Message(
        id = id,
        conversationId = conversationId,
        senderRole = role,
        message = text,
        createdAt = at,
        attachmentName = null,
        attachmentType = null,
        attachmentUrl = null,
        attachmentThumbUrl = null,
        expiresAt = null,
        maxViews = 0,
        viewCount = 0,
        mediaExpired = false,
    )

    @Test
    fun `parses the server timestamp in the site timezone not the device zone`() {
        val default = TimeZone.getDefault()
        try {
            // Same instant, two very different device zones: the epoch must be
            // identical because the zone comes from the server, not the phone.
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val utc = ChatRows.epochMillis("2026-10-16 20:00:00", zone)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val tokyo = ChatRows.epochMillis("2026-10-16 20:00:00", zone)

            assertEquals(utc, tokyo)
            assertTrue(utc > 0)
        } finally {
            TimeZone.setDefault(default)
        }
    }

    @Test
    fun `inserts a day separator before the first message of each day`() {
        val rows = ChatRows.build(
            listOf(
                msg(1, "user", "day one", "2026-10-14 09:00:00"),
                msg(2, "user", "day two", "2026-10-15 11:00:00"),
            ),
            zoneId = zone,
        )

        val separators = rows.count { it.type == ChatRow.TYPE_DAY }
        assertEquals(2, separators)
        assertEquals(ChatRow.TYPE_DAY, rows.first().type)
        assertEquals("Wednesday 14 October", rows.first().dayLabel)
    }

    @Test
    fun `labels today's separator as Today`() {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .apply { timeZone = TimeZone.getTimeZone(zone) }
            .format(java.util.Date())

        val rows = ChatRows.build(listOf(msg(1, "user", "hi", now)), zoneId = zone)

        val separator = rows.firstOrNull { it.type == ChatRow.TYPE_DAY }
        assertEquals("Today", separator?.dayLabel)
    }

    @Test
    fun `consecutive messages from one sender form a group`() {
        val rows = ChatRows.build(
            listOf(
                msg(1, "user", "one", "2026-10-15 10:00:00"),
                msg(2, "user", "two", "2026-10-15 10:01:00"),
                msg(3, "user", "three", "2026-10-15 10:02:00"),
            ),
            zoneId = zone,
        )

        val bubbles = rows.filter { it.message != null }
        assertEquals(3, bubbles.size)
        assertTrue("first row of a group shows the header", bubbles[0].showHeader)
        assertFalse("second row hides the header", bubbles[1].showHeader)
        assertFalse("third row hides the header", bubbles[2].showHeader)
        assertTrue("middle row is fully grouped", bubbles[1].grouped)
        assertFalse("last row is a group edge", bubbles[2].grouped)
    }

    @Test
    fun `a change of sender breaks the group`() {
        val rows = ChatRows.build(
            listOf(
                msg(1, "user", "member speaks", "2026-10-15 10:00:00"),
                msg(2, "operator", "operator answers", "2026-10-15 10:00:30"),
            ),
            zoneId = zone,
        )

        val bubbles = rows.filter { it.message != null }
        assertTrue(bubbles[0].showHeader)
        assertTrue("a sender change always shows the header", bubbles[1].showHeader)
        assertFalse(bubbles[1].grouped)
    }

    @Test
    fun `a long gap between messages breaks the group`() {
        val rows = ChatRows.build(
            listOf(
                msg(1, "user", "morning", "2026-10-15 09:00:00"),
                msg(2, "user", "an hour later", "2026-10-15 10:30:00"),
            ),
            zoneId = zone,
        )

        val bubbles = rows.filter { it.message != null }
        assertTrue(bubbles[0].showHeader)
        assertTrue("a 90 minute gap is a new group", bubbles[1].showHeader)
    }

    @Test
    fun `an outgoing message is typed and aligned as outgoing`() {
        val rows = ChatRows.build(
            listOf(msg(9, "operator", "mine", "2026-10-15 10:00:00")),
            zoneId = zone,
        )

        val bubble = rows.single { it.message != null }
        assertEquals(ChatRow.TYPE_OUTGOING, bubble.type)
        assertTrue(bubble.isOutgoing)
    }

    @Test
    fun `unknown roles render as incoming`() {
        val rows = ChatRows.build(
            listOf(msg(9, "ai", "bot", "2026-10-15 10:00:00")),
            zoneId = zone,
        )

        assertEquals(ChatRow.TYPE_INCOMING, rows.single { it.message != null }.type)
    }

    @Test
    fun `delivery state is carried on the row`() {
        val rows = ChatRows.build(
            listOf(
                msg(5, "operator", "queued reply", ""),
                msg(6, "operator", "sent reply", "2026-10-15 10:00:00"),
            ),
            deliveries = mapOf(5L to Delivery.QUEUED, 6L to Delivery.SENT),
            zoneId = zone,
        )

        val bubbles = rows.filter { it.message != null }
        assertEquals(Delivery.QUEUED, bubbles[0].delivery)
        assertEquals(Delivery.SENT, bubbles[1].delivery)
        assertTrue("rows without a timestamp carry no clock", bubbles[0].timeLabel.isEmpty())
        // Rendered in the SITE zone, so 10:00 server time shows as 10:00 even
        // on a phone whose own clock is somewhere else.
        assertEquals("10:00", bubbles[1].timeLabel)
    }

    @Test
    fun `every message keeps its row and its position`() {
        val messages = (1..6).map { i ->
            msg(i.toLong(), if (i % 2 == 0) "operator" else "user", "m$i", "2026-10-15 1$i:0${i % 10}:00")
        }

        val rows = ChatRows.build(messages, zoneId = zone)

        val ids = rows.filter { it.message != null }.map { it.message!!.id }
        assertEquals(messages.map { it.id }, ids)
        assertTrue("day separators add rows, never remove messages", rows.size >= messages.size)
    }

    @Test
    fun `an empty thread produces no rows`() {
        assertEquals(emptyList<ChatRow>(), ChatRows.build(emptyList(), zoneId = zone))
    }
}
