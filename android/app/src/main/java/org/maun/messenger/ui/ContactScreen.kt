package org.maun.messenger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.AppLock
import org.maun.messenger.Avatars
import org.maun.messenger.Messenger
import org.maun.messenger.data.ContactEntity

/**
 * Everything about one contact, in the order a person needs it: who this is,
 * how to reach them, whether their ID has been checked, and only then what can
 * be done about them.
 *
 * The picture at the top is the one this contact chose, which arrives from
 * their phone. It cannot be changed from here: what somebody looks like on
 * their own phone is theirs to decide. A photo added here is a private note on
 * this phone, kept apart from it and marked as such.
 */
@Composable
fun ContactScreen(
    contact: ContactEntity,
    safetyNumber: String?,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onChecked: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onBlock: () -> Unit,
    /** Starts a call with this contact, a video call when given true. Null where calls cannot be made. */
    onCall: ((Boolean) -> Unit)? = null,
    /** Keeps a photo for this person on this phone only. It is never sent anywhere. */
    onPhoto: (android.net.Uri) -> Unit = {},
    onRemovePhoto: () -> Unit = {},
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                TopBar(title = "Contact", onBack = onBack)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Who this is stays put while the rest of the page scrolls, so
                // the picture is never left half under the bar.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Avatar(id = contact.id, name = contact.name, size = 84.dp)
                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    contact.username?.let {
                        Text(
                            "@$it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onCall != null) {
                            val call = rememberCallStarter(onCall)
                            FilledTonalButton(onClick = { call(false) }) {
                                Icon(MaunIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Call")
                            }
                            FilledTonalButton(onClick = { call(true) }) {
                                Icon(MaunIcons.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Video")
                            }
                        }
                        TextButton(onClick = {
                            newName = contact.name
                            renaming = true
                        }) { Text("Change name") }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PictureSection(contact, onPhoto, onRemovePhoto)

                    SafetySection(contact, safetyNumber, onChecked)

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionTitle("Their ID")
                        SelectionContainer { IdText(contact.id) }
                    }

                    BodyText(
                        "Messages with ${contact.name} are encrypted between your two phones. The relay " +
                            "holds a message only until their phone has collected it.",
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle("Remove or block")
                        OutlinedButton(
                            onClick = { confirming = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Remove contact and delete chat") }
                        OutlinedButton(
                            onClick = { confirmingBlock = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Block", color = MaterialTheme.colorScheme.error) }
                        Text(
                            text = "Removing deletes this conversation from this phone. Blocking also throws " +
                                "away anything they send from now on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Change name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("The name is stored on this phone only. They are not told.")
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
 * Their picture, which came from their phone, and beneath it a photo kept on
 * this phone only. The two are never mixed: one is theirs, the other is a note
 * to oneself, and each says which it is.
 */
@Composable
private fun PictureSection(contact: ContactEntity, onPhoto: (android.net.Uri) -> Unit, onRemovePhoto: () -> Unit) {
    val context = LocalContext.current
    // A picture that arrives, or is taken down, while this screen is open is
    // read again rather than left as it was when the screen was first drawn.
    val revision = LocalAvatarRevision.current
    var theirs by remember(contact.id, revision) { mutableStateOf(Avatars.has(context, contact.id)) }
    var mine by remember(contact.id, revision) { mutableStateOf(Avatars.hasLocal(context, contact.id)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        AppLock.externalFlow = false
        if (uri != null) {
            mine = true
            onPhoto(uri)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Profile picture")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(id = contact.id, name = contact.name, size = 44.dp)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = when {
                            mine -> "A photo you chose"
                            theirs -> "Their own picture"
                            else -> "No picture"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = when {
                            mine && theirs -> "Shown instead of the picture their phone sent."
                            mine -> "On this phone only. Their own picture is not shown here."
                            theirs -> "Set on their phone, and seen here without asking."
                            else -> "Their phone has not shared one, or took it down."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = {
                if (mine) {
                    mine = false
                    onRemovePhoto()
                } else {
                    try {
                        AppLock.externalFlow = true
                        pick.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    } catch (_: Exception) {
                        AppLock.externalFlow = false
                    }
                }
            }) { Text(if (mine) "Remove my photo" else "Add a photo on this phone") }
            Text(
                text = "Their picture belongs to them: it can only be changed on their own phone, and it " +
                    "changes here by itself when they do. A photo added here stays on this phone, sealed " +
                    "like everything else, and nobody is told.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The safety number and what it means, in one card that fits a narrow screen. */
@Composable
private fun SafetySection(contact: ContactEntity, safetyNumber: String?, onChecked: (Boolean) -> Unit) {
    val passed = LocalStatusColors.current.passed
    val warning = LocalStatusColors.current.warning
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    MaunIcons.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (contact.verified) passed else MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = if (contact.verified) "Checked" else "Not checked yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (contact.verified) passed else warning,
                )
            }
            Text(
                text = if (contact.verified) {
                    "You scanned this ID from their screen, or compared the safety number with them. " +
                        "You are writing to their phone and nobody else's."
                } else {
                    "This ID was typed or pasted, so it could have been swapped on the way to you. " +
                        "To be sure, compare the safety number below with the one on their phone: " +
                        "meet, or read it out on a call. If every digit matches, it is them."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Safety number", style = MaterialTheme.typography.labelLarge)
            if (safetyNumber == null) {
                Text("Working it out…", style = MaterialTheme.typography.bodyMedium)
            } else {
                // Sixty digits in three lines of twenty: legible on a phone, and
                // read out in the same groups by both people.
                SelectionContainer {
                    Text(
                        text = safetyNumber,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                text = "They see the same number when they open your name in their Maun.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (contact.verified) {
                TextButton(onClick = { onChecked(false) }) { Text("Mark as not checked") }
            } else {
                Button(onClick = { onChecked(true) }, enabled = safetyNumber != null) { Text("The numbers match") }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ContactPreview() {
    MaunTheme {
        ContactScreen(
            contact = ContactEntity("ab12".repeat(16), "Asha", verified = false, addedAt = 0),
            safetyNumber = "12345 67890 12345 67890\n12345 67890 12345 67890\n12345 67890 12345 67890",
            onBack = {},
            onRename = {},
            onChecked = {},
            onRemove = {},
            onBlock = {},
        )
    }
}
