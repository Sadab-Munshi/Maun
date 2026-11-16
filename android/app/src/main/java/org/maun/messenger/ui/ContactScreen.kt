package org.maun.messenger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.AppLock
import org.maun.messenger.Avatars
import org.maun.messenger.Messenger
import org.maun.messenger.data.ContactEntity

/**
 * Everything about one contact. [safetyNumber] is the sixty digits both
 * phones show for this pair of people, or null while it is being worked out.
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
    /** Keeps a picture for this person, on this phone only. */
    onPhoto: (android.net.Uri) -> Unit = {},
    onRemovePhoto: () -> Unit = {},
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var newName by rememberSaveable { mutableStateOf("") }

    ScreenFrame(title = "Contact", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(id = contact.id, name = contact.name, size = 88.dp)
            Text(
                text = contact.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            contact.username?.let {
                Text("@$it", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = "A picture you add is kept on this phone only.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    newName = contact.name
                    renaming = true
                }) { Text("Change name") }
                val context = LocalContext.current
                var hasPhoto by remember(contact.id) { mutableStateOf(Avatars.has(context, contact.id)) }
                val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    AppLock.externalFlow = false
                    if (uri != null) {
                        hasPhoto = true
                        onPhoto(uri)
                    }
                }
                TextButton(onClick = {
                    if (hasPhoto) {
                        hasPhoto = false
                        onRemovePhoto()
                    } else {
                        try {
                            AppLock.externalFlow = true
                            pickPhoto.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        } catch (_: Exception) {
                            AppLock.externalFlow = false
                        }
                    }
                }) { Text(if (hasPhoto) "Remove photo" else "Add photo") }
            }
            if (onCall != null) {
                val call = rememberCallStarter(onCall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { call(false) }) {
                        Icon(MaunIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Call", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = { call(true) }) {
                        Icon(MaunIcons.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Video call", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

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
                        tint = if (contact.verified) LocalStatusColors.current.passed else MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = if (contact.verified) "Checked" else "Not checked yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (contact.verified) LocalStatusColors.current.passed else LocalStatusColors.current.warning,
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
                    SelectionContainer {
                        Text(
                            text = safetyNumber,
                            style = MaterialTheme.typography.titleMedium,
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

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Their ID")
            SelectionContainer { IdText(contact.id) }
        }

        BodyText(
            "Messages with ${contact.name} are encrypted between your two phones. The relay " +
                "holds a message only until their phone has collected it.",
        )

        OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Remove contact and delete chat")
        }
        OutlinedButton(onClick = { confirmingBlock = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Block")
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
