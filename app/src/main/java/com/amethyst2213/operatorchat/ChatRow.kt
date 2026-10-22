package com.amethyst2213.operatorchat

import java.text.SimpleDateFormat
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
 *
 * PERFORMANCE: this used to run on EVERY incoming message, and it
 * rebuilt the whole list - three SimpleDateFormat objects per call, and a
 * parse per message per formatter, on the UI thread. With a busy
 * conversation that was thousands of parses per message. The formatters
 * are now cached per timezone, and the caller is expected to build the
 * rows ONCE per batch and then apply deltas.
 */
object ChatRows {

    /** Two messages this close together, from the same sender, are "grouped". */
    private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

    /**
     * The formatters, cached per timezone.
     *
     * The old code kept ONE shared SimpleDateFormat and mutated its
     * timezone on every call, so two callers on different threads
     * corrupted each other. The cache is keyed by the zone and the
     * instances are only ever read after being configured.
     */
    private class Formatters(zoneId: String) {
        val display: TimeZone = TimeZone.getTimeZone(zoneId)
        val server: SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = display }
        val day: SimpleDateFormat =
            SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).apply { timeZone = display }
        val time: SimpleDateFormat =
            SimpleDateFormat("HH:mm", Locale.getDefault()).apply { timeZone = display }
        val dayKey: SimpleDateFormat =
            SimpleDateFormat("yyyyMMdd", Locale.getDefault()).apply { timeZone = display }
    }

    private val formatterCache = HashMap<String, Formatters>()

    @Synchronized
    private fun formatters(zoneId: String): Formatters =
        formatterCache.getOrPut(zoneId) { Formatters(zoneId) }

    /** Parses the server's timestamp in the SITE timezone, not the device's. */
    fun epochMillis(raw: String, zoneId: String = DEFAULT_ZONE): Long {
        if (raw.isBlank()) return 0L
        return try {
            formatters(zoneId).server.parse(raw.trim())?.time ?: 0L
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

        val fmt = formatters(zoneId)
        val nowKey = fmt.dayKey.format(Date())

        // The epochs are parsed ONCE per message and reused for the day key,
        // the group window and the label. The old code parsed each
        // timestamp up to three times per message.
        val epochs = LongArray(messages.size)
        for (i in messages.indices) {
            epochs[i] = epochMillis(messages[i].createdAt, zoneId)
        }

        val rows = ArrayList<ChatRow>(messages.size + 4)
        var lastDayKey: String? = null

        for (index in messages.indices) {
            val m = messages[index]
            val epoch = epochs[index]
            val dayKey = if (epoch > 0) fmt.dayKey.format(Date(epoch)) else null

            if (dayKey != null && dayKey != lastDayKey) {
                rows += ChatRow(
                    // The day separator's key used to be derived from the
                    // message INDEX, so prepending a page of history
                    // changed every separator's key and the whole list
                    // churned. It is now derived from the DAY, so it is
                    // stable across pagination.
                    key = dayKey.toLong() * -1L,
                    type = ChatRow.TYPE_DAY,
                    dayLabel = when (dayKey) {
                        nowKey -> "Today"
                        else -> fmt.day.format(Date(epoch))
                    },
                )
            }
            if (dayKey != null) lastDayKey = dayKey

            val outgoing = m.senderRole.equals("operator", ignoreCase = true)
            val prevEpoch = if (index > 0) epochs[index - 1] else 0L
            val prevSameSender = index > 0 &&
                messages[index - 1].senderRole.equals(m.senderRole, ignoreCase = true) &&
                epoch > 0 && prevEpoch > 0 &&
                (epoch - prevEpoch) in 0..GROUP_WINDOW_MS

            // A row is the visual "edge" of its group when the NEXT row is not
            // from the same sender in the same window.
            val nextEpoch = if (index + 1 < messages.size) epochs[index + 1] else 0L
            val nextSameSender = index + 1 < messages.size &&
                messages[index + 1].senderRole.equals(m.senderRole, ignoreCase = true) &&
                nextEpoch > 0 && epoch > 0 &&
                (nextEpoch - epoch) in 0..GROUP_WINDOW_MS

            rows += ChatRow(
                key = m.id,
                message = m,
                type = if (outgoing) ChatRow.TYPE_OUTGOING else ChatRow.TYPE_INCOMING,
                showHeader = !prevSameSender,
                grouped = prevSameSender && nextSameSender,
                delivery = deliveries[m.id] ?: Delivery.SENT,
                timeLabel = if (epoch > 0) fmt.time.format(Date(epoch)) else "",
            )
        }

        return rows
    }

    /**
     * The rows for the messages that were APPENDED to an existing thread.
     *
     * The old code rebuilt the whole list on every message, so a busy
     * conversation did O(n) work per message - and the list churned. The
     * thread now appends only what is new and recomputes the group
     * boundary of the row before it.
     */
    fun append(
        existing: List<ChatRow>,
        messages: List<ChatBridge.Message>,
        deliveries: Map<Long, Delivery> = emptyMap(),
        zoneId: String = DEFAULT_ZONE,
    ): List<ChatRow> {
        if (messages.isEmpty()) return existing
        if (existing.isEmpty()) return build(messages, deliveries, zoneId)

        val fmt = formatters(zoneId)
        val out = ArrayList<ChatRow>(existing.size + messages.size + 2)
        out.addAll(existing)

        val lastMessage = messages.last()
        val lastEpoch = epochMillis(lastMessage.createdAt, zoneId)
        val nowKey = fmt.dayKey.format(Date())

        // The previous row's group state can change when a new message
        // arrives from the same sender, so the row before the new ones is
        // rebuilt.
        val previousMessage = messages.firstOrNull()
        val previousRow = out.lastOrNull { it.message != null }
        if (previousRow != null && previousMessage != null) {
            val prevRow = out.indexOf(previousRow)
            if (prevRow >= 0) {
                val rebuilt = build(
                    listOf(previousMessage) + messages,
                    deliveries,
                    zoneId,
                )
                // The rebuilt list starts with the previous message; drop
                // its first row (the day separator, if any) and keep the
                // rest, so the appended messages are the new tail.
                val tail = rebuilt.drop(if (rebuilt.size > messages.size + 1) 1 else 0)
                while (out.size > prevRow + 1) {
                    out.removeAt(out.size - 1)
                }
                out.addAll(tail)
            }
        }

        var lastDayKey: String? = out.lastOrNull { it.type == ChatRow.TYPE_DAY }
            ?.let { (it.key * -1L).toString() }
        var prev = messages.firstOrNull()
        var prevEpoch = lastEpoch

        for (m in messages) {
            val epoch = epochMillis(m.createdAt, zoneId)
            val dayKey = if (epoch > 0) fmt.dayKey.format(Date(epoch)) else null

            if (dayKey != null && dayKey != lastDayKey) {
                out += ChatRow(
                    key = dayKey.toLong() * -1L,
                    type = ChatRow.TYPE_DAY,
                    dayLabel = when (dayKey) {
                        nowKey -> "Today"
                        else -> fmt.day.format(Date(epoch))
                    },
                )
            }
            if (dayKey != null) lastDayKey = dayKey

            val outgoing = m.senderRole.equals("operator", ignoreCase = true)
            val prevSameSender = prev != null &&
                prev.senderRole.equals(m.senderRole, ignoreCase = true) &&
                epoch > 0 && prevEpoch > 0 &&
                (epoch - prevEpoch) in 0..GROUP_WINDOW_MS

            out += ChatRow(
                key = m.id,
                message = m,
                type = if (outgoing) ChatRow.TYPE_OUTGOING else ChatRow.TYPE_INCOMING,
                showHeader = !prevSameSender,
                grouped = false,
                delivery = deliveries[m.id] ?: Delivery.SENT,
                timeLabel = if (epoch > 0) fmt.time.format(Date(epoch)) else "",
            )
            prev = m
            prevEpoch = epoch
        }

        return out
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
