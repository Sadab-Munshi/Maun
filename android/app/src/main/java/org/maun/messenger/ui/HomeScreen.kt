package org.maun.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.network.RelayState

@Composable
fun HomeScreen(
    relay: RelayState,
    contacts: List<ContactEntity>,
    onShowMyCode: () -> Unit,
    onAddContact: () -> Unit,
    onOpenContact: (String) -> Unit,
    onReconnect: () -> Unit,
    onOpenSetupCheck: () -> Unit,
) {
    ScreenFrame(title = "Maun") {
        ConnectionStatus(relay, onReconnect)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onShowMyCode, modifier = Modifier.weight(1f)) { Text("My code") }
            OutlinedButton(onClick = onAddContact, modifier = Modifier.weight(1f)) { Text("Add contact") }
        }

        Text("Contacts", style = MaterialTheme.typography.titleMedium)
        if (contacts.isEmpty()) {
            BodyText(
                "Nobody here yet. Show your code to someone, or scan theirs. " +
                    "Both of you need to add each other.",
            )
        } else {
            contacts.forEach { contact -> ContactRow(contact, onClick = { onOpenContact(contact.id) }) }
            Text(
                text = "Messaging arrives in the next version. For now this confirms that both " +
                    "phones are registered and know each other's ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        TextButton(onClick = onOpenSetupCheck, modifier = Modifier.fillMaxWidth()) { Text("Setup check") }
    }
}

@Composable
private fun ConnectionStatus(relay: RelayState, onReconnect: () -> Unit) {
    val colors = LocalStatusColors.current
    val (color, text) = when (relay) {
        RelayState.Online -> colors.passed to "Connected and signed in"
        RelayState.Connecting -> MaterialTheme.colorScheme.outline to "Connecting…"
        RelayState.Offline -> MaterialTheme.colorScheme.outline to "Not connected"
        is RelayState.Retrying -> colors.warning to "${relay.reason} Trying again…"
        is RelayState.Rejected -> colors.failed to relay.reason
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(10.dp)) {}
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (relay is RelayState.Rejected) {
                TextButton(onClick = onReconnect) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: ContactEntity, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = contact.name.trim().take(1).uppercase().ifEmpty { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = shortId(contact.id),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (contact.verified) "Scanned" else "Typed",
                style = MaterialTheme.typography.labelMedium,
                color = if (contact.verified) LocalStatusColors.current.passed else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    MaunTheme {
        HomeScreen(
            relay = RelayState.Online,
            contacts = listOf(
                ContactEntity("ab12".repeat(16), "Asha", verified = true, addedAt = 0),
                ContactEntity("cd34".repeat(16), "Rohan", verified = false, addedAt = 0),
            ),
            onShowMyCode = {},
            onAddContact = {},
            onOpenContact = {},
            onReconnect = {},
            onOpenSetupCheck = {},
        )
    }
}
