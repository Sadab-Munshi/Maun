package org.maun.messenger.ui

import android.app.Application
import java.io.File
import org.maun.messenger.voice.VoicePlayer
import org.maun.messenger.voice.VoiceState
import android.graphics.Bitmap
import android.net.Uri
import org.maun.messenger.Attachment
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.maun.messenger.Messenger
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maun.messenger.AppLock
import org.maun.messenger.BuildConfig
import org.maun.messenger.CodeCheck
import org.maun.messenger.MaunApp
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.network.RelayException
import org.maun.messenger.push.Background
import org.maun.messenger.push.Notifications
import org.maun.messenger.push.PushApps

/**
 * A conversation as its screen shows it: the newest messages first, and
 * whether older ones are waiting to be loaded. [loaded] is false only for the
 * moment before the messages have been read from storage.
 */
data class Conversation(
    val messages: List<MessageEntity> = emptyList(),
    val hasOlder: Boolean = false,
    val loaded: Boolean = true,
)

/** What the welcome screen needs beyond the account and relay state. */
data class OnboardingUiState(
    val relayUrl: String,
    /** True while the address is being checked and the identity created. */
    val working: Boolean = false,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val messenger = (application as MaunApp).messenger
    private val settings = application.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    val account = messenger.account
    val relay = messenger.relay
    val contacts = messenger.contacts
    val blocked = messenger.blocked
    val waiting = messenger.waiting
    val pushProblem = messenger.pushProblem

    /** On a member-only relay: the email address this ID is tied to. */
    val memberEmail = messenger.memberEmail

    /** The relay's owner has shut this member out. */
    val banned = messenger.banned

    val fileProgress = messenger.fileProgress

    private val attachProblem = MutableStateFlow<String?>(null)

    /** Why a file could not be picked up, sent or fetched: said once, then cleared. */
    val fileProblem: StateFlow<String?> = combine(attachProblem, messenger.fileProblem) { mine, theirs -> mine ?: theirs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clearFileProblem() {
        attachProblem.value = null
        messenger.clearFileProblem()
    }

    /** On a member-only relay: this account's username. */
    val username = messenger.username

    /** The email login is done and the administrator has not approved this account yet. */
    val awaitingApproval = messenger.awaitingApproval

    private val _login = MutableStateFlow(LoginUi())

    /** Where the email login has got to. */
    val login: StateFlow<LoginUi> = _login.asStateFlow()

    private fun loginStep(email: String, action: suspend () -> Messenger.LoginStep, onDone: LoginUi) {
        if (_login.value.working) return
        _login.update { it.copy(working = true, problem = null) }
        viewModelScope.launch {
            _login.value = when (val step = action()) {
                Messenger.LoginStep.Done -> onDone
                Messenger.LoginStep.NeedsUsername -> LoginUi(LoginUi.Step.Username, email)
                is Messenger.LoginStep.Problem -> _login.value.copy(working = false, problem = step.message)
            }
        }
    }

    /** Asks for a code to be sent to [email], the first time or again. */
    fun loginSendCode(email: String) {
        if (_login.value.working) return
        _login.update { it.copy(working = true, problem = null) }
        viewModelScope.launch {
            val problem = messenger.requestLoginCode(email)
            _login.value = when {
                problem == null -> LoginUi(LoginUi.Step.Code, email)
                // Asked again too soon: the code already sent still works.
                _login.value.step == LoginUi.Step.Code -> _login.value.copy(working = false, problem = problem)
                else -> LoginUi(LoginUi.Step.Email, email, problem = problem)
            }
        }
    }

    fun loginSubmitCode(code: String) {
        val email = _login.value.email
        // Once the relay has the ticket, the connection decides what is shown next.
        loginStep(email, { messenger.submitLoginCode(email, code) }, onDone = LoginUi(email = email))
    }

    fun loginChooseUsername(name: String) {
        val email = _login.value.email
        loginStep(email, { messenger.chooseUsername(name) }, onDone = LoginUi(email = email))
    }

    fun loginStartOver() {
        _login.value = LoginUi(email = _login.value.email)
    }

    suspend fun findUser(name: String): Messenger.UserSearch = messenger.findUser(name)

    suspend fun usernameOf(id: String): String? = messenger.usernameOf(id)

    fun checkBanAgain() = messenger.checkBanAgain()

    fun sendFile(peer: String, uri: Uri) {
        viewModelScope.launch { attachProblem.value = messenger.sendFile(peer, uri) }
    }

    fun startFile(seq: Long) = messenger.startFile(seq)

    fun saveFile(seq: Long, file: Attachment, target: Uri) {
        viewModelScope.launch {
            if (!messenger.saveFile(seq, file, target)) attachProblem.value = "The file could not be saved there."
        }
    }

    suspend fun image(seq: Long, file: Attachment, maxPixels: Int): Bitmap? = messenger.image(seq, file, maxPixels)

    fun cachedImage(seq: Long, maxPixels: Int): Bitmap? = messenger.cachedImage(seq, maxPixels)

    private val player = VoicePlayer(viewModelScope)

    /** The voice message that is loaded for playing, if any. */
    val voice: StateFlow<VoiceState?> = player.state

    /** A voice message was recorded for [peer]. The file is taken over and deleted. */
    fun sendVoice(peer: String, recording: File, millis: Long) {
        viewModelScope.launch { attachProblem.value = messenger.sendVoice(peer, recording, millis) }
    }

    /** Plays the voice message of message [seq], or pauses it. */
    fun toggleVoice(seq: Long, file: Attachment) {
        viewModelScope.launch {
            val played = player.toggle(seq) { messenger.voiceBytes(seq, file) }
            if (!played) attachProblem.value = "That voice message could not be played."
        }
    }

    fun stopVoice() = player.stop()

    /** Calls [peer]. If it cannot be done right now, says why. */
    fun startCall(peer: String) {
        player.stop()
        messenger.startCall(peer)?.let { attachProblem.value = it }
    }

    override fun onCleared() {
        player.stop()
        super.onCleared()
    }

    // The conversations looked at lately, and the ones at the top of the list,
    // are kept in memory so that opening one shows its messages at once.
    private val recent = object : LinkedHashMap<String, Conversation>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Conversation>) = size > CHATS_IN_MEMORY
    }
    private val preloadedAt = HashMap<String, Long>()

    /** The conversation with [peer] as it was last seen, or null when it is not in memory. */
    fun cachedConversation(peer: String): Conversation? = synchronized(recent) { recent[peer] }

    init {
        viewModelScope.launch {
            messenger.chats.collect { chats ->
                for (chat in chats.take(CHATS_PRELOADED)) {
                    val fresh = synchronized(recent) { preloadedAt[chat.peer] == chat.seq && chat.peer in recent }
                    if (fresh) continue
                    val page = messenger.messages(chat.peer, Messenger.MESSAGE_PAGE).firstOrNull() ?: continue
                    synchronized(recent) {
                        recent[chat.peer] = Conversation(page, hasOlder = page.size >= Messenger.MESSAGE_PAGE)
                        preloadedAt[chat.peer] = chat.seq
                    }
                }
            }
        }
    }

    private val _delivery = MutableStateFlow(DeliveryUi())

    /** How messages arrive while Maun is closed. Call [refreshDelivery] to bring it up to date. */
    val delivery: StateFlow<DeliveryUi> = _delivery.asStateFlow()

    private val _privacy = MutableStateFlow(PrivacyUi())

    /** The app lock and screenshot switches. Call [refreshPrivacy] to bring it up to date. */
    val privacy: StateFlow<PrivacyUi> = _privacy.asStateFlow()

    val home: StateFlow<HomeLists> = combine(messenger.contacts, messenger.chats, ::homeLists)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeLists())

    private val _onboarding = MutableStateFlow(
        OnboardingUiState(relayUrl = settings.getString(KEY_RELAY_URL, null) ?: BuildConfig.RELAY_URL),
    )
    val onboarding: StateFlow<OnboardingUiState> = _onboarding.asStateFlow()

    fun onRelayUrlChange(value: String) {
        _onboarding.update { it.copy(relayUrl = value, error = null) }
    }

    fun createAccount() {
        if (_onboarding.value.working) return
        val relayUrl = _onboarding.value.relayUrl.trim()
        if (relayUrl.isEmpty()) {
            _onboarding.update { it.copy(error = "Enter your relay address first.") }
            return
        }
        settings.edit { putString(KEY_RELAY_URL, relayUrl) }
        _onboarding.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            val error = try {
                messenger.createAccount(relayUrl)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                e.message
            } catch (e: Exception) {
                "Could not create your ID: ${e.message ?: e.javaClass.simpleName}"
            }
            _onboarding.update { it.copy(working = false, error = error) }
        }
    }

    fun reconnect() = messenger.reconnect()

    /** Reads the things that change outside the app: permissions, installed push apps. */
    fun refreshDelivery() {
        val context = getApplication<Application>()
        val pushApp = PushApps.current(context)
        _delivery.value = DeliveryUi(
            notificationsAllowed = Notifications(context).allowed,
            pushApp = pushApp?.label,
            google = pushApp?.google == true,
            googleOffered = pushApp == null && Background.googleOff(context) && PushApps.googleAvailable(context),
            pushChoices = if (pushApp == null) PushApps.installed(context) else emptyList(),
            stayConnected = Background.stayConnected(context),
            unrestricted = Background.unrestricted(context),
        )
    }

    fun choosePushApp(packageName: String) {
        PushApps.choose(getApplication(), packageName)
        refreshDelivery()
    }

    /** Turns waking by Google's push service on or off. */
    fun setGooglePush(on: Boolean) {
        val context = getApplication<Application>()
        if (on) {
            PushApps.useGoogle(context)
        } else {
            PushApps.stopGoogle(context)
            // The relay must forget the address too.
            messenger.onPushAddress(null)
        }
        refreshDelivery()
    }

    /** Reads the two privacy switches and whether the phone has a screen lock. */
    fun refreshPrivacy() {
        val context = getApplication<Application>()
        _privacy.value = PrivacyUi(
            appLock = AppLock.enabled(context),
            canLock = AppLock.available(context),
            blockScreenshots = AppLock.blocksScreenshots(context),
            lockBlocksScreenshots = AppLock.lockBlocksScreenshots,
        )
    }

    fun setStayConnected(on: Boolean) {
        Background.setStayConnected(getApplication(), on)
        refreshDelivery()
    }

    suspend fun checkCode(text: String, typed: Boolean): CodeCheck = messenger.checkCode(text, typed)

    /** A contact's ID changed (old to new): a screen showing the old one should show the new one. */
    val moved = messenger.moved

    fun saveContact(id: String, name: String, verified: Boolean, username: String? = null) {
        viewModelScope.launch { messenger.saveContact(id, name, verified, username) }
    }

    fun removeContact(id: String) {
        viewModelScope.launch { messenger.removeContact(id) }
    }

    fun renameContact(id: String, name: String) {
        viewModelScope.launch { messenger.renameContact(id, name) }
    }

    fun setContactChecked(id: String, checked: Boolean) {
        viewModelScope.launch { messenger.setContactChecked(id, checked) }
    }

    /**
     * The conversation with [peer]. [limit] is how many of the newest
     * messages to load; each new value replaces the list with a longer one.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun conversation(peer: String, limit: Flow<Int>): Flow<Conversation> =
        limit.flatMapLatest { count ->
            messenger.messages(peer, count).map { Conversation(messages = it, hasOlder = it.size >= count) }
        }.onEach { shown -> synchronized(recent) { recent[peer] = shown } }

    fun deleteMessage(seq: Long) {
        viewModelScope.launch { messenger.deleteMessage(seq) }
    }

    fun sendText(peer: String, text: String) {
        viewModelScope.launch {
            try {
                messenger.sendText(peer, text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The screen only offers Send for text that can be sent to a
                // contact; a race with removing the contact ends up here.
            }
        }
    }

    fun markRead(peer: String) {
        viewModelScope.launch { messenger.markRead(peer) }
    }

    fun block(peer: String) {
        viewModelScope.launch { messenger.block(peer) }
    }

    fun unblock(peer: String) {
        viewModelScope.launch { messenger.unblock(peer) }
    }

    companion object {
        /** Conversations kept in memory, and how many from the top of the list are read ahead. */
        private const val CHATS_IN_MEMORY = 12
        private const val CHATS_PRELOADED = 6
        const val SETTINGS = "settings"
        const val KEY_RELAY_URL = "relay_url"
    }
}
