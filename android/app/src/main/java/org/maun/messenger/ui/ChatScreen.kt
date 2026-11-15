package org.maun.messenger.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.alpha
import org.maun.messenger.voice.VoiceRecorder
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import java.io.File
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberUpdatedState
import org.maun.messenger.Attachment
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.time.ZoneId
import kotlinx.coroutines.launch
import org.maun.messenger.Messenger
import org.maun.messenger.Payload
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.data.ReceiptLevel
import org.maun.messenger.network.RelayState

/**
 * One conversation. [messages] is newest first; [hasOlder] says there are
 * more on this phone than were loaded, and [onLoadOlder] asks for them.
 * [contactName] is null while the other person is not a contact: their
 * messages are shown as a request, and nothing can be sent until it is
 * accepted. [files] is what the paperclip and the file messages work with.
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

    // Files. Android's own picker chooses what to send and where to save, so
    // Maun needs no permission to read the phone's storage.
    val currentMessages by rememberUpdatedState(messages)
    val fileOf = { seq: Long ->
        currentMessages.firstOrNull { it.seq == seq && it.kind == MessageKind.FILE }?.let { Attachment.fromLocal(it.text) }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) files.onAttach(uri)
    }
    var saving by rememberSaveable { mutableStateOf<Long?>(null) }
    val pickPlace = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val seq = saving
        saving = null
        val file = seq?.let(fileOf)
        if (uri != null && seq != null && file != null) files.onSave(seq, file, uri)
    }
    val save = { seq: Long, file: Attachment ->
        saving = seq
        pickPlace.launch(file.name)
    }
    var viewing by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(files.problem) {
        val problem = files.problem ?: return@LaunchedEffect
        Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
        files.onProblemShown()
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

    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                ChatHeader(
                    peerId = peerId,
                    name = contactName ?: peerUsername?.let { "@$it" },
                    onBack = onBack,
                    onOpenDetails = if (isContact) onOpenDetails else null,
                    onCall = if (isContact) onCall else null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        items(items, key = { it.key }, contentType = { it is DayItem }) { item ->
                            when (item) {
                                is DayItem -> Pill(dayLabel(item.day, today))
                                is MessageItem -> if (item.message.kind == MessageKind.NOTICE) {
                                    Pill(item.message.text)
                                } else MessageRow(
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
                        // Above the oldest message that is loaded.
                        item(key = "top", contentType = "top") {
                            if (loading) {
                                Unit
                            } else if (hasOlder) {
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
                    if (scrolledBack) {
                        SmallFloatingActionButton(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) { Icon(MaunIcons.Down, contentDescription = "Go to the newest message") }
                    }
                }

                if (waiting != null) Notice(waiting)
                if (isContact) {
                    Composer(peerId = peerId, onSend = onSend, onAttach = { pickFile.launch("*/*") }, onVoice = files.onVoice)
                } else {
                    RequestBar(peerId = peerId, onAccept = onAccept, onBlock = onBlock)
                }
            }
        }
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

@Composable
private fun ChatHeader(peerId: String, name: String?, onBack: () -> Unit, onOpenDetails: (() -> Unit)?, onCall: ((Boolean) -> Unit)? = null) {
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
                    text = name ?: "Message request",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = shortId(peerId),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (onCall != null) {
            // The microphone, and for a video call the camera, are asked for the
            // first time they are needed. A call needs the microphone; without
            // the camera a video call still goes ahead, with sound only.
            val context = LocalContext.current
            var wantsVideo by remember { mutableStateOf(false) }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
                val mic = granted[Manifest.permission.RECORD_AUDIO] == true ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (mic) onCall(wantsVideo) else Toast.makeText(context, "Maun needs the microphone for a call.", Toast.LENGTH_LONG).show()
            }
            fun call(video: Boolean) {
                val needed = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { video })
                    .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                wantsVideo = video
                if (needed.isEmpty()) onCall(video) else ask.launch(needed.toTypedArray())
            }
            IconButton(onClick = { call(video = true) }) { Icon(MaunIcons.Videocam, contentDescription = "Video call") }
            IconButton(onClick = { call(video = false) }) { Icon(MaunIcons.Call, contentDescription = "Voice call") }
        }
        if (onOpenDetails != null) {
            IconButton(onClick = onOpenDetails) { Icon(MaunIcons.Info, contentDescription = "Contact details") }
        }
    }
}

