package org.maun.messenger.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.maun.messenger.AppLock
import org.maun.messenger.push.Notifications

/** Snapshot of the permissions Maun asks for right after first login. */
data class PermissionSetupState(
    val notifications: Boolean = false,
    val microphone: Boolean = false,
    val camera: Boolean = false,
    val fullScreenCalls: Boolean = true,
) {
    val allGranted: Boolean
        get() = notifications && microphone && camera && fullScreenCalls
}

/**
 * Shown once right after first login so notifications, voice notes, camera
 * photos, and incoming calls work out of the box, with full freedom to skip
 * any item or turn permissions off later.
 */
@Composable
fun PermissionsScreen(
    onContinue: () -> Unit,
    initialState: PermissionSetupState? = null,
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(initialState ?: readPermissionSetupState(context)) }
    var batchAsked by rememberSaveable { mutableStateOf(false) }
    var fullScreenOffered by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(Unit) {
        AppLock.externalFlow = true
        onDispose { AppLock.externalFlow = false }
    }

    fun refresh() {
        if (initialState == null) state = readPermissionSetupState(context)
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    val openFullScreenCallSettings = {
        fullScreenOffered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.openSafe(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }
    }

    val requestBatch = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        batchAsked = true
        val latest = readPermissionSetupState(context)
        state = latest
        if (!latest.fullScreenCalls && !fullScreenOffered) {
            openFullScreenCallSettings()
        } else if (latest.allGranted) {
            onContinue()
        }
    }

    val requestSingle = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refresh()
        if (!granted && batchAsked) {
            context.openSafe(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}")),
            )
        }
    }

    val missingRuntime = buildList {
        if (!state.notifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!state.microphone) add(Manifest.permission.RECORD_AUDIO)
        if (!state.camera) add(Manifest.permission.CAMERA)
    }

    ScreenFrame(title = "Set up permissions") {
        BodyText(
            "Allow these once so messages, voice notes, photos, and incoming calls work right away. " +
                "You can skip any of them now or turn them off anytime in Settings.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionRow(
                icon = MaunIcons.Bell,
                title = "Notifications",
                description = "Alerts you when messages or calls arrive while Maun is closed.",
                granted = state.notifications,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !batchAsked) {
                        requestSingle.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        context.openSafe(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }
                },
            )

            PermissionRow(
                icon = MaunIcons.Mic,
                title = "Microphone",
                description = "For recording voice messages and speaking on voice or video calls.",
                granted = state.microphone,
                onClick = { requestSingle.launch(Manifest.permission.RECORD_AUDIO) },
            )

            PermissionRow(
                icon = MaunIcons.Camera,
                title = "Camera",
                description = "For video calls, quick camera photos in chats, and scanning contact codes.",
                granted = state.camera,
                onClick = { requestSingle.launch(Manifest.permission.CAMERA) },
            )

            PermissionRow(
                icon = MaunIcons.Call,
                title = "Full-screen incoming calls",
                description = "Lets incoming calls ring full-screen over your lock screen on Android 14+.",
                granted = state.fullScreenCalls,
                actionLabel = "Settings",
                onClick = openFullScreenCallSettings,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        when {
            missingRuntime.isNotEmpty() && !batchAsked -> {
                Button(
                    onClick = { requestBatch.launch(missingRuntime.toTypedArray()) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow permissions")
                }
                TextButton(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Skip for now")
                }
            }

            !state.fullScreenCalls && !fullScreenOffered -> {
                Button(
                    onClick = openFullScreenCallSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Allow full-screen calls")
                }
                TextButton(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Skip for now")
                }
            }

            else -> {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue")
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String = "Allow",
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val statusColors = LocalStatusColors.current
    Surface(
        color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (!granted) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (granted) colors.primaryContainer else colors.surfaceContainerHigh,
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (granted) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (granted) {
                Surface(
                    color = statusColors.passed.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MaunIcons.Check,
                            contentDescription = null,
                            tint = statusColors.passed,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "On",
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColors.passed,
                        )
                    }
                }
            } else {
                Surface(
                    color = colors.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private fun readPermissionSetupState(context: Context): PermissionSetupState {
    val notifications = Notifications(context)
    val notifyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && notifications.allowed
    } else {
        notifications.allowed
    }
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    return PermissionSetupState(
        notifications = notifyGranted,
        microphone = micGranted,
        camera = cameraGranted,
        fullScreenCalls = notifications.mayShowCallsOverLockScreen(),
    )
}

private fun Context.openSafe(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Fallback to general application details settings if this Android build lacks the specific screen.
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName")),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PermissionsPreview() {
    MaunTheme {
        PermissionsScreen(
            onContinue = {},
            initialState = PermissionSetupState(
                notifications = true,
                microphone = false,
                camera = false,
                fullScreenCalls = false,
            ),
        )
    }
}
