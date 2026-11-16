package org.maun.messenger.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import org.maun.messenger.Attachment
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.voice.VoiceState

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
    /** Brief confirmation when a file or photo is saved (e.g. "Saved to Gallery"). */
    val notice: String? = null,
    val onNoticeShown: () -> Unit = {},
    /** The user picked or captured a file to send. */
    val onAttach: (Uri) -> Unit = {},
    /** The user picked several pictures or documents at once. */
    val onAttachMany: (List<Uri>) -> Unit = {},
    /** Open the file of a message in whichever app on this phone reads that kind of file. */
    val onOpen: (Long, Attachment) -> Unit = { _, _ -> },
    /** Send or fetch the file of a message, whichever it still needs. */
    val onStart: (Long) -> Unit = {},
    /** Save the file of a message directly to Gallery or Downloads/Maun via MediaStore. */
    val onSave: (Long, Attachment) -> Unit = { _, _ -> },
    /** The picture of a message, about so many pixels on its longer side. */
    val loadImage: suspend (Long, Attachment, Int) -> Bitmap? = { _, _, _ -> null },
    /** The same without waiting, when it has been shown since the app started. */
    val cachedImage: (Long, Int) -> Bitmap? = { _, _ -> null },
    /** A voice message was recorded: the file it is in, and how long it is. */
    val onVoice: (File, Long) -> Unit = { file, _ -> file.delete() },
    /** Play the voice message of a message, or pause it. */
    val onPlay: (Long, Attachment) -> Unit = { _, _ -> },
    /** The voice message that is loaded for playing, if any. */
    val voice: VoiceState? = null,
)

/** Pictures and short videos up to this size are fetched as soon as their message is on screen. */
internal const val AUTO_FETCH_BYTES = 5_000_000L
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

/** Whether this message renders as an edge-to-edge photo bubble with overlaid timestamp. */
internal fun isPictureBubble(message: MessageEntity, file: Attachment, step: FileStep): Boolean {
    if (!file.isImage || file.isVoice) return false
    val here = step == FileStep.Open || (message.outgoing && step != FileStep.Gone)
    val arriving = !message.outgoing && (step == FileStep.Working || (step == FileStep.Fetch && file.size <= AUTO_FETCH_BYTES))
    return here || arriving
}

/** Covers the small gap between inserting an outgoing row and publishing its file. */
internal suspend fun <T : Any> loadPictureWhenAvailable(outgoing: Boolean, load: suspend () -> T?): T? {
    val attempts = if (outgoing) 21 else 1
    repeat(attempts) { attempt ->
        load()?.let { return it }
        if (attempt + 1 < attempts) delay(100)
    }
    return null
}

