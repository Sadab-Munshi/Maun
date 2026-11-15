package org.maun.messenger.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.maun.messenger.Attachment
import org.maun.messenger.data.MessageEntity

/**
 * What a conversation needs in order to show and move files. The defaults do
 * nothing, for previews and for screens drawn without the app behind them.
 */
class FileActions(
    /** Transfers under way, by message: how much is done, from 0 to 1. */
    val progress: Map<Long, Float> = emptyMap(),
    /** Why a file could not be sent or fetched, to be said once. */
    val problem: String? = null,
    val onProblemShown: () -> Unit = {},
    /** The user picked a file to send. */
    val onAttach: (Uri) -> Unit = {},
    /** Send or fetch the file of a message, whichever it still needs. */
    val onStart: (Long) -> Unit = {},
    /** Write the file of a message, decrypted, to the place the user picked. */
    val onSave: (Long, Attachment, Uri) -> Unit = { _, _, _ -> },
    /** The picture of a message, about so many pixels on its longer side. */
    val loadImage: suspend (Long, Attachment, Int) -> Bitmap? = { _, _, _ -> null },
    /** The same without waiting, when it has been shown since the app started. */
    val cachedImage: (Long, Int) -> Bitmap? = { _, _ -> null },
)

/** Pictures up to this size are fetched as soon as their message is on screen. */
private const val AUTO_FETCH_BYTES = 5_000_000L
private const val PREVIEW_PIXELS = 720
private const val FULL_PIXELS = 2048

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> "${(bytes + 500) / 1_000} kB"
    else -> "$bytes B"
}

/** What can be done with a file right now, and what its bubble says. */
internal enum class FileStep { Working, Fetch, Open, Retry, Gone }

/** [active]: a transfer for this message is running right now. */
internal fun fileStep(file: Attachment, outgoing: Boolean, active: Boolean): FileStep = when {
    active -> FileStep.Working
    file.state == Attachment.READY -> FileStep.Open
    file.state == Attachment.GONE -> FileStep.Gone
    file.state == Attachment.REMOTE && !outgoing -> FileStep.Fetch
    // Marked as under way, but nothing is running: the app was closed in the middle.
    else -> FileStep.Retry
}

internal fun fileLine(file: Attachment, outgoing: Boolean, step: FileStep, fraction: Float?): String {
    val size = formatSize(file.size)
    return when (step) {
        FileStep.Working -> {
            val percent = ((fraction ?: 0f) * 100).toInt()
            if (file.key.isEmpty()) "$size · Sending $percent%" else "$size · Fetching $percent%"
        }
        FileStep.Fetch -> "$size · Tap to fetch"
        FileStep.Open -> if (file.isImage) size else "$size · Tap to save"
        FileStep.Retry -> if (outgoing && file.key.isEmpty()) "$size · Not sent. Tap to try again" else "$size · Tap to try again"
        FileStep.Gone -> if (outgoing) "$size · No longer on this phone" else "$size · No longer available"
    }
}

/**
 * The inside of a file message's bubble: the picture itself once it is on
 * this phone, otherwise a row with the file's name, its size, and what
 * tapping the bubble will do.
 */
@Composable
fun FileContent(
    message: MessageEntity,
    file: Attachment,
    files: FileActions,
    text: Color,
    quiet: Color,
) {
    val fraction = files.progress[message.seq]
    val step = fileStep(file, message.outgoing, active = fraction != null)

    // A small picture someone sent is fetched without being asked for.
    LaunchedEffect(message.seq, step) {
        if (step == FileStep.Fetch && file.isImage && file.size <= AUTO_FETCH_BYTES) files.onStart(message.seq)
    }

    // A picture that is on this phone is shown as a picture: one that arrived,
    // and one of one's own that is still on its way out.
    val unsent = message.outgoing && file.key.isEmpty()
    val here = file.isImage && (step == FileStep.Open || (unsent && step != FileStep.Gone))
    // One that is being fetched gets its room straight away, so the list does not jump when it lands.
    val arriving = file.isImage && !message.outgoing &&
        (step == FileStep.Working || (step == FileStep.Fetch && file.size <= AUTO_FETCH_BYTES))
    val picture by produceState(files.cachedImage(message.seq, PREVIEW_PIXELS), message.seq, here) {
        if (here && value == null) value = files.loadImage(message.seq, file, PREVIEW_PIXELS)
    }
    if (here || arriving) {
        val shown = picture
        val ratio = when {
            shown != null -> shown.width.toFloat() / shown.height
            file.width > 0 && file.height > 0 -> file.width.toFloat() / file.height
            else -> 4f / 3f
        }.coerceIn(0.6f, 1.8f)
        Box(
            modifier = Modifier
                .width(240.dp)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(12.dp))
                .background(text.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = "Photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            when (step) {
                FileStep.Working -> Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { fraction ?: 0f },
                        modifier = Modifier.size(30.dp),
                        strokeWidth = 3.dp,
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f),
                    )
                }
                FileStep.Retry -> Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(MaunIcons.Retry, contentDescription = "Not sent. Tap to try again", tint = Color.White) }
                else -> Unit
            }
        }
        return
    }

    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(text.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            when (step) {
                FileStep.Working -> CircularProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = text,
                    trackColor = text.copy(alpha = 0.2f),
                )
                FileStep.Fetch -> Icon(MaunIcons.Download, contentDescription = null, tint = text)
                FileStep.Retry -> Icon(MaunIcons.Retry, contentDescription = null, tint = text)
                else -> Icon(if (file.isImage) MaunIcons.Image else MaunIcons.File, contentDescription = null, tint = text)
            }
        }
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = fileLine(file, message.outgoing, step, fraction),
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
            )
        }
    }
}

/** A picture, as large as the screen allows, over everything else. */
@Composable
fun ImageViewer(seq: Long, file: Attachment, files: FileActions, onSave: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val picture by produceState<Bitmap?>(null, seq) { value = files.loadImage(seq, file, FULL_PIXELS) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val shown = picture
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(MaunIcons.Close, contentDescription = "Close", tint = Color.White) }
            Text(
                text = file.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = onSave) { Icon(MaunIcons.Download, contentDescription = "Save", tint = Color.White) }
        }
    }
}
