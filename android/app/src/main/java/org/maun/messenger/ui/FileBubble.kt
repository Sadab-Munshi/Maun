package org.maun.messenger.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay
import org.maun.messenger.Attachment
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.voice.VoiceState

/**
 * What a conversation needs in order to show and move files. The defaults do
 * nothing, for previews and for screens drawn without the app behind them.
 *
 * [equals] says what a row would draw differently, so Compose can leave a row
 * alone when nothing it shows has changed. The callbacks are left out on
 * purpose: they are made again on every recomposition of the screen, and
 * comparing them — by identity — would make every row in a long conversation
 * redraw itself for the mere act of being recomposed above, which is what made
 * scrolling back through old messages heavy.
 */
@Immutable
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
    /** Hand the file of a message to another app on this phone, read-only and for a while. */
    val onShare: (Long, Attachment) -> Unit = { _, _ -> },
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
) {
    override fun equals(other: Any?): Boolean = other is FileActions &&
        other.progress == progress &&
        other.problem == problem &&
        other.notice == notice &&
        other.voice == voice

    override fun hashCode(): Int = listOf(progress, problem, notice, voice).hashCode()
}

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

/**
 * Whether a file's bubble offers to save it. Only a file that arrived, is on
 * this phone, is not a voice message, and is not already in Gallery or
 * Downloads: there is nothing to save of one's own file, and nothing more to
 * save of one that has been saved.
 */
internal fun fileNeedsSaving(message: MessageEntity, file: Attachment, step: FileStep): Boolean =
    step == FileStep.Open && !message.outgoing && !message.savedToGallery && !file.isVoice

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
        // A file that is here says only what it is: a note about where a copy
        // went is not part of the file, and the menu is where saving lives.
        FileStep.Open -> size
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
        // One small button with the three things a file can be given to: the
        // app that reads it, this phone, or someone else. It is the same on a
        // file one sent and a file that arrived; a file that is already in
        // Gallery or Downloads is only saved again when it is asked for.
        if (step == FileStep.Open && !file.isVoice) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        imageVector = MaunIcons.More,
                        contentDescription = "Open, save or share ${file.name}",
                        tint = text.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        leadingIcon = { Icon(MaunIcons.File, contentDescription = null) },
                        onClick = {
                            menu = false
                            files.onOpen(message.seq, file)
                        },
                    )
                    if (fileNeedsSaving(message, file, step)) {
                        DropdownMenuItem(
                            text = { Text("Save to device") },
                            leadingIcon = { Icon(MaunIcons.Download, contentDescription = null) },
                            onClick = {
                                menu = false
                                files.onSave(message.seq, file)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(MaunIcons.Share, contentDescription = null) },
                        onClick = {
                            menu = false
                            files.onShare(message.seq, file)
                        },
                    )
                }
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

/**
 * A picture, as large as the screen allows, over everything else.
 *
 * It is shown in a window of its own rather than laid over the conversation,
 * which is what keeps the conversation from being touched: Android sends a
 * touch to the topmost window and nowhere else, so no call button under the
 * picture can be pressed, no message opened and nothing scrolled by accident,
 * however the picture is tapped, held or dragged.
 */
@Composable
fun ImageViewer(
    seq: Long,
    file: Attachment,
    files: FileActions,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit = {},
) {
    val picture by produceState<Bitmap?>(null, seq) { value = files.loadImage(seq, file, FULL_PIXELS) }
    // Pinch to look closer, two fingers to move about, and a double tap to put
    // it back. Everything else about the picture is still only the picture:
    // this is a window of its own, so the conversation underneath is deaf to
    // the fingers on it.
    var scale by remember(seq) { mutableFloatStateOf(1f) }
    var moved by remember(seq) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        moved = if (scale > 1f) moved + panChange else Offset.Zero
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val shown = picture
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = moved.x
                            translationY = moved.y
                        }
                        .pointerInput(seq) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1f) {
                                        scale = 1f
                                        moved = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        }
                        .transformable(transform),
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
                IconButton(onClick = onShare) {
                    Icon(MaunIcons.Share, contentDescription = "Share this picture", tint = Color.White)
                }
                IconButton(onClick = onSave) {
                    Icon(MaunIcons.Download, contentDescription = "Save to this phone", tint = Color.White)
                }
            }
        }
    }
}
