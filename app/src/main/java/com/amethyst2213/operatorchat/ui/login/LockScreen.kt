package com.amethyst2213.operatorchat.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.amethyst2213.operatorchat.ui.components.ErrorStrip
import com.amethyst2213.operatorchat.ui.components.GhostButton
import com.amethyst2213.operatorchat.ui.components.PrimaryButton
import com.amethyst2213.operatorchat.ui.components.StudioBackdrop
import com.amethyst2213.operatorchat.ui.theme.Studio

@Composable
fun LockScreen(
    error: String?,
    biometricAvailable: Boolean,
    passcodeSet: Boolean,
    onUnlockPin: (String) -> Unit,
    onBiometric: () -> Unit,
    onForgot: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirmForgot by remember { mutableStateOf(false) }

    StudioBackdrop {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Studio.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Studio.Amber,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Text("Locked", style = MaterialTheme.typography.headlineLarge, color = Studio.Ivory)
            Spacer(Modifier.height(6.dp))
            Text(
                if (passcodeSet) "Enter your passcode to open the console." else "Use your fingerprint to open the console.",
                style = MaterialTheme.typography.bodyMedium,
                color = Studio.Muted,
            )
            Spacer(Modifier.height(26.dp))

            if (!error.isNullOrBlank()) {
                ErrorStrip(error)
                Spacer(Modifier.height(14.dp))
            }

            if (passcodeSet) {
                StudioField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter { it.isDigit() }.take(32)
                    },
                    label = "Passcode",
                    keyboardType = KeyboardType.NumberPassword,
                    secret = true,
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    text = "Unlock",
                    onClick = { onUnlockPin(pin) },
                    enabled = pin.isNotBlank(),
                )
            }

            if (biometricAvailable) {
                if (passcodeSet) Spacer(Modifier.height(10.dp))
                GhostButton(
                    text = "Use biometrics",
                    onClick = onBiometric,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.Fingerprint,
                )
            }

            Spacer(Modifier.height(26.dp))
            if (!confirmForgot) {
                Text(
                    "Forgot passcode?",
                    style = MaterialTheme.typography.labelLarge,
                    color = Studio.Dim,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { confirmForgot = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            } else {
                Text(
                    "This wipes the saved connection and the passcode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Studio.Coral,
                )
                Spacer(Modifier.height(8.dp))
                GhostButton(
                    text = "Wipe and start over",
                    onClick = onForgot,
                    modifier = Modifier.fillMaxWidth(),
                    tint = Studio.Coral,
                )
            }
        }
    }
}
