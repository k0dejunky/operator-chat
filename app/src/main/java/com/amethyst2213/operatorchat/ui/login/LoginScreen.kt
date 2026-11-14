package com.amethyst2213.operatorchat.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.UpdateOffer
import com.amethyst2213.operatorchat.ui.components.ErrorStrip
import com.amethyst2213.operatorchat.ui.components.PrimaryButton
import com.amethyst2213.operatorchat.ui.components.SectionLabel
import com.amethyst2213.operatorchat.ui.components.StudioBackdrop
import com.amethyst2213.operatorchat.ui.components.UpdateStrip
import com.amethyst2213.operatorchat.ui.theme.Studio

/**
 * Sign-in: server address + operator device token. The token is stored in the
 * Android keystore; the screen never keeps it in a saved instance state.
 */
@Composable
fun LoginScreen(
    initialUrl: String,
    initialToken: String,
    busy: Boolean,
    error: String?,
    update: UpdateOffer?,
    onConnect: (String, String) -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf(initialToken) }
    var reveal by remember { mutableStateOf(false) }

    StudioBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Studio.Amber),
                )
                Text(
                    "OPERATOR CONSOLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = Studio.Amber,
                )
            }
            Text(
                "Your studio,\nin your pocket.",
                style = MaterialTheme.typography.displayMedium,
                color = Studio.Ivory,
            )
            Text(
                "Messages, the live feed and the show schedule — one console, one device.",
                style = MaterialTheme.typography.bodyLarge,
                color = Studio.Muted,
            )

            if (update != null) {
                UpdateStrip(update, onInstall = onInstallUpdate, onDismiss = onDismissUpdate)
            }

            if (!error.isNullOrBlank()) {
                ErrorStrip(error)
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Studio.Surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionLabel("Connection")
                StudioField(
                    value = url,
                    onValueChange = { url = it },
                    label = "Server address",
                    placeholder = "https://amethyst2213.com/gallery",
                    keyboardType = KeyboardType.Uri,
                    testTag = "login_url",
                )
                StudioField(
                    value = token,
                    onValueChange = { token = it },
                    label = "Operator token",
                    placeholder = "Paste the device token",
                    keyboardType = KeyboardType.Password,
                    secret = !reveal,
                    testTag = "login_token",
                    trailing = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (reveal) "Hide token" else "Show token",
                                tint = Studio.Muted,
                            )
                        }
                    },
                )
                PrimaryButton(
                    text = "Connect",
                    onClick = { onConnect(url, token) },
                    loading = busy,
                    enabled = !busy,
                )
                Text(
                    "The token is a device token from the admin panel (Chat, then Operator app). " +
                        "Only a device token can start a broadcast.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Dim,
                )
            }

            Spacer(Modifier.weight(1f))
            Text(
    // BuildConfig.VERSION_NAME is generated because buildConfig is enabled.
                "Operator Chat ${com.amethyst2213.operatorchat.BuildConfig.VERSION_NAME} · amethyst2213",
                style = MaterialTheme.typography.labelSmall,
                color = Studio.Dim,
            )
        }
    }
}

@Composable
fun StudioField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    imeAction: ImeAction = ImeAction.Next,
    trailing: (@Composable () -> Unit)? = null,
    testTag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().then(
            if (testTag != null) Modifier.testTag(testTag) else Modifier,
        ),
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Studio.Dim) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Studio.Amber,
            unfocusedBorderColor = Studio.Hairline,
            focusedLabelColor = Studio.Amber,
            unfocusedLabelColor = Studio.Dim,
            cursorColor = Studio.Amber,
            focusedTextColor = Studio.Ivory,
            unfocusedTextColor = Studio.Ivory,
            focusedContainerColor = Studio.InkSoft,
            unfocusedContainerColor = Studio.InkSoft,
        ),
    )
}
