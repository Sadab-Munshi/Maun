package org.maun.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.Avatars

/**
 * Log out is not Clear data: everything stays on the phone, and this screen is
 * the way back in. It says plainly what signing out did and what it did not do,
 * so that nobody signs out expecting their chats to be gone, or stays signed in
 * expecting to be protected.
 */
@Composable
fun SignedOutScreen(
    username: String?,
    working: Boolean = false,
    onLogIn: () -> Unit,
) {
    ScreenFrame(title = "Signed out") {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(id = Avatars.SELF, name = username ?: "You", size = 96.dp)
            Text(
                text = username?.let { "@$it" } ?: "Your Maun ID",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Maun is signed out on this phone. It is not connected, it cannot be woken " +
                    "for new messages, and it will not notify you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        SectionTitle("Still on this phone")
        Text(
            text = "Your chats, your pictures, your settings and your Maun ID are all still here, " +
                "and they were not uploaded anywhere. Logging in again on this phone brings back the " +
                "same account, with everything that arrived meanwhile.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Signing out does not delete your account, and does not " +
                "remove anything from it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = onLogIn,
            enabled = !working,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(if (working) "Logging in…" else "Log in") }
    }
}

@Preview(showBackground = true)
@Composable
private fun SignedOutPreview() {
    MaunTheme {
        SignedOutScreen(username = "sadab", onLogIn = {})
    }
}
