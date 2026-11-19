package org.maun.messenger.ui

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.graphics.toArgb
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
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

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
                    Text("Share for ${howLong(minutes)}")
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
            // Being told that somebody is looking is half of what a share is for.
            title = if (record.state == LiveShare.WATCHING) "$name is watching" else "Sharing your location",
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
            detail = "Ended. Nothing of where anybody was is kept.",
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
    val dot = MaterialTheme.colorScheme.primary.toArgb()
    // One map for as long as the screen is open: the point moves under it, the
    // map itself is not thrown away and drawn again with every new point.
    val mapView = remember(context) {
        prepareMaps(context)
        MapView(context).apply {
            // MapLibre asks for the whole of a View's life, not half of it: a
            // map that has been created but not started draws nothing.
            onCreate(null)
            onStart()
            onResume()
        }
    }
    DisposableEffect(mapView) {
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    LaunchedEffect(mapView) {
        mapView.getMapAsync { ready -> map = ready }
    }
    LaunchedEffect(map) {
        val loaded = map ?: return@LaunchedEffect
        loaded.uiSettings.apply {
            // A second finger should not spin the map under somebody who is
            // trying to read a street name.
            isRotateGesturesEnabled = false
            isTiltGesturesEnabled = false
            isZoomGesturesEnabled = true
            isAttributionEnabled = true
        }
        loaded.setStyle(styleFor(point, dot))
    }
    // The point moves while a share is being watched, so the map follows it.
    LaunchedEffect(map, point.lat, point.lon) {
        val loaded = map ?: return@LaunchedEffect
        loaded.cameraPosition = CameraPosition.Builder()
            .target(LatLng(point.lat, point.lon))
            .zoom(ZOOM)
            .build()
        // The style may not have finished loading; when it has not, the first
        // point is already in the style it was built with.
        loaded.style?.getSourceAs<GeoJsonSource>(PIN_SOURCE)?.setGeoJson(pin(point))
    }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
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
                // The map is a picture of the point; this hands the point to
                // whatever map app the phone already has, which is what is
                // wanted when the next step is walking there. It works whether
                // or not a single tile loaded.
                TextButton(onClick = { openInMaps(context, point, title) }) { Text("Maps") }
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            Text(
                text = "Map from OpenFreeMap, drawn from OpenStreetMap data. It sees the area " +
                    "this phone is looking at, and nothing else.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The map is OpenFreeMap's: OpenStreetMap data packaged as vector tiles and
 * drawn on the phone by MapLibre, which is free software. No key, no account,
 * no Google, and no card behind it.
 *
 * The trade is the usual one for a map: the tile server learns which part of
 * the world this phone is looking at, and roughly when, and the map says so.
 * Moving to tiles this project served itself would be a change to this one
 * URL, because everything else about the map is the drawing.
 */
private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/bright"

/** The one layer that moves: where the phone is, or was. */
private const val PIN_SOURCE = "maun-pin"
private const val PIN_DOT_LAYER = "maun-pin-dot"
private const val ZOOM = 16.0

/**
 * A map of [point], with the point already on it, ready to hand to MapLibre.
 *
 * The point is put into the style rather than added afterwards, so the first
 * draw of the map has it: there is no frame where the map is there and the dot
 * is not.
 */
private fun styleFor(point: LivePoint, color: Int): Style.Builder = Style.Builder()
    .fromUri(MAP_STYLE)
    .withSource(GeoJsonSource(PIN_SOURCE, pin(point)))
    .withLayer(
        CircleLayer(PIN_DOT_LAYER, PIN_SOURCE).withProperties(
            circleRadius(9f),
            circleColor(color),
            circleOpacity(1f),
            // A white edge, so the dot is the same dot on a pale street and on
            // a dark park.
            circleStrokeColor(android.graphics.Color.WHITE),
            circleStrokeWidth(3f),
        ),
    )

/** The point as GeoJSON: MapLibre wants longitude first, and this is the one place that flips it. */
private fun pin(point: LivePoint): FeatureCollection = FeatureCollection.fromFeature(
    Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)),
)

/** Hands the point to the phone's own maps app, without asking it where it is. */
private fun openInMaps(context: Context, point: LivePoint, title: String) {
    val at = "${point.lat},${point.lon}"
    val uri = Uri.parse("geo:$at?q=" + Uri.encode(at) + "(" + Uri.encode(title) + ")")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // No maps app on this phone: the map on screen is all there is, and it
        // is enough to see where the point is.
    }
}

/**
 * MapLibre is told about this phone once, before the first map is made. It
 * needs no key and no account: what it needs is to exist before a MapView does.
 */
private var mapsReady = false

private fun prepareMaps(context: Context) {
    if (mapsReady) return
    MapLibre.getInstance(context)
    mapsReady = true
}

/** "15 minutes", "1 hour", "8 hours", "24 hours": how long a share is started for. */
internal fun howLong(minutes: Int): String = when {
    minutes < 60 -> "$minutes minutes"
    minutes % 60 != 0 -> "${minutes / 60} h ${minutes % 60} min"
    minutes == 60 -> "1 hour"
    else -> "${minutes / 60} hours"
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

/** How often a card that is being watched asks the relay. A point comes every fifteen seconds. */
private const val POLL_MS = 10_000L

/** Whether this phone's own location switch is on: permission is not the same thing. */
fun locationSwitchedOn(context: Context): Boolean {
    val manager = context.getSystemService(LocationManager::class.java) ?: return false
    return try {
        manager.isLocationEnabled
    } catch (_: Exception) {
        false
    }
}

/** Opens the phone's own location settings, so that the switch can be found. */
fun openLocationSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        // No settings app will answer; the dialog has already said what to do.
    }
}