/** A small centred label between messages: the day, or a note about the conversation. */
@Composable
private fun Pill(text: String, locked: Boolean = false) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(12.dp),
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
    // What a tap on a file's bubble does, if anything.
    val onTap: (() -> Unit)? = when (fileStep) {
        null, FileStep.Working, FileStep.Gone -> null
        FileStep.Open -> ({ onOpenFile(message.seq, file) })
        FileStep.Fetch, FileStep.Retry -> ({ files.onStart(message.seq) })
    }
    val readable = message.kind == MessageKind.TEXT
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }

    // Round everywhere, except on the writer's side where the bubbles of one
    // run meet, and at the bottom corner that points at the writer.
    val round = 18.dp
    val tight = 5.dp
    val joint = if (item.startsRun) round else tight
    val shape = if (outgoing) {
        RoundedCornerShape(topStart = round, topEnd = joint, bottomEnd = tight, bottomStart = round)
    } else {
        RoundedCornerShape(topStart = joint, topEnd = round, bottomEnd = round, bottomStart = tight)
    }
    val text = if (outgoing) colors.onPrimaryContainer else colors.onSurface
    val quiet = if (outgoing) colors.onPrimaryContainer.copy(alpha = 0.7f) else colors.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (item.startsRun) 8.dp else 2.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        // A bubble takes the room its text needs, up to most of the width.
        Box(
            modifier = Modifier.fillMaxWidth(0.84f),
            contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box {
                Surface(
                    color = if (outgoing) colors.primaryContainer else colors.surfaceContainerHigh,
                    shape = shape,
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .clip(shape)
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
                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 6.dp)) {
                        if (readable) {
                            Text(text = message.text, style = MaterialTheme.typography.bodyLarge, color = text)
                        } else if (file != null) {
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
                                Spacer(modifier = Modifier.width(4.dp))
                                StatusTick(message.status, online, stuck, quiet)
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
                    if (file != null && fileStep == FileStep.Open) {
                        DropdownMenuItem(
                            text = { Text("Save") },
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
 * How far one's own message has got, as the mark every messenger uses: a
 * clock while it waits on this phone, one tick once the relay has it, two
 * when it has reached their phone, and two coloured ones when it was read.
 */
@Composable
fun StatusTick(status: Int, online: Boolean, stuck: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val icon = when {
        status >= MessageStatus.DELIVERED -> MaunIcons.CheckAll
        status == MessageStatus.SENT -> MaunIcons.Check
        else -> MaunIcons.Clock
    }
    val color = when {
        status >= MessageStatus.READ -> LocalStatusColors.current.read
        status < MessageStatus.SENT && stuck -> LocalStatusColors.current.warning
        else -> tint
    }
    Icon(
        imageVector = icon,
        contentDescription = statusLabel(status, online, stuck),
        tint = color,
        modifier = modifier.size(16.dp),
    )
}

private fun copyMessage(context: Context, text: String) {
    val clip = ClipData.newPlainText("Message", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Keeps the text out of the preview Android shows after copying.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    // From Android 13 on, the system says so itself.
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
 * Where a message is written. While the field is empty the button on the
 * right is a microphone: a tap starts recording a voice message, and the
 * field makes way for [RecordingBar] until it is sent or thrown away.
 */
@Composable
private fun Composer(peerId: String, onSend: (String) -> Unit, onAttach: () -> Unit, onVoice: (File, Long) -> Unit) {
    var draft by rememberSaveable(peerId) { mutableStateOf("") }
    val bytes = draft.trim().toByteArray(Charsets.UTF_8).size
    val tooLong = bytes > Payload.MAX_TEXT_BYTES
    val field = MaterialTheme.colorScheme.surfaceContainerHigh

    val context = LocalContext.current
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

    // While recording: the clock and the moving bars.
    LaunchedEffect(recording) {
        while (recording) {
            delay(RECORDING_TICK_MILLIS)
            elapsed = recorder.elapsedMillis
            if (!recorder.paused) {
                levels.add(recorder.level())
                if (levels.size > RECORDING_BARS) levels.removeAt(0)
            }
            // The longest a voice message can be: send what there is.
            if (elapsed >= Attachment.MAX_VOICE_MILLIS) send()
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(4000)
            hint = null
        }
    }
    // Back throws the recording away rather than leaving the conversation with it running.
    BackHandler(enabled = recording) {
        recorder.cancel()
        recording = false
    }

    if (recording) {
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
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)) {
        val note = if (tooLong) "Too long for one message. Shorten it or send it in parts." else hint
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onAttach, modifier = Modifier.padding(bottom = 4.dp).size(48.dp)) {
                Icon(MaunIcons.Attach, contentDescription = "Send a file", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                maxLines = 5,
                isError = tooLong,
                shape = RoundedCornerShape(26.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                // A plain rounded field: no line underneath in any state.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = field,
                    unfocusedContainerColor = field,
                    errorContainerColor = field,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            if (bytes > 0) {
                FilledIconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = !tooLong,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).size(48.dp),
                ) { Icon(MaunIcons.Send, contentDescription = "Send") }
            } else {
                FilledIconButton(
                    onClick = {
                        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (allowed) begin() else askForMic.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp).size(48.dp),
                ) { Icon(MaunIcons.Mic, contentDescription = "Record a voice message") }
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
            // One bar for each tenth of a second, the newest on the right.
            val bar = if (paused) colors.outline else colors.primary
            Canvas(modifier = Modifier.weight(1f).height(32.dp)) {
                val width = 3.dp.toPx()
                val step = width + 2.dp.toPx()
                var x = size.width - width
                for (i in levels.indices.reversed()) {
                    if (x < 0) break
                    // Quiet speech is far from the top of the scale; the root brings it into view.
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
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
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
            FilledIconButton(onClick = onSend, modifier = Modifier.size(52.dp)) {
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
