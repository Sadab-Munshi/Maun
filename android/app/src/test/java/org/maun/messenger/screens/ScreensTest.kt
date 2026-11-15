package org.maun.messenger.screens

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.util.Base64
import org.maun.messenger.Attachment
import org.maun.messenger.ui.BannedScreen
import org.maun.messenger.ui.FileActions
import org.maun.messenger.crypto.ContactCode
import org.maun.messenger.ui.LoginScreen
import org.maun.messenger.ui.LoginUi
import org.maun.messenger.ui.MyCodeScreen
import org.maun.messenger.ui.CallActions
import org.maun.messenger.ui.CallScreen
import org.maun.messenger.ui.PendingScreen
import org.maun.messenger.ui.RecordingBar
import org.maun.messenger.webrtc.CallUi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.maun.messenger.ui.SettingUpScreen
import org.maun.messenger.voice.VoiceState
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.network.RelayState
import org.maun.messenger.ui.ChatRow
import org.maun.messenger.ui.ChatScreen
import org.maun.messenger.ui.ContactScreen
import org.maun.messenger.ui.DeliveryUi
import org.maun.messenger.ui.HomeLists
import org.maun.messenger.ui.HomeScreen
import org.maun.messenger.ui.LockScreen
import org.maun.messenger.ui.MaunTheme
import org.maun.messenger.ui.PrivacyUi
import org.maun.messenger.ui.SettingsScreen
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the main screens with made-up people and saves each as a picture
 * under build/screens, so that a change to the look can be seen without a
 * phone. Nothing is compared and nothing can fail on looks alone.
 *
 * Not part of the normal test run. Ask for it:
 *
 *     ./gradlew :app:testDebugUnitTest -Pmaun.screens=true
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the real one would start background work nobody needs here.
@Config(application = Application::class, qualifiers = "w360dp-h760dp-xhdpi")
class ScreensTest {

    private val asha = ContactEntity("ab12".repeat(16), "Asha", verified = true, addedAt = 0)
    private val rohan = ContactEntity("cd34".repeat(16), "Rohan Mehta", verified = false, addedAt = 0)
    private val zoya = ContactEntity("9a7c".repeat(16), "Zoya", verified = true, addedAt = 0)
    private val stranger = "ef56".repeat(16)

    private val now = System.currentTimeMillis()
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun shoot(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/screens/$name.png") { MaunTheme(content) }
    }

    private fun summary(peer: String, text: String, outgoing: Boolean, status: Int, ago: Long, unread: Int = 0) =
        ChatSummary(peer, seq = 1, kind = MessageKind.TEXT, text = text, outgoing = outgoing, status = status, at = now - ago, unread = unread)

    @Composable
    private fun Home(relay: RelayState = RelayState.Online, delivery: DeliveryUi = DeliveryUi(pushApp = "Google's push service", google = true)) {
        HomeScreen(
            relay = relay,
            lists = HomeLists(
                chats = listOf(
                    ChatRow(asha.id, asha, summary(asha.id, "Are we still on for today?", false, 0, 2 * minute, unread = 2)),
                    ChatRow(rohan.id, rohan, summary(rohan.id, "See you at six", true, MessageStatus.READ, 50 * minute)),
                    ChatRow(zoya.id, zoya, summary(zoya.id, "Sent you the address, it is the second gate after the bridge", true, MessageStatus.SENT, day)),
                    ChatRow("77aa".repeat(16), ContactEntity("77aa".repeat(16), "Imran", true, 0), null),
                ),
                requests = listOf(ChatRow(stranger, null, summary(stranger, "Hi, it's Meera from the workshop", false, 0, 3 * day, unread = 1))),
            ),
            delivery = delivery,
            pushProblem = null,
            onChoosePushApp = {},
            onGooglePush = {},
            onStayConnected = {},
            onDeliveryChanged = {},
            onShowMyCode = {},
            onAddContact = {},
            onOpenChat = {},
            onReconnect = {},
            onOpenSettings = {},
        )
    }

    private fun message(seq: Long, outgoing: Boolean, text: String, ago: Long, status: Int = MessageStatus.READ, kind: Int = MessageKind.TEXT) =
        MessageEntity(seq = seq, peer = asha.id, id = "m$seq", outgoing = outgoing, kind = kind, text = text, at = now - ago, status = status)

