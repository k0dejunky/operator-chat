package com.amethyst2213.operatorchat.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amethyst2213.operatorchat.ui.theme.Studio

/* ------------------------------------------------------------------ surfaces */

@Composable
fun StudioBackdrop(content: @Composable () -> Unit) {
    Box(
        Modifier
            .background(Studio.Ink)
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x1A8C6DFF), Color.Transparent),
                    radius = 1200f,
                ),
            ),
    ) { content() }
}

@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Studio.Surface)
            .border(1.dp, Studio.Hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Studio.Dim,
        )
        trailing?.invoke()
    }
}

/* ------------------------------------------------------------------- controls */

@Composable
fun StudioPill(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) Studio.Amber else Studio.SurfaceHigh
    val fg = if (selected) Studio.OnAmber else Studio.Muted
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    fill: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(if (enabled && !loading) Studio.Amber else Studio.SurfaceHigh)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Studio.OnAmber,
                strokeWidth = 2.dp,
            )
        } else {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) Studio.OnAmber else Studio.Dim,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Studio.OnAmber else Studio.Dim,
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color = Studio.Ivory,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(Studio.SurfaceHigh)
            .border(1.dp, Studio.Hairline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(7.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) tint else Studio.Dim,
        )
    }
}

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    GhostButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        tint = Studio.Coral,
    )
}

@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Studio.Muted,
    size: Dp = 20.dp,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size))
    }
}

/* -------------------------------------------------------------------- status */

@Composable
fun StatusDot(live: Boolean, size: Dp = 8.dp) {
    val color = if (live) Studio.Mint else Studio.Dim
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun LivePill(text: String = "LIVE") {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Studio.Mint.copy(alpha = 0.14f))
            .border(1.dp, Studio.Mint.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(true, 6.dp)
        Text(text, style = MaterialTheme.typography.labelSmall, color = Studio.Mint)
    }
}

@Composable
fun InitialAvatar(name: String, size: Dp = 48.dp) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val palette = listOf(
        Color(0xFF3B2A63), Color(0xFF173F3A), Color(0xFF4A2A2A),
        Color(0xFF2A3654), Color(0xFF4A3B1E), Color(0xFF3C2A45),
    )
    val color = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color.White.copy(alpha = 0.06f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            color = Studio.Ivory,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.36f).sp,
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Studio.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Studio.Muted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Studio.Ivory, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = Studio.Muted,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun ErrorStrip(text: String, onRetry: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Studio.Coral.copy(alpha = 0.1f))
            .border(1.dp, Studio.Coral.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Studio.Coral,
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Text(
                "Retry",
                style = MaterialTheme.typography.labelLarge,
                color = Studio.Coral,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "typing-alpha",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Studio.Muted.copy(alpha = alpha)),
            )
        }
    }
}

/* ---------------------------------------------------------------------- chrome */

@Composable
fun StudioTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            IconAction(Icons.Outlined.ArrowBack, "Back", onBack)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = Studio.Ivory,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun StudioBottomBar(selected: StudioTab, onSelect: (StudioTab) -> Unit) {    Surface(color = Studio.InkSoft, tonalElevation = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(width = 0.dp, color = Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StudioTab.entries.forEach { tab ->
                val active = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(tab) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (active) Studio.Amber else Studio.Dim,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                        color = if (active) Studio.Amber else Studio.Dim,
                    )
                }
            }
        }
    }
}

enum class StudioTab(val label: String, val icon: ImageVector) {
    Chats("Chats", Icons.Outlined.ChatBubbleOutline),
    Live("Live", Icons.Outlined.Sensors),
    Schedule("Shows", Icons.Outlined.Event),
    Settings("Settings", Icons.Outlined.Settings),
}

@Composable
fun UpdateStrip(
    offer: com.amethyst2213.operatorchat.UpdateOffer,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = offer.phase == com.amethyst2213.operatorchat.UpdatePhase.DOWNLOADING ||
        offer.phase == com.amethyst2213.operatorchat.UpdatePhase.VERIFYING ||
        offer.phase == com.amethyst2213.operatorchat.UpdatePhase.INSTALLING
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Studio.SurfaceHigh)
            .border(1.dp, Studio.Amber.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Studio.Amber,
                strokeWidth = 2.dp,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(offer.headline, style = MaterialTheme.typography.titleSmall, color = Studio.Ivory)
            if (offer.notes.isNotBlank() && !busy) {
                Text(
                    offer.notes.take(120),
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Muted,
                    maxLines = 2,
                )
            }
        }
        if (!busy) {
            Text(
                if (offer.phase == com.amethyst2213.operatorchat.UpdatePhase.FAILED) "Try again" else "Install",
                style = MaterialTheme.typography.labelLarge,
                color = Studio.Amber,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onInstall)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
        Text(
            "×",
            style = MaterialTheme.typography.titleMedium,
            color = Studio.Dim,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
}
