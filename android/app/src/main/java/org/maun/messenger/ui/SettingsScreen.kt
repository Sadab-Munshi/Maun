package org.maun.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.Avatars
import org.maun.messenger.SoundSettings
import org.maun.messenger.network.RelayState
import org.maun.messenger.updates.PublishedRelease
import org.maun.messenger.updates.UpdateState

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
    relay: RelayState,
    delivery: DeliveryUi,
    privacy: PrivacyUi,
    blockedCount: Int,
    sounds: SoundChoices = SoundChoices(),
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
    /** What the update check has found, if it has been asked. */
    update: UpdateState = UpdateState.Idle,
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: (PublishedRelease) -> Unit = {},
    onInstallUpdate: (PublishedRelease) -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onOpenBlocked: () -> Unit,
    onOpenSetupCheck: () -> Unit,
    onBack: () -> Unit,
) {
    var choosingSent by remember { mutableStateOf(false) }
    var choosingIncoming by remember { mutableStateOf(false) }
    var privacyNote by remember { mutableStateOf(false) }
    var showingUpdate by rememberSaveable { mutableStateOf(false) }
    // The answer arrives after the row was tapped: show it as it comes.
    LaunchedEffect(update) {
        if (update !is UpdateState.Idle) showingUpdate = true
    }

    val context = LocalContext.current

    ScreenFrame(title = "Settings", onBack = onBack) {
        Group("Profile") {
            ProfileRow(onClick = onOpenProfile)
        }

        Group("Notifications") {
            NotificationsSection(delivery, onChanged = onNotificationsChanged)
            SwitchRow(
                icon = MaunIcons.Bell,
                title = "Show messages in notifications",
                text = "Name who wrote and show what they said. A locked screen never shows either.",
                checked = privacy.notifyText,
                enabled = true,
                onChange = onNotifyText,
            )
            LinkRow(
                icon = MaunIcons.Settings,
                title = "This phone's notification settings",
                text = "Sounds, vibration and pop-ups, as Android keeps them.",
                onClick = { context.openAppNotificationSettings() },
            )
        }

        Group("Calls") {
            if (privacy.lockScreenCallsBlocked) {
                LinkRow(
                    icon = MaunIcons.Call,
                    title = "Let calls light up the locked screen",
                    text = "Android is not letting a call show over the lock screen. Tap and allow it.",
                    onClick = onLockScreenCalls,
                )
            }
            SwitchRow(
                icon = MaunIcons.Call,
                title = "Treat Maun calls as phone calls",
                text = "Answer from a headset or from the car, and a phone call does not talk over it.",
                checked = privacy.telecomCalls,
                enabled = true,
                onChange = onTelecomCalls,
            )
            if (privacy.batterySaving) {
                LinkRow(
                    icon = MaunIcons.Bell,
                    title = "Let Maun run in the background",
                    text = "Battery saving may put Maun to sleep, so calls do not ring and messages come late. " +
                        "Tap to take Maun out of it.",
                    onClick = onBattery,
                )
            }
        }

        Group("Chats and media") {
            SwitchRow(
                icon = MaunIcons.Download,
                title = "Save arriving files automatically",
                text = "Photos and videos go to Gallery, everything else to Download. " +
                    "Off: you save each one yourself.",
                checked = privacy.saveIncomingFiles,
                enabled = true,
                onChange = onSaveIncomingFiles,
            )
            SwitchRow(
                icon = MaunIcons.Wallpaper,
                title = "Chat background pattern",
                text = "A soft pattern behind conversations. Off for a plain background.",
                checked = privacy.chatPattern,
                enabled = true,
                onChange = onChatPattern,
            )
        }

        Group("Message sounds") {
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

        Group("Privacy") {
            SwitchRow(
                icon = MaunIcons.Lock,
                title = "App lock",
                text = when {
                    !privacy.canLock && !privacy.appLock ->
                        "Set a screen lock on this phone first; the app lock asks for the same one."
                    privacy.lockBlocksScreenshots ->
                        "Ask for your fingerprint, face or screen lock each time Maun opens. " +
                            "On this version of Android it also blocks screenshots."
                    else ->
                        "Ask for your fingerprint, face or screen lock each time Maun opens, " +
                            "and hide Maun from the recent-apps list."
                },
                checked = privacy.appLock,
                enabled = privacy.canLock || privacy.appLock,
                onChange = onAppLock,
            )
            SwitchRow(
                icon = MaunIcons.Shield,
                title = "Block screenshots",
                text = "Keep Maun out of screenshots and screen recordings made on this phone.",
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

        Group("About") {
            InfoRow(
                icon = MaunIcons.Info,
                title = "Maun $version",
                text = when (relay) {
                    RelayState.Online -> "Connected."
                    RelayState.Connecting -> "Connecting…"
                    RelayState.Offline -> "Not connected."
                    is RelayState.Retrying -> "Not connected. Trying again…"
                    is RelayState.Rejected -> "Not connected. ${relay.reason}"
                    is RelayState.LoginNeeded -> "Not signed in."
                    RelayState.Banned -> "This account no longer has access."
                    RelayState.Pending -> "Waiting to be let in."
                },
            )
            LinkRow(
                icon = MaunIcons.Download,
                title = "Check for updates",
                text = when (val state = update) {
                    UpdateState.Idle -> "Maun $version"
                    UpdateState.Checking -> "Checking…"
                    is UpdateState.UpToDate -> "You're up to date."
                    is UpdateState.Available -> "Maun ${state.release.versionName} is available."
                    is UpdateState.Downloading -> "Downloading ${state.release.versionName}…"
                    is UpdateState.ReadyToInstall -> "${state.release.versionName} is ready to install."
                    is UpdateState.Failed -> "Couldn't check. Tap to try again."
                },
                onClick = {
                    when (val state = update) {
                        // Already answered: show the answer again rather than
                        // asking GitHub the same question twice.
                        UpdateState.Idle -> onCheckForUpdates()
                        else -> showingUpdate = true
                    }
                },
            )
            LinkRow(
                icon = MaunIcons.Check,
                title = "Setup check",
                text = "Test this phone's encryption, storage and connection.",
                onClick = onOpenSetupCheck,
            )
            LinkRow(
                icon = MaunIcons.Shield,
                title = "Privacy",
                text = "What Maun keeps, and who can see it.",
                onClick = { privacyNote = true },
            )
        }
    }

    if (privacyNote) PrivacyNote(onDismiss = { privacyNote = false })

    if (showingUpdate) {
        UpdateDialog(
            state = update,
            installed = "Maun $version",
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
            onDismiss = {
                showingUpdate = false
                onDismissUpdate()
            },
        )
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
 * What the update check has to say, and the one thing to do about it. Android
 * installs the APK itself, with its own question; this only downloads it and
 * points the way. Every state says something a person can act on, including
 * the ones where nothing happened.
 */
@Composable
internal fun UpdateDialog(
    state: UpdateState,
    installed: String,
    onDownload: (PublishedRelease) -> Unit,
    onInstall: (PublishedRelease) -> Unit,
    onDismiss: () -> Unit,
) {
    val release = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (state) {
                    UpdateState.Idle, UpdateState.Checking -> "Checking for updates"
                    is UpdateState.UpToDate -> "You're up to date"
                    is UpdateState.Available -> "Maun ${state.release.versionName} is available"
                    is UpdateState.Downloading -> "Downloading Maun ${state.release.versionName}"
                    is UpdateState.ReadyToInstall -> "Ready to install"
                    is UpdateState.Failed -> "Couldn't check for updates"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    UpdateState.Idle, UpdateState.Checking -> Text(
                        text = "Looking for a newer version of Maun…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is UpdateState.UpToDate -> Text(
                        text = "$installed is the newest version there is.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is UpdateState.Available -> {
                        Text(
                            text = "You have $installed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.release.notes.isNotEmpty()) {
                            Text(
                                text = state.release.notes,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        Text(
                            text = "Your chats, contacts and settings stay as they are.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is UpdateState.Downloading -> {
                        val fraction = state.fraction
                        if (fraction == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            text = if (fraction == null) {
                                "Downloading…"
                            } else {
                                "${(fraction * 100).toInt()}% of the download done."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is UpdateState.ReadyToInstall -> Text(
                        text = "Downloaded. Android will ask you to confirm the install; Maun cannot " +
                            "install anything by itself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is UpdateState.Failed -> Text(
                        text = state.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = { onDownload(state.release) }) {
                    Text(if (release == null) "Update" else "Update")
                }
                is UpdateState.ReadyToInstall -> TextButton(onClick = { onInstall(state.release) }) {
                    Text("Install")
                }
                is UpdateState.Failed -> TextButton(onClick = onDismiss) { Text("Close") }
                else -> {}
            }
        },
        dismissButton = {
            when (state) {
                // Nothing to put away while the file is on its way.
                is UpdateState.Downloading -> {}
                is UpdateState.Available, is UpdateState.ReadyToInstall -> TextButton(onClick = onDismiss) {
                    Text("Later")
                }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/**
 * One heading and the rows under it. The gap above a heading is what separates
 * the groups: a heading sits closer to its own rows than to the group before it.
 */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    MaunTheme {
        SettingsScreen(
            version = "0.23.0",
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
