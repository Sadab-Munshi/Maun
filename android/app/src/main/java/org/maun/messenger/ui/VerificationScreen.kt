package org.maun.messenger.ui

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.data.ContactEntity

/**
 * Everything that proves who a contact is, on a screen of its own: the safety
 * number the two phones agree on, their identity, where the check stands, and
 * the two ways to change it.
 *
 * Nothing here is automatic. Maun never decides on its own that a contact has
 * been checked: only a person reading the same sixty digits on both phones, or
 * scanning an ID from the other screen, can say so, and both of those are a
 * button pressed here.
 */
@Composable
fun VerificationScreen(
    contact: ContactEntity,
    safetyNumber: String?,
    onBack: () -> Unit,
    onChecked: (Boolean) -> Unit,
) {
    val passed = LocalStatusColors.current.passed
    val warning = LocalStatusColors.current.warning
    val state = if (contact.verified) passed else warning

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                TopBar(title = "Encryption", onBack = onBack)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(MaunIcons.Shield, contentDescription = null, tint = state, modifier = Modifier.size(22.dp))
                        Text(
                            text = if (contact.verified) "Checked" else "Not checked yet",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = state,
                        )
                    }

                    BodyText(
                        if (contact.verified) {
                            "You compared the safety number with ${contact.name}, or scanned their ID from " +
                                "their screen. Every message goes to their phone and nobody else's."
                        } else {
                            "This ID reached this phone through the relay, so it could have been swapped on " +
                                "the way. To be sure it is really ${contact.name}, compare the safety number " +
                                "below with the same number on their phone: meet, or read it out on a call."
                        },
                    )

                    SectionTitle("Safety number")
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            if (safetyNumber == null) {
                                // A few thousand rounds of hashing: quick, but not instant.
                                Text("Working it out…", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                // Sixty digits in three lines of twenty: legible on a
                                // phone, and read out in the same groups by both people.
                                SelectionContainer {
                                    Text(
                                        text = safetyNumber,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "They see the same number when they open your name in their Maun.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SectionTitle("Their identity")
                    SelectionContainer { IdText(contact.id) }

                    if (contact.username != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Username",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("@${contact.username}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    if (contact.verified) {
                        OutlinedButton(
                            onClick = { onChecked(false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Mark as not checked") }
                    } else {
                        Button(
                            onClick = { onChecked(true) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = safetyNumber != null,
                        ) { Text("The numbers match") }
                    }
                    Text(
                        text = "Maun never marks a contact as checked by itself. Only this button does, and " +
                            "only you can press it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun VerificationPreview() {
    MaunTheme {
        VerificationScreen(
            contact = ContactEntity("ab12".repeat(16), "Asha", verified = true, addedAt = 0),
            safetyNumber = "12345 67890 12345 67890\n12345 67890 12345 67890\n12345 67890 12345 67890",
            onBack = {},
            onChecked = {},
        )
    }
}
