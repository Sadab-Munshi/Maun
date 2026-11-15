package org.maun.messenger.screens

import android.app.Application
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
    private fun Settings() {
        SettingsScreen(
            version = "0.4.0",
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
