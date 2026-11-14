package com.amethyst2213.operatorchat.live

/**
 * One message in the chat of a live stream.
 */
data class LiveChatMessage(
    val id: Long = 0,
    /** "user" for a member, "operator" for the person who is broadcasting. */
    val senderRole: String = "user",
    val name: String = "member",
    val message: String = "",
    val createdAt: String = "",
)
