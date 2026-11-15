package org.maun.messenger.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.maun.messenger.data.BlockedEntity

@Composable
fun BlockedScreen(blocked: List<BlockedEntity>, onUnblock: (String) -> Unit, onBack: () -> Unit) {
    ScreenFrame(title = "Blocked", onBack = onBack) {
        if (blocked.isEmpty()) {
            BodyText("Nobody is blocked.")
        } else {
            BodyText(
                "Messages from these IDs are thrown away unread. Unblocking does not bring back " +
                    "anything that was sent in the meantime.",
            )
            blocked.forEach { entry ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = shortId(entry.id),
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = "Blocked ${formatWhen(entry.blockedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onUnblock(entry.id) }) { Text("Unblock") }
                    }
                }
            }
        }
    }
}