internal fun fileLine(file: Attachment, message: MessageEntity, step: FileStep, fraction: Float?): String {
    val size = formatSize(file.size)
    return when (step) {
        FileStep.Working -> {
            val percent = ((fraction ?: 0f) * 100).toInt()
            if (file.key.isEmpty()) "$size · Sending $percent%" else "$size · Fetching $percent%"
        }
        FileStep.Fetch -> "$size · Tap to fetch"
        FileStep.Open -> when {
            file.isImage -> size
            message.savedToGallery && file.isGalleryMedia -> "$size · Saved to Gallery"
            message.savedToGallery -> "$size · Saved to Downloads/Maun"
            else -> "$size · Tap to open"
        }
        FileStep.Retry -> if (message.outgoing && file.key.isEmpty()) {
            "$size · Not sent. Tap to try again"
        } else {
            "$size · Tap to try again"
        }
        FileStep.Gone -> if (message.outgoing) "$size · No longer on this phone" else "$size · No longer available"
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
    imageShape: Shape = RoundedCornerShape(15.dp),
    imageOverlay: (@Composable BoxScope.() -> Unit)? = null,
) {
    val fraction = files.progress[message.seq]
    val step = fileStep(file, message.outgoing, active = fraction != null)

    // Small incoming photos, short videos, and voice messages are fetched right away.
    LaunchedEffect(message.seq, step) {
        val small = (file.isGalleryMedia && file.size <= AUTO_FETCH_BYTES) || file.isVoice
        if (step == FileStep.Fetch && small) files.onStart(message.seq)
    }

    if (file.isVoice) {
        VoiceContent(message, file, files.voice?.takeIf { it.seq == message.seq }, step, fraction, text, quiet)
        return
    }

    val showAsPicture = isPictureBubble(message, file, step)
    val here = file.isImage && (step == FileStep.Open || (message.outgoing && step != FileStep.Gone))
    // A sent row can appear before its encrypted file is renamed into place.
    // Retry briefly and also when transfer availability changes, rather than
    // caching the first (possibly premature) null for the lifetime of the row.
    val active = fraction != null
    val picture by produceState(files.cachedImage(message.seq, PREVIEW_PIXELS), message.seq, here, file.state, file.key, active) {
        if (here && value == null) {
            value = loadPictureWhenAvailable(message.outgoing) {
                files.loadImage(message.seq, file, PREVIEW_PIXELS)
            }
        }
    }
    if (showAsPicture) {
        val shown = picture
        val ratio = when {
            shown != null -> shown.width.toFloat() / shown.height
            file.width > 0 && file.height > 0 -> file.width.toFloat() / file.height
            else -> 4f / 3f
        }.coerceIn(0.6f, 1.8f)
        Box(
            modifier = Modifier
                .width(248.dp)
                .aspectRatio(ratio)
                .clip(imageShape)
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
            imageOverlay?.invoke(this)
        }
        return
    }

    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(CircleShape).background(text.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            when (step) {
                FileStep.Working -> CircularProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = text,
                    trackColor = text.copy(alpha = 0.2f),
                )
                FileStep.Fetch -> Icon(MaunIcons.Download, contentDescription = null, tint = text)
                FileStep.Retry -> Icon(MaunIcons.Retry, contentDescription = null, tint = text)
                else -> Icon(
                    when {
                        file.isImage -> MaunIcons.Image
                        file.isVideo -> MaunIcons.Videocam
                        else -> MaunIcons.File
                    },
                    contentDescription = null,
                    tint = text,
                )
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
                text = fileLine(file, message, step, fraction),
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
            )
        }
        // Saving is asked for on purpose; opening a file is what a tap does.
        if (step == FileStep.Open) {
            IconButton(onClick = { files.onSave(message.seq, file) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = MaunIcons.Download,
                    contentDescription = if (message.savedToGallery) "Saved. Tap to save again" else "Save to this phone",
                    tint = text.copy(alpha = if (message.savedToGallery) 0.55f else 0.85f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A voice message: a round button, a bar that fills while it plays, and its
 * length. Tapping the bubble plays or pauses; that is wired where the bubble is.
 */
@Composable
private fun VoiceContent(
    message: MessageEntity,
    file: Attachment,
    playing: VoiceState?,
    step: FileStep,
    fraction: Float?,
    text: Color,
    quiet: Color,
) {
    Row(
        modifier = Modifier.width(220.dp).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(42.dp).clip(CircleShape).background(text.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                step == FileStep.Working -> CircularProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = text,
                    trackColor = text.copy(alpha = 0.2f),
                )
                step == FileStep.Retry -> Icon(MaunIcons.Retry, contentDescription = null, tint = text)
                step == FileStep.Gone -> Icon(MaunIcons.Mic, contentDescription = null, tint = quiet)
                playing?.playing == true -> Icon(MaunIcons.Pause, contentDescription = "Pause", tint = text)
                else -> Icon(MaunIcons.Play, contentDescription = "Play voice message", tint = text)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LinearProgressIndicator(
                progress = { playing?.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
                color = text,
                trackColor = text.copy(alpha = 0.2f),
            )
            Text(
                text = when (step) {
                    FileStep.Working -> if (file.key.isEmpty()) "Sending…" else "Fetching…"
                    FileStep.Retry -> if (message.outgoing && file.key.isEmpty()) "Not sent. Tap to try again" else "Tap to try again"
                    FileStep.Gone -> "No longer available"
                    FileStep.Fetch -> formatDuration(file.duration)
                    FileStep.Open ->
                        if (playing != null) "${formatDuration(playing.positionMillis)} / ${formatDuration(file.duration)}"
                        else formatDuration(file.duration)
                },
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
            IconButton(onClick = onSave) { Icon(MaunIcons.Download, contentDescription = "Save to Gallery", tint = Color.White) }
        }
    }
}
