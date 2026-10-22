package com.amethyst2213.operatorchat.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The operator app's palette.
 *
 * One accent (amethyst) on a near-black surface. The old XML layouts mixed
 * an Instagram story gradient with Snapchat yellow, which read as a toy;
 * a single accent used consistently - outgoing bubbles, the unread badge,
 * the send button, the live badge - reads as one product.
 */
object Palette {
    val Amethyst = Color(0xFF8B5CF6)
    val AmethystDark = Color(0xFF6D28D9)
    val AmethystLight = Color(0xFFA78BFA)
    val AmethystContainer = Color(0xFF2A1B4D)

    val Surface = Color(0xFF0E0E13)
    val SurfaceCard = Color(0xFF17171F)
    val SurfaceElevated = Color(0xFF22222E)
    val SurfaceInput = Color(0xFF1B1B25)

    val TextPrimary = Color(0xFFF4F4F6)
    val TextSecondary = Color(0xFF9C9CA8)
    val TextTertiary = Color(0xFF6E6E7C)

    val Border = Color(0xFF2B2B38)
    val Divider = Color(0xFF21212C)

    val Danger = Color(0xFFFF6B6B)
    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)

    /** The live badge, and the countdown's "now" state. */
    val Live = Color(0xFFDC2626)

    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
}
