package org.maun.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.Messenger
import org.maun.messenger.data.ContactEntity

/**
 * Everything about one contact, in the order a person needs it: who this is,
 * how to reach them, whether their phone has been checked, and only then what
 * can be done about them.
 *
 * The picture at the top is the one this contact chose on their own phone and
 * shared with this one. It cannot be changed from here, and nothing else is
 * drawn in its place: what somebody looks like is theirs to decide.
 *
 * The safety number and the raw ID used to take up most of this page, which
 * made a page about a person look like a page about cryptography. They have
 * moved one tap away, behind the encryption row, and everything a person does
 * here is now the length of a list.
 */
@Composable
fun ContactScreen(
    contact: ContactEntity,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit,
    /** Starts a call with this contact, a video call when given true. Null where calls cannot be made. */
    onCall: ((Boolean) -> Unit)? = null,
    /** Opens the screen that holds the safety number and the check itself. */
    onOpenEncryption: (() -> Unit)? = null,
    /**
     * The relay says Maun's administrators have checked this account. This is
     * not [ContactEntity.verified], which is whether the user themselves has
     * checked the safety number.
     */
    ticked: Boolean = false,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                TopBar(
                    title = "Contact",
                    onBack = onBack,
                    actions = {
                        // What can be changed about a contact is in one place,
                        // out of the way of what the page is actually for.
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(MaunIcons.More, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Edit contact name") },
                                    leadingIcon = { Icon(MaunIcons.Edit, contentDescription = null) },
                                    onClick = {
                                        menu = false
                                        newName = contact.name
                                        renaming = true
                                    },
                                )
                            }
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Who this is stays put while the rest of the page scrolls, so
                // the picture is never left half under the bar.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Avatar(id = contact.id, name = contact.name, size = 88.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = contact.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        VerifiedBadge(ticked)
                    }
                    if (ticked) {
                        Text(
                            "Verified by Maun",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    contact.username?.let {
                        Text(
                            "@$it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onCall != null) {
                        val call = rememberCallStarter(onCall)
                        // Two buttons of the same size, side by side, whatever
                        // the width of the phone.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            FilledTonalButton(
                                onClick = { call(false) },
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            ) {
                                Icon(MaunIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Voice call")
                            }
                            FilledTonalButton(
                                onClick = { call(true) },
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                            ) {
                                Icon(MaunIcons.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Video call")
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    EncryptionRow(verified = contact.verified, onClick = onOpenEncryption)

                    BodyText(
                        "Messages with ${contact.name} are encrypted on this phone and read only on theirs. " +
                            "The relay carries them and cannot read them.",
                    )

                    Spacer(modifier = Modifier.height(2.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(2.dp))

                    // What can be done about a contact, as a list rather than a
                    // wall of outlined buttons. Blocking is red: it is the one
                    // that cannot be taken back from here.
                    Text(
                        text = "Remove or block",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                    ActionRow(
                        icon = MaunIcons.Delete,
                        title = "Remove contact",
                        detail = "Deletes this conversation from this phone only.",
                        onClick = { confirming = true },
                    )
                    ActionRow(
                        icon = MaunIcons.Block,
                        title = "Block",
                        detail = "Throws away anything ${contact.name} sends from now on. They are not told.",
                        destructive = true,
                        onClick = { confirmingBlock = true },
                    )
                }
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Edit contact name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("The name is kept on this phone only. They are not told.")
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(Messenger.MAX_NAME_LENGTH) },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        renaming = false
                        onRename(newName.trim())
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    if (confirmingBlock) {
        BlockDialog(
            who = contact.name,
            onConfirm = {
                confirmingBlock = false
                onBlock()
            },
            onDismiss = { confirmingBlock = false },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove ${contact.name}?") },
            text = {
                Text(
                    "The contact and your conversation are deleted from this phone only. If they " +
                        "write again, it shows up as a message request.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onRemove()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep") } },
        )
    }
}

/**
 * One line about encryption that says where things stand and leads to the
 * place where they can be checked. A padlock, a state, and a chevron: the
 * safety number itself lives behind it, on its own screen.
 */
@Composable
private fun EncryptionRow(verified: Boolean, onClick: (() -> Unit)?) {
    val passed = LocalStatusColors.current.passed
    val warning = LocalStatusColors.current.warning
    val state = if (verified) passed else warning
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(state.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MaunIcons.Lock, contentDescription = null, tint = state, modifier = Modifier.size(19.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = "End-to-end encryption",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (verified) {
                        "Checked — the safety number was compared"
                    } else {
                        "Not checked yet — compare the safety number"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (verified) passed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClick != null) {
                Icon(
                    MaunIcons.Forward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** A row in the list of things that can be done about a contact. */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    detail: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
                fontWeight = if (destructive) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ContactPreview() {
    MaunTheme {
        ContactScreen(
            contact = ContactEntity("ab12".repeat(16), "Asha", verified = false, addedAt = 0),
            onBack = {},
            onRename = {},
            onRemove = {},
            onBlock = {},
        )
    }
}
