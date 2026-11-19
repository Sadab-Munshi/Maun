package org.maun.messenger.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maun.messenger.LivePhase
import org.maun.messenger.LivePoint
import org.maun.messenger.LiveShare
import org.maun.messenger.LiveState
import org.maun.messenger.LiveView
import org.maun.messenger.metresBetween
import org.maun.messenger.Place
import org.maun.messenger.data.MessageEntity

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
    /** Sends where this phone is, once, to [peer]: no share, nothing running behind it. */
    val onSendNow: (peer: String) -> Unit = { _ -> },
    /** The newest thing the relay says about a share. Null when it cannot be asked. */
    val onFetch: suspend (peer: String, share: String, key: String) -> LiveView? = { _, _, _ -> null },
)

/**
 * A place to put on the map, and what to call it.
 *
 * [title] is what the card said, and what the phone's map app will label the
 * pin with; [who] is just the person's name, which is what fits at the top of
 * a map on a phone held in one hand.
 */
data class MapAsk(val point: LivePoint, val title: String, val who: String = "")

/**
 * The sheet behind the paperclip, offering the two ways to say where you are.
 *
 * **Send my location now** is one message and nothing else: it is for "I am
 * here", and it leaves nothing running. **Share live** is for being followed
 * for a while — a walk home, a night out — and is the only one of the two that
 * keeps a service, a notification and a countdown going.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveShareSheet(
    name: String,
    onDismiss: () -> Unit,
    onSendNow: () -> Unit,
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
            Text("Where you are", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            // One point in time: nothing runs afterwards, on either phone.
            Button(
                onClick = onSendNow,
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            ) {
                Text("Send my location now")
            }
            Text(
                text = "One place, sent once. $name can see where you were when you pressed it, and " +
                    "nothing keeps following you: no countdown, no notification, no update.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text("Or let $name follow you", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "$name will see where you are, as you move, until the time you pick runs out. " +
                    "They cannot ask for it again, and you can stop it at any moment from the notification " +
                    "or from the conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            for (minutes in LiveShare.CHOICES) {
                OutlinedButton(
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
    /** Draws the point on a map, full screen: only a share that is still moving offers it. */
    onOpenMap: (MapAsk) -> Unit = {},
) {
    val record = LiveShare.fromLocal(message.text)
    val outgoing = message.outgoing
    val chatColors = LocalChatColors.current

    // A place that was sent once is not a share, and nothing about it counts
    // down or asks again: it is drawn and left alone.
    val sent = if (record == null) Place.fromLocal(message.text) else null
    if (sent != null) {
        val point = sent.point()
        val now = System.currentTimeMillis()
        val context = LocalContext.current
        val label = if (outgoing) "Where you were" else "$name's location"
        Card(
            outgoing = outgoing,
            title = if (outgoing) "You sent your location" else "$name sent their location",
            detail = buildString {
                // The name first, in the words the sending phone found for it,
                // then the numbers underneath, because the numbers never lie.
                if (sent.label.isNotEmpty()) append(sent.label).append('\n')
                append(place(point)).append(" · sent ").append(since(sent.at, now))
            },
            chatColors = chatColors,
            // A place that was sent once is a preview: the whole card is the
            // way through, and there is no second button to read first. Tapped,
            // it is handed to the phone's own map app, which knows the name, the
            // address and how to get there — the same thing WhatsApp does, and
            // the reason a bubble does not need all of it.
            modifier = modifier.clickable { openInMaps(context, point, label) },
            pulse = false,
            // A small map in the corner, so that it reads as a thing that opens.
            opensMapApp = true,
        )
        return
    }
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
            val heading = if (ended) "Live location ended" else "$name's live location"
            Card(
                outgoing = outgoing,
                title = heading,
                detail = detail,
                chatColors = chatColors,
                modifier = modifier,
                action = {
                    if (!ended && point != null && !stale) {
                        TextButton(onClick = { live.onStop(message.peer, record.share) }) { Text("Hide") }
                    }
                },
                pulse = !ended && !stale,
                // Only a share that is still moving is worth a map of its own:
                // a place sent once already opens the map app on a tap, and a
                // share that is over has nothing left to show.
                openMap = if (!ended && !stale && point != null) {
                    { onOpenMap(MapAsk(point, heading, name)) }
                } else {
                    null
                },
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
        )
    }
}

