package org.maun.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.Avatars
import org.maun.messenger.SoundSettings
import org.maun.messenger.network.RelayState

/** Privacy, incoming media, and chat appearance switches, as the settings screen shows them. */
data class PrivacyUi(
    val appLock: Boolean = false,
    /** The phone has a screen lock, so the app lock has something to ask for. */
    val canLock: Boolean = true,
    val blockScreenshots: Boolean = false,
    /** On this version of Android the app lock can only hide Maun from the recent-apps list by blocking screenshots too. */
    val lockBlocksScreenshots: Boolean = false,
    /** Notifications quote the newest message. */
    val notifyText: Boolean = true,
    /** The phone's battery saving applies to Maun, and may put it to sleep. */
    val batterySaving: Boolean = false,
    /** Android does not let Maun open a call over the lock screen. */
    val lockScreenCallsBlocked: Boolean = false,
    /** Calls are handed to Android's own call system. */
    val telecomCalls: Boolean = true,
    /** Save arriving files by themselves: pictures and videos to Gallery, the rest to Downloads. */
    val saveIncomingFiles: Boolean = true,
    /** Show a soft background pattern behind conversations (off = solid background). */
    val chatPattern: Boolean = true,
)

/** Which sounds Maun makes, as the settings screen shows them. */
data class SoundChoices(
    /** One of SoundSettings.SENT_MAUN, SENT_SILENT. */
    val sent: String = SoundSettings.DEFAULT_SENT,
    /** One of SoundSettings.INCOMING_MAUN, INCOMING_SYSTEM, INCOMING_SILENT. */
    val incoming: String = SoundSettings.DEFAULT_INCOMING,
) {
    val sentLabel: String
        get() = if (sent == SoundSettings.SENT_SILENT) "Silent" else "Maun default"

    val incomingLabel: String
        get() = when (incoming) {
            SoundSettings.INCOMING_SYSTEM -> "This phone's notification sound"
            SoundSettings.INCOMING_SILENT -> "Silent"
            else -> "Maun default"
        }
}

