package org.maun.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.maun.messenger.LivePhase
import org.maun.messenger.LivePoint
import org.maun.messenger.LiveShare
import org.maun.messenger.LiveState
import org.maun.messenger.LiveView
import org.maun.messenger.data.MessageEntity
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * What the conversation can do about a live location: offer one, stop one,
 * open somebody else's, and ask the relay where they are.
 */
data class LiveActions(
    /** Shares where this phone is with [peer], for the minutes picked. */
    val onShare: (peer: String, minutes: Int) -> Unit = { _, _ -> },
    /** Ends the share with [peer] here and now. */
    val onStop: (peer: String, share: String) -> Unit = { _, _ -> },
    /** The person watching has opened it: they are told, and the card starts asking. */
    val onWatch: (peer: String, share: String) -> Unit = { _, _ -> },
    /** The newest thing the relay says about a share. Null when it cannot be asked. */
    val onFetch: suspend (peer: String, share: String, key: String) -> LiveView? = { _, _, _ -> null },
)

/**
 * The sheet behind the paperclip: a quarter of an hour or an hour, and what
 * that means. Nothing is shared until one of the two is pressed, and the phone
 * says so in the notification for the whole time it lasts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveShareSheet(
    name: String,
    onDismiss: () -> Unit,
    onShare: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Share your location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "$name will see where you are, as you move, until the time you pick runs out. " +
                    "They cannot ask for it again, and you can stop it at any moment from the notification " +
                    "or from the conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            for (minutes in LiveShare.CHOICES) {
                Button(
                    onClick = { onShare(minutes) },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                ) {
                    Text(if (minutes < 60) "Share for $minutes minutes" else "Share for 1 hour")
                }
            }
            Text(
                text = "Maun asks this phone for its location only while a share runs. What the relay " +
                    "keeps is sealed on this phone, and thrown away when the time is up.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * One live-location card in a conversation.
 *
 * It is the whole of the feature on screen: a share this phone started, one
 * somebody else started and this phone has not opened, one being watched, and
 * the four ways all of them can end. No coordinates are shown for a share that
 * is over, and none for one that has gone quiet: the card says updates have
 * paused rather than a place where nobody may be any more.
 */
@Composable
fun LiveLocationRow(
    message: MessageEntity,
    name: String,
    live: LiveActions,
    modifier: Modifier = Modifier,
    /** Draws the point on a map, full screen: the card only ever offers the button. */
    onOpenMap: (LivePoint, String) -> Unit = { _, _ -> },
) {
    val record = LiveShare.fromLocal(message.text)
    val outgoing = message.outgoing
    val chatColors = LocalChatColors.current
    // The card counts down, so it is redrawn once a second while it is on screen.
    var now by remember(record?.share) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(record?.share) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    if (record == null) {
        // A record this version cannot read: say so rather than show nothing.
        Card(
            outgoing = outgoing,
            title = LiveShare.UNREADABLE,
            detail = "This version of Maun cannot show it.",
            chatColors = chatColors,
            modifier = modifier,
        )
        return
    }

    val phase = record.phase(outgoing = outgoing, now = now)
    var view by remember(record.share) { mutableStateOf<LiveView?>(null) }

    // Nobody is asked anything until the card is on screen, and nobody is
    // asked anything again once the share is over.
    LaunchedEffect(record.share, phase) {
        if (phase != LivePhase.WATCHING) return@LaunchedEffect
        while (true) {
            view = live.onFetch(message.peer, record.share, record.key)
            val state = view?.state
            if (state == LiveState.ENDED || state == LiveState.GONE) break
            delay(POLL_MS)
        }
    }

    val ended = phase == LivePhase.ENDED || view?.state == LiveState.ENDED || view?.state == LiveState.GONE
    val point = view?.point

    when (phase) {
        LivePhase.SHARING -> Card(
            outgoing = outgoing,
            title = "Sharing your location",
            detail = "Until ${clock(record.until)} · ${left(record.until - now)} left",
            chatColors = chatColors,
            modifier = modifier,
            action = {
                TextButton(onClick = { live.onStop(message.peer, record.share) }) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            },
            pulse = true,
            onOpenMap = null,
            openMap = onOpenMap,
        )

        LivePhase.OFFER -> Card(
            outgoing = outgoing,
            title = "$name is sharing their location",
            detail = "Until ${clock(record.until)} · ${left(record.until - now)} left",
            chatColors = chatColors,
            modifier = modifier,
            action = {
                Button(onClick = { live.onWatch(message.peer, record.share) }) { Text("View") }
            },
            pulse = true,
            onOpenMap = null,
            openMap = onOpenMap,
        )

        LivePhase.WATCHING -> {
            val stale = point != null && (view?.stale(now) ?: true)
            val detail = when {
                ended -> "This location has ended."
                view == null -> "Asking the relay…"
                point == null -> "Waiting for the first update…"
                stale -> "Updates paused — last update ${since(point.at, now)}."
                else -> "Updated ${since(point.at, now)} · ${place(point)}"
            }
            Card(
                outgoing = outgoing,
                title = if (ended) "Live location ended" else "$name's live location",
                detail = detail,
                chatColors = chatColors,
                modifier = modifier,
                action = {
                    if (!ended && point != null && !stale) {
                        TextButton(onClick = { live.onStop(message.peer, record.share) }) { Text("Hide") }
                    }
                },
                pulse = !ended && !stale,
                onOpenMap = if (ended || stale || point == null) null else point,
                openMap = onOpenMap,
            )
        }

        LivePhase.ENDED -> Card(
            outgoing = outgoing,
            title = if (outgoing) "You shared your location" else "$name shared their location",
            detail = "Ended at ${clock(record.until)}. Nothing of where anybody was is kept.",
            chatColors = chatColors,
            modifier = modifier,
            action = null,
            pulse = false,
            onOpenMap = null,
            openMap = onOpenMap,
        )
    }
}

