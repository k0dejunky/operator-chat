package com.amethyst2213.operatorchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val OperatorColors = darkColorScheme(
    primary = Palette.Amethyst,
    onPrimary = Palette.White,
    primaryContainer = Palette.AmethystContainer,
    onPrimaryContainer = Palette.TextPrimary,
    secondary = Palette.AmethystLight,
    onSecondary = Palette.Black,
    background = Palette.Surface,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceElevated,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Border,
    outlineVariant = Palette.Divider,
    error = Palette.Danger,
    onError = Palette.White,
    scrim = Palette.Black,
)

/**
 * The type scale.
 *
 * The old layouts used whatever size each TextView happened to carry, so
 * the same information was set at three different sizes depending on the
 * screen. These are the only sizes the app uses.
 */
private val OperatorTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
        color = Palette.TextPrimary,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
        color = Palette.TextPrimary,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold,
        color = Palette.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        color = Palette.TextPrimary,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
        color = Palette.TextPrimary,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, color = Palette.TextPrimary,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp, lineHeight = 21.sp, color = Palette.TextPrimary,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp, lineHeight = 17.sp, color = Palette.TextSecondary,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
        color = Palette.TextPrimary,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
        color = Palette.TextSecondary,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
        color = Palette.TextTertiary,
    ),
)

@Composable
fun OperatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OperatorColors,
        typography = OperatorTypography,
        content = content,
    )
}
