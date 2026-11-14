package com.amethyst2213.operatorchat.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.UpdateOffer
import com.amethyst2213.operatorchat.ui.components.DangerButton
import com.amethyst2213.operatorchat.ui.components.ErrorStrip
import com.amethyst2213.operatorchat.ui.components.GhostButton
import com.amethyst2213.operatorchat.ui.components.PrimaryButton
import com.amethyst2213.operatorchat.ui.components.SectionLabel
import com.amethyst2213.operatorchat.ui.components.StudioCard
import com.amethyst2213.operatorchat.ui.components.StudioTopBar
import com.amethyst2213.operatorchat.ui.components.UpdateStrip
import com.amethyst2213.operatorchat.ui.login.StudioField
import com.amethyst2213.operatorchat.ui.theme.Studio

@Composable
fun SettingsScreen(
    state: SettingsViewModel.Ui,
    update: UpdateOffer?,
    lockEnabled: Boolean,
    biometricAvailable: Boolean,
    onUrl: (String) -> Unit,
    onToken: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onNotifications: (Boolean) -> Unit,
    onQuiet: (Boolean) -> Unit,
    onQuietStart: (String) -> Unit,
    onQuietEnd: (String) -> Unit,
    onTone: (String?) -> Unit,
    onLockChanged: (Boolean) -> Unit,
    onSetPasscode: (String) -> Boolean,
    onRemovePasscode: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onDisconnect: () -> Unit,
    onClearStatus: () -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    var passcodeDialog by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    val toneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            onTone(uri?.toString())
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { StudioTopBar(title = "Settings", subtitle = state.version) }

        if (update != null) {
            item { UpdateStrip(update, onInstall = onInstallUpdate, onDismiss = onDismissUpdate) }
        }
        if (!state.error.isNullOrBlank()) {
            item { ErrorStrip(state.error, onRetry = onClearStatus) }
        }
        if (!state.status.isNullOrBlank()) {
            item {
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Mint,
                )
            }
        }

        item {
            StudioCard {
                SectionLabel("Connection")
                Spacer(Modifier.height(10.dp))
                StudioField(
                    value = state.url,
                    onValueChange = onUrl,
                    label = "Server address",
                    placeholder = "https://amethyst2213.com/gallery",
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(10.dp))
                StudioField(
                    value = state.token,
                    onValueChange = onToken,
                    label = "Operator token",
                    placeholder = "Device token",
                    keyboardType = KeyboardType.Password,
                    secret = !reveal,
                    trailing = {
                        Text(
                            if (reveal) "Hide" else "Show",
                            style = MaterialTheme.typography.labelSmall,
                            color = Studio.Amber,
                            modifier = Modifier
                                .clickable { reveal = !reveal }
                                .padding(horizontal = 8.dp),
                        )
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        "Save",
                        onClick = onSave,
                        modifier = Modifier.weight(1f),
                        loading = state.busy,
                        enabled = !state.busy,
                    )
                    GhostButton("Test", onClick = onTest, modifier = Modifier.weight(1f))
                }
            }
        }

        item {
            StudioCard {
                SectionLabel("Notifications")
                Spacer(Modifier.height(6.dp))
                Toggle(
                    title = "New message notifications",
                    body = "A notification when a member writes, with quick reply.",
                    checked = state.notifications,
                    onChange = onNotifications,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                                )
                            }
                            toneLauncher.launch(intent)
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Notifications, null, tint = Studio.Muted, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.size(10.dp))
                    Text("Tone", style = MaterialTheme.typography.bodyLarge, color = Studio.Ivory, modifier = Modifier.weight(1f))
                    Text(
                        state.toneName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Studio.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Outlined.ChevronRight, null, tint = Studio.Dim, modifier = Modifier.size(18.dp))
                }
                Toggle(
                    title = "Quiet hours",
                    body = "Notifications arrive silently inside this window.",
                    checked = state.quiet,
                    onChange = onQuiet,
                )
                if (state.quiet) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietField("From", state.quietStart, onQuietStart, Modifier.weight(1f))
                        QuietField("To", state.quietEnd, onQuietEnd, Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            StudioCard {
                SectionLabel("Security")
                Spacer(Modifier.height(6.dp))
                Toggle(
                    title = "Lock on open",
                    body = if (biometricAvailable) {
                        "Ask for the passcode or biometrics when the app opens."
                    } else {
                        "Ask for the passcode when the app opens."
                    },
                    checked = lockEnabled,
                    onChange = onLockChanged,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { passcodeDialog = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = Studio.Muted, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Passcode", style = MaterialTheme.typography.bodyLarge, color = Studio.Ivory)
                        Text(
                            if (state.passcodeSet) "Set. Tap to change." else "Not set. Tap to create one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Studio.Dim,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = Studio.Dim, modifier = Modifier.size(18.dp))
                }
                if (state.passcodeSet) {
                    TextButton(onClick = onRemovePasscode) {
                        Text("Remove passcode", color = Studio.Coral, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        item {
            StudioCard {
                SectionLabel("Updates")
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Installed", style = MaterialTheme.typography.bodyLarge, color = Studio.Ivory)
                        Text(state.version, style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
                    }
                    GhostButton("Check now", onClick = onCheckUpdate)
                }
            }
        }

        item {
            StudioCard {
                SectionLabel("Diagnostics")
                Spacer(Modifier.height(6.dp))
                Detail("Site timezone", state.siteZone)
                Detail("Last sync", state.lastSync)
                Detail("Last error", state.lastError ?: "none")
                Detail("Biometrics", if (biometricAvailable) "available" else "not available")
            }
        }

        item {
            DangerButton(
                text = "Disconnect this device",
                onClick = { confirmDisconnect = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                "The token can be revoked from the admin panel (Chat, Operator app) at any time.",
                style = MaterialTheme.typography.labelSmall,
                color = Studio.Dim,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (passcodeDialog) {
        PasscodeDialog(
            changing = state.passcodeSet,
            onSubmit = { pin ->
                passcodeDialog = false
                onSetPasscode(pin)
            },
            onDismiss = { passcodeDialog = false },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            containerColor = Studio.Surface,
            title = { Text("Disconnect?", color = Studio.Ivory) },
            text = { Text("The server address and the token are forgotten. The passcode stays.", color = Studio.Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    onDisconnect()
                }) { Text("Disconnect", color = Studio.Coral) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel", color = Studio.Muted) }
            },
        )
    }
}

@Composable
private fun Toggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Studio.Ivory)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Studio.Dim)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Studio.OnAmber,
                checkedTrackColor = Studio.Amber,
                uncheckedThumbColor = Studio.Muted,
                uncheckedTrackColor = Studio.SurfaceHigh,
            ),
        )
    }
}

@Composable
private fun QuietField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Studio.Amber,
            unfocusedBorderColor = Studio.Hairline,
            focusedLabelColor = Studio.Amber,
            unfocusedLabelColor = Studio.Dim,
            focusedContainerColor = Studio.InkSoft,
            unfocusedContainerColor = Studio.InkSoft,
            cursorColor = Studio.Amber,
            focusedTextColor = Studio.Ivory,
            unfocusedTextColor = Studio.Ivory,
        ),
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Studio.Muted)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = Studio.Ivory,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PasscodeDialog(
    changing: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Studio.Surface,
        title = { Text(if (changing) "Change passcode" else "Create a passcode", color = Studio.Ivory) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(32) },
                    label = { Text("4 to 32 digits") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Studio.Amber,
                        unfocusedBorderColor = Studio.Hairline,
                        focusedContainerColor = Studio.InkSoft,
                        unfocusedContainerColor = Studio.InkSoft,
                        cursorColor = Studio.Amber,
                        focusedTextColor = Studio.Ivory,
                        unfocusedTextColor = Studio.Ivory,
                    ),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(32) },
                    label = { Text("Repeat it") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Studio.Amber,
                        unfocusedBorderColor = Studio.Hairline,
                        focusedContainerColor = Studio.InkSoft,
                        unfocusedContainerColor = Studio.InkSoft,
                        cursorColor = Studio.Amber,
                        focusedTextColor = Studio.Ivory,
                        unfocusedTextColor = Studio.Ivory,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (pin == confirm && pin.length >= 4) onSubmit(pin) },
                enabled = pin.length >= 4 && pin == confirm,
            ) { Text("Save", color = Studio.Amber) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Studio.Muted) }
        },
    )
}
