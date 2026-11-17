package org.maun.messenger.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.AppLock
import kotlinx.coroutines.launch
import org.maun.messenger.Avatars
import org.maun.messenger.Messenger
import org.maun.messenger.crypto.Recovery

/**
 * This phone's own profile: the picture their contacts see for them, and the
 * ID others need to reach them.
 *
 * The picture is one's own to change and nobody else's. It is shrunk here,
 * sealed with a key of its own, kept on the relay under this ID, and given to
 * contacts inside ordinary encrypted messages, so they see a new picture
 * without being told anything by the relay.
 */
@Composable
fun ProfileScreen(
    code: String,
    relayName: String,
    username: String?,
    memberEmail: String?,
    onPhoto: (android.net.Uri) -> Unit,
    onRemovePhoto: () -> Unit,
    /** The picture chosen here is kept, but has not reached the relay yet. */
    sharingPending: Boolean = false,
    /** Maun's administrators have given this account their tick. */
    verified: Boolean = false,
    onShowCode: () -> Unit,
    onBack: () -> Unit,
    /** Whether a sealed copy of this account is on the relay, and when it last was. */
    recovery: Messenger.RecoveryUi = Messenger.RecoveryUi(),
    onEnableRecovery: suspend (CharArray) -> String? = { null },
    onDisableRecovery: suspend () -> String? = { null },
    /** Signs this phone out without touching anything that is on it: see [Messenger.logOut]. */
    onLogOut: () -> Unit = {},
) {
    val context = LocalContext.current
    var hasPhoto by remember(code) { mutableStateOf(Avatars.hasShared(context, Avatars.SELF)) }
    var confirmingLogOut by remember { mutableStateOf(false) }
    var settingRecovery by remember { mutableStateOf(false) }
    var confirmingRecoveryOff by remember { mutableStateOf(false) }
    var recoveryRemoving by remember { mutableStateOf(false) }
    var recoveryProblem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(recoveryRemoving) {
        if (!recoveryRemoving) return@LaunchedEffect
        recoveryProblem = onDisableRecovery()
        recoveryRemoving = false
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        AppLock.externalFlow = false
        if (uri != null) {
            hasPhoto = true
            onPhoto(uri)
        }
    }

    ScreenFrame(title = "Your profile", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(id = Avatars.SELF, name = username ?: "You", size = 104.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = username?.let { "@$it" } ?: "No username on this relay",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                VerifiedBadge(verified)
            }
            Text(
                text = memberEmail ?: "This ID is not tied to an email address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {
                    try {
                        AppLock.externalFlow = true
                        pickPhoto.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    } catch (_: Exception) {
                        AppLock.externalFlow = false
                        Toast.makeText(context, "Could not open Gallery.", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(MaunIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (hasPhoto) "Change photo" else "Add photo")
                }
                if (hasPhoto) {
                    OutlinedButton(onClick = {
                        hasPhoto = false
                        onRemovePhoto()
                    }) { Text("Remove") }
                }
            }
            Text(
                text = when {
                    sharingPending -> "Saved. It goes to the people you talk to as soon as Maun can reach the relay."
                    hasPhoto -> "The people you talk to see this picture. It travels sealed, and only they can open it."
                    else -> "You have no picture. The people you talk to see your initial."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        Spacer(modifier = Modifier.size(8.dp))

        SectionTitle("Your Maun ID", modifier = Modifier.padding(bottom = 4.dp))
        Text(
            text = groupedId(code),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "On $relayName. Anyone with this ID can write to you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onShowCode, modifier = Modifier.padding(top = 8.dp)) {
            Icon(MaunIcons.Qr, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Show my code")
        }

        Spacer(modifier = Modifier.size(16.dp))

        SectionTitle("Recovery")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(MaunIcons.Shield, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep a sealed backup on the relay", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = when {
                        recovery.on ->
                            "On. A sealed copy of your keys is on the relay, and only your passphrase " +
                                "opens it. If this phone is lost, log in on another one and restore it."
                        else ->
                            "Off. If this phone is lost or wiped, this Maun ID cannot be brought back: " +
                                "its keys are only here. The backup is sealed on this phone with a " +
                                "passphrase you choose, and the relay only ever holds the ciphertext."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = recovery.on,
                onCheckedChange = { on ->
                    recoveryProblem = null
                    if (on) settingRecovery = true else confirmingRecoveryOff = true
                },
            )
        }
        if (recoveryProblem != null) {
            Text(
                text = recoveryProblem!!,
                style = MaterialTheme.typography.bodySmall,
                color = LocalStatusColors.current.warning,
            )
        }
        if (settingRecovery) {
            RecoveryDialog(
                onCancel = { settingRecovery = false },
                onConfirm = { passphrase ->
                    settingRecovery = false
                    recoveryProblem = null
                    scope.launch { recoveryProblem = onEnableRecovery(passphrase) }
                },
            )
        }
        if (confirmingRecoveryOff) {
            AlertDialog(
                onDismissRequest = { confirmingRecoveryOff = false },
                title = { Text("Stop keeping a backup?") },
                text = {
                    Text(
                        "The sealed copy on the relay is deleted. If this phone is lost after that, this " +
                            "Maun ID cannot be brought back.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingRecoveryOff = false
                        recoveryProblem = null
                    }) { Text("Cancel") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmingRecoveryOff = false
                        // The answer comes back on the next frame; the switch follows the state.
                        recoveryRemoving = true
                    }) { Text("Delete it") }
                },
            )
        }

        Spacer(modifier = Modifier.size(16.dp))

        SectionTitle("Account")
        OutlinedButton(
            onClick = { confirmingLogOut = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Log out") }
        Text(
            text = "Signs this phone out. Your chats, pictures and ID stay on it, and logging " +
                "in again on this phone brings back the same account. This is not Clear data, " +
                "and it does not delete your account.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmingLogOut) {
        AlertDialog(
            onDismissRequest = { confirmingLogOut = false },
            title = { Text("Log out of Maun?") },
            text = {
                Text(
                    "Maun stops connecting and stops being woken for new messages until you log " +
                        "in again. Your chats, your pictures, your settings and your Maun ID stay " +
                        "on this phone, and nothing is deleted from the relay.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogOut = false
                    onLogOut()
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmingLogOut = false }) { Text("Stay") } },
        )
    }
}

/**
 * Asks for the passphrase the sealed backup is opened with, twice, so that a
 * typo cannot leave an account that only a mistyped word would recover.
 */
@Composable
private fun RecoveryDialog(onCancel: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val longEnough = first.length >= Recovery.MIN_PASSPHRASE
    val same = first == second
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Set a recovery passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This word or phrase is the only thing that opens your sealed backup. Nobody at " +
                        "Maun has it, so nobody can reset it: if it is forgotten, the backup is worthless. " +
                        "Use at least ${Recovery.MIN_PASSPHRASE} characters and write it down somewhere safe.",
                )
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it.take(200) },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it.take(200) },
                    label = { Text("Again") },
                    singleLine = true,
                    isError = second.isNotEmpty() && !same,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (!longEnough && first.isNotEmpty()) {
                    Text(
                        "At least ${Recovery.MIN_PASSPHRASE} characters, please.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalStatusColors.current.warning,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(first.toCharArray()) },
                enabled = longEnough && same,
            ) { Text("Keep the backup") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Preview(showBackground = true)
@Composable
private fun ProfilePreview() {
    MaunTheme {
        ProfileScreen(
            code = "ab12cd34ef56ab12cd34ef56ab12cd34",
            relayName = "maun.example",
            username = "sadab",
            memberEmail = null,
            onPhoto = {},
            onRemovePhoto = {},
            sharingPending = false,
            onShowCode = {},
            onBack = {},
        )
    }
}