/**
 * The card itself: a title, a line under it, and whatever can be done about it.
 *
 * [openMap] draws the one button a card ever needs; [opensMapApp] is for a card
 * that is itself the button, and only marks the corner so it reads that way.
 */
@Composable
private fun Card(
    outgoing: Boolean,
    title: String,
    detail: String,
    chatColors: ChatColors,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    pulse: Boolean = false,
    openMap: (() -> Unit)? = null,
    opensMapApp: Boolean = false,
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
                if (opensMapApp) {
                    Icon(
                        MaunIcons.Map,
                        contentDescription = null,
                        tint = if (outgoing) chatColors.outgoingMeta else chatColors.incomingMeta,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
            if (openMap != null) {
                TextButton(onClick = openMap) { Text("Open the map") }
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
 *
 * Two dots can be on it, and they are never the same dot: the other phone's is
 * the bigger, red one, this phone's own is the smaller, green one, and the line
 * under the map says which is which. That is everything the colours mean.
 */
@Composable
fun LiveMapScreen(ask: MapAsk, onClose: () -> Unit) {
    val context = LocalContext.current
    val point = ask.point
    // The name goes on the map as well as in the bar: a dot with its name
    // under it needs no key, and a name in the bar needs no dot.
    val header = mapHeadline(ask)
    val under = mapSubtitle(ask)
    val theirName = mapLabel(header)
    val theirs = Color(THEIR_DOT)
    val yours = MaterialTheme.colorScheme.primary
    // Where this phone is, when the person looking has said Maun may ask. It
    // never leaves the phone: the distance below is subtraction on this device.
    var mine by remember { mutableStateOf<LivePoint?>(null) }
    var following by remember { mutableStateOf(false) }
    val askForMe = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val allowed = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (allowed) following = true
    }
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
    // Following stops the moment the screen does, and the last place is
    // forgotten with it.
    DisposableEffect(following) {
        if (!following) return@DisposableEffect onDispose { }
        val stop = followMyLocation(context) { fix -> mine = fix }
        onDispose { stop() }
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
            // The credit is drawn by this screen instead, at the bottom, where
            // the card under the map cannot cover it up.
            isAttributionEnabled = false
        }
        loaded.setStyle(styleFor(point, theirName, theirs.toArgb(), yours.toArgb()))
    }
    // The point moves while a share is being watched, so the map follows it.
    LaunchedEffect(map, point.lat, point.lon, mine?.lat, mine?.lon) {
        val loaded = map ?: return@LaunchedEffect
        loaded.style?.getSourceAs<GeoJsonSource>(PIN_SOURCE)?.setGeoJson(pin(point, theirName))
        loaded.style?.getSourceAs<GeoJsonSource>(MY_SOURCE)
            ?.setGeoJson(mine?.let { pin(it, MY_NAME) } ?: nobody())
        loaded.cameraPosition = loaded.cameraFor(point, mine) ?: CameraPosition.Builder()
            .target(LatLng(point.lat, point.lon))
            .zoom(ZOOM)
            .build()
    }
    val distance = mine?.let { metresBetween(it, point) }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // The bar sits below the phone's own clock, not under it, and the
        // actions are icons at the end: a name is what has to fit here.
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(MaunIcons.Back, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                    Text(
                        text = header,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (under.isNotEmpty()) {
                        Text(
                            text = under,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (mine == null) {
                    // Off until somebody asks for it: a viewer of a share needs
                    // no permission at all, and this is the only thing that
                    // would ask for one.
                    TextButton(
                        onClick = {
                            if (locationAllowed(context)) {
                                following = true
                            } else {
                                askForMe.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text("How far?", style = MaterialTheme.typography.labelLarge)
                    }
                }
                // The map is a picture of the point; this hands the point to
                // whatever map app the phone already has, which is what is
                // wanted when the next step is walking there. It works whether
                // or not a single tile loaded.
                IconButton(onClick = { openInMaps(context, point, ask.title) }) {
                    Icon(
                        MaunIcons.Map,
                        contentDescription = "Open in the map app",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // At the bottom: how far apart the two dots are, which dot is which,
        // what this phone does and does not send, and who drew the map.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (distance != null) {
                        Text(
                            text = "$header is ${howFar(distance)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Key(theirs = theirs, yours = yours, who = theirName)
                    }
                    Text(
                        text = if (distance != null) {
                            "The distance is worked out on this phone; where you are is never sent anywhere."
                        } else {
                            "It sees the area this phone is looking at, and nothing else."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // OpenStreetMap's credit, where nothing can sit on top of it.
            Text(
                text = "© OpenStreetMap contributors · tiles by OpenFreeMap",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { openUri(context, "https://www.openstreetmap.org/copyright") }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Which dot is which, in the same colours the map draws them. */
@Composable
private fun Key(theirs: Color, yours: Color, who: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyDot(theirs, who)
        KeyDot(yours, MY_NAME)
    }
}

@Composable
private fun KeyDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The name at the top of the map: whose place it is, in the fewest words. */
internal fun mapHeadline(ask: MapAsk): String = ask.who.ifBlank { ask.title }

/** And what is being shown, when there is a name above it to say it under. */
internal fun mapSubtitle(ask: MapAsk): String = if (ask.who.isBlank()) "" else "Live location"

/**
 * A name short enough to write under a dot. The map is the wrong place for a
 * sentence: a long title is cut rather than allowed to cover the street it is
 * standing on.
 */
internal fun mapLabel(name: String): String = when {
    name.isBlank() -> "Their place"
    name.length <= MAX_NAME -> name
    else -> name.take(MAX_NAME - 1).trimEnd() + "…"
}

/** This phone's own dot, as it is written on the map and in the key. */
internal const val MY_NAME = "You"

/** The camera that has both dots on it, with room for the bars above and below. */
private fun MapLibreMap.cameraFor(theirs: LivePoint, mine: LivePoint?): CameraPosition? {
    if (mine == null) return null
    val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
        .include(LatLng(theirs.lat, theirs.lon))
        .include(LatLng(mine.lat, mine.lon))
        .build()
    // Left, top, right, bottom: the distance card sits at the bottom.
    return getCameraForLatLngBounds(bounds, intArrayOf(120, 300, 120, 440))
}

/** Nobody's place, for the source that waits until somebody asks for theirs. */
private fun nobody(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())

/**
 * Asks this phone where it is, and keeps asking, until the returned function is
 * called. Nothing is sent anywhere: this is for the distance under the map, and
 * the phone that shares is never told that the question was asked.
 */
@SuppressLint("MissingPermission")
private fun followMyLocation(context: Context, onFix: (LivePoint) -> Unit): () -> Unit {
    val manager = context.getSystemService(LocationManager::class.java) ?: return {}
    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.time <= 0L) return
            onFix(LivePoint(location.latitude, location.longitude, location.accuracy.toDouble(), location.time))
        }

        @Deprecated("Required by LocationListener")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
        runCatching {
            manager.requestLocationUpdates(provider, 5_000L, 5f, listener, Looper.getMainLooper())
        }
    }
    return { runCatching { manager.removeUpdates(listener) } }
}

/** Whether permission to use this phone's own location has already been given. */
private fun locationAllowed(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/** "180 m away", "1.4 km away", "12 km away": a distance said the way a person would. */
internal fun howFar(metres: Double): String = when {
    !metres.isFinite() || metres < 0 -> "an unknown distance away"
    metres < 950 -> "${metres.toInt()} m away"
    metres < 10_000 -> String.format(java.util.Locale.US, "%.1f km away", metres / 1000)
    else -> "${(metres / 1000).roundToInt()} km away"
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

/** The layer that moves: where the other phone is, or was. */
private const val PIN_SOURCE = "maun-pin"
private const val PIN_DOT_LAYER = "maun-pin-dot"

/** And this phone's own, drawn only when the person looking has asked to see how far away it is. */
private const val MY_SOURCE = "maun-me"
private const val MY_DOT_LAYER = "maun-me-dot"
private const val ZOOM = 16.0

/**
 * The other phone's dot: the bigger one, in a red that no road, water or park
 * on the map is drawn in.
 */
internal val THEIR_DOT: Int = 0xFFD93025.toInt()
internal const val THEIR_DOT_RADIUS = 11f

/**
 * This phone's own dot: smaller, and the app's green, so that a glance says
 * which of the two is which without reading anything.
 */
internal const val MY_DOT_RADIUS = 7f

/** The name written under each dot, and how far under it, in text heights. */
private const val PIN_NAME_LAYER = "maun-pin-name"
private const val MY_NAME_LAYER = "maun-me-name"
private const val LABEL_PROPERTY = "label"
private const val THEIR_NAME_OFFSET = 1.35f
private const val MY_NAME_OFFSET = 0.95f

/**
 * The font the map's own labels are set in, asked for by the name the style
 * serves its glyphs under. It is written into the same style the map already
 * downloads tiles for, so nothing new is fetched for it.
 */
private const val TEXT_FONT = "Noto Sans Bold"
private const val NAME_SIZE = 11.5f

/** How many characters of a name are written under a dot before it is cut. */
internal const val MAX_NAME = 18

/**
 * A map of [point], with the point already on it, ready to hand to MapLibre.
 *
 * The point is put into the style rather than added afterwards, so the first
 * draw of the map has it: there is no frame where the map is there and the dot
 * is not.
 */
private fun styleFor(point: LivePoint, theirName: String, theirs: Int, yours: Int): Style.Builder = Style.Builder()
    .fromUri(MAP_STYLE)
    .withSource(GeoJsonSource(PIN_SOURCE, pin(point, theirName)))
    // Empty until somebody asks for it, so the second dot is a setGeoJson
    // rather than the whole style being built again under the map.
    .withSource(GeoJsonSource(MY_SOURCE, nobody()))
    .withLayer(dot(PIN_DOT_LAYER, PIN_SOURCE, theirs, THEIR_DOT_RADIUS))
    .withLayer(dot(MY_DOT_LAYER, MY_SOURCE, yours, MY_DOT_RADIUS))
    // After the dots, so that a name is drawn over the map and not under it.
    .withLayer(nameUnder(PIN_NAME_LAYER, PIN_SOURCE, theirs, THEIR_NAME_OFFSET))
    .withLayer(nameUnder(MY_NAME_LAYER, MY_SOURCE, yours, MY_NAME_OFFSET))

/**
 * A dot with a white edge, so it is the same dot on a pale street and on a dark
 * park, and at [radius] so that the two dots are told apart by size as well as
 * by colour.
 */
private fun dot(id: String, source: String, color: Int, radius: Float): CircleLayer =
    CircleLayer(id, source).withProperties(
        circleRadius(radius),
        circleColor(color),
        circleOpacity(1f),
        circleStrokeColor(android.graphics.Color.WHITE),
        circleStrokeWidth(3f),
    )

/**
 * The name of a dot, written under it in its own colour with a white edge, so
 * that the map says which dot is which without the key being read.
 *
 * Nothing here is placed against anything else: two names that would touch both
 * get drawn, because the dots are what is being read and a name that went
 * missing would be worse than one drawn over another. If the font cannot be
 * fetched, this draws nothing at all and the key under the map still says who
 * is who.
 */
private fun nameUnder(layerId: String, source: String, color: Int, offset: Float): SymbolLayer =
    SymbolLayer(layerId, source).withProperties(
        textField(Expression.get(LABEL_PROPERTY)),
        textFont(arrayOf(TEXT_FONT)),
        textSize(NAME_SIZE),
        textColor(color),
        textHaloColor(android.graphics.Color.WHITE),
        textHaloWidth(1.8f),
        textAnchor(Property.TEXT_ANCHOR_TOP),
        textOffset(arrayOf(0f, offset)),
        textAllowOverlap(true),
        textIgnorePlacement(true),
    )

/** The point as GeoJSON: MapLibre wants longitude first, and this is the one place that flips it. */
private fun pin(point: LivePoint, label: String): FeatureCollection = FeatureCollection.fromFeature(
    Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).apply {
        addStringProperty(LABEL_PROPERTY, label)
    },
)

/** Opens a page in whatever the phone uses for pages. */
private fun openUri(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

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
