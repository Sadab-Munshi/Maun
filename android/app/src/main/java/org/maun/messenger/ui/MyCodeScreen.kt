package org.maun.messenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.maun.messenger.crypto.ContactCode

/** The user's own code, as a QR to be scanned and as text to be sent. */
@Composable
fun MyCodeScreen(code: ContactCode, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember(code) { code.encode() }
    var copied by remember { mutableStateOf(false) }

    ScreenFrame(title = "My code", onBack = onBack) {
        BodyText("Ask the other person to open Add contact in Maun and point their camera here.")

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            QrImage(
                text = text,
                description = "QR code holding your Maun ID",
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
            )
        }

        Text("Your ID", style = MaterialTheme.typography.titleMedium)
        IdText(code.id)
        Text(
            text = "Relay: ${code.relay}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Maun code", text))
                    copied = true
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (copied) "Copied" else "Copy code") }
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "Send my Maun code"))
                },
                modifier = Modifier.weight(1f),
            ) { Text("Send code") }
        }

        Text(
            text = "The code is not a password: it lets people add you, not sign in as you. " +
                "When it is sent as text instead of scanned, the other person cannot be sure " +
                "it was not swapped on the way, so scan in person when you can.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
