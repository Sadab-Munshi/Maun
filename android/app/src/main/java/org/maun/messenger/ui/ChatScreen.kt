package org.maun.messenger.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.maun.messenger.data.Reactions
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
    /** Sends the text, answering the message whose ID is given when there is one. */
    onSend: (text: String, replyTo: String?) -> Unit,
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
    /** The relay says this account carries Maun's administrators' tick. */
    peerVerified: Boolean = false,
    /** What each message carries, by message ID: an emoji from each side. */
    reactions: Map<String, Reactions> = emptyMap(),
    /** Puts an emoji on a message, or takes it off when the emoji is empty. */
    onReact: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    /** Who a message can be forwarded to. */
    contacts: List<org.maun.messenger.data.ContactEntity> = emptyList(),
    /** Forwards the texts to [peer], in the order they were written. */
    onForward: (peer: String, texts: List<String>) -> Unit = { _, _ -> },
    /** Messages picked out already: for a screenshot of the selection look, and empty otherwise. */
    startSelected: Set<Long> = emptySet(),
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
    var deleting by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    // What was long-pressed, or tapped while something is: the bar below does
    // for one message or for many what the old popup did for one.
    // Seeded only when the screen is drawn for a picture of the selection look;
    // in the app itself nothing is selected until a finger says so.
    var selection by remember(peerId) { mutableStateOf(startSelected) }
    // The message the composer is answering, until it is sent or put down.
    var replying by remember(peerId) { mutableStateOf<MessageEntity?>(null) }
    // What the forward picker would send, until it is sent or put down.
    var forwarding by remember(peerId) { mutableStateOf<List<MessageEntity>>(emptyList()) }
    // The quotes are drawn from this phone's own copy of the conversation.
    val byId = remember(messages) { messages.associateBy { it.id } }
    var showAttachSheet by rememberSaveable { mutableStateOf(false) }

    val currentMessages by rememberUpdatedState(messages)
    val fileOf = { seq: Long ->
        currentMessages.firstOrNull { it.seq == seq && it.kind == MessageKind.FILE }?.let { Attachment.fromLocal(it.text) }
    }

    // Gallery, Document, and Quick Camera launchers. Several pictures or
    // documents can be picked at once; they are sent one after the other.
    val pickGallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_FILES_AT_ONCE),
    ) { uris ->
        AppLock.externalFlow = false
        if (uris.isNotEmpty()) files.onAttachMany(uris)
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        AppLock.externalFlow = false
        if (uris.isNotEmpty()) files.onAttachMany(uris)
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

    // A conversation opens at its newest messages, and stays there while the
    // first page is read from storage. Nothing is scrolled to and nothing has to
    // arrive first, so there is no jump to flicker and no race to lose: the list
    // is drawn upside down, so its newest message is item 0.
    LaunchedEffect(peerId, loading) {
        if (!loading) listState.scrollToItem(0)
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

    // What is selected, and what can be done with it. The toolbar at the top
    // and the emoji bar at the message are two faces of the same choice.
    val picked = messages.filter { it.seq in selection }.sortedBy { it.seq }
    val only = picked.singleOrNull()
    val onlyFile = only?.let { message ->
        if (message.kind == MessageKind.FILE) Attachment.fromLocal(message.text) else null
    }
    val copyPicked = {
        copyMessage(context, picked.joinToString("\n") { it.text })
        selection = emptySet()
    }
    val saveOnly = {
        if (only != null && onlyFile != null) files.onSave(only.seq, onlyFile)
        selection = emptySet()
    }
    val shareOnly = {
        if (only != null && onlyFile != null) files.onShare(only.seq, onlyFile)
        selection = emptySet()
    }
    val replyToOnly = {
        replying = only
        selection = emptySet()
    }
    val forwardPicked = {
        // Only words travel: a file is handed over by Share, which needs no
        // copy of it and no second upload.
        forwarding = picked.filter { it.kind == MessageKind.TEXT }
        selection = emptySet()
    }
    val reactToOnly = { emoji: String ->
        if (only != null) {
            // The same emoji again takes it off.
            val take = reactions[only.id]?.mine == emoji
            onReact(only.id, if (take) "" else emoji)
        }
    }

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                if (picked.isEmpty()) {
                    ChatHeader(
                        peerId = peerId,
                        name = contactName ?: (contactUsername ?: peerUsername)?.let { "@$it" },
                        verified = peerVerified,
                        onBack = onBack,
                        onOpenDetails = if (isContact) onOpenDetails else null,
                        onCall = if (isContact) onCall else null,
                    )
                } else {
                    // Count and actions where the contact's name was: nothing
                    // covers the conversation, and nothing has to wrap.
                    SelectionBar(
                        picked = picked,
                        onCopy = copyPicked,
                        onSave = saveOnly,
                        onDelete = { deleting = picked },
                        onReply = replyToOnly,
                        onForward = forwardPicked,
                        onShare = shareOnly,
                        onClose = { selection = emptySet() },
                    )
                }
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
                                                    selected = item.message.seq in selection,
                                                    selectionActive = selection.isNotEmpty(),
                                                    reactions = reactions[item.message.id],
                                                    quoted = item.message.replyTo?.let { byId[it] },
                                                    reactionBar = only != null && only.seq == item.message.seq,
                                                    onReactEmoji = reactToOnly,
                                                    onTap = { selection = toggle(selection, item.message.seq) },
                                                    onLongPress = {
                                                        // A notice is not a message: nothing to do with it.
                                                        if (item.message.kind != MessageKind.NOTICE) {
                                                            selection = selection + item.message.seq
                                                        }
                                                    },
                                                    files = files,
                                                    onOpenFile = { seq, file ->
                                                        when {
                                                            file.isVoice -> files.onPlay(seq, file)
                                                            file.isImage -> viewing = seq
                                                            // A document, a video, a sound: whichever app reads it.
                                                            else -> files.onOpen(seq, file)
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
                                replyingTo = replying,
                                onCancelReply = { replying = null },
                                onSend = { text ->
                                    onSend(text, replying?.id)
                                    replying = null
                                },
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
            ImageViewer(
                seq = seq,
                file = file,
                files = files,
                onSave = { save(seq, file) },
                onClose = { viewing = null },
                onShare = { files.onShare(seq, file) },
            )
        }
    }

    if (forwarding.isNotEmpty()) {
        ForwardPicker(
            contacts = contacts.filter { it.id != peerId },
            count = forwarding.size,
            onPick = { target ->
                val texts = forwarding.map { it.text }
                forwarding = emptyList()
                onForward(target.id, texts)
                Toast.makeText(context, "Forwarded to ${target.name}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { forwarding = emptyList() },
        )
    }

    if (deleting.isNotEmpty()) {
        val picked = deleting
        AlertDialog(
            onDismissRequest = { deleting = emptyList() },
            title = { Text(if (picked.size == 1) "Delete this message?" else "Delete ${picked.size} messages?") },
            text = {
                Text(
                    if (picked.size > 1) {
                        // One question for the lot, as with one message.
                        val unsent = picked.count { it.outgoing && it.status == MessageStatus.PENDING }
                        "They are deleted from this phone only. " +
                            (if (unsent > 0) "$unsent of them ${if (unsent == 1) "has" else "have"} not been sent, and now will not be. " else "") +
                            "${contactName ?: "The other person"} keeps their copy."
                    } else {
                        val message = picked.first()
                        when {
                            !message.outgoing -> "It is deleted from this phone only."
                            message.status == MessageStatus.PENDING -> "It has not been sent yet, and now it will not be."
                            else -> "It is deleted from this phone only. ${contactName ?: "The other person"} keeps their copy."
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = emptyList()
                    selection = emptySet()
                    picked.forEach { onDelete(it.seq) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = emptyList() }) { Text("Keep") } },
        )
    }
}

/** One line that stands for a message when it is quoted: what it says, or what it is. */
private fun quotePreview(message: MessageEntity): String = when (message.kind) {
    MessageKind.FILE -> Attachment.fromLocal(message.text)?.let { file ->
        when {
            file.isVoice -> "Voice message"
            file.isImage -> "Photo"
            else -> file.name
        }
    } ?: "File"
    MessageKind.UNREADABLE -> "A message that could not be decrypted"
    else -> message.text.replace('\n', ' ').ifBlank { "Message" }
}

/**
 * The quote inside a bubble: who it was, and the first line of it, over the
 * text that answers it. The words come from this phone's own copy of the
 * conversation, so nothing extra travelled to make it appear.
 */
@Composable
private fun InBubbleQuote(quoted: MessageEntity, outgoing: Boolean) {
    val chatColors = LocalChatColors.current
    val accent = if (outgoing) chatColors.outgoingMeta else chatColors.incomingMeta
    Row(
        modifier = Modifier
            .padding(start = 9.dp, end = 9.dp, top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Column(modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp)) {
            Text(
                text = if (quoted.outgoing) "You" else "Them",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            Text(
                text = quotePreview(quoted),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The strip above the field that says what the next message will answer. */
@Composable
private fun QuotedStrip(quoted: MessageEntity, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(38.dp)
                    .background(colors.primary, RoundedCornerShape(2.dp)),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                Text(
                    text = if (quoted.outgoing) "You" else "Reply",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                )
                Text(
                    text = quotePreview(quoted),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(MaunIcons.Close, contentDescription = "Stop replying")
            }
        }
    }
}

/**
 * Asks who a message should go to: the people one talks to, by name, with the
 * one whose conversation this is left out. Nothing is sent until a name is
 * tapped, and the question can be put down without sending anything.
 */
@Composable
internal fun ForwardPicker(
    contacts: List<org.maun.messenger.data.ContactEntity>,
    count: Int,
    onPick: (org.maun.messenger.data.ContactEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Forward to…" else "Forward $count messages to…") },
        text = {
            if (contacts.isEmpty()) {
                Text("There is nobody else to forward to yet.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(contact) }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(id = contact.id, name = contact.name, size = 36.dp)
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Adds or removes one message from the selection. */
private fun toggle(selection: Set<Long>, seq: Long): Set<Long> =
    if (seq in selection) selection - seq else selection + seq

/**
 * What long-pressing a message does, in place of the old popup: the emoji bar
 * for the one message that is selected, and the same Copy, Save and Delete for
 * one or for many. Nothing here acts twice: Delete asks once, for all of them.
 */
@Composable
internal fun SelectionBar(
    picked: List<MessageEntity>,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    /** Answer the one selected message. */
    onReply: () -> Unit = {},
    /** Send the selected words to somebody else. */
    onForward: () -> Unit = {},
    /** Hand the selected file to another app on this phone, read-only. */
    onShare: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val one = picked.singleOrNull()
    val text = one != null && one.kind == MessageKind.TEXT
    val file = one != null && one.kind == MessageKind.FILE
    val words = picked.any { it.kind == MessageKind.TEXT }

    // What can be done, in the order a thumb expects. Icons, not words: the row
    // is as wide as the phone, and a word that does not fit has to wrap or be
    // cut. This is the same strip the conversation's own header occupies, so
    // nothing of the messages is covered while it is up.
    val actions = buildList {
        if (one != null) add(Triple(MaunIcons.Reply, "Reply", onReply))
        if (words) add(Triple(MaunIcons.Forward, "Forward", onForward))
        if (words) add(Triple(MaunIcons.Copy, "Copy", onCopy))
        if (file) add(Triple(MaunIcons.Download, "Save", onSave))
        if (file) add(Triple(MaunIcons.Share, "Share", onShare))
        add(Triple(MaunIcons.Delete, "Delete", onDelete))
    }

    Surface(color = colors.surfaceContainer, tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(MaunIcons.Close, contentDescription = "Nothing selected")
            }
            Text(
                text = "${picked.size}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                // One number, one line, whatever the count.
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(start = 2.dp, end = 8.dp),
            )
            Row(
                // On a narrow phone the last action would be a sliver off the
                // edge, so the strip gives way a little rather than clipping one.
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { (icon, label, action) ->
                    IconButton(onClick = action, modifier = Modifier.size(44.dp)) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = if (label == "Delete") colors.error else colors.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/** How many pictures or documents can be picked and sent in one go. */
private const val MAX_FILES_AT_ONCE = 10

/** How close to the oldest loaded message the reader must be before more are fetched, in list items. */
private const val LOAD_AHEAD = 12

/**
 * Soft, calm wallpaper behind the conversation: either a subtle motif pattern
 * or a clean solid surface, depending on the user's Settings choice.
 *
 * The motif is drawn once into a tile the size of one cell and repeated by the
 * GPU. Drawing several hundred shapes on every frame is what made a long
 * conversation scroll unevenly.
 */
@Composable
private fun ChatWallpaper(
    patterned: Boolean,
    baseColor: Color,
    patternTint: Color,
    modifier: Modifier = Modifier,
) {
    if (!patterned) {
        Box(modifier = modifier.background(baseColor))
        return
    }
    val density = LocalDensity.current
    val tile = remember(patternTint, density) { patternTile(patternTint, density) }
    // One shader for the wallpaper's whole life: making a new one inside the
    // draw call meant allocating it again on every frame that had to be drawn.
    val brush = remember(tile) { ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated)) }
    Box(modifier = modifier.background(baseColor)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(brush = brush)
        }
    }
}

/** How wide one repeat of the wallpaper is. */
private const val CELL_DP = 96f

/** One cell of the wallpaper, drawn once: a bubble, a ring, a sparkle, and a soft stroke. */
private fun patternTile(tint: Color, density: Density): ImageBitmap {
    val cell = with(density) { CELL_DP.dp.toPx() }
    val side = cell.toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val stroke = Paint().apply {
        color = tint.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = with(density) { 1.25.dp.toPx() }
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    val fill = Paint().apply {
        color = tint.toArgb()
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    // A bubble outline, top left.
    canvas.drawRoundRect(
        cell * 0.14f,
        cell * 0.16f,
        cell * 0.30f,
        cell * 0.27f,
        cell * 0.035f,
        cell * 0.035f,
        stroke,
    )
    // A ring, top right.
    canvas.drawCircle(cell * 0.68f, cell * 0.26f, cell * 0.062f, stroke)
    // A four-point sparkle, lower left.
    val sx = cell * 0.34f
    val sy = cell * 0.66f
    val r = cell * 0.062f
    val sparkle = Path().apply {
        moveTo(sx, sy - r)
        lineTo(sx + r * 0.35f, sy - r * 0.35f)
        lineTo(sx + r, sy)
        lineTo(sx + r * 0.35f, sy + r * 0.35f)
        lineTo(sx, sy + r)
        lineTo(sx - r * 0.35f, sy + r * 0.35f)
        lineTo(sx - r, sy)
        lineTo(sx - r * 0.35f, sy - r * 0.35f)
        close()
    }
    canvas.drawPath(sparkle, stroke)
    // A short stroke, lower right, and a small dot.
    canvas.drawLine(cell * 0.72f, cell * 0.76f, cell * 0.80f, cell * 0.72f, stroke)
    canvas.drawCircle(cell * 0.50f, cell * 0.44f, cell * 0.012f, fill)
    return bitmap.asImageBitmap()
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
                text = "Up to $MAX_FILES_AT_ONCE at once · 25 MB per file · Encrypted end-to-end",
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
    verified: Boolean,
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
                // The tick sits at the end of the name, on the name's own line.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = name ?: shortId(peerId),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = if (name == null) FontFamily.Monospace else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    VerifiedBadge(verified)
                }
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

/**
 * The five emoji, floating at the message they would go on. It sits directly
 * above the bubble and moves with it, so it is never somewhere else on the
 * screen than the message it reacts to. The emoji already on the message is
 * marked, and tapping it again takes it off.
 */
@Composable
private fun ReactionBar(reactions: Reactions?, onReact: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Payload.REACTIONS.forEach { emoji ->
                val mine = reactions?.mine == emoji
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .then(
                            // The one this phone chose stands out.
                            if (mine) Modifier.background(colors.primary.copy(alpha = 0.20f)) else Modifier,
                        )
                        .clickable(onClickLabel = "React $emoji") { onReact(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = emoji, fontSize = 20.sp)
                }
            }
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
    /** This message is part of the selection. */
    selected: Boolean = false,
    /** Something is selected, so a tap picks this one instead of opening it. */
    selectionActive: Boolean = false,
    /** The emoji on it, from each side. */
    reactions: Reactions? = null,
    /** The message this one answers, when this phone still has it. */
    quoted: MessageEntity? = null,
    /** This is the one message selected, so the emoji bar hangs at it. */
    reactionBar: Boolean = false,
    /** Puts this emoji on it, or takes it off when it is already on. */
    onReactEmoji: (String) -> Unit = {},
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    files: FileActions = FileActions(),
    onOpenFile: (Long, Attachment) -> Unit = { _, _ -> },
    onSaveFile: (Long, Attachment) -> Unit = { _, _ -> },
) {
    val message = item.message
    val outgoing = message.outgoing
    // Worked out when the list was built, not on every frame (see MessageItem.file).
    val file = item.file
    val fileStep = file?.let { fileStep(it, outgoing, active = message.seq in files.progress) }
    val fileTap: (() -> Unit)? = when (fileStep) {
        null, FileStep.Working, FileStep.Gone -> null
        FileStep.Open -> ({ onOpenFile(message.seq, file) })
        FileStep.Fetch, FileStep.Retry -> ({ files.onStart(message.seq) })
    }
    val readable = message.kind == MessageKind.TEXT
    val imageBubble = file != null && fileStep != null && isPictureBubble(message, file, fileStep)
    val chatColors = LocalChatColors.current
    val haptics = LocalHapticFeedback.current

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

    // Web addresses in the text, marked in Maun's accent. Worked out once for
    // the message rather than on every frame, and only for text: what a file is
    // called is not an address even when it looks like one.
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    val linkStyle = remember(accent) { SpanStyle(color = accent, textDecoration = TextDecoration.Underline) }
    val linked = remember(message.text, readable, linkStyle) {
        if (!readable) AnnotatedString(message.text) else linkedText(
            text = message.text,
            style = linkStyle,
            onOpen = { url ->
                if (!openWebLink(context, url)) {
                    Toast.makeText(context, "No app on this phone opens that link.", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (item.startsRun) 6.dp else 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(0.85f),
            contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Column(horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start) {
                if (reactionBar) {
                    ReactionBar(
                        reactions = reactions,
                        onReact = onReactEmoji,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Surface(
                    color = bubbleColor,

                    shape = shape,
                    shadowElevation = 1.dp,
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .then(
                            // A selected bubble says so, in the same green the app uses
                            // for what is pressed.
                            if (selected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                            } else {
                                Modifier
                            },
                        )
                        .pointerInput(message.seq, fileStep, selectionActive) {
                            detectTapGestures(
                                onTap = {
                                    if (selectionActive) onTap() else fileTap?.invoke()
                                },
                                onLongPress = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongPress()
                                },
                            )
                        }
                        .semantics {
                            onLongClick(label = "Select") {
                                onLongPress()
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

                        readable -> Column {
                            // The quote stands above the answer rather than
                            // under it: the bubble's slot draws one over the
                            // other, so both need a column of their own.
                            if (quoted != null) InBubbleQuote(quoted = quoted, outgoing = outgoing)
                            InlineMessageBubble(
                                text = linked,
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
                if (reactions != null && !reactions.isEmpty) {
                    // What is on it, under it: this phone's emoji first, then theirs.
                    Row(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(chatColors.incomingBubble)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        reactions.mine?.let { Text(text = it, fontSize = 12.sp) }
                        reactions.theirs?.let { Text(text = it, fontSize = 12.sp) }
                    }
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
    text: AnnotatedString,
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
    /** The message this text will answer, if any. */
    replyingTo: MessageEntity? = null,
    onCancelReply: () -> Unit = {},
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
        // What is being answered, above the field and in the way of nothing:
        // a tap on the × puts it down and the text stays as it was.
        if (replyingTo != null) {
            QuotedStrip(quoted = replyingTo, onCancel = onCancelReply)
        }
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

                    // While something is being written, the words get the room:
                    // attaching and the camera step aside and come back the
                    // moment the field is empty again. Anything that is not a
                    // blank keeps them away, so a draft is never hidden.
                    if (draft.isEmpty()) {
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
            onSend = { _, _ -> },
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