/** The card itself: a title, a line under it, and whatever can be done about it. */
@Composable
private fun Card(
    outgoing: Boolean,
    title: String,
    detail: String,
    chatColors: ChatColors,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    pulse: Boolean = false,
    onOpenMap: LivePoint? = null,
    openMap: (LivePoint, String) -> Unit = { _, _ -> },
) {
    Surface(
        modifier = modifier.fillMaxWidth(0.85f),
        shape = RoundedCornerShape(16.dp),
        color = if (outgoing) chatColors.outgoingBubble else chatColors.incomingBubble,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MaunIcons.Pin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (outgoing) chatColors.outgoingContent else chatColors.incomingContent,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (outgoing) chatColors.outgoingMeta else chatColors.incomingMeta,
                    )
                }
                if (pulse) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            if (onOpenMap != null) {
                TextButton(onClick = { openMap(onOpenMap, title) }) { Text("Open the map") }
            }
            action?.invoke()
        }
    }
}

/**
 * Where the point is, on a map.
 *
 * The tiles come from OpenStreetMap, straight from the phone: free, and with
 * no account and no key. That also means the tile server learns which part of
 * the world this phone is looking at, and when. Nothing else is sent with the
 * request, and the map is only drawn while it is open on screen.
 */
@Composable
fun LiveMapScreen(title: String, point: LivePoint, onClose: () -> Unit) {
    val context = LocalContext.current
    // One map for as long as the screen is open: the point moves under it, the
    // map itself is not thrown away and drawn again every half minute.
    val map = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            controller.setZoom(16.0)
        }
    }
    val marker = remember(map) {
        Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(this)
        }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
    // The point moves while the map is open, so the map follows it.
    LaunchedEffect(point.lat, point.lon, title) {
        val at = GeoPoint(point.lat, point.lon)
        marker.position = at
        marker.title = title
        map.controller.setCenter(at)
        map.invalidate()
    }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(MaunIcons.Back, contentDescription = "Back")
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            Text(
                text = "Map tiles by OpenStreetMap, which sees the area this phone is looking at.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** "14 min", "under a minute": what is left of a share. */
internal fun left(millis: Long): String = when {
    millis <= 0L -> "no time"
    millis < 60_000L -> "under a minute"
    else -> "${(millis + 59_999L) / 60_000L} min"
}

/** "just now", "20s ago", "3 min ago". */
internal fun since(at: Long?, now: Long): String {
    if (at == null) return "nothing yet"
    val seconds = (now - at) / 1000L
    return when {
        seconds < 10L -> "just now"
        seconds < 90L -> "${seconds}s ago"
        else -> "${(seconds + 30L) / 60L} min ago"
    }
}

/** "51.50731, -3.17909": enough to find a place by, which is what the card is for. */
internal fun place(point: LivePoint): String =
    "%.5f, %.5f".format(point.lat, point.lon) + if (point.accuracy > 0) " (±${point.accuracy.toInt()} m)" else ""

/** The minute a share ends at, on this phone's clock. */
private fun clock(until: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(until).atZone(zone).toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

/** How often a card that is being watched asks the relay. A point comes every half minute. */
private const val POLL_MS = 15_000L
