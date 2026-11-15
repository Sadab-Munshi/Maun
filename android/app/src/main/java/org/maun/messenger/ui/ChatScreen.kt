package org.maun.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import org.maun.messenger.Messenger
import org.maun.messenger.Payload
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.data.ReceiptLevel
import org.maun.messenger.network.RelayState

/**
 * One conversation. [messages] is newest first. [contactName] is null while
 * the other person is not a contact: their messages are shown as a request,
 * and nothing can be sent until it is accepted.
 */
@Composable
fun ChatScreen(
    peerId: String,
    contactName: String?,
    messages: List<MessageEntity>,
    waiting: String?,
    relay: RelayState,
    onSend: (String) -> Unit,
    onSeen: () -> Unit,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
    onAccept: (String) -> Unit,
    onBlock: () -> Unit,
) {
    val isContact = contactName != null
    val listState = rememberLazyListState()
    val newest = messages.firstOrNull()

    // Keep the newest message in view: always for one's own, and for an
    // incoming one unless the reader has scrolled back to look at history.
    LaunchedEffect(newest?.seq) {
        if (newest != null && (newest.outgoing || listState.firstVisibleItemIndex <= 1)) {
            listState.scrollToItem(0)
        }
    }

    // A message counts as read only while this screen is really in front.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inFront = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val unseen = messages.any { !it.outgoing && (it.unread || (isContact && it.receipt < ReceiptLevel.READ)) }
    LaunchedEffect(inFront, unseen, newest?.seq) {
        if (inFront && unseen) onSeen()
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                ChatHeader(
                    title = contactName ?: "Message request",
                    subtitle = shortId(peerId),
                    onBack = onBack,
                    onOpenDetails = if (isContact) onOpenDetails else null,
                )
                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
                ) {
                    items(messages, key = { it.seq }) { message ->
                        MessageBubble(message, online = relay == RelayState.Online, stuck = waiting != null)
                    }
                    if (messages.isEmpty()) {
                        item {
                            Text(
                                text = "No messages yet. Messages are encrypted on this phone and can " +
                                    "only be read on theirs.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                            )
                        }
                    }
                    if (messages.size >= Messenger.MESSAGE_WINDOW) {
                        item {
                            Text(
                                text = "Older messages are kept on this phone but not shown yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            )
                        }
                    }
                }

                if (waiting != null) Notice(waiting)
                if (isContact) {
                    Composer(peerId = peerId, onSend = onSend)
                } else {
                    RequestBar(peerId = peerId, onAccept = onAccept, onBlock = onBlock)
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(title: String, subtitle: String, onBack: () -> Unit, onOpenDetails: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("‹ Back") }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onOpenDetails != null) {
            TextButton(onClick = onOpenDetails, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Details") }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity, online: Boolean, stuck: Boolean) {
    val outgoing = message.outgoing
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
        Surface(
            color = if (outgoing) colors.primaryContainer else colors.surfaceVariant,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                if (message.kind == MessageKind.UNREADABLE) {
                    Text(
                        text = "A message arrived that this phone could not decrypt.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (outgoing) colors.onPrimaryContainer else colors.onSurface,
                        )
                    }
                }
                val time = formatWhen(message.at)
                Text(
                    text = if (outgoing) "$time · ${statusLabel(message.status, online, stuck)}" else time,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (outgoing && message.status == MessageStatus.READ) colors.primary else colors.onSurfaceVariant,
                    fontWeight = if (outgoing && message.status == MessageStatus.READ) FontWeight.SemiBold else null,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Not delivered yet. $text",
            style = MaterialTheme.typography.bodySmall,
            color = LocalStatusColors.current.warning,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Composer(peerId: String, onSend: (String) -> Unit) {
    var draft by rememberSaveable(peerId) { mutableStateOf("") }
    val bytes = draft.trim().toByteArray(Charsets.UTF_8).size
    val tooLong = bytes > Payload.MAX_TEXT_BYTES
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (tooLong) {
            Text(
                text = "Too long for one message. Shorten it or send it in parts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 5,
                isError = tooLong,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Button(
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = bytes > 0 && !tooLong,
            ) { Text("Send") }
        }
    }
}

@Composable
private fun RequestBar(peerId: String, onAccept: (String) -> Unit, onBlock: () -> Unit) {
    var naming by rememberSaveable(peerId) { mutableStateOf(false) }
    var confirmingBlock by rememberSaveable(peerId) { mutableStateOf(false) }
    var name by rememberSaveable(peerId) { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "This person is not in your contacts. They cannot tell whether their message " +
                    "arrived or was read unless you accept. Accept only if you know who this is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { naming = true }, modifier = Modifier.weight(1f)) { Text("Accept") }
                OutlinedButton(onClick = { confirmingBlock = true }, modifier = Modifier.weight(1f)) { Text("Block") }
            }
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Add as a contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Give them a name. It is stored on this phone only.")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(Messenger.MAX_NAME_LENGTH) },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        naming = false
                        onAccept(name.trim())
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Accept") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    if (confirmingBlock) {
        BlockDialog(
            who = "this person",
            onConfirm = {
                confirmingBlock = false
                onBlock()
            },
            onDismiss = { confirmingBlock = false },
        )
    }
}

@Composable
fun BlockDialog(who: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block $who?") },
        text = {
            Text(
                "The conversation is deleted from this phone, and anything they send from now on " +
                    "is thrown away unread. They are not told. You can unblock them later.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Block") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A row that opens something when tapped; shared by the home screen's lists. */
@Composable
fun TappableSurface(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        content = content,
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatPreview() {
    val peer = "ab12".repeat(16)
    MaunTheme {
        ChatScreen(
            peerId = peer,
            contactName = "Asha",
            messages = listOf(
                MessageEntity(seq = 4, peer = peer, id = "4", outgoing = true, text = "See you at six", at = 0),
                MessageEntity(seq = 3, peer = peer, id = "3", outgoing = true, text = "Yes!", at = 0, status = MessageStatus.DELIVERED),
                MessageEntity(seq = 2, peer = peer, id = "2", outgoing = false, text = "Are we still on for today?", at = 0),
                MessageEntity(seq = 1, peer = peer, id = "1", outgoing = true, text = "Hello", at = 0, status = MessageStatus.READ),
            ),
            waiting = null,
            relay = RelayState.Online,
            onSend = {},
            onSeen = {},
            onBack = {},
            onOpenDetails = {},
            onAccept = {},
            onBlock = {},
        )
    }
}
