package org.maun.messenger.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import org.maun.messenger.webrtc.AudioRoute
import org.maun.messenger.webrtc.CallUi
import org.maun.messenger.webrtc.PictureSink
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** What the screen needs to show the pictures of a video call: the two streams, and what to draw them with. */
class CallVideo(val egl: EglBase.Context, val remote: PictureSink, val own: PictureSink)

/** The buttons of a call. The defaults do nothing, for pictures of the screen. */
class CallActions(
    val onAccept: () -> Unit = {},
    val onHangUp: () -> Unit = {},
    val onMute: (Boolean) -> Unit = {},
    val onRoute: (AudioRoute) -> Unit = {},
    val onCamera: (Boolean) -> Unit = {},
    val onSwitchCamera: () -> Unit = {},
)

/**
 * A call, over everything else: who it is with, where it has got to, and the
 * buttons for it. While it rings here those are Decline and Answer; after
 * that, the microphone, where the sound comes out, and hanging up, and in a
 * video call the camera. [video] carries the pictures of a video call; a
 * voice call shows the person's initial instead. [now] is
 * only there so that a picture of the screen can be drawn with a fixed clock.
 */
@Composable
fun CallScreen(
    call: CallUi,
    name: String,
    actions: CallActions,
    video: CallVideo? = null,
    now: () -> Long = SystemClock::elapsedRealtime,
) {
    var clock by remember { mutableLongStateOf(now()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallUi.Phase.Active) {
            clock = now()
            delay(500)
        }
    }
    val kind = if (call.video) "video call" else "voice call"
    val status = when (call.phase) {
        CallUi.Phase.Ringing -> if (call.incoming) "Incoming $kind" else "Ringing…"
        CallUi.Phase.Connecting -> "Connecting…"
        CallUi.Phase.Active -> formatDuration((clock - call.connectedAt).coerceAtLeast(0) / 1000 * 1000)
        CallUi.Phase.Ended -> call.ended ?: "Call ended"
    }
    val ringingHere = call.incoming && call.phase == CallUi.Phase.Ringing
    // A video call shows pictures from the moment this phone's camera runs, before the other side answers.
    // A voice call stays a voice call: it has no camera button.
    val pictures = call.video && video != null && !ringingHere && call.phase != CallUi.Phase.Ended

    if (pictures && video != null) {
        VideoCall(call, name, status, actions, video)
    } else {
        VoiceCall(call, name, status, ringingHere, actions)
    }
}

@Composable
private fun VoiceCall(call: CallUi, name: String, status: String, ringingHere: Boolean, actions: CallActions) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = colors.background) {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Avatar(id = call.peer, name = name, size = 112.dp)
            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
                color = if (call.phase == CallUi.Phase.Ended) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (call.phase != CallUi.Phase.Ended) {
                Text(
                    text = "End-to-end encrypted. Neither phone learns the other's address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(modifier = Modifier.weight(2f))

            when {
                call.phase == CallUi.Phase.Ended -> Unit
                ringingHere -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                    RoundButton(MaunIcons.CallEnd, "Decline", colors.error, colors.onError, onClick = actions.onHangUp)
                    RoundButton(
                        icon = if (call.video) MaunIcons.Videocam else MaunIcons.Call,
                        label = "Answer",
                        container = LocalStatusColors.current.passed,
                        content = Color.White,
                        onClick = actions.onAccept,
                    )
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                        Toggle(if (call.muted) MaunIcons.MicOff else MaunIcons.Mic, if (call.muted) "Muted" else "Mute", call.muted) {
                            actions.onMute(!call.muted)
                        }
                        RouteButton(call, actions.onRoute) { icon, label, on, onClick -> Toggle(icon, label, on, onClick) }
                    }
                    RoundButton(MaunIcons.CallEnd, "Hang up", colors.error, colors.onError, onClick = actions.onHangUp)
                }
            }
        }
    }
}

private fun iconOf(route: AudioRoute): ImageVector = when (route) {
    AudioRoute.Earpiece -> MaunIcons.Call
    AudioRoute.Speaker -> MaunIcons.Speaker
    AudioRoute.Wired -> MaunIcons.Headset
    AudioRoute.Bluetooth -> MaunIcons.Bluetooth
}

private fun nameOf(route: AudioRoute): String = when (route) {
    AudioRoute.Earpiece -> "Phone"
    AudioRoute.Speaker -> "Speaker"
    AudioRoute.Wired -> "Headset"
    AudioRoute.Bluetooth -> "Bluetooth"
}

