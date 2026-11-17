package org.maun.messenger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maun.messenger.Avatars
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import kotlin.math.floor
import org.maun.messenger.crypto.QrCodec

/**
 * The page layout every screen shares: a bar with the title and a way back,
 * then the content, which scrolls. A screen with nothing to go back to shows
 * its title large instead.
 */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                if (onBack != null) TopBar(title = title, onBack = onBack, actions = actions)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (onBack == null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    content()
                }
            }
        }
    }
}

/** The bar across the top of a screen: back arrow, title, and whatever [actions] adds on the right. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(MaunIcons.Back, contentDescription = "Back") }
        } else {
            Spacer(modifier = Modifier.width(16.dp))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/**
 * Changes when a profile picture does, so that every avatar on screen redraws
 * at once without each of them asking the disk again.
 */
val LocalAvatarRevision = staticCompositionLocalOf { 0 }

/**
 * A person's picture, or the circle that stands in for one: their initial on
 * their own colour. Someone without a name (a message request) gets a grey "?".
 * A picture is read once and kept in memory; it never leaves this phone.
 */
@Composable
fun Avatar(id: String, name: String?, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val context = LocalContext.current
    val revision = LocalAvatarRevision.current
    // Only the picture a person shared from their own phone is drawn. Nothing
    // chosen on this phone takes its place: their face is their own.
    var picture by remember(id) { mutableStateOf(Avatars.cachedShared(id)) }
    LaunchedEffect(id, revision) {
        if (!Avatars.hasShared(context, id)) {
            picture = null
            return@LaunchedEffect
        }
        val known = Avatars.cachedShared(id)
        picture = known ?: withContext(Dispatchers.IO) { Avatars.loadShared(context, id) }
    }
    val shown = picture
    val known = !name.isNullOrBlank()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (known) avatarColor(id) else MaterialTheme.colorScheme.surfaceContainerHighest)
            // Decoration: the name next to it is what a screen reader should say.
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = initialOf(name),
                color = if (known) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** The small coloured heading above a group of rows. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A Maun ID split into groups of four so that two people can compare it by eye. */
fun groupedId(id: String): String =
    id.chunked(4).chunked(4).joinToString("\n") { it.joinToString(" ") }

/**
 * A moment the way the phone's language writes it: just the time for today,
 * date and time for anything older.
 */
fun formatWhen(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val format = if (dayOf(atMillis, zone) == dayOf(nowMillis, zone)) {
        DateFormat.getTimeInstance(DateFormat.SHORT)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }
    return format.format(Date(atMillis))
}

/** The time of day alone. In a conversation the date stands on its own line above. */
fun formatTime(atMillis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(atMillis))

/** For the list of chats, where there is little room: the time today, "Yesterday", or a short date. */
fun formatListTime(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val zone = ZoneId.systemDefault()
    val day = dayOf(atMillis, zone)
    val today = dayOf(nowMillis, zone)
    return when (day) {
        today -> formatTime(atMillis)
        today.minusDays(1) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(atMillis))
    }
}

/** The first and last few characters, enough to tell contacts apart in a list. */
fun shortId(id: String): String = if (id.length <= 12) id else "${id.take(6)}…${id.takeLast(6)}"

@Composable
fun IdText(id: String, modifier: Modifier = Modifier) {
    Text(
        text = groupedId(id),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Draws [text] as a QR code. Always black on white with a clear border,
 * whatever the theme, because that is what cameras read best.
 */
@Composable
fun QrImage(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrCodec.encode(text) }
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .semantics { contentDescription = description },
    ) {
        val modules = matrix.width
        val quietZone = 4
        // Whole pixels per module, so neighbouring modules meet without gaps.
        val cell = floor(size.minDimension / (modules + 2 * quietZone))
        if (cell < 1f) return@Canvas
        val left = floor((size.width - cell * modules) / 2)
        val top = floor((size.height - cell * modules) / 2)
        for (y in 0 until modules) {
            var x = 0
            while (x < modules) {
                if (!matrix[x, y]) {
                    x++
                    continue
                }
                var end = x
                while (end + 1 < modules && matrix[end + 1, y]) end++
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(left + x * cell, top + y * cell),
                    size = Size((end - x + 1) * cell, cell),
                )
                x = end + 1
            }
        }
    }
}
