package com.amethyst2213.operatorchat

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Delivery state shown on an outgoing bubble. */
enum class Delivery { SENDING, QUEUED, SENT, FAILED }

/**
 * One rendered row in the thread.
 *
 * A raw [ChatBridge.Message] cannot be drawn directly: the list needs day
 * separators, consecutive-message grouping and a per-message delivery state.
 * Building those here (instead of inside the adapter) keeps the maths in a
 * plain function that the unit tests exercise on the JVM.
 */
data class ChatRow(
    val key: Long,
    val message: ChatBridge.Message? = null,
    val type: Int = TYPE_INCOMING,
    val dayLabel: String? = null,
    /** Show the "You"/"Member" header: the first row of a sender group. */
    val showHeader: Boolean = false,
    /** Draw the bubble with the tighter grouped corner (not the group edge). */
    val grouped: Boolean = false,
    val delivery: Delivery = Delivery.SENT,
    val timeLabel: String = "",
) {
    val isOutgoing: Boolean get() = type == TYPE_OUTGOING

    companion object {
        const val TYPE_INCOMING = 0
        const val TYPE_OUTGOING = 1
        const val TYPE_DAY = 2
    }
}

/**
 * Turns a flat message list into displayable rows.
 *
 * Inserted invariants the adapter relies on:
 *  - a day separator precedes the first row of each calendar day;
 *  - rows from the same sender within 5 minutes share a group (no header,
 *    tighter corners);
 *  - optimistic placeholders (negative ids) keep their position by insertion
 *    order, and are never reordered away from the end.
 */
object ChatRows {

    /** Two messages this close together, from the same sender, are "grouped". */
    private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

    private val serverFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Parses the server's timestamp in the SITE timezone, not the device's. */
    fun epochMillis(raw: String, zoneId: String = DEFAULT_ZONE): Long {
        if (raw.isBlank()) return 0L
        return try {
            serverFormat.timeZone = TimeZone.getTimeZone(zoneId)
            serverFormat.parse(raw.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * @param messages oldest-first, as delivered by the adapter's own list.
     * @param deliveries  delivery state keyed by message id.
     */
    fun build(
        messages: List<ChatBridge.Message>,
        deliveries: Map<Long, Delivery> = emptyMap(),
        zoneId: String = DEFAULT_ZONE,
    ): List<ChatRow> {
        if (messages.isEmpty()) return emptyList()

        val display = TimeZone.getTimeZone(zoneId)
        val dayFmt  = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).apply { timeZone = display }
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = display }
        val today   = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).apply { timeZone = display }
        val nowKey  = today.format(Date())

        val rows = ArrayList<ChatRow>(messages.size + 4)
        var lastDayKey: String? = null
        var prev: ChatBridge.Message? = null

        messages.forEachIndexed { index, m ->
            val epoch = epochMillis(m.createdAt, zoneId)
            val dayKey = if (epoch > 0) today.format(Date(epoch)) else null

            if (dayKey != null && dayKey != lastDayKey) {
                rows += ChatRow(
                    key = -(10_000L + index),
                    type = ChatRow.TYPE_DAY,
                    dayLabel = when (dayKey) {
                        nowKey -> "Today"
                        else -> dayFmt.format(Date(epoch))
                    },
                )
            }
            if (dayKey != null) lastDayKey = dayKey

            val outgoing = m.senderRole.equals("operator", ignoreCase = true)
            val prevEpoch = prev?.let { epochMillis(it.createdAt, zoneId) } ?: 0L
            val prevSameSender = prev != null &&
                prev.senderRole.equals(m.senderRole, ignoreCase = true) &&
                epoch > 0 && prevEpoch > 0 &&
                (epoch - prevEpoch) in 0..GROUP_WINDOW_MS

            // A row is the visual "edge" of its group when the NEXT row is not
            // from the same sender in the same window.
            val nextSameSender = messages.getOrNull(index + 1)?.let { nxt ->
                val nEpoch = epochMillis(nxt.createdAt, zoneId)
                nxt.senderRole.equals(m.senderRole, ignoreCase = true) &&
                    nEpoch > 0 && epoch > 0 &&
                    (nEpoch - epoch) in 0..GROUP_WINDOW_MS
            } ?: false

            rows += ChatRow(
                key = m.id,
                message = m,
                type = if (outgoing) ChatRow.TYPE_OUTGOING else ChatRow.TYPE_INCOMING,
                showHeader = !prevSameSender,
                grouped = prevSameSender && nextSameSender,
                delivery = deliveries[m.id] ?: Delivery.SENT,
                timeLabel = if (epoch > 0) timeFmt.format(Date(epoch)) else "",
            )
            prev = m
        }

        return rows
    }

    /** Zone the site reports in (Settings > timezone). Kept in one place so the
     *  thread and the inbox agree on what "2 minutes ago" means. */
    const val DEFAULT_ZONE = "America/Denver"
}

/** Small human label for the thread header ("Live", "Reconnecting…"). */
object TimeLabels {
    fun short(epochMs: Long, zoneId: String): String =
        SimpleDateFormat("HH:mm", Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone(zoneId) }
            .format(Date(epochMs))
}