/**
 * The button for where the sound comes out. With only the phone's own two
 * places it switches between them; with a headset as well it opens a list.
 * [button] draws it, so that it looks like its neighbours on either screen.
 */
@Composable
private fun RouteButton(
    call: CallUi,
    onRoute: (AudioRoute) -> Unit,
    button: @Composable (icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    Box {
        button(iconOf(call.route), nameOf(call.route), call.route != AudioRoute.Earpiece) {
            val others = call.routes.filter { it != call.route }
            when (others.size) {
                0 -> Unit
                1 -> onRoute(others.first())
                else -> choosing = true
            }
        }
        DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
            for (route in call.routes) {
                DropdownMenuItem(
                    text = { Text(nameOf(route)) },
                    leadingIcon = { Icon(iconOf(route), contentDescription = null) },
                    trailingIcon = { if (route == call.route) Icon(MaunIcons.Check, contentDescription = "In use") },
                    onClick = {
                        choosing = false
                        onRoute(route)
                    },
                )
            }
        }
    }
}

/**
 * A video call: the other person fills the screen, this phone's own camera
 * sits small in a corner, and the name, the time and the buttons lie over it.
 */
@Composable
private fun VideoCall(call: CallUi, name: String, status: String, actions: CallActions, video: CallVideo) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        VideoView(video, video.remote, mirror = false, onTop = false, modifier = Modifier.fillMaxSize())
        if (!call.remoteVideo) {
            // Until their pictures arrive: who it is.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Avatar(id = call.peer, name = name, size = 112.dp)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = status, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                }
                if (call.cameraOn) {
                    // Mirrored only for the camera that faces the user, the way a mirror would show them.
                    VideoView(video, video.own, mirror = call.frontCamera, onTop = true, modifier = Modifier.size(width = 96.dp, height = 128.dp))
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val quiet = Color.White.copy(alpha = 0.22f)
                SmallButton(if (call.cameraOn) MaunIcons.Videocam else MaunIcons.VideocamOff, if (call.cameraOn) "Turn camera off" else "Turn camera on", if (call.cameraOn) quiet else Color.White, if (call.cameraOn) Color.White else Color.Black) {
                    actions.onCamera(!call.cameraOn)
                }
                if (call.cameraOn) SmallButton(MaunIcons.SwitchCamera, "Other camera", quiet, Color.White, onClick = actions.onSwitchCamera)
                SmallButton(if (call.muted) MaunIcons.MicOff else MaunIcons.Mic, if (call.muted) "Unmute" else "Mute", if (call.muted) Color.White else quiet, if (call.muted) Color.Black else Color.White) {
                    actions.onMute(!call.muted)
                }
                RouteButton(call, actions.onRoute) { icon, label, _, onClick -> SmallButton(icon, label, quiet, Color.White, onClick = onClick) }
                SmallButton(MaunIcons.CallEnd, "Hang up", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, size = 64.dp, onClick = actions.onHangUp)
            }
        }
    }
}

/** A view that shows one stream of pictures. [onTop] puts it over another such view. */
@Composable
private fun VideoView(video: CallVideo, pictures: PictureSink, mirror: Boolean, onTop: Boolean, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceViewRenderer(context).apply {
                init(video.egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                if (onTop) setZOrderMediaOverlay(true)
                pictures.target = this
            }
        },
        update = { it.setMirror(mirror) },
        onRelease = {
            // Stop the stream from drawing into a view that is going away, then let go of it.
            if (pictures.target === it) pictures.target = null
            it.release()
        },
    )
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, container: Color, content: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(
            onClick = onClick,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
            modifier = Modifier.size(72.dp),
        ) { Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A round button without words under it, for the row of five over a video call. */
@Composable
private fun SmallButton(icon: ImageVector, label: String, container: Color, content: Color, size: Dp = 52.dp, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = content),
        modifier = Modifier.size(size),
    ) { Icon(icon, contentDescription = label) }
}

/** A button that stays lit while what it stands for is on. */
@Composable
private fun Toggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    RoundButton(
        icon = icon,
        label = label,
        container = if (on) colors.primary else colors.surfaceContainerHighest,
        content = if (on) colors.onPrimary else colors.onSurface,
        onClick = onClick,
    )
}

@Preview(showBackground = true)
@Composable
private fun CallPreview() {
    MaunTheme {
        CallScreen(CallUi("ab".repeat(32), incoming = true, phase = CallUi.Phase.Ringing), "Asha", CallActions())
    }
}
