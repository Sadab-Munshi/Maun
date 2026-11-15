package org.maun.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The first moments of a fresh install: the phone makes its ID and tells the
 * relay, without asking anything. Usually gone within a second or two. Stays,
 * with [problem] and a way to try again, when the relay cannot be reached.
 * [onChooseServer] is offered until the ID has been made.
 */
@Composable
fun SettingUpScreen(problem: String?, working: Boolean, onRetry: () -> Unit, onChooseServer: (() -> Unit)?) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (problem == null || working) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
                Text("Setting up Maun…", style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(MaunIcons.Info, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Maun could not finish setting up",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "$problem\n\nCheck that this phone is online and try again.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
                Button(onClick = onRetry) { Text("Try again") }
                if (onChooseServer != null) TextButton(onClick = onChooseServer) { Text("Use another server") }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingUpPreview() {
    MaunTheme { SettingUpScreen(problem = "Could not reach the relay: no route to host", working = false, onRetry = {}, onChooseServer = {}) }
}
