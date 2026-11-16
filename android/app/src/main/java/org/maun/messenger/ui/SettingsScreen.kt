package org.maun.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.network.RelayState

/** The two privacy switches, as the settings screen shows them. */
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
)

@Composable
fun SettingsScreen(
    version: String,
    relayName: String,
    relay: RelayState,
    delivery: DeliveryUi,
    pushProblem: String?,
    privacy: PrivacyUi,
    blockedCount: Int,
    memberEmail: String? = null,
    username: String? = null,
    onChoosePushApp: (String) -> Unit,
    onGooglePush: (Boolean) -> Unit,
    onStayConnected: (Boolean) -> Unit,
    onDeliveryChanged: () -> Unit,
    onAppLock: (Boolean) -> Unit,
    onBlockScreenshots: (Boolean) -> Unit,
    onNotifyText: (Boolean) -> Unit = {},
    onBattery: () -> Unit = {},
    onLockScreenCalls: () -> Unit = {},
    onTelecomCalls: (Boolean) -> Unit = {},
    onOpenBlocked: () -> Unit,
    onOpenSetupCheck: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenFrame(title = "Settings", onBack = onBack) {
        DeliveryCard(delivery, pushProblem, onChoosePushApp, onGooglePush, onStayConnected, onDeliveryChanged)

        Column {
            SectionTitle("Calls and arriving on time", modifier = Modifier.padding(bottom = 4.dp))
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
            SectionTitle("Privacy", modifier = Modifier.padding(bottom = 4.dp))
            SwitchRow(
                icon = MaunIcons.Bell,
                title = "Show messages in notifications",
                text = "A notification names who wrote and shows the message. Off, it only says that a " +
                    "message arrived. A locked screen never shows either.",
                checked = privacy.notifyText,
                enabled = true,
                onChange = onNotifyText,
            )
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
            pushProblem = null,
            privacy = PrivacyUi(appLock = true),
            blockedCount = 2,
            onChoosePushApp = {},
            onGooglePush = {},
            onStayConnected = {},
            onDeliveryChanged = {},
            onAppLock = {},
            onBlockScreenshots = {},
            onOpenBlocked = {},
            onOpenSetupCheck = {},
            onBack = {},
        )
    }
}
