package org.maun.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.AccountState
import org.maun.messenger.network.RelayState

/**
 * First launch: explains what is about to happen, then creates the ID.
 *
 * It is also shown when an ID was created but the relay has not accepted it
 * yet, for example because the phone went offline halfway.
 */
@Composable
fun OnboardingScreen(
    ui: OnboardingUiState,
    account: AccountState,
    relay: RelayState,
    onRelayUrlChange: (String) -> Unit,
    onCreate: () -> Unit,
    onOpenSetupCheck: () -> Unit,
) {
    val waitingForRelay = account is AccountState.Present && !account.registered
    val registering = waitingForRelay && (relay is RelayState.Connecting || relay is RelayState.Online)
    val retrying = waitingForRelay && relay is RelayState.Retrying
    val busy = ui.working || registering || retrying
    val problem = ui.error ?: when {
        !waitingForRelay -> null
        relay is RelayState.Retrying -> relay.reason
        relay is RelayState.Rejected -> relay.reason
        else -> null
    }
    val focus = LocalFocusManager.current
    val create = {
        focus.clearFocus()
        onCreate()
    }

    ScreenFrame(title = "Welcome to Maun") {
        BodyText(
            "Maun gives you a private ID that is made on this phone. " +
                "It needs no phone number, no email and no password.",
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Point("Your ID is a key that only this phone holds. Nobody else can sign in as you.")
                Point("People can reach you only if you show them your code.")
                Point(
                    "If you lose this phone or remove Maun, the ID is gone. " +
                        "There is no way to recover it yet.",
                )
            }
        }

        OutlinedTextField(
            value = ui.relayUrl,
            onValueChange = onRelayUrlChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
            label = { Text("Relay address") },
            placeholder = { Text("maun.your-name.workers.dev") },
            supportingText = { Text("The server that passes your encrypted messages along.") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { create() }),
        )

        if (problem != null) {
            Text(
                text = if (retrying) "$problem Trying again…" else problem,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalStatusColors.current.failed,
            )
        }

        Button(onClick = create, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (ui.working) "Checking the relay…" else "Registering your ID…")
                }
            } else {
                Text(if (waitingForRelay) "Try again" else "Create my ID")
            }
        }

        TextButton(onClick = onOpenSetupCheck, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Check this phone first")
        }
    }
}

@Composable
private fun Point(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPreview() {
    MaunTheme {
        OnboardingScreen(
            ui = OnboardingUiState(relayUrl = "maun.example.workers.dev"),
            account = AccountState.None,
            relay = RelayState.Offline,
            onRelayUrlChange = {},
            onCreate = {},
            onOpenSetupCheck = {},
        )
    }
}
