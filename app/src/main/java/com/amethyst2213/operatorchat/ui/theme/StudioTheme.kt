package com.amethyst2213.operatorchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The v4 "studio" visual language.
 *
 * Ink and warm ivory, with amber as the action colour and amethyst as the
 * secondary accent. This is deliberately NOT the old purple-on-black skin:
 * the operator app is a working console, so the palette is quiet and the
 * accent is used only where something can be pressed or is live.
 */
object Studio {
    val Ink = Color(0xFF0B0B10)
    val InkSoft = Color(0xFF101017)
    val Surface = Color(0xFF15151D)
    val SurfaceHigh = Color(0xFF1D1D27)
    val SurfaceMax = Color(0xFF262633)
    val Hairline = Color(0xFF2B2B38)

    val Ivory = Color(0xFFF4EFE6)
    val Muted = Color(0xFFA3A0AE)
    val Dim = Color(0xFF706D7C)

    val Amber = Color(0xFFF2B84B)
    val AmberDeep = Color(0xFF9C6A10)
    val OnAmber = Color(0xFF1B1302)
    val Amethyst = Color(0xFFA78BFA)
    val AmethystDeep = Color(0xFF5B3FA8)
    val Mint = Color(0xFF3DDC97)
    val Coral = Color(0xFFFF6B81)

    /** Card fill used across the app. */
    val Card = Surface
    val CardBorder = Hairline
}

private val StudioColors = darkColorScheme(
    primary = Studio.Amber,
    onPrimary = Studio.OnAmber,
    primaryContainer = Studio.AmberDeep,
    onPrimaryContainer = Studio.Ivory,
    secondary = Studio.Amethyst,
    onSecondary = Studio.Ink,
    secondaryContainer = Studio.AmethystDeep,
    onSecondaryContainer = Studio.Ivory,
    tertiary = Studio.Mint,
    onTertiary = Studio.Ink,
    background = Studio.Ink,
    onBackground = Studio.Ivory,
    surface = Studio.Surface,
    onSurface = Studio.Ivory,
    surfaceVariant = Studio.SurfaceHigh,
    onSurfaceVariant = Studio.Muted,
    outline = Studio.Hairline,
    outlineVariant = Studio.Hairline,
    error = Studio.Coral,
    onError = Studio.Ink,
    errorContainer = Color(0xFF4A1D27),
    onErrorContainer = Color(0xFFFFD9DF),
    scrim = Color(0xCC000000),
    inverseSurface = Studio.Ivory,
    inverseOnSurface = Studio.Ink,
)

private val StudioType = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.5.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp,
    ),
)

private val StudioShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    // The studio console is always dark; a light system theme must not leak
    // into it (the old app duplicated every colour for that reason).
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = StudioColors,
        typography = StudioType,
        shapes = StudioShapes,
        content = content,
    )
}