@Composable
fun SettingsScreen(
    version: String,
    relayName: String,
    relay: RelayState,
    delivery: DeliveryUi,
    privacy: PrivacyUi,
    blockedCount: Int,
    sounds: SoundChoices = SoundChoices(),
    memberEmail: String? = null,
    username: String? = null,
    onAppLock: (Boolean) -> Unit,
    onBlockScreenshots: (Boolean) -> Unit,
    onNotifyText: (Boolean) -> Unit = {},
    /** Re-reads whether Maun may notify, after the phone's own settings were changed. */
    onNotificationsChanged: () -> Unit = {},
    onBattery: () -> Unit = {},
    onLockScreenCalls: () -> Unit = {},
    onTelecomCalls: (Boolean) -> Unit = {},
    onSaveIncomingFiles: (Boolean) -> Unit = {},
    onChatPattern: (Boolean) -> Unit = {},
    onSentSound: (String) -> Unit = {},
    onIncomingSound: (String) -> Unit = {},
    onPreviewIncomingSound: (String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenBlocked: () -> Unit,
    onOpenSetupCheck: () -> Unit,
    onBack: () -> Unit,
) {
    var choosingSent by remember { mutableStateOf(false) }
    var choosingIncoming by remember { mutableStateOf(false) }

    val context = LocalContext.current

    ScreenFrame(title = "Settings", onBack = onBack) {
        SectionTitle("Profile", modifier = Modifier.padding(bottom = 4.dp))
        ProfileRow(onClick = onOpenProfile)

        Column {
            SectionTitle("Notifications", modifier = Modifier.padding(bottom = 4.dp))
            NotificationsSection(delivery, onChanged = onNotificationsChanged)
            SwitchRow(
                icon = MaunIcons.Bell,
                title = "Show messages in notifications",
                text = "A notification names who wrote and shows the message. Off, it only says that a " +
                    "message arrived. A locked screen never shows either.",
                checked = privacy.notifyText,
                enabled = true,
                onChange = onNotifyText,
            )
            LinkRow(
                icon = MaunIcons.Settings,
                title = "This phone's notification settings",
                text = "Sounds, vibration, pop-ups and what Maun may show on a locked screen, " +
                    "as Android keeps them.",
                onClick = { context.openAppNotificationSettings() },
            )
        }

        Column {
            SectionTitle("Calls", modifier = Modifier.padding(bottom = 4.dp))
            if (privacy.lockScreenCallsBlocked) {
                LinkRow(
                    icon = MaunIcons.Call,
                    title = "Let calls light up the locked screen",
                    text = "Android is not letting Maun show a call over the lock screen, so a call only " +
                        "rings and shows as a notification. Tap, then allow full-screen notifications for Maun.",
                    onClick = onLockScreenCalls,
                )
            }
            SwitchRow(
                icon = MaunIcons.Call,
                title = "Treat Maun calls as phone calls",
                text = "Android handles a Maun call like any call: a headset's or a car's buttons answer it, " +
                    "and a phone call does not talk over it. Turn this off if a call sounds wrong or does not connect.",
                checked = privacy.telecomCalls,
                enabled = true,
                onChange = onTelecomCalls,
            )
            if (privacy.batterySaving) {
                LinkRow(
                    icon = MaunIcons.Bell,
                    title = "Let Maun run in the background",
                    text = "This phone's battery saving may put Maun to sleep, so that calls do not ring and " +
                        "messages come late. Tap to take Maun out of it. If calls still do not ring, also " +
                        "allow Maun to start by itself in the phone's own settings (often called Auto-launch).",
                    onClick = onBattery,
                )
            }
        }

        Column {
            SectionTitle("Chats and media", modifier = Modifier.padding(bottom = 4.dp))
            SwitchRow(
                icon = MaunIcons.Download,
                title = "Save arriving files automatically",
                text = "Photos and videos go to Gallery under Pictures/Maun; documents and other files to " +
                    "Download/Maun. Off: a file is saved only when you ask for it.",
                checked = privacy.saveIncomingFiles,
                enabled = true,
                onChange = onSaveIncomingFiles,
            )
            SwitchRow(
                icon = MaunIcons.Wallpaper,
                title = "Chat background pattern",
                text = "Show a soft, subtle pattern behind conversations. Turn off for a solid background.",
                checked = privacy.chatPattern,
                enabled = true,
                onChange = onChatPattern,
            )
        }

        Column {
            SectionTitle("Message sounds", modifier = Modifier.padding(bottom = 4.dp))
            ChoiceRow(
                icon = MaunIcons.Send,
                title = "Sound when you send",
                text = sounds.sentLabel,
                onClick = { choosingSent = true },
            )
            ChoiceRow(
                icon = MaunIcons.Bell,
                title = "Sound when a message arrives",
                text = sounds.incomingLabel,
                onClick = { choosingIncoming = true },
                onPreview = if (sounds.incoming == SoundSettings.INCOMING_SILENT) null else {
                    { onPreviewIncomingSound(sounds.incoming) }
                },
            )
        }

        Column {
            SectionTitle("Privacy", modifier = Modifier.padding(bottom = 4.dp))
            SwitchRow(
                icon = MaunIcons.Lock,
                title = "App lock",
                text = when {
                    !privacy.canLock && !privacy.appLock ->
                        "Set a screen lock on this phone first. The app lock asks for the same fingerprint, face, PIN or pattern."
                    privacy.lockBlocksScreenshots ->
                        "Ask for your fingerprint, face or screen lock every time Maun is opened. " +
                            "On this version of Android it also blocks screenshots."
                    else ->
                        "Ask for your fingerprint, face or screen lock every time Maun is opened, " +
                            "and hide Maun in the list of recent apps."
                },
                checked = privacy.appLock,
                enabled = privacy.canLock || privacy.appLock,
                onChange = onAppLock,
            )
            SwitchRow(
                icon = MaunIcons.Shield,
                title = "Block screenshots",
                text = "Keep Maun out of screenshots and screen recordings made on this phone. " +
                    "It cannot stop the other person from capturing their own screen.",
                checked = privacy.blockScreenshots,
                enabled = true,
                onChange = onBlockScreenshots,
            )
            LinkRow(
                icon = MaunIcons.Block,
                title = "Blocked people",
                text = if (blockedCount == 0) "Nobody is blocked." else "$blockedCount blocked.",
                onClick = onOpenBlocked,
            )
        }

        Column {
            SectionTitle("About", modifier = Modifier.padding(bottom = 4.dp))
            LinkRow(
                icon = MaunIcons.Check,
                title = "Setup check",
                text = "Test this phone's encryption, storage and connection.",
                onClick = onOpenSetupCheck,
            )
            if (memberEmail != null) {
                InfoRow(
                    icon = MaunIcons.Mail,
                    title = if (username != null) "@$username" else "Logged in",
                    text = "Logged in as $memberEmail. This relay is for members. Its administrator can see " +
                        "this address, your username and ID, and which phone you use (its make, model and a " +
                        "fingerprint of it). " +
                        "Your messages stay unreadable to the relay and its owner.",
                )
            }
            InfoRow(
                icon = MaunIcons.Info,
                title = "Maun $version",
                text = "Relay: $relayName\n" + when (relay) {
                    RelayState.Online -> "Connected and signed in."
                    RelayState.Connecting -> "Connecting…"
                    RelayState.Offline -> "Not connected."
                    is RelayState.Retrying -> "${relay.reason} Trying again…"
                    is RelayState.Rejected -> relay.reason
                    is RelayState.LoginNeeded -> "The relay asks you to log in."
                    RelayState.Banned -> "The relay no longer accepts this account."
                    RelayState.Pending -> "Waiting for the relay's administrator."
                },
            )
        }
    }

    if (choosingSent) {
        SoundChoiceDialog(
            title = "Sound when you send",
            explanation = "Played once when a message has been handed to this phone's sending queue. " +
                "Replies, receipts and messages read back from storage never make a sound.",
            options = listOf(
                SoundSettings.SENT_MAUN to "Maun default",
                SoundSettings.SENT_SILENT to "Silent",
            ),
            chosen = sounds.sent,
            onChoose = {
                choosingSent = false
                onSentSound(it)
            },
            onDismiss = { choosingSent = false },
        )
    }

    if (choosingIncoming) {
        SoundChoiceDialog(
            title = "Sound when a message arrives",
            explanation = "While Maun is open the app plays this itself. While it is closed, Android makes " +
                "the sound for the notification, on a channel of its own for each choice here, so a sound " +
                "turned off in Android's own settings stays off.",
            options = listOf(
                SoundSettings.INCOMING_MAUN to "Maun default",
                SoundSettings.INCOMING_SYSTEM to "This phone's notification sound",
                SoundSettings.INCOMING_SILENT to "Silent",
            ),
            chosen = sounds.incoming,
            onChoose = {
                choosingIncoming = false
                onIncomingSound(it)
            },
            onDismiss = { choosingIncoming = false },
            onPreview = onPreviewIncomingSound,
        )
    }
}

/**
 * One choice of a sound, or of none. The row that is in use is marked, and
 * each of the others can be heard before it is chosen.
 */
@Composable
private fun SoundChoiceDialog(
    title: String,
    explanation: String,
    options: List<Pair<String, String>>,
    chosen: String,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    onPreview: ((String) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .selectable(selected = value == chosen, role = Role.RadioButton) { onChoose(value) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = value == chosen, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (onPreview != null && value != SoundSettings.INCOMING_SILENT) {
                            IconButton(onClick = { onPreview(value) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    MaunIcons.Speaker,
                                    contentDescription = "Hear it",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A settings row that says what is chosen now, and opens the choices. */
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    text: String,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        RowText(title, text, modifier = Modifier.weight(1f))
        if (onPreview != null) {
            IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
                Icon(
                    MaunIcons.Speaker,
                    contentDescription = "Hear it",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Icon(MaunIcons.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    text: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // The whole row is the switch, for fingers and for screen readers alike.
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        RowText(title, text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The top row of Settings: this phone's own picture, and a way to change it. */
@Composable
private fun ProfileRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(id = Avatars.SELF, name = "You", size = 46.dp)
        RowText(
            title = "Your profile",
            text = "Picture, username, and Maun ID.",
            modifier = Modifier.weight(1f),
        )
        Icon(MaunIcons.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        RowText(title, text, modifier = Modifier.weight(1f))
        Icon(MaunIcons.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        RowText(title, text, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RowText(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    MaunTheme {
        SettingsScreen(
            version = "0.4.0",
            relayName = "maun.example.workers.dev",
            relay = RelayState.Online,
            delivery = DeliveryUi(pushApp = "Google's push service", google = true),
            privacy = PrivacyUi(appLock = true),
            blockedCount = 2,
            onNotificationsChanged = {},
            onAppLock = {},
            onBlockScreenshots = {},
            onOpenBlocked = {},
            onOpenSetupCheck = {},
            onBack = {},
        )
    }
}
