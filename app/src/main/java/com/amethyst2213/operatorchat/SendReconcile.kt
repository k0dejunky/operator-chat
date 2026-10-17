package com.amethyst2213.operatorchat

/**
 * Pure decisions around reply idempotency, extracted from ChatActivity so the
 * JVM tests can prove them without an Android runtime.
 *
 * Background: the reply endpoint is idempotent, and an idempotent answer
 * carries the id of the message the key was FIRST used for. A client that
 * reuses a key for different text therefore receives the OLD message's id; if
 * it trusts that answer, it swaps a duplicate of the old message in place of
 * the new text and the new message is silently lost. These functions encode
 * the rule that prevents that.
 */
object SendReconcile {

    /** Mirror of ChatMessage::MAX_MESSAGE_LENGTH on the server. */
    const val MAX_REPLY_CHARS = 2000

    /** What to do with a `Sent` result. */
    enum class Outcome {
        /** The server's copy really is this message: swap it in. */
        CONFIRMED,

        /**
         * The server answered idempotently for an OLDER message, so this text
         * was never stored: put it in the outbox under a fresh key.
         */
        QUEUED,

        /**
         * The copy could not be verified (fetch failed, or the id is missing):
         * drop the optimistic row WITHOUT advancing the high-water mark so the
         * SSE stream re-fetches this id and renders the server's real copy.
         */
        UNCONFIRMED,
    }

    /**
     * True when [stored] (the server's copy) is the text that was sent. The
     * server trims and truncates to [MAX_REPLY_CHARS] on insert, so a long
     * message must also compare against its truncated form.
     */
    fun sameText(stored: String, sent: String): Boolean =
        stored == sent || stored == sent.trim().take(MAX_REPLY_CHARS)

    /**
     * Classifies the outcome of a `Sent` result whose server copy is [fetched]
     * (null when it was not found), with [fetchFailed] true when the history
     * call itself threw.
     */
    fun classify(sentId: Long, fetched: ChatBridge.Message?, fetchFailed: Boolean, sentText: String): Outcome =
        when {
            fetchFailed || sentId <= 0L -> Outcome.UNCONFIRMED
            fetched == null -> Outcome.UNCONFIRMED
            sameText(fetched.message, sentText) -> Outcome.CONFIRMED
            else -> Outcome.QUEUED
        }

    /**
     * The key for a NEW send attempt. Always fresh: a key left over from an
     * earlier attempt must never be reused for different text (see the class
     * doc). Retries of the same attempt reuse the key stored with that attempt.
     */
    fun newKey(): String = java.util.UUID.randomUUID().toString()
}
