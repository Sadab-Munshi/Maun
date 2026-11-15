package org.maun.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.network.RelayState

/**
 * The first screen: the list of conversations. Everything that is not a
 * conversation (the user's code, settings) is one tap away in the top bar.
 */
@Composable
fun HomeScreen(
    relay: RelayState,
    lists: HomeLists,
    delivery: DeliveryUi,
    pushProblem: String?,
    onChoosePushApp: (String) -> Unit,
    onGooglePush: (Boolean) -> Unit,
    onStayConnected: (Boolean) -> Unit,
    onDeliveryChanged: () -> Unit,
    onShowMyCode: () -> Unit,
    onAddContact: () -> Unit,
    onOpenChat: (String) -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val nobody = lists.chats.isEmpty() && lists.requests.isEmpty()
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            // With nobody in the list, the same button stands in the middle of the screen instead.
            if (!nobody) {
                ExtendedFloatingActionButton(
                    onClick = onAddContact,
                    icon = { Icon(MaunIcons.AddPerson, contentDescription = null) },
                    text = { Text("Add contact") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Maun",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(onClick = onShowMyCode) { Icon(MaunIcons.Qr, contentDescription = "My code") }
                    IconButton(onClick = onOpenSettings) { Icon(MaunIcons.Settings, contentDescription = "Settings") }
                }
                ConnectionLine(relay, onReconnect)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Room for the button that floats over the end of the list.
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    // Shown here only while something about background delivery
                    // needs doing. Once settled it lives in Settings.
                    if (delivery.needsAttention) {
                        item(key = "delivery") {
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                DeliveryCard(
                                    delivery,
                                    pushProblem,
                                    onChoosePushApp,
                                    onGooglePush,
                                    onStayConnected,
                                    onDeliveryChanged,
                                )
                            }
                        }
                    }
                    if (lists.requests.isNotEmpty()) {
                        item(key = "requests") { ListHeading("Message requests") }
                        items(lists.requests, key = { "r" + it.peer }) { row ->
                            ChatRowItem(row, onClick = { onOpenChat(row.peer) })
                        }
                        if (lists.chats.isNotEmpty()) item(key = "chats") { ListHeading("Chats") }
                    }
                    items(lists.chats, key = { "c" + it.peer }) { row ->
                        ChatRowItem(row, onClick = { onOpenChat(row.peer) })
                    }
                    if (nobody) {
                        item(key = "nobody") { NobodyYet(onAddContact = onAddContact, onShowMyCode = onShowMyCode) }
                    }
                }
            }
        }
    }
}

/** Says so when Maun is not connected. Says nothing when it is: that is the normal state. */
@Composable
private fun ConnectionLine(relay: RelayState, onReconnect: () -> Unit) {
    val colors = LocalStatusColors.current
    val (color, text) = when (relay) {
        RelayState.Online -> return
        RelayState.Connecting -> MaterialTheme.colorScheme.outline to "Connecting…"
        RelayState.Offline -> MaterialTheme.colorScheme.outline to "Not connected"
        is RelayState.Retrying -> colors.warning to "${relay.reason} Trying again…"
        is RelayState.Rejected -> colors.failed to relay.reason
        is RelayState.LoginNeeded -> colors.warning to "This relay asks you to log in."
        RelayState.Banned -> colors.failed to "This relay no longer accepts this account."
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) {}
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (relay is RelayState.Rejected) {
                TextButton(onClick = onReconnect) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun ListHeading(text: String) {
    SectionTitle(text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun ChatRowItem(row: ChatRow, onClick: () -> Unit) {
    val last = row.last
    val unread = last?.unread ?: 0
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(id = row.peer, name = row.contact?.name)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.contact?.name ?: shortId(row.peer),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = if (row.contact == null) FontFamily.Monospace else null,
                    fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (last != null) {
                    Text(
                        text = formatListTime(last.at),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (unread > 0) colors.primary else colors.onSurfaceVariant,
                        fontWeight = if (unread > 0) FontWeight.Bold else null,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (last != null && last.outgoing) {
                    // The home screen does not know why a message is stuck; the conversation says.
                    StatusTick(last.status, online = true, stuck = false, tint = colors.onSurfaceVariant)
                }
                Text(
                    text = previewOf(last),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (unread > 0) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unread > 0) {
                    Surface(shape = CircleShape, color = colors.primary) {
                        Text(
                            text = if (unread > 99) "99+" else unread.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onPrimary,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NobodyYet(onAddContact: () -> Unit, onShowMyCode: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            MaunIcons.Chat,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text("Nobody here yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Show your code to someone, or scan theirs. Once you have added each other you can write.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAddContact, modifier = Modifier.widthIn(min = 220.dp)) { Text("Add contact") }
        OutlinedButton(onClick = onShowMyCode, modifier = Modifier.widthIn(min = 220.dp)) { Text("Show my code") }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    val asha = ContactEntity("ab12".repeat(16), "Asha", verified = true, addedAt = 0)
    val rohan = ContactEntity("cd34".repeat(16), "Rohan", verified = false, addedAt = 0)
    val meera = "ef56".repeat(16)
    MaunTheme {
        HomeScreen(
            relay = RelayState.Connecting,
            lists = HomeLists(
                chats = listOf(
                    ChatRow(asha.id, asha, ChatSummary(asha.id, 2, 0, "Are we still on for today?", false, 0, 0, 2)),
                    ChatRow(rohan.id, rohan, ChatSummary(rohan.id, 3, 0, "See you at six", true, MessageStatus.READ, 0, 0)),
                ),
                requests = listOf(ChatRow(meera, null, ChatSummary(meera, 1, 0, "Hi, it's Meera", false, 0, 0, 1))),
            ),
            delivery = DeliveryUi(pushApp = "Sunup"),
            pushProblem = null,
            onChoosePushApp = {},
            onGooglePush = {},
            onStayConnected = {},
            onDeliveryChanged = {},
            onShowMyCode = {},
            onAddContact = {},
            onOpenChat = {},
            onReconnect = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyHomePreview() {
    MaunTheme {
        HomeScreen(
            relay = RelayState.Online,
            lists = HomeLists(),
            delivery = DeliveryUi(pushApp = "Sunup"),
            pushProblem = null,
            onChoosePushApp = {},
            onGooglePush = {},
            onStayConnected = {},
            onDeliveryChanged = {},
            onShowMyCode = {},
            onAddContact = {},
            onOpenChat = {},
            onReconnect = {},
            onOpenSettings = {},
        )
    }
}
