package org.maun.messenger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.maun.messenger.push.PushApp

/** How messages reach this phone while Maun is closed, as the home screen shows it. */
data class DeliveryUi(
    val notificationsAllowed: Boolean = true,
    /** The name of what wakes Maun, a push app or Google's push service, or null when nothing does. */
    val pushApp: String? = null,
    /** What wakes Maun is Google's push service, the one most apps use. */
    val google: Boolean = false,
    /** This phone has Google's push service and the user turned it off for Maun. */
    val googleOffered: Boolean = false,
    /** What to choose from, when several push apps are installed and none is chosen. */
    val pushChoices: List<PushApp> = emptyList(),
    val stayConnected: Boolean = false,
    /** Android has been told not to pause Maun while the phone rests. */
    val unrestricted: Boolean = true,
) {
    /** Something here stops messages from arriving, or from being announced. */
    val needsAttention: Boolean
        get() = !notificationsAllowed || (pushApp == null && !stayConnected) || (stayConnected && !unrestricted)
}

/**
 * Says how messages arrive while Maun is closed, and offers what can be done
 * about it. [problem] is why waking this phone does not work, if it does not.
 */
@Composable
fun DeliveryCard(
    ui: DeliveryUi,
    problem: String?,
    onChoosePushApp: (String) -> Unit,
    onGooglePush: (Boolean) -> Unit,
    onStayConnected: (Boolean) -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    // Android shows its permission question only so often. After a refusal,
    // the button leads to the settings page instead.
    var refused by rememberSaveable { mutableStateOf(false) }
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) refused = true
        onChanged()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("When Maun is closed", style = MaterialTheme.typography.titleSmall)

            when {
                ui.stayConnected -> {
                    Note(
                        "Maun stays connected, so messages arrive right away. " +
                            "Android shows a permanent notification while it does.",
                    )
                    if (!ui.unrestricted) {
                        Note(
                            "Android may still pause Maun when the phone has been lying still for a while. " +
                                "Messages then arrive late.",
                            warning = true,
                        )
                        Button(onClick = { context.open(batterySettings(context)) }) { Text("Allow background use") }
                    }
                    if (ui.pushApp != null) {
                        Note("This phone does not need it: ${ui.pushApp} can wake Maun instead.")
                    }
                    TextButton(onClick = { onStayConnected(false) }) { Text("Stop staying connected") }
                }

                ui.pushApp != null && ui.google -> {
                    Note(
                        "Google's push service wakes Maun when a message is waiting, the way it does for most apps. " +
                            "Nothing of Maun keeps running. Google learns that this phone was woken and when, " +
                            "never who wrote or what the message says.",
                    )
                    if (problem != null) Note(problem, warning = true)
                    TextButton(onClick = { onGooglePush(false) }) { Text("Do not use Google") }
                }

                ui.pushApp != null -> {
                    Note(
                        "${ui.pushApp} wakes Maun when a message is waiting. " +
                            "It is not told who wrote or what the message says.",
                    )
                    if (problem != null) Note(problem, warning = true)
                }

                ui.pushChoices.size > 1 -> {
                    Note("More than one push service can wake Maun on this phone. Choose one.")
                    ui.pushChoices.forEach { app ->
                        OutlinedButton(onClick = { onChoosePushApp(app.packageName) }) { Text("Use ${app.label}") }
                    }
                }

                ui.googleOffered -> {
                    Note(
                        "Messages arrive when you open Maun, and Maun looks for them about once an hour. " +
                            "To get them right away without a permanent notification, let Google's push service wake Maun.",
                    )
                    Button(onClick = { onGooglePush(true) }) { Text("Use Google's push service") }
                }

                else -> {
                    Note(
                        "Messages arrive when you open Maun, and Maun looks for them about once an hour. " +
                            "This phone has no push service. Install a push app such as Sunup and open Maun again, " +
                            "or turn on Stay connected below if push notifications are blocked on your device.",
                    )
                }
            }

            if (!ui.notificationsAllowed) {
                Note("Maun is not allowed to show notifications, so it cannot tell you about new messages.", warning = true)
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !refused) {
                            askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.open(notificationSettings(context))
                        }
                    },
                ) { Text("Allow notifications") }
            } else {
                Note("A locked screen is only told that a message arrived. What an unlocked one shows is set below.")
            }
        }
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) LocalStatusColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun notificationSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

// Asking to be left running is what a messenger without a push service has to
// do, and the user turned "Stay connected" on themselves.
@SuppressLint("BatteryLife")
private fun batterySettings(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // This phone has no such settings page.
    }
}

@Preview(showBackground = true)
@Composable
private fun DeliveryPreview() {
    MaunTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            DeliveryCard(DeliveryUi(pushApp = "Google's push service", google = true), null, {}, {}, {}, {})
            DeliveryCard(DeliveryUi(pushApp = "Sunup"), null, {}, {}, {}, {})
            DeliveryCard(DeliveryUi(notificationsAllowed = false, googleOffered = true), null, {}, {}, {}, {})
            DeliveryCard(DeliveryUi(stayConnected = true, unrestricted = false), null, {}, {}, {}, {})
        }
    }
}