    @Composable
    private fun Chat(contactName: String? = asha.name, waiting: String? = null) {
        ChatScreen(
            peerId = asha.id,
            contactName = contactName,
            // Newest first.
            messages = listOf(
                message(9, true, "Leaving now", 1 * minute, MessageStatus.PENDING),
                message(8, true, "See you at six", 2 * minute, MessageStatus.SENT),
                message(7, true, "Yes! I booked the table by the window, the one you liked last time.", 3 * minute, MessageStatus.DELIVERED),
                message(6, false, "Are we still on for today?", 5 * minute),
                message(5, false, "Good morning", 6 * minute),
                message(4, true, "Sleep well", day + 10 * minute),
                message(3, false, "", day + 12 * minute, kind = MessageKind.UNREADABLE),
                message(2, false, "That was a long day. Talk tomorrow?", day + 15 * minute),
                message(1, true, "Hello", day + 20 * minute),
            ),
            hasOlder = false,
            waiting = waiting,
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

    @Composable
    private fun Settings(memberEmail: String? = null, username: String? = null) {
        SettingsScreen(
            memberEmail = memberEmail,
            username = username,
            version = "0.10.0",
            relayName = "maun.example.workers.dev",
            relay = RelayState.Online,
            delivery = DeliveryUi(pushApp = "Google's push service", google = true),
            pushProblem = null,
            privacy = PrivacyUi(appLock = true),
            blockedCount = 2,
            onChoosePushApp = {},
            onGooglePush = {},
            onStayConnected = {},
            onDeliveryChanged = {},
            onAppLock = {},
            onBlockScreenshots = {},
            onOpenBlocked = {},
            onOpenSetupCheck = {},
            onBack = {},
        )
    }

    @Composable
    private fun Contact(contact: ContactEntity) {
        ContactScreen(
            contact = contact,
            safetyNumber = "12345 67890 12345 67890\n12345 67890 12345 67890\n12345 67890 12345 67890",
            onBack = {},
            onRename = {},
            onChecked = {},
            onRemove = {},
            onBlock = {},
        )
    }

    @Test
    fun home() = shoot("home") { Home() }

    @Test
    @Config(qualifiers = "+night")
    fun homeDark() = shoot("home-dark") { Home() }

    @Test
    fun homeNeedingAttention() = shoot("home-attention") {
        Home(relay = RelayState.Connecting, delivery = DeliveryUi(notificationsAllowed = false, googleOffered = true))
    }

    @Test
    fun homeEmpty() = shoot("home-empty") {
        HomeScreen(
            relay = RelayState.Online,
            lists = HomeLists(),
            delivery = DeliveryUi(pushApp = "Google's push service", google = true),
            pushProblem = null,
            onChoosePushApp = {},
            onGooglePush = {},
            onStayConnected = {},
            onDeliveryChanged = {},
            onShowMyCode = {},
            onAddContact = {},
            onOpenChat = {},
            onReconnect = {},
            onOpenSettings = {},
        )
    }

    @Test
    fun chat() = shoot("chat") { Chat() }

    @Test
    @Config(qualifiers = "+night")
    fun chatDark() = shoot("chat-dark") { Chat() }

    @Test
    fun chatStuck() = shoot("chat-stuck") { Chat(waiting = "Their mailbox is full until they come online.") }

    @Test
    fun messageRequest() = shoot("chat-request") { Chat(contactName = null) }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-mdpi")
    fun chatOnATablet() = shoot("chat-tablet") { Chat() }

    // ---- Files ---------------------------------------------------------------

    private fun attachment(name: String, mime: String, size: Long, state: Int, key: String = "ab".repeat(32)) =
        Attachment(key, Base64.getEncoder().encodeToString(ByteArray(32)), name, mime, size, state)

    private fun fileMessage(seq: Long, outgoing: Boolean, file: Attachment, ago: Long, status: Int = MessageStatus.READ) =
        MessageEntity(seq = seq, peer = asha.id, id = "f$seq", outgoing = outgoing, kind = MessageKind.FILE, text = file.toLocal(), at = now - ago, status = status)

    /** A stand-in for a photo: nothing is decrypted when a screen is only drawn. */
    private fun picture(): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 640f, 480f, 0xFF2F6B55.toInt(), 0xFFE7B76B.toInt(), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawRect(0f, 0f, 640f, 480f, paint)
        return bitmap
    }

    @Composable
    private fun ChatWithFiles() {
        val photo = picture()
        ChatScreen(
            peerId = asha.id,
            contactName = asha.name,
            // Newest first.
            messages = listOf(
                fileMessage(7, true, attachment("Budget 2026.xlsx", "application/vnd.ms-excel", 1_840_000, Attachment.UPLOADING, key = ""), minute, MessageStatus.PENDING),
                fileMessage(6, true, attachment("Lease.pdf", "application/pdf", 9_300_000, Attachment.FAILED, key = ""), 2 * minute, MessageStatus.PENDING),
                fileMessage(5, false, attachment("Old video.mp4", "video/mp4", 24_100_000, Attachment.GONE), 3 * minute),
                fileMessage(4, false, attachment("Tickets.pdf", "application/pdf", 412_000, Attachment.READY), 4 * minute),
                fileMessage(3, false, attachment("Route map with a rather long name for a file.gpx", "application/gpx+xml", 86_000, Attachment.REMOTE), 5 * minute),
                message(2, true, "Here is the view from the window", 6 * minute),
                fileMessage(1, true, attachment("IMG_2041.jpg", "image/jpeg", 2_600_000, Attachment.READY), 7 * minute),
            ),
            hasOlder = false,
            waiting = null,
            relay = RelayState.Online,
            files = FileActions(progress = mapOf(7L to 0.62f), loadImage = { seq, _, _ -> if (seq == 1L) photo else null }),
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

    @Test
    fun chatAfterAMove() = shoot("chat-moved") {
        ChatScreen(
            peerId = asha.id,
            contactName = asha.name,
            // Newest first.
            messages = listOf(
                message(4, false, "Yes, new phone. Everything from before is gone here.", 2 * minute),
                message(3, true, "Is this still you?", 3 * minute),
                MessageEntity(
                    seq = 2, peer = asha.id, id = "n2", outgoing = false, kind = MessageKind.NOTICE, at = now - 4 * minute,
                    text = "Asha set up Maun again. Messages now go to the new setup, and the safety number has changed.",
                ),
                message(1, true, "See you at six", 3 * 60 * minute),
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

    @Composable
    private fun ChatWithVoice() {
        fun voice(millis: Long, state: Int, key: String = "ab".repeat(32)) =
            attachment("Voice message.ogg", Attachment.VOICE_MIME, millis * 3, state, key).copy(duration = millis)
        ChatScreen(
            peerId = asha.id,
            contactName = asha.name,
            // Newest first.
            messages = listOf(
                fileMessage(5, true, voice(8_000, Attachment.UPLOADING, key = ""), minute, MessageStatus.PENDING),
                fileMessage(4, false, voice(95_000, Attachment.REMOTE), 2 * minute),
                fileMessage(3, false, voice(42_000, Attachment.READY), 3 * minute),
                message(2, false, "Listen to this", 4 * minute),
                fileMessage(1, true, voice(17_000, Attachment.READY), 5 * minute),
            ),
            hasOlder = false,
            waiting = null,
            relay = RelayState.Online,
            files = FileActions(progress = mapOf(5L to 0.4f), voice = VoiceState(seq = 3, playing = true, positionMillis = 15_000, durationMillis = 42_000)),
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

    @Test
    fun chatWithVoice() = shoot("chat-voice") { ChatWithVoice() }

    @Test
    @Config(qualifiers = "+night")
    fun chatWithVoiceDark() = shoot("chat-voice-dark") { ChatWithVoice() }

    @Test
    fun recording() = shoot("recording") {
        // The bar that takes the place of the message field while a voice message is recorded.
        val levels = List(44) { i -> ((i * 37) % 11) / 10f * if (i % 7 == 0) 0.2f else 1f }
        // On the app's background, as it stands under a conversation.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(verticalArrangement = Arrangement.Bottom) {
                RecordingBar(elapsedMillis = 7_000, levels = levels, paused = false, onDelete = {}, onPause = {}, onSend = {})
                RecordingBar(elapsedMillis = 83_000, levels = levels, paused = true, onDelete = {}, onPause = {}, onSend = {})
            }
        }
    }

    private fun call(incoming: Boolean, phase: CallUi.Phase, connectedAt: Long = 0, muted: Boolean = false, speaker: Boolean = false, ended: String? = null, video: Boolean = false) =
        CallUi(peer = asha.id, incoming = incoming, phase = phase, connectedAt = connectedAt, muted = muted, speaker = speaker, ended = ended, video = video)

    @Composable
    private fun Call(call: CallUi) {
        CallScreen(call = call, name = asha.name, actions = CallActions(), now = { 1_000_000 })
    }

    @Test
    fun callIncoming() = shoot("call-incoming") { Call(call(incoming = true, phase = CallUi.Phase.Ringing)) }

    @Test
    @Config(qualifiers = "+night")
    fun callActive() = shoot("call-active-dark") {
        Call(call(incoming = false, phase = CallUi.Phase.Active, connectedAt = 1_000_000 - 154_000, muted = true))
    }

    // The pictures of a video call cannot be drawn here; what can is the screen that asks to take one.
    @Test
    fun callIncomingVideo() = shoot("call-incoming-video") { Call(call(incoming = true, phase = CallUi.Phase.Ringing, video = true)) }

    @Test
    fun callOutgoing() = shoot("call-outgoing") { Call(call(incoming = false, phase = CallUi.Phase.Ringing, speaker = true)) }

    @Test
    fun callEnded() = shoot("call-ended") { Call(call(incoming = false, phase = CallUi.Phase.Ended, ended = "No answer")) }

    @Test
    fun settingUpFailed() = shoot("setting-up-failed") {
        SettingUpScreen(problem = "Could not reach the relay: no route to host", working = false, onRetry = {}, onChooseServer = {})
    }

    @Test
    fun chatWithFiles() = shoot("chat-files") { ChatWithFiles() }

    @Test
    @Config(qualifiers = "+night")
    fun chatWithFilesDark() = shoot("chat-files-dark") { ChatWithFiles() }

    // ---- A member-only relay ---------------------------------------------------

    private val relayName = "maun.example.workers.dev"

    @Composable
    private fun Login(ui: LoginUi, checking: Boolean = false) {
        LoginScreen(relayName, ui, checking, onEmail = {}, onCode = {}, onUsername = {}, onStartOver = {})
    }

    @Test
    fun login() = shoot("login") { Login(LoginUi()) }

    @Test
    fun loginCode() = shoot("login-code") { Login(LoginUi(LoginUi.Step.Code, "asha@example.com")) }

    @Test
    @Config(qualifiers = "+night")
    fun loginCodeWrong() = shoot("login-code-wrong-dark") {
        Login(LoginUi(LoginUi.Step.Code, "asha@example.com", problem = "That code is not right."))
    }

    @Test
    fun loginUsername() = shoot("login-username") {
        Login(LoginUi(LoginUi.Step.Username, "asha@example.com", problem = "Somebody already has that username."))
    }

    @Test
    fun pending() = shoot("pending") { PendingScreen(relayName, "asha", checking = false, onCheckAgain = {}) }

    @Test
    fun myCodeWithUsername() = shoot("my-code-member") {
        MyCodeScreen(code = ContactCode(asha.id, relayName), onBack = {}, username = "asha")
    }

    @Test
    fun banned() = shoot("banned") { BannedScreen(relayName = "maun.example.workers.dev", onCheckAgain = {}) }

    @Test
    fun settingsOfAMember() = shoot("settings-member") { Settings(memberEmail = "asha@example.com", username = "asha") }

    @Test
    fun settings() = shoot("settings") { Settings() }

    @Test
    @Config(qualifiers = "+night")
    fun settingsDark() = shoot("settings-dark") { Settings() }

    @Test
    fun contact() = shoot("contact") { Contact(rohan) }

    @Test
    @Config(qualifiers = "+night")
    fun contactChecked() = shoot("contact-checked-dark") { Contact(asha) }

    @Test
    fun locked() = shoot("locked") { LockScreen(onShown = {}, onUnlock = {}) }
}
