package org.maun.messenger.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.maun.messenger.webrtc.CallUi

/**
 * A call, over everything else: who it is with, where it has got to, and the
 * buttons for it. While it rings here those are Decline and Answer; after
 * that, the microphone, the loudspeaker and hanging up. [now] is only there
 * so that a picture of the screen can be drawn with a fixed clock.
 */
@Composable
fun CallScreen(
    call: CallUi,
    name: String,
    onAccept: () -> Unit,
    onHangUp: () -> Unit,
    onMute: (Boolean) -> Unit,
    onSpeaker: (Boolean) -> Unit,
    now: () -> Long = SystemClock::elapsedRealtime,
) {
    val colors = MaterialTheme.colorScheme
    var clock by remember { mutableLongStateOf(now()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallUi.Phase.Active) {
            clock = now()
            delay(500)
        }
    }
    val status = when (call.phase) {
        CallUi.Phase.Ringing -> if (call.incoming) "Incoming voice call" else "Ringing…"
        CallUi.Phase.Connecting -> "Connecting…"
        CallUi.Phase.Active -> formatDuration((clock - call.connectedAt).coerceAtLeast(0) / 1000 * 1000)
        CallUi.Phase.Ended -> call.ended ?: "Call ended"
    }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                when {
                    call.phase == CallUi.Phase.Ended -> Unit
                    call.incoming && call.phase == CallUi.Phase.Ringing -> {
                        RoundButton(MaunIcons.CallEnd, "Decline", colors.error, colors.onError, onClick = onHangUp)
                        RoundButton(MaunIcons.Call, "Answer", LocalStatusColors.current.passed, Color.White, onClick = onAccept)
                    }
                    else -> {
                        Toggle(if (call.muted) MaunIcons.MicOff else MaunIcons.Mic, if (call.muted) "Muted" else "Mute", call.muted) { onMute(!call.muted) }
                        RoundButton(MaunIcons.CallEnd, "Hang up", colors.error, colors.onError, onClick = onHangUp)
                        Toggle(MaunIcons.Speaker, "Speaker", call.speaker) { onSpeaker(!call.speaker) }
                    }
                }
            }
        }
    }
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
        CallScreen(CallUi("ab".repeat(32), incoming = true, phase = CallUi.Phase.Ringing), "Asha", {}, {}, {}, {})
    }
}
