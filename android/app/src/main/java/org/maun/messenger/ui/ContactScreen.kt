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
fun ContactScreen(contact: ContactEntity, onBack: () -> Unit, onRemove: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }

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

        BodyText("Messaging arrives in the next version.")

        OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Remove contact")
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove ${contact.name}?") },
            text = { Text("They are removed from this phone only. You can add them again with their code.") },
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
