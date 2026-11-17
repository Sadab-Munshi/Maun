package org.maun.messenger.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.PowerManager
import java.io.File
import org.maun.messenger.voice.VoicePlayer
import org.maun.messenger.webrtc.TelecomBridge
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
import org.maun.messenger.MediaSaver
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.Reactions
import org.maun.messenger.network.RelayException
import org.maun.messenger.push.Background
import org.maun.messenger.SoundSettings
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
    val chats = messenger.chats
    val blocked = messenger.blocked
    val waiting = messenger.waiting
    val pushProblem = messenger.pushProblem

    /** Which contacts carry the tick Maun's administrators hand out. */
    val verified = messenger.verified

    /** Whether this account itself carries that tick. */
    val ownVerified = messenger.ownVerified

    /** Asks the relay again which contacts are verified. Cheap, and worth doing when the app returns. */
    fun refreshBadges() = messenger.refreshBadges()

    /** On a member-only relay: the email address this ID is tied to. */
    val memberEmail = messenger.memberEmail

    /** The relay's owner has shut this member out. */
    val banned = messenger.banned

    /** Whether this account can be recovered on another phone. */
    val recovery = messenger.recovery

    /** Leaves a sealed copy of the account on the relay. Returns why it could not, or null. */
    suspend fun enableRecovery(passphrase: CharArray): String? = messenger.enableRecovery(passphrase)

    /** Has the relay throw the sealed copy away. */
    suspend fun disableRecovery(): String? = messenger.disableRecovery()

    /** This phone has been signed out. The chats and the ID are still here. */
    val signedOut = messenger.signedOut

    /** Signing out, WhatsApp's way: keep everything, stop connecting, stop being woken. */
    fun logOut() {
        viewModelScope.launch { messenger.logOut() }
    }

    /** Signing back in on this phone: same ID, same chats, same pictures. */
    fun logIn() {
        viewModelScope.launch { messenger.resumeSession() }
    }

    /** Shown once right after first-time login so permissions work out of the box. */
    val needsPermissionSetup = messenger.needsPermissionSetup

    fun completePermissionSetup() {
        messenger.completePermissionSetup()
        refreshDelivery()
        refreshPrivacy()
    }

    val fileProgress = messenger.fileProgress

    private val attachProblem = MutableStateFlow<String?>(null)
    private val _fileNotice = MutableStateFlow<String?>(null)

    /** Brief confirmation when a file or media item is saved (e.g. "Saved to Gallery"). */
    val fileNotice: StateFlow<String?> = _fileNotice.asStateFlow()

    fun clearFileNotice() {
        _fileNotice.value = null
    }

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
                is Messenger.LoginStep.ExistingAccount -> LoginUi(
                    step = LoginUi.Step.Existing,
                    email = email,
                    existing = LoginUi.Existing(step.id, step.username, step.hasRecovery),
                )
                is Messenger.LoginStep.Restored -> if (step.problem == null) {
                    onDone
                } else {
                    _login.value.copy(working = false, problem = step.problem)
                }
                is Messenger.LoginStep.Replaced -> if (step.problem == null) {
                    onDone
                } else {
                    _login.value.copy(working = false, problem = step.problem)
                }
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

    /** Restores the ID this address already has, from its sealed backup. */
    fun loginRestore(passphrase: CharArray) {
        val email = _login.value.email
        loginStep(email, { messenger.restoreAccount(passphrase) }, onDone = LoginUi(email = email))
    }

    /** Starts a new ID in place of the one this address has, on purpose, having been told what that costs. */
    fun loginStartNewIdentity() {
        val email = _login.value.email
        loginStep(email, { messenger.startNewIdentity() }, onDone = LoginUi(email = email))
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
        viewModelScope.launch {
            val problem = messenger.sendFile(peer, uri)
            attachProblem.value = problem
            if (problem == null) messenger.sounds.playSent()
        }
    }

    /** Several pictures or documents picked at once, sent one after the other. */
    fun sendFiles(peer: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val problem = messenger.sendFiles(peer, uris)
            attachProblem.value = problem
            // Several files sent in one action make one sound, not a burst.
            if (problem == null) messenger.sounds.playSent()
        }
    }

    /**
     * Opens the file of message [seq] in whichever app on this phone reads that
     * kind of file, so a PDF opens in a PDF reader and a video in a player.
     */
    fun openFile(seq: Long, file: Attachment) {
        viewModelScope.launch {
            val uri = messenger.fileUri(seq, file)
            if (uri == null) {
                attachProblem.value = "That file is not on this phone yet."
                return@launch
            }
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, file.mime.ifBlank { "*/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            view.clipData = ClipData.newRawUri(file.name, uri)
            try {
                getApplication<Application>().startActivity(
                    Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                attachProblem.value = "No app on this phone opens ${file.name}."
            } catch (_: SecurityException) {
                attachProblem.value = "This phone would not let another app read that file."
            }
        }
    }

    /**
     * Hands the file of message [seq] to another app on this phone: the
     * original name, the right kind, and read-only permission for a while.
     * The file itself is not copied, so sharing twice makes no second file.
     */
    fun shareFile(seq: Long, file: Attachment) {
        viewModelScope.launch {
            val uri = messenger.fileUri(seq, file)
            if (uri == null) {
                attachProblem.value = "That file is not on this phone yet."
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType(file.mime.ifBlank { "application/octet-stream" })
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            send.clipData = ClipData.newRawUri(file.name, uri)
            try {
                getApplication<Application>().startActivity(
                    Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
                attachProblem.value = "No app on this phone can share ${file.name}."
            } catch (_: SecurityException) {
                attachProblem.value = "This phone would not let another app read that file."
            }
        }
    }

    fun startFile(seq: Long) = messenger.startFile(seq)

    fun saveFile(seq: Long, file: Attachment) {
        viewModelScope.launch {
            val saved = messenger.saveFileToDevice(seq, file)
            if (saved != null) {
                _fileNotice.value = saved
            } else {
                attachProblem.value = "The file could not be saved."
            }
        }
    }

    /** Bumped whenever a profile picture changes, so that the names and lists redraw. */
    private val _avatarRevision = MutableStateFlow(0)

    /** Which pictures exist, by person: read after a change to know what to draw. */
    val avatarRevision: StateFlow<Int> = _avatarRevision.asStateFlow()

    /** Whether this phone's own picture is kept here but not yet shared with contacts. */
    private val _avatarPending = MutableStateFlow(messenger.avatarAwaitingUpload())

    val avatarPending: StateFlow<Boolean> = _avatarPending.asStateFlow()

    init {
        // A picture that arrived from a contact, or left for one, redraws the avatars.
        viewModelScope.launch {
            messenger.avatarChanges.collect {
                _avatarPending.value = messenger.avatarAwaitingUpload()
                _avatarRevision.update { revision -> revision + 1 }
            }
        }
    }

    /**
     * Keeps [uri] as this phone's own picture and shares it with contacts. The
     * picture is shown here at once; it reaches the relay as soon as there is
     * a connection, so no one is asked to wait for one.
     */
    fun setAvatar(uri: Uri) {
        viewModelScope.launch {
            val why = messenger.setAvatar(uri)
            if (why != null) attachProblem.value = why
            _avatarPending.value = messenger.avatarAwaitingUpload()
            _avatarRevision.update { it + 1 }
        }
    }

    /** Takes this phone's picture down, here and for the people it was shown to. */
    fun removeAvatar() {
        viewModelScope.launch {
            messenger.removeAvatar()
            _avatarPending.value = messenger.avatarAwaitingUpload()
            _avatarRevision.update { it + 1 }
        }
    }

    suspend fun image(seq: Long, file: Attachment, maxPixels: Int): Bitmap? = messenger.image(seq, file, maxPixels)

    fun cachedImage(seq: Long, maxPixels: Int): Bitmap? = messenger.cachedImage(seq, maxPixels)

    private val player = VoicePlayer(viewModelScope)

    /** The voice message that is loaded for playing, if any. */
    val voice: StateFlow<VoiceState?> = player.state

    /** A voice message was recorded for [peer]. The file is taken over and deleted. */
    fun sendVoice(peer: String, recording: File, millis: Long) {
        viewModelScope.launch {
            val problem = messenger.sendVoice(peer, recording, millis)
            attachProblem.value = problem
            if (problem == null) messenger.sounds.playSent()
        }
    }

    /** Plays the voice message of message [seq], or pauses it. */
    fun toggleVoice(seq: Long, file: Attachment) {
        viewModelScope.launch {
            val played = player.toggle(seq) { messenger.voiceBytes(seq, file) }
            if (!played) attachProblem.value = "That voice message could not be played."
        }
    }

    fun stopVoice() = player.stop()

    /** Calls [peer], with pictures when [video]. If it cannot be done right now, says why. */
    fun startCall(peer: String, video: Boolean = false) {
        player.stop()
        messenger.startCall(peer, video)?.let { attachProblem.value = it }
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

    private val _privacy = MutableStateFlow(readPrivacy())

    /** Privacy, media saving, and chat wallpaper switches. Call [refreshPrivacy] to bring it up to date. */
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

    /** Reads privacy, media-saving, and wallpaper switches and whether the phone has a screen lock. */
    fun refreshPrivacy() {
        _privacy.value = readPrivacy()
    }

    private fun readPrivacy(): PrivacyUi {
        val context = getApplication<Application>()
        return PrivacyUi(
            appLock = AppLock.enabled(context),
            canLock = AppLock.available(context),
            blockScreenshots = AppLock.blocksScreenshots(context),
            lockBlocksScreenshots = AppLock.lockBlocksScreenshots,
            notifyText = Notifications(context).showsText,
            lockScreenCallsBlocked = !Notifications(context).mayShowCallsOverLockScreen(),
            telecomCalls = TelecomBridge.enabled(context),
            batterySaving = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(context.packageName) == false,
            saveIncomingFiles = MediaSaver.saveIncomingFilesEnabled(context),
            chatPattern = MediaSaver.chatPatternEnabled(context),
        )
    }

    fun setSaveIncomingFiles(on: Boolean) {
        MediaSaver.setSaveIncomingFilesEnabled(getApplication(), on)
        refreshPrivacy()
    }

    fun setChatPattern(on: Boolean) {
        MediaSaver.setChatPatternEnabled(getApplication(), on)
        refreshPrivacy()
    }

    private val _soundChoices = MutableStateFlow(
        SoundChoices(
            sent = SoundSettings.sent(getApplication()),
            incoming = SoundSettings.incoming(getApplication()),
        ),
    )

    /** What the "Message sounds" rows of Settings show. */
    val soundChoices: StateFlow<SoundChoices> = _soundChoices.asStateFlow()

    fun refreshSounds() {
        val context: Context = getApplication()
        _soundChoices.value = SoundChoices(
            sent = SoundSettings.sent(context),
            incoming = SoundSettings.incoming(context),
        )
    }

    fun setSentSound(choice: String) {
        SoundSettings.setSent(getApplication(), choice)
        refreshSounds()
        // Heard at once, so that the choice means something before the next message.
        if (choice != SoundSettings.SENT_SILENT) messenger.sounds.previewSent()
    }

    fun setIncomingSound(choice: String) {
        SoundSettings.setIncoming(getApplication(), choice)
        refreshSounds()
        // The notification channel follows the choice; it is made once and
        // then left to Android, never rewritten.
        Notifications(getApplication()).createChannels()
    }

    fun previewIncomingSound(choice: String) = messenger.sounds.previewIncoming(choice)

    fun setStayConnected(on: Boolean) {
        Background.setStayConnected(getApplication(), on)
        refreshDelivery()
    }

    suspend fun checkCode(text: String, typed: Boolean): CodeCheck = messenger.checkCode(text, typed)

    /** The conversation a tapped notification was about, until the screen has gone there. */
    val openChat = messenger.openChat

    fun chatOpened() = messenger.requestChat(null)

    fun setTelecomCalls(on: Boolean) {
        TelecomBridge.setEnabled(getApplication(), on)
        refreshPrivacy()
    }

    fun setNotifyText(on: Boolean) {
        Notifications(getApplication()).showsText = on
        refreshPrivacy()
    }

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

    /** What each message of a conversation carries, as it changes. */
    fun reactions(peer: String): Flow<Map<String, Reactions>> = messenger.reactions(peer)

    /** Puts an emoji on a message, or takes it off when [emoji] is empty. */
    fun react(peer: String, messageId: String, emoji: String) {
        viewModelScope.launch {
            try {
                messenger.react(peer, messageId, emoji)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The message went away under the bar; nothing to mark.
            }
        }
    }

    fun sendText(peer: String, text: String, replyTo: String? = null) {
        viewModelScope.launch {
            try {
                messenger.sendText(peer, text, replyTo)
                // Handed to the sending queue: one sound, once. Nothing that
                // comes back later (a receipt, a retry, a message read from
                // storage) makes a sound of its own.
                messenger.sounds.playSent()
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
