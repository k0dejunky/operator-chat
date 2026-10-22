package com.amethyst2213.operatorchat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.ui.components.ErrorBanner
import com.amethyst2213.operatorchat.ui.components.PrimaryButton
import com.amethyst2213.operatorchat.ui.theme.Palette

/**
 * The sign-in screen.
 *
 * The old app had this form inside a NestedScrollView that was set to
 * `visibility="gone"` and that no code ever showed again - so on a fresh
 * install the operator saw an empty screen with no way to enter the server
 * address or the token. It is a screen of its own now, and it is reached
 * whenever there are no stored credentials.
 */
@Composable
fun LoginScreen(
    initialUrl: String,
    initialToken: String,
    busy: Boolean,
    error: String?,
    onConnect: (url: String, token: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var token by rememberSaveable { mutableStateOf(initialToken) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        Icon(
            imageVector = Icons.Outlined.Podcasts,
            contentDescription = null,
            tint = Palette.Amethyst,
            modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Operator",
            style = MaterialTheme.typography.headlineSmall,
            color = Palette.TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Sign in to the gallery's chat and live controls.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server address") },
            placeholder = { Text("https://amethyst2213.com/gallery") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Operator token") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (showToken) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector = if (showToken) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (showToken) "Hide the token" else "Show the token",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth(),
        )

        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(14.dp))
            ErrorBanner(error)
        }

        Spacer(Modifier.height(22.dp))

        PrimaryButton(
            text = if (busy) "Connecting…" else "Connect",
            onClick = {
                keyboard?.hide()
                onConnect(url.trim(), token.trim())
            },
            enabled = !busy && url.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = "The token is an operator device token. Create one in the admin panel under Chat.",
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextTertiary,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(40.dp))
    }
}
