package com.amethyst2213.operatorchat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.UpdateOffer
import com.amethyst2213.operatorchat.UpdatePhase
import com.amethyst2213.operatorchat.ui.components.DetailRow
import com.amethyst2213.operatorchat.ui.components.ErrorBanner
import com.amethyst2213.operatorchat.ui.components.PrimaryButton
import com.amethyst2213.operatorchat.ui.components.SecondaryButton
import com.amethyst2213.operatorchat.ui.components.SectionHeader
import com.amethyst2213.operatorchat.ui.components.UpdateBanner
import com.amethyst2213.operatorchat.ui.theme.Palette

/**
 * The settings.
 *
 * The old screen could only look at the server it was handed: there was
 * no way to change the address or the token without reinstalling, and the
 * quiet hours never silenced anything because the notification channel was
 * created with the wrong importance.
 */
@Composable
fun SettingsScreen(
    serverUrl: String,
    token: String,
    notificationsOn: Boolean,
    biometricOn: Boolean,
    quietOn: Boolean,
    quietStart: String,
    quietEnd: String,
    toneName: String,
    siteTimezone: String,
    appVersion: String,
    lastSync: String,
    lastError: String?,
    status: String?,
    error: String?,
    onServerUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onNotificationsChange: (Boolean) -> Unit,
    onBiometricChange: (Boolean) -> Unit,
    onQuietChange: (Boolean) -> Unit,
    onQuietStartChange: (String) -> Unit,
    onQuietEndChange: (String) -> Unit,
    onPickTone: () -> Unit,
    onSaveConnection: () -> Unit,
    onPing: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDisconnect: () -> Unit,
    update: UpdateOffer? = null,
    onInstallUpdate: () -> Unit = {},
) {
    var tokenVisible by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // The connection.
        SettingsCard {
            SectionHeader("Connection")
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server address") },
                placeholder = { Text("https://amethyst2213.com/gallery") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                label = { Text("Operator token") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation = if (tokenVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    androidx.compose.material3.TextButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(if (tokenVisible) "Hide" else "Show", color = Palette.TextSecondary)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = "Save",
                    onClick = onSaveConnection,
                    icon = Icons.Outlined.CloudDone,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Test",
                    onClick = onPing,
                    icon = Icons.Outlined.Dns,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(error)
            }
            if (!status.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // The notifications.
        SettingsCard {
            SectionHeader("Notifications")
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "New-message notifications",
                subtitle = "A sound and a banner when a member writes.",
                checked = notificationsOn,
                onCheckedChange = onNotificationsChange,
            )
            ToggleRow(
                title = "Biometric lock",
                subtitle = "Ask for a fingerprint or a face before the inbox opens.",
                checked = biometricOn,
                onCheckedChange = onBiometricChange,
            )
            ToggleRow(
                title = "Quiet hours",
                subtitle = "Between these times the notifications are silent.",
                checked = quietOn,
                onCheckedChange = onQuietChange,
            )
            if (quietOn) {
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = quietStart,
                        onValueChange = onQuietStartChange,
                        label = { Text("From") },
                        placeholder = { Text("22:00") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = quietEnd,
                        onValueChange = onQuietEndChange,
                        label = { Text("Until") },
                        placeholder = { Text("08:00") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = Palette.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Tone: $toneName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(text = "Change", onClick = onPickTone)
            }
        }

        Spacer(Modifier.height(12.dp))

        // The diagnostics.
        SettingsCard {
            SectionHeader("Diagnostics")
            Spacer(Modifier.height(8.dp))
            DetailRow("App version", appVersion)
            DetailRow("Site timezone", siteTimezone)
            DetailRow("Last sync", lastSync)
            if (!lastError.isNullOrBlank()) {
                DetailRow("Last error", lastError)
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = "Check for updates",
                onClick = onCheckUpdate,
                icon = Icons.Outlined.SystemUpdate,
                modifier = Modifier.fillMaxWidth(),
            )
            if (update != null) {
                Spacer(Modifier.height(10.dp))
                UpdateBanner(update = update, onInstall = onInstallUpdate)
            }
        }

        Spacer(Modifier.height(12.dp))

        // The danger zone.
        SettingsCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = Palette.TextTertiary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Disconnecting forgets the server and the token on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextTertiary,
                )
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton(
                text = "Disconnect",
                onClick = onDisconnect,
                icon = Icons.AutoMirrored.Outlined.Logout,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Palette.SurfaceCard),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
