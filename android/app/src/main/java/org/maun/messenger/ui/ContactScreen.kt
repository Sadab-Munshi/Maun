package org.maun.messenger.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.maun.messenger.data.ContactEntity

@Composable
fun ContactScreen(contact: ContactEntity, onBack: () -> Unit, onRemove: () -> Unit, onBlock: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var confirmingBlock by rememberSaveable { mutableStateOf(false) }

    ScreenFrame(title = contact.name, onBack = onBack) {
        Text(
            text = if (contact.verified) {
                "Scanned from their screen. This ID came straight from their phone."
            } else {
                "Typed or pasted. To be sure this ID is really theirs, compare it with the one " +
                    "on their My code screen, or remove them and scan their code instead."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (contact.verified) LocalStatusColors.current.passed else LocalStatusColors.current.warning,
        )

        Text("Their ID", style = MaterialTheme.typography.titleMedium)
        SelectionContainer { IdText(contact.id) }

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
