package org.maun.messenger.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.io.File
import java.time.ZoneId
import kotlin.math.ceil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maun.messenger.AppLock
import org.maun.messenger.Attachment
import org.maun.messenger.Messenger
import org.maun.messenger.Payload
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.data.ReceiptLevel
import org.maun.messenger.network.RelayState
import org.maun.messenger.voice.VoiceRecorder

/**
 * One conversation. [messages] is newest first; [hasOlder] says there are
 * more on this phone than were loaded, and [onLoadOlder] asks for them.
 * [contactName] is null while the other person is not a contact: their
 * messages are shown as a request, and nothing can be sent until it is
 * accepted. [files] is what the attachment sheet, camera, and file bubbles work with.
 */
@Composable
fun ChatScreen(
    peerId: String,
    contactName: String?,
    messages: List<MessageEntity>,
    hasOlder: Boolean,
    waiting: String?,
    relay: RelayState,
    onSend: (String) -> Unit,
    onSeen: () -> Unit,
    files: FileActions = FileActions(),
    /** The messages have not been read from storage yet; say nothing about them until they have. */
    loading: Boolean = false,
    /** What someone who is not a contact calls themselves on this relay, if it has usernames. */
    peerUsername: String? = null,
    /** Starts a call with this contact, a video call when given true. Null where calls cannot be made. */
    onCall: ((Boolean) -> Unit)? = null,
    /** The contact's username on the relay, where there is one. */
    contactUsername: String? = null,
    /** Show the soft subtle pattern behind the chat (off = solid background). */
    chatPattern: Boolean = true,
    onLoadOlder: () -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
    onAccept: (String) -> Unit,
    onBlock: () -> Unit,
) {
    val isContact = contactName != null
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val newest = messages.firstOrNull()
    val zone = remember { ZoneId.systemDefault() }
    val items = remember(messages, zone) { chatItems(messages, zone) }
    val today = dayOf(System.currentTimeMillis(), zone)
    var deleting by remember { mutableStateOf<MessageEntity?>(null) }
    var showAttachSheet by rememberSaveable { mutableStateOf(false) }

    val currentMessages by rememberUpdatedState(messages)
    val fileOf = { seq: Long ->
        currentMessages.firstOrNull { it.seq == seq && it.kind == MessageKind.FILE }?.let { Attachment.fromLocal(it.text) }
    }

    // Gallery, Document, and Quick Camera launchers.
    val pickGallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        AppLock.externalFlow = false
        if (uri != null) files.onAttach(uri)
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        AppLock.externalFlow = false
        if (uri != null) files.onAttach(uri)
    }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        AppLock.externalFlow = false
        val savedUri = pendingCameraUri
        pendingCameraUri = null
        if (taken && savedUri != null) {
            files.onAttach(Uri.parse(savedUri))
        } else if (savedUri != null) {
            runCatching { context.contentResolver.delete(Uri.parse(savedUri), null, null) }
        }
    }

    fun launchCameraCapture() {
        try {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val photoFile = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            pendingCameraUri = uri.toString()
            AppLock.externalFlow = true
            takePicture.launch(uri)
        } catch (_: Exception) {
            AppLock.externalFlow = false
            pendingCameraUri = null
            Toast.makeText(context, "The camera could not be opened on this phone.", Toast.LENGTH_SHORT).show()
        }
    }

    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            Toast.makeText(context, "Maun needs camera access to take a photo.", Toast.LENGTH_SHORT).show()
        }
    }

    val triggerCamera = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchCameraCapture() else askForCamera.launch(Manifest.permission.CAMERA)
    }

    val save = { seq: Long, file: Attachment ->
        files.onSave(seq, file)
    }

    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(files.problem) {
        val problem = files.problem ?: return@LaunchedEffect
        Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
        files.onProblemShown()
    }
    LaunchedEffect(files.notice) {
        val notice = files.notice ?: return@LaunchedEffect
        Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
        files.onNoticeShown()
    }

    // Keep the newest message in view: always for one's own, and for an
    // incoming one unless the reader has scrolled back to look at history.
    LaunchedEffect(newest?.seq) {
        if (newest != null && (newest.outgoing || listState.firstVisibleItemIndex <= 1)) {
            listState.scrollToItem(0)
        }
    }

    // A message counts as read only while this screen is really in front.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inFront = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val unseen = messages.any { !it.outgoing && (it.unread || (isContact && it.receipt < ReceiptLevel.READ)) }
    LaunchedEffect(inFront, unseen, newest?.seq) {
        if (inFront && unseen) onSeen()
    }

    // The list is upside down: its last items are the oldest messages. When
    // the reader gets near them, the next page of history is loaded.
    val nearOldest by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= info.totalItemsCount - LOAD_AHEAD
        }
    }
    LaunchedEffect(nearOldest, hasOlder, messages.size) {
        if (nearOldest && hasOlder) onLoadOlder()
    }
    val scrolledBack by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    val chatColors = LocalChatColors.current

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                ChatHeader(
                    peerId = peerId,
                    name = contactName ?: (contactUsername ?: peerUsername)?.let { "@$it" },
                    onBack = onBack,
                    onOpenDetails = if (isContact) onOpenDetails else null,
                    onCall = if (isContact) onCall else null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    ChatWallpaper(
                        patterned = chatPattern,
                        baseColor = chatColors.wallpaper,
                        patternTint = chatColors.patternTint,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                state = listState,
                                reverseLayout = true,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                items(items, key = { it.key }, contentType = { it is DayItem }) { item ->
                                    Box(modifier = Modifier.animateItem(fadeInSpec = tween(160), fadeOutSpec = tween(120))) {
                                        when (item) {
                                            is DayItem -> Pill(dayLabel(item.day, today))
                                            is MessageItem -> if (item.message.kind == MessageKind.NOTICE) {
                                                Pill(item.message.text)
                                            } else {
                                                MessageRow(
                                                    item = item,
                                                    online = relay == RelayState.Online,
                                                    stuck = waiting != null,
                                                    onCopy = { copyMessage(context, it) },
                                                    onDelete = { deleting = it },
                                                    files = files,
                                                    onOpenFile = { seq, file ->
                                                        when {
                                                            file.isVoice -> files.onPlay(seq, file)
                                                            file.isImage -> viewing = seq
                                                            else -> save(seq, file)
                                                        }
                                                    },
                                                    onSaveFile = save,
                                                )
                                            }
                                        }
                                    }
                                }
                                // Above the oldest message that is loaded.
                                item(key = "top", contentType = "top") {
                                    if (!loading) {
                                        if (hasOlder) {
                                            Pill("Loading earlier messages…")
                                        } else {
                                            Pill(
                                                text = "Messages are encrypted on this phone and can only be read on " +
                                                    "theirs. The relay that carries them cannot read them.",
                                                locked = true,
                                            )
                                        }
                                    }
                                }
                            }
                            Crossfade(
                                targetState = loading,
                                animationSpec = tween(180),
                                modifier = Modifier.align(Alignment.Center),
                                label = "conversation loading",
                            ) { isLoading ->
                                if (isLoading) Pill("Opening conversation…")
                            }
                            if (scrolledBack) {
                                SmallFloatingActionButton(
                                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                                    containerColor = chatColors.incomingBubble,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ) { Icon(MaunIcons.Down, contentDescription = "Go to the newest message") }
                            }
                        }

                        if (waiting != null) Notice(waiting)
                        if (isContact) {
                            Composer(
                                peerId = peerId,
                                onSend = onSend,
                                onAttach = { showAttachSheet = true },
                                onCamera = triggerCamera,
                                onVoice = files.onVoice,
                            )
                        } else {
                            RequestBar(peerId = peerId, onAccept = onAccept, onBlock = onBlock)
                        }
                    }
                }
            }
        }
    }

    if (showAttachSheet) {
        AttachmentSheet(
            onDismiss = { showAttachSheet = false },
            onGallery = {
                showAttachSheet = false
                try {
                    AppLock.externalFlow = true
                    pickGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                } catch (_: Exception) {
                    AppLock.externalFlow = false
                    Toast.makeText(context, "Could not open Gallery.", Toast.LENGTH_SHORT).show()
                }
            },
            onCamera = {
                showAttachSheet = false
                triggerCamera()
            },
            onDocument = {
                showAttachSheet = false
                try {
                    AppLock.externalFlow = true
                    pickDocument.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    AppLock.externalFlow = false
                    Toast.makeText(context, "Could not open file picker.", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    viewing?.let { seq ->
        val file = fileOf(seq)
        if (file == null) {
            viewing = null
        } else {
            ImageViewer(seq = seq, file = file, files = files, onSave = { save(seq, file) }, onClose = { viewing = null })
        }
    }

    deleting?.let { message ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this message?") },
            text = {
                Text(
                    when {
                        !message.outgoing -> "It is deleted from this phone only."
                        message.status == MessageStatus.PENDING -> "It has not been sent yet, and now it will not be."
                        else -> "It is deleted from this phone only. ${contactName ?: "The other person"} keeps their copy."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(message.seq)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } },
        )
    }
}

/** How close to the oldest loaded message the reader must be before more are fetched, in list items. */
private const val LOAD_AHEAD = 12

/**
 * Soft, calm wallpaper behind the conversation: either a subtle staggered
 * motif pattern or a clean solid surface depending on the user's Settings choice.
 */
@Composable
private fun ChatWallpaper(
    patterned: Boolean,
    baseColor: Color,
    patternTint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(baseColor)
            .then(
                if (!patterned) Modifier
                else Modifier.drawWithCache {
                    val cell = 96.dp.toPx()
                    val stroke = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val rSmall = 3.dp.toPx()
                    val rMed = 6.dp.toPx()
                    val bubbleW = 14.dp.toPx()
                    val bubbleH = 10.dp.toPx()
                    val corner = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx())
                    val diamondPath = Path()
                    onDrawBehind {
                        val cols = (size.width / cell).toInt() + 2
                        val rows = (size.height / cell).toInt() + 2
                        for (row in 0 until rows) {
                            val shift = if (row % 2 == 1) cell * 0.5f else 0f
                            val cy = row * cell
                            for (col in -1 until cols) {
                                val cx = col * cell + shift
                                // Tiny rounded chat bubble outline
                                drawRoundRect(
                                    color = patternTint,
                                    topLeft = Offset(cx + cell * 0.14f, cy + cell * 0.16f),
                                    size = Size(bubbleW, bubbleH),
                                    cornerRadius = corner,
                                    style = stroke,
                                )
                                // Soft circle ring
                                drawCircle(
                                    color = patternTint,
                                    radius = rMed,
                                    center = Offset(cx + cell * 0.68f, cy + cell * 0.26f),
                                    style = stroke,
                                )
                                // Four-point star / sparkle
                                val sx = cx + cell * 0.34f
                                val sy = cy + cell * 0.66f
                                diamondPath.reset()
                                diamondPath.moveTo(sx, sy - rMed)
                                diamondPath.lineTo(sx + rSmall * 0.65f, sy - rSmall * 0.65f)
                                diamondPath.lineTo(sx + rMed, sy)
                                diamondPath.lineTo(sx + rSmall * 0.65f, sy + rSmall * 0.65f)
                                diamondPath.lineTo(sx, sy + rMed)
                                diamondPath.lineTo(sx - rSmall * 0.65f, sy + rSmall * 0.65f)
                                diamondPath.lineTo(sx - rMed, sy)
                                diamondPath.lineTo(sx - rSmall * 0.65f, sy - rSmall * 0.65f)
                                diamondPath.close()
                                drawPath(diamondPath, color = patternTint, style = stroke)

                                // Tiny paper-plane / leaf chevron + soft dot
                                val lx = cx + cell * 0.76f
                                val ly = cy + cell * 0.74f
                                drawLine(
                                    color = patternTint,
                                    start = Offset(lx - rMed, ly + rSmall),
                                    end = Offset(lx + rMed, ly - rSmall),
                                    strokeWidth = stroke.width,
                                    cap = StrokeCap.Round,
                                )
                                drawCircle(
                                    color = patternTint,
                                    radius = 1.6.dp.toPx(),
                                    center = Offset(cx + cell * 0.50f, cy + cell * 0.44f),
                                )
                            }
                        }
                    }
                },
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentSheet(
    onDismiss: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onDocument: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AttachmentChoice(
                    icon = MaunIcons.Image,
                    label = "Gallery",
                    tint = Color(0xFF1F7A58),
                    container = Color(0xFFD5F0E3),
                    onClick = onGallery,
                )
                AttachmentChoice(
                    icon = MaunIcons.Camera,
                    label = "Camera",
                    tint = Color(0xFF9C3D54),
                    container = Color(0xFFF8DCE3),
                    onClick = onCamera,
                )
                AttachmentChoice(
                    icon = MaunIcons.File,
                    label = "Document",
                    tint = Color(0xFF2E5C8A),
                    container = Color(0xFFD9E8F7),
                    onClick = onDocument,
                )
            }
            Text(
                text = "Up to 25 MB per file · Encrypted end-to-end",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AttachmentChoice(
    icon: ImageVector,
    label: String,
    tint: Color,
    container: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Surface(
            color = container,
            shape = CircleShape,
            shadowElevation = 1.dp,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChatHeader(
    peerId: String,
    name: String?,
    onBack: () -> Unit,
    onOpenDetails: (() -> Unit)?,
    onCall: ((Boolean) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(MaunIcons.Back, contentDescription = "Back") }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = onOpenDetails != null, onClickLabel = "Contact details") { onOpenDetails?.invoke() }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(id = peerId, name = name, size = 40.dp)
            Column {
                Text(
                    text = name ?: shortId(peerId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = if (name == null) FontFamily.Monospace else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onCall != null) {
            val call = rememberCallStarter(onCall)
            IconButton(onClick = { call(true) }) { Icon(MaunIcons.Videocam, contentDescription = "Video call") }
            IconButton(onClick = { call(false) }) { Icon(MaunIcons.Call, contentDescription = "Voice call") }
        } else if (onOpenDetails != null) {
            IconButton(onClick = onOpenDetails) { Icon(MaunIcons.Info, contentDescription = "Contact details") }
        }
    }
}

/** A small centred label between messages: the day, or a note about the conversation. */
@Composable
private fun Pill(text: String, locked: Boolean = false) {
    val chatColors = LocalChatColors.current
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = chatColors.incomingBubble.copy(alpha = 0.92f),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 0.5.dp,
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (locked) {
                    Icon(
                        MaunIcons.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MessageRow(
    item: MessageItem,
    online: Boolean,
    stuck: Boolean,
    onCopy: (String) -> Unit,
    onDelete: (MessageEntity) -> Unit,
    files: FileActions = FileActions(),
    onOpenFile: (Long, Attachment) -> Unit = { _, _ -> },
    onSaveFile: (Long, Attachment) -> Unit = { _, _ -> },
) {
    val message = item.message
    val outgoing = message.outgoing
    val file = if (message.kind == MessageKind.FILE) remember(message.text) { Attachment.fromLocal(message.text) } else null
    val fileStep = file?.let { fileStep(it, outgoing, active = message.seq in files.progress) }
    val onTap: (() -> Unit)? = when (fileStep) {
        null, FileStep.Working, FileStep.Gone -> null
        FileStep.Open -> ({ onOpenFile(message.seq, file) })
        FileStep.Fetch, FileStep.Retry -> ({ files.onStart(message.seq) })
    }
    val readable = message.kind == MessageKind.TEXT
    val imageBubble = file != null && fileStep != null && isPictureBubble(message, file, fileStep)
    val chatColors = LocalChatColors.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }

    val round = 16.dp
    val tight = 4.dp
    val joint = if (item.startsRun) round else tight
    val shape = if (outgoing) {
        RoundedCornerShape(topStart = round, topEnd = joint, bottomEnd = tight, bottomStart = round)
    } else {
        RoundedCornerShape(topStart = joint, topEnd = round, bottomEnd = round, bottomStart = tight)
    }
    val innerImageRound = 13.5.dp
    val innerImageTight = 2.5.dp
    val innerImageJoint = if (item.startsRun) innerImageRound else innerImageTight
    val innerImageShape = if (outgoing) {
        RoundedCornerShape(
            topStart = innerImageRound,
            topEnd = innerImageJoint,
            bottomEnd = innerImageTight,
            bottomStart = innerImageRound,
        )
    } else {
        RoundedCornerShape(
            topStart = innerImageJoint,
            topEnd = innerImageRound,
            bottomEnd = innerImageRound,
            bottomStart = innerImageTight,
        )
    }

    val bubbleColor = if (outgoing) chatColors.outgoingBubble else chatColors.incomingBubble
    val text = if (outgoing) chatColors.outgoingContent else chatColors.incomingContent
    val quiet = if (outgoing) chatColors.outgoingMeta else chatColors.incomingMeta

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (item.startsRun) 6.dp else 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(0.85f),
            contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box {
                Surface(
                    color = bubbleColor,
                    shape = shape,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .pointerInput(message.seq, fileStep) {
                            detectTapGestures(
                                onTap = onTap?.let { tap -> { tap() } },
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menu = true
                                },
                            )
                        }
                        .semantics {
                            onLongClick(label = "Copy or delete") {
                                menu = true
                                true
                            }
                        },
                ) {
                    when {
                        imageBubble -> {
                            // Minimal 2.5dp frame around images, with timestamp + ticks overlaid bottom-right.
                            Box(modifier = Modifier.padding(2.5.dp)) {
                                FileContent(
                                    message = message,
                                    file = file,
                                    files = files,
                                    text = text,
                                    quiet = quiet,
                                    imageShape = innerImageShape,
                                    imageOverlay = {
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(6.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color.Black.copy(alpha = 0.54f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = formatTime(message.at),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                            )
                                            if (outgoing) {
                                                Spacer(modifier = Modifier.width(3.dp))
                                                StatusTick(
                                                    status = message.status,
                                                    online = online,
                                                    stuck = stuck,
                                                    tint = Color.White.copy(alpha = 0.92f),
                                                    readTint = Color(0xFF6CC3F5),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        readable -> {
                            InlineMessageBubble(
                                text = message.text,
                                textColor = text,
                                modifier = Modifier.padding(start = 11.dp, end = 9.dp, top = 6.dp, bottom = 5.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = formatTime(message.at),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = quiet,
                                    )
                                    if (outgoing) {
                                        Spacer(modifier = Modifier.width(3.dp))
                                        StatusTick(message.status, online, stuck, quiet)
                                    }
                                }
                            }
                        }

                        else -> {
                            Column(modifier = Modifier.padding(start = 11.dp, end = 10.dp, top = 7.dp, bottom = 5.dp)) {
                                if (file != null) {
                                    FileContent(message, file, files, text, quiet)
                                } else {
                                    Text(
                                        text = "A message arrived that this phone could not decrypt.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontStyle = FontStyle.Italic,
                                        color = quiet,
                                    )
                                }
                                Row(
                                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = formatTime(message.at),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = quiet,
                                    )
                                    if (outgoing) {
                                        Spacer(modifier = Modifier.width(3.dp))
                                        StatusTick(message.status, online, stuck, quiet)
                                    }
                                }
                            }
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (readable) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            leadingIcon = { Icon(MaunIcons.Copy, contentDescription = null) },
                            onClick = {
                                menu = false
                                onCopy(message.text)
                            },
                        )
                    }
                    if (file != null && fileStep == FileStep.Open && !file.isVoice) {
                        DropdownMenuItem(
                            text = { Text(if (file.isGalleryMedia) "Save to Gallery" else "Save") },
                            leadingIcon = { Icon(MaunIcons.Download, contentDescription = null) },
                            onClick = {
                                menu = false
                                onSaveFile(message.seq, file)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(MaunIcons.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onDelete(message)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Places message text and its [meta] (timestamp + read ticks) on the same line
 * whenever there is room at the end of the last line (WhatsApp-style compact
 * bubble), only wrapping onto an extra bottom-right line when the last line is full.
 */
@Composable
private fun InlineMessageBubble(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    meta: @Composable () -> Unit,
) {
    var layoutResult by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Layout(
        modifier = modifier,
        content = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
                onTextLayout = { layoutResult = it },
            )
            meta()
        },
    ) { measurables, constraints ->
        val maxW = constraints.maxWidth
        val textPlaceable = measurables[0].measure(Constraints(minWidth = 0, maxWidth = maxW))
        val metaPlaceable = measurables[1].measure(Constraints(minWidth = 0, maxWidth = maxW))
        val gap = 8.dp.roundToPx()
        val extraRowGap = 2.dp.roundToPx()

        val result = layoutResult
        val lastLine = (result?.lineCount ?: 1) - 1
        val isLtr = result == null || lastLine < 0 ||
            result.getParagraphDirection(lastLine) == ResolvedTextDirection.Ltr
        val lastLineEnd = if (result != null && lastLine >= 0 && isLtr) {
            ceil(result.getLineRight(lastLine)).toInt()
        } else {
            textPlaceable.width
        }

        if (isLtr && lastLineEnd + gap + metaPlaceable.width <= maxW) {
            // Fits inline at the bottom-right of the same / last line!
            val width = maxOf(textPlaceable.width, lastLineEnd + gap + metaPlaceable.width)
            val height = if ((result?.lineCount ?: 1) <= 1) {
                maxOf(textPlaceable.height, metaPlaceable.height)
            } else {
                val lastTop = result?.getLineTop(lastLine)?.toInt() ?: 0
                maxOf(textPlaceable.height, lastTop + metaPlaceable.height)
            }
            layout(width, height) {
                textPlaceable.placeRelative(0, 0)
                metaPlaceable.placeRelative(width - metaPlaceable.width, height - metaPlaceable.height)
            }
        } else {
            // Last line fills the available width (or RTL): place timestamp neatly bottom-right below.
            val width = maxOf(textPlaceable.width, metaPlaceable.width)
            val height = textPlaceable.height + extraRowGap + metaPlaceable.height
            layout(width, height) {
                textPlaceable.placeRelative(0, 0)
                metaPlaceable.placeRelative(width - metaPlaceable.width, textPlaceable.height + extraRowGap)
            }
        }
    }
}

/**
 * How far one's own message has got, as the mark every messenger uses: a
 * clock while it waits on this phone, one tick once the relay has it, two
 * when it has reached their phone, and two coloured ones when it was read.
 */
@Composable
fun StatusTick(
    status: Int,
    online: Boolean,
    stuck: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    readTint: Color = LocalStatusColors.current.read,
) {
    val icon = when {
        status >= MessageStatus.DELIVERED -> MaunIcons.CheckAll
        status == MessageStatus.SENT -> MaunIcons.Check
        else -> MaunIcons.Clock
    }
    val color = when {
        status >= MessageStatus.READ -> readTint
        status < MessageStatus.SENT && stuck -> LocalStatusColors.current.warning
        else -> tint
    }
    Icon(
        imageVector = icon,
        contentDescription = statusLabel(status, online, stuck),
        tint = color,
        modifier = modifier.size(15.dp),
    )
}

private fun copyMessage(context: Context, text: String) {
    val clip = ClipData.newPlainText("Message", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Not delivered yet. $text",
            style = MaterialTheme.typography.bodySmall,
            color = LocalStatusColors.current.warning,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * WhatsApp-style bottom composer:
 * A quiet, compact message field with attachments, camera, and a send/voice action.
 */
@Composable
private fun Composer(
    peerId: String,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onCamera: () -> Unit,
    onVoice: (File, Long) -> Unit,
) {
    var draft by rememberSaveable(peerId) { mutableStateOf("") }
    val bytes = draft.trim().toByteArray(Charsets.UTF_8).size
    val tooLong = bytes > Payload.MAX_TEXT_BYTES
    val chatColors = LocalChatColors.current

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val recorder = remember { VoiceRecorder(context) }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }
    var recording by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }
    var hint by remember { mutableStateOf<String?>(null) }
    val currentOnVoice by rememberUpdatedState(onVoice)

    fun begin() {
        if (recorder.start()) {
            levels.clear()
            elapsed = 0
            paused = false
            recording = true
        } else {
            hint = "The microphone could not be started. Is a call using it?"
        }
    }
    fun send() {
        val done = recorder.stop()
        recording = false
        if (done == null) hint = "That was too short to send." else currentOnVoice(done.file, done.millis)
    }
    val askForMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else hint = "Maun needs the microphone to record a voice message."
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(RECORDING_TICK_MILLIS)
            elapsed = recorder.elapsedMillis
            if (!recorder.paused) {
                levels.add(recorder.level())
                if (levels.size > RECORDING_BARS) levels.removeAt(0)
            }
            if (elapsed >= Attachment.MAX_VOICE_MILLIS) send()
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(4000)
            hint = null
        }
    }
    BackHandler(enabled = recording) {
        recorder.cancel()
        recording = false
    }

    if (recording) {
        Surface(color = chatColors.composerPill, shadowElevation = 2.dp) {
            RecordingBar(
                elapsedMillis = elapsed,
                levels = levels,
                paused = paused,
                onDelete = {
                    recorder.cancel()
                    recording = false
                },
                onPause = {
                    if (paused) recorder.resume() else recorder.pause()
                    paused = recorder.paused
                },
                onSend = ::send,
            )
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
        val note = if (tooLong) "Too long for one message. Shorten it or send it in parts." else hint
        if (note != null) {
            Surface(
                color = chatColors.composerPill.copy(alpha = 0.95f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
            ) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // A simple message field; emoji remain available on the device keyboard.
            Surface(
                color = chatColors.composerPill,
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 1.dp,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp)
                            .padding(vertical = 9.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            maxLines = 5,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                    }

                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            onAttach()
                        },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = MaunIcons.Attach,
                            contentDescription = "Send a file",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    IconButton(
                        onClick = onCamera,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = MaunIcons.Camera,
                            contentDescription = "Take a photo",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // Circular green Mic / Send button to the right of the pill.
            val buttonColors = IconButtonDefaults.filledIconButtonColors(
                containerColor = chatColors.actionButton,
                contentColor = chatColors.onActionButton,
                disabledContainerColor = chatColors.actionButton.copy(alpha = 0.45f),
                disabledContentColor = chatColors.onActionButton.copy(alpha = 0.7f),
            )
            Surface(
                shape = CircleShape,
                color = Color.Transparent,
                shadowElevation = 2.dp,
            ) {
                if (bytes > 0) {
                    FilledIconButton(
                        onClick = {
                            onSend(draft)
                            draft = ""
                        },
                        enabled = !tooLong,
                        shape = CircleShape,
                        colors = buttonColors,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(MaunIcons.Send, contentDescription = "Send") }
                } else {
                    FilledIconButton(
                        onClick = {
                            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (allowed) begin() else askForMic.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        shape = CircleShape,
                        colors = buttonColors,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(MaunIcons.Mic, contentDescription = "Record a voice message") }
                }
            }
        }
    }
}

private const val RECORDING_TICK_MILLIS = 100L
private const val RECORDING_BARS = 44

/**
 * What stands in place of the message field while a voice message is being
 * recorded: how long it is so far and how loud it has been, and under that
 * the three things that can be done with it: throw it away, pause it, send it.
 */
@Composable
fun RecordingBar(
    elapsedMillis: Long,
    levels: List<Float>,
    paused: Boolean,
    onDelete: () -> Unit,
    onPause: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chatColors = LocalChatColors.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = formatDuration(elapsedMillis),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                modifier = Modifier.semantics { contentDescription = "Recording, ${formatDuration(elapsedMillis)}" },
            )
            val bar = if (paused) colors.outline else chatColors.actionButton
            Canvas(modifier = Modifier.weight(1f).height(32.dp)) {
                val width = 3.dp.toPx()
                val step = width + 2.dp.toPx()
                var x = size.width - width
                for (i in levels.indices.reversed()) {
                    if (x < 0) break
                    val height = (kotlin.math.sqrt(levels[i]) * size.height).coerceIn(width, size.height)
                    drawRoundRect(
                        color = bar,
                        topLeft = Offset(x, (size.height - height) / 2),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(width / 2),
                    )
                    x -= step
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledIconButton(
                onClick = onDelete,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.errorContainer,
                    contentColor = colors.onErrorContainer,
                ),
                modifier = Modifier.size(52.dp),
            ) { Icon(MaunIcons.Delete, contentDescription = "Delete the recording") }
            Surface(
                onClick = onPause,
                color = colors.surfaceContainerHigh,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (paused) MaunIcons.Mic else MaunIcons.Pause, contentDescription = null, tint = colors.onSurface)
                    Text(if (paused) "Resume" else "Pause", style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                }
            }
            FilledIconButton(
                onClick = onSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = chatColors.actionButton,
                    contentColor = chatColors.onActionButton,
                ),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(MaunIcons.Send, contentDescription = "Send the voice message")
            }
        }
    }
}

@Composable
private fun RequestBar(peerId: String, onAccept: (String) -> Unit, onBlock: () -> Unit) {
    var naming by rememberSaveable(peerId) { mutableStateOf(false) }
    var confirmingBlock by rememberSaveable(peerId) { mutableStateOf(false) }
    var name by rememberSaveable(peerId) { mutableStateOf("") }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "This person is not in your contacts. They cannot tell whether their message " +
                    "arrived or was read unless you accept. Accept only if you know who this is.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { naming = true }, modifier = Modifier.weight(1f)) { Text("Accept") }
                OutlinedButton(onClick = { confirmingBlock = true }, modifier = Modifier.weight(1f)) { Text("Block") }
            }
        }
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Add as a contact") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Give them a name. It is stored on this phone only.")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(Messenger.MAX_NAME_LENGTH) },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        naming = false
                        onAccept(name.trim())
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Accept") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }

    if (confirmingBlock) {
        BlockDialog(
            who = "this person",
            onConfirm = {
                confirmingBlock = false
                onBlock()
            },
            onDismiss = { confirmingBlock = false },
        )
    }
}

@Composable
fun BlockDialog(who: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block $who?") },
        text = {
            Text(
                "The conversation is deleted from this phone, and anything they send from now on " +
                    "is thrown away unread. They are not told. You can unblock them later.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Block") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatPreview() {
    val peer = "ab12".repeat(16)
    MaunTheme {
        ChatScreen(
            peerId = peer,
            contactName = "Asha",
            messages = listOf(
                MessageEntity(seq = 5, peer = peer, id = "5", outgoing = true, text = "See you at six", at = 0),
                MessageEntity(seq = 4, peer = peer, id = "4", outgoing = true, text = "Yes!", at = 0, status = MessageStatus.SENT),
                MessageEntity(seq = 3, peer = peer, id = "3", outgoing = true, text = "I am", at = 0, status = MessageStatus.DELIVERED),
                MessageEntity(seq = 2, peer = peer, id = "2", outgoing = false, text = "Are we still on for today?", at = 0),
                MessageEntity(seq = 1, peer = peer, id = "1", outgoing = true, text = "Hello", at = 0, status = MessageStatus.READ),
            ),
            hasOlder = false,
            waiting = null,
            relay = RelayState.Online,
            onSend = {},
            onSeen = {},
            onLoadOlder = {},
            onDelete = {},
            onBack = {},
            onOpenDetails = {},
            onAccept = {},
            onBlock = {},
        )
    }
}
