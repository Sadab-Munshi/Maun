package org.maun.messenger.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.network.RelayState

@Composable
fun HomeScreen(
    relay: RelayState,
    lists: HomeLists,
    blockedCount: Int,
    onShowMyCode: () -> Unit,
    onAddContact: () -> Unit,
    onOpenChat: (String) -> Unit,
    onReconnect: () -> Unit,
    onOpenBlocked: () -> Unit,
    onOpenSetupCheck: () -> Unit,
) {
    ScreenFrame(title = "Maun") {
        ConnectionStatus(relay, onReconnect)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onShowMyCode, modifier = Modifier.weight(1f)) { Text("My code") }
            OutlinedButton(onClick = onAddContact, modifier = Modifier.weight(1f)) { Text("Add contact") }
        }

        if (lists.requests.isNotEmpty()) {
            Text("Message requests", style = MaterialTheme.typography.titleMedium)
            lists.requests.forEach { row -> ChatRowItem(row, onClick = { onOpenChat(row.peer) }) }
        }

        Text("Chats", style = MaterialTheme.typography.titleMedium)
        if (lists.chats.isEmpty()) {
            BodyText(
                "Nobody here yet. Show your code to someone, or scan theirs. " +
                    "Once you have added each other you can write.",
            )
        } else {
            lists.chats.forEach { row -> ChatRowItem(row, onClick = { onOpenChat(row.peer) }) }
            Text(
                text = "Messages arrive while Maun is open. Delivery in the background comes in the next version.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (blockedCount > 0) {
            TextButton(onClick = onOpenBlocked, modifier = Modifier.fillMaxWidth()) { Text("Blocked ($blockedCount)") }
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
private fun ChatRowItem(row: ChatRow, onClick: () -> Unit) {
    val name = row.contact?.name ?: shortId(row.peer)
    val last = row.last
    val unread = last?.unread ?: 0
    TappableSurface(onClick = onClick) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = row.contact?.name?.trim()?.take(1)?.uppercase().orEmpty().ifEmpty { "?" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = if (row.contact == null) FontFamily.Monospace else null,
                    fontWeight = if (unread > 0) FontWeight.Bold else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = previewOf(last).replace('\n', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (last != null) {
                    Text(
                        text = formatWhen(last.at),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (unread > 0) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Text(
                            text = if (unread > 99) "99+" else unread.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    val asha = ContactEntity("ab12".repeat(16), "Asha", verified = true, addedAt = 0)
    val rohan = ContactEntity("cd34".repeat(16), "Rohan", verified = false, addedAt = 0)
    MaunTheme {
        HomeScreen(
            relay = RelayState.Online,
            lists = HomeLists(
                chats = listOf(
                    ChatRow(asha.id, asha, ChatSummary(asha.id, 2, 0, "Are we still on for today?", false, 0, 0, 2)),
                    ChatRow(rohan.id, rohan, null),
                ),
                requests = listOf(
                    ChatRow("ef56".repeat(16), null, ChatSummary("ef56".repeat(16), 1, 0, "Hi, it's Meera", false, 0, 0, 1)),
                ),
            ),
            blockedCount = 1,
            onShowMyCode = {},
            onAddContact = {},
            onOpenChat = {},
            onReconnect = {},
            onOpenBlocked = {},
            onOpenSetupCheck = {},
        )
    }
}
