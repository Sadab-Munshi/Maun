package org.maun.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import org.maun.messenger.network.AuthApi
import org.maun.messenger.webrtc.CallManager
import org.maun.messenger.webrtc.CallSignals
import org.maun.messenger.webrtc.CallUi
import org.maun.messenger.webrtc.RingService
import org.maun.messenger.network.Addresses
import org.maun.messenger.network.AvatarApi
import org.maun.messenger.network.FileApi
import org.maun.messenger.network.LocationApi
import org.maun.messenger.network.LocationException
import org.maun.messenger.push.LocationService
import org.maun.messenger.network.Usernames
import org.maun.messenger.security.DeviceFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.maun.messenger.crypto.ContactCode
import org.maun.messenger.crypto.DeviceKey
import org.maun.messenger.crypto.Identity
import org.maun.messenger.crypto.Recovery
import org.maun.messenger.crypto.SignalSessions
import org.maun.messenger.data.AccountEntity
import org.maun.messenger.data.AppDatabase
import org.maun.messenger.data.BlockedEntity
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.DatabaseKey
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.Reactions
import org.maun.messenger.data.MetaEntry
import org.maun.messenger.data.RoomSignalStorage
import org.maun.messenger.network.PushAddress
import org.maun.messenger.network.PushState
import org.maun.messenger.network.RelayClient
import org.maun.messenger.network.RelayConnection
import org.maun.messenger.network.RelayException
import org.maun.messenger.network.RelayState
import org.maun.messenger.push.PushApps
import org.maun.messenger.push.ChatPushPart
import org.maun.messenger.push.Background
import org.maun.messenger.push.FetchWorker
import org.maun.messenger.push.Notifications
import org.maun.messenger.updates.Updater
import org.maun.messenger.push.newestUnread
import org.maun.messenger.push.announce

sealed interface AccountState {
    data object Loading : AccountState

    /** First launch: nothing has been created yet. */
    data object None : AccountState

    /**
     * [code] is this user's own contact code. [registered] turns true once the
     * relay has accepted the account.
     */
    data class Present(val code: ContactCode, val relayUrl: String, val registered: Boolean) : AccountState

    /** The encrypted storage cannot be opened on this phone any more. */
    data class Broken(val message: String) : AccountState
}

/** What happened when the user tried to add a contact. */
sealed interface CodeCheck {
    data class Ok(val code: ContactCode) : CodeCheck
    data object NotACode : CodeCheck
    data object OwnCode : CodeCheck
    data class OtherRelay(val relay: String) : CodeCheck
    data class AlreadyAdded(val name: String) : CodeCheck
}

/**
 * The one long-lived object behind the screens: the account, its connection to
 * the relay, the contact list and the conversations. Created once per app
 * process.
 */
class Messenger(context: Context) {

    private val appContext = context.applicationContext
    private val settingsPrefs = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relayClient = RelayClient()

    /** The update check: asked for from Settings, never by itself. */
    val updates = Updater(appContext)

    private val _needsPermissionSetup = MutableStateFlow(
        settingsPrefs.getBoolean(KEY_PERMISSIONS_SETUP_PENDING, false),
    )

    /** Shown once right after the user logs in for the first time, until completed or skipped. */
    val needsPermissionSetup: StateFlow<Boolean> = _needsPermissionSetup.asStateFlow()

    private val _account = MutableStateFlow<AccountState>(AccountState.Loading)
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _relay = MutableStateFlow<RelayState>(RelayState.Offline)
    val relay: StateFlow<RelayState> = _relay.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactEntity>>(emptyList())
    val contacts: StateFlow<List<ContactEntity>> = _contacts.asStateFlow()

    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())

    /** The newest message of every conversation, most recent conversation first. */
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()

    private val _blocked = MutableStateFlow<List<BlockedEntity>>(emptyList())
    val blocked: StateFlow<List<BlockedEntity>> = _blocked.asStateFlow()

    private val _memberEmail = MutableStateFlow<String?>(null)

    /** On a member-only relay: the email address this ID is tied to. Null on an open relay. */
    val memberEmail: StateFlow<String?> = _memberEmail.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)

    /** On a member-only relay: this account's username. */
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _awaiting = MutableStateFlow(false)

    /** The email login is done and the relay's administrator has not approved this account yet. */
    val awaitingApproval: StateFlow<Boolean> = _awaiting.asStateFlow()

    /** The relay's login routes, and the ticket of a login that still needs a username. */
    @Volatile
    private var auth: AuthApi? = null

    @Volatile
    private var unnamedTicket: String? = null

    private val _moved = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

    /** A contact's ID changed, from the first to the second, because they set Maun up again. */
    val moved: SharedFlow<Pair<String, String>> = _moved.asSharedFlow()

    /**
     * What this phone says about itself to a member-only relay: collected
     * once, the first time such a relay asks. An open relay is told nothing.
     */
    private val phoneReport by lazy { DeviceFingerprint.collect(appContext) }

    private val _banned = MutableStateFlow(false)

    /** The relay's owner has shut this member out. Nothing connects while this is set. */
    val banned: StateFlow<Boolean> = _banned.asStateFlow()

    private val _signedOut = MutableStateFlow(false)

    /**
     * Someone signed this phone out. Nothing connects, nothing wakes it, and
     * no notification is put up; the ID, the keys, the chats, the pictures
     * and the settings stay where they are. See [logOut] and [resumeSession].
     */
    val signedOut: StateFlow<Boolean> = _signedOut.asStateFlow()

    private val _fileProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())

    /** File transfers under way, by message: how much is done, from 0 to 1. */
    val fileProgress: StateFlow<Map<Long, Float>> = _fileProgress.asStateFlow()

    private val _fileProblem = MutableStateFlow<String?>(null)

    /** Why the last file that could not be sent or fetched could not. */
    val fileProblem: StateFlow<String?> = _fileProblem.asStateFlow()

    /**
     * The proof of an approved email address (a Cloudflare Access token),
     * from the moment the login page hands it over until the relay has tied
     * the address to this ID. It is kept in the encrypted database, whose key
     * is in the Android Keystore, and deleted as soon as it has done its job.
     */
    @Volatile
    private var accessToken: String? = null

    /**
     * The ticket of a login that turned out to be for an address that already
     * has a Maun ID. What happens next — restoring it, or starting again over
     * it — is decided with the person before it is shown to the relay.
     */
    @Volatile
    private var restoreTicket: String? = null

    @Volatile
    private var files: FileTransfers? = null

    @Volatile
    private var pictures: ProfilePictures? = null

    /** The relay's live-location routes, once there is an account to sign in with. */
    private var places: LocationApi? = null

    private val _avatarChanges = MutableStateFlow(0)

    /** Bumped whenever a profile picture on this phone is new, so that avatars redraw. */
    val avatarChanges: StateFlow<Int> = _avatarChanges.asStateFlow()

    private val _verified = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Which contacts carry the tick Maun's administrators hand out, by ID. */
    val verified: StateFlow<Map<String, Boolean>> = _verified.asStateFlow()

    private val _ownVerified = MutableStateFlow(false)

    /** Whether this account itself has that tick. */
    val ownVerified: StateFlow<Boolean> = _ownVerified.asStateFlow()

    private val _waiting = MutableStateFlow<Map<String, String>>(emptyMap())

    /** For each person whose messages are stuck: why, in words for the screen. */
    val waiting: StateFlow<Map<String, String>> = _waiting.asStateFlow()

    private val _unsent = MutableStateFlow(0)

    /**
     * How much is saved and waiting to go to the relay. Anything above zero
     * while the app is not on screen is a reason to ask Android to wake this
     * phone as soon as it has a network, so that a message written without one
     * leaves on its own instead of waiting for the app to be opened.
     */
    val unsent: StateFlow<Int> = _unsent.asStateFlow()

    /** What is waiting to go, for the worker that runs while the app is closed. */
    fun unsentNow(): Int = _unsent.value

    private val _pushProblem = MutableStateFlow<String?>(null)

    /** Why this phone cannot be woken for new messages, in words for the screen. Null when all is well. */
    val pushProblem: StateFlow<String?> = _pushProblem.asStateFlow()

    private val notifications = Notifications(appContext)

    /** The two sounds Maun makes itself. Freed when the app closes. */
    val sounds = MessageSounds(appContext)

    /** Voice calls. One at a time; see [CallManager]. */
    val calls = CallManager(
        appContext,
        object : CallSignals {
            override suspend fun send(peer: String, call: Payload.Call, urgent: Boolean): Boolean =
                engine?.sendCall(peer, call, urgent) == true

            override suspend fun servers(): List<ChatEngine.CallServer> =
                (engine ?: throw RelayException("Not connected to the relay.")).callServers()

            override fun note(peer: String, text: String, missed: Boolean) {
                scope.launch { engine?.addNotice(peer, text, missed) }
            }

            override fun ringing(peer: String?, on: Boolean, video: Boolean): Boolean {
                // In front, the call has the whole screen already and rings there.
                if (!on || peer == null || inForeground) {
                    RingService.stop(appContext)
                    notifications.clearIncomingCall()
                    return false
                }
                val name = nameOf(peer)
                if (!RingService.ring(appContext, name, video)) {
                    notifications.showIncomingCall(notifications.incomingCall(name, video))
                }
                return true
            }

            override fun nameOf(peer: String): String = _contacts.value.firstOrNull { it.id == peer }?.name ?: "Maun"
        },
    )

    init {
        // A call needs the connection for as long as it lasts, also behind the lock screen.
        scope.launch { calls.state.collect { synchronized(this@Messenger) { connectOrDrop(linger = true) } } }
    }

    /** Calls [peer], with pictures when [video]. Returns why it cannot be done right now, or null when it is ringing. */
    fun startCall(peer: String, video: Boolean = false): String? = when {
        calls.busy -> "There is a call going on already."
        _relay.value != RelayState.Online -> "Not connected to the relay. Try again in a moment."
        else -> {
            calls.start(peer, video)
            null
        }
    }
    private var announcedThrough = 0L
    private var notificationEpoch = ""
    private val presentation = Mutex()

    private val setup = Mutex()

    @Volatile
    private var database: AppDatabase? = null

    @Volatile
    private var engine: ChatEngine? = null
    private var engineScope: CoroutineScope? = null
    private var connection: RelayConnection? = null
    private var connectionWatcher: Job? = null
    private var pendingStop: Job? = null

    // The three reasons to be connected: the app is on screen, the phone was
    // woken to fetch, or the user asked Maun to stay connected.
    private var inForeground = false
    private var fetches = 0
    private var stayingConnected = false

    init {
        scope.launch { load() }
    }

    private suspend fun load() = setup.withLock {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.open(appContext)
                val row = db.account().get()
                database = db
                // Read once before anything is shown, so that the first screen
                // is the real list and not an empty one that fills in later.
                _contacts.value = db.contacts().observeAll().first()
                // The ticks the relay last gave out, so an offline list looks
                // like the one that was on screen when it went offline.
                _verified.value = Badges.parse(db.meta().get(Badges.KEY))
                _chats.value = db.chat().observeChats().first()
                // Only the first upgrade baselines existing history. Every later launch
                // recovers unread rows committed after the last successful presentation.
                notificationEpoch = db.meta().get(KEY_NOTIFICATION_EPOCH) ?: java.util.UUID.randomUUID().toString().also {
                    db.meta().put(MetaEntry(KEY_NOTIFICATION_EPOCH, it))
                }
                announcedThrough = db.meta().get(KEY_ANNOUNCED)?.toLongOrNull() ?: newestUnread(_chats.value).also {
                    db.meta().put(MetaEntry(KEY_ANNOUNCED, it.toString()))
                }
                scope.launch { db.contacts().observeAll().collect { _contacts.value = it } }
                scope.launch {
                    account.first { it !is AccountState.Loading }
                    db.chat().observeChats().collect {
                        _chats.value = it
                        presentUnread(db)
                    }
                }
                scope.launch { db.chat().observeBlocked().collect { _blocked.value = it } }
                if (row == null) {
                    _account.value = AccountState.None
                } else {
                    if (row.registered && !settingsPrefs.getBoolean(KEY_PERMISSIONS_SETUP_PENDING, false)) {
                        // Already logged in before this launch: do not interrupt with first-login onboarding.
                        settingsPrefs.edit().putBoolean(KEY_PERMISSIONS_SETUP_DONE, true).apply()
                    }
                    open(db, row)
                }
            } catch (e: DatabaseKey.LostException) {
                _account.value = AccountState.Broken(
                    "This phone can no longer unlock Maun's storage. Clearing the app's data " +
                        "in Android settings lets you start again with a new ID.",
                )
            } catch (e: Exception) {
                _account.value = AccountState.Broken(
                    "Maun's storage could not be opened: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    /**
     * Creates this phone's identity and registers it with the relay at
     * [relayInput]. Returns once the identity is saved and the connection has
     * been started; registration itself is reported through [relay] and
     * [account]. Throws [RelayException] with a message for the user when the
     * address is not a working Maun relay.
     *
     * May be called again while the account is still unregistered, for example
     * after a failed first attempt: nobody has seen the old ID, so a new one
     * replaces it.
     */
    suspend fun createAccount(relayInput: String) = setup.withLock {
        val db = database ?: throw RelayException("Maun's storage is not open yet")
        val current = _account.value
        check(current !is AccountState.Present || !current.registered) { "This phone already has an ID" }
        val relayUrl = RelayClient.normalizeRelayUrl(relayInput)
            ?: throw RelayException("That does not look like a web address")
        relayClient.checkHealth(relayUrl)

        closeConnection()
        val row = AccountEntity(
            relayUrl = relayUrl.toString(),
            identityKeyPair = Identity.generate().serialize(),
            registered = false,
            createdAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            // Pre-keys made for an ID that was never registered are signed by
            // that ID's key. They are useless to the new one.
            db.inTransaction {
                db.signal().clearSessions()
                db.signal().clearPreKeys()
                db.signal().clearKyberUses()
            }
            db.meta().put(MetaEntry(KEY_REGISTRATION_ID, SignalSessions.newRegistrationId().toString()))
            // The new account's mailbox has never been given a wake-up address.
            db.meta().put(MetaEntry(KEY_PUSH_UPLOADED, ""))
            db.account().put(row)
        }
        open(db, row)
    }

    /** Publishes the account and starts its connection if the app is on screen. */
    private suspend fun open(db: AppDatabase, row: AccountEntity) {
        val identity = Identity.restore(row.identityKeyPair)
        val relayUrl = row.relayUrl.toHttpUrl()
        // Accounts made before messaging existed have no registration ID yet.
        val registrationId = withContext(Dispatchers.IO) {
            db.meta().get(KEY_REGISTRATION_ID)?.toIntOrNull()
                ?: SignalSessions.newRegistrationId().also {
                    db.meta().put(MetaEntry(KEY_REGISTRATION_ID, it.toString()))
                }
        }
        val (savedToken, savedEmail, wasBanned) = withContext(Dispatchers.IO) {
            Triple(
                db.meta().get(KEY_ACCESS_TOKEN)?.ifEmpty { null },
                db.meta().get(KEY_MEMBER_EMAIL)?.ifEmpty { null },
                db.meta().get(KEY_BANNED) == "1",
            )
        }
        accessToken = savedToken
        _signedOut.value = withContext(Dispatchers.IO) { db.meta().get(KEY_SIGNED_OUT) == "1" }
        _recovery.value = withContext(Dispatchers.IO) {
            val at = db.meta().get(KEY_RECOVERY_SEALED_AT)?.toLongOrNull() ?: 0
            RecoveryUi(on = at > 0, at = at)
        }
        _memberEmail.value = savedEmail
        _banned.value = wasBanned
        _username.value = withContext(Dispatchers.IO) { db.meta().get(KEY_USERNAME)?.ifEmpty { null } }
        _awaiting.value = withContext(Dispatchers.IO) { db.meta().get(KEY_AWAITING) == "1" }
        auth = AuthApi(relayUrl)
        val chatScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val chat = ChatEngine(
            selfId = identity.id,
            signal = SignalSessions(identity, registrationId, RoomSignalStorage(db.signal())),
            dao = db.chat(),
            tx = db,
            scope = chatScope,
            push = object : PushState {
                override suspend fun wanted() = PushAddress.parse(db.meta().get(KEY_PUSH_WANTED))
                override suspend fun uploaded() = PushAddress.parse(db.meta().get(KEY_PUSH_UPLOADED))
                override suspend fun setUploaded(address: PushAddress?) =
                    db.meta().put(MetaEntry(KEY_PUSH_UPLOADED, address?.serialize().orEmpty()))
                override suspend fun rejected() = PushAddress.parse(db.meta().get(KEY_PUSH_REJECTED))
                override suspend fun setRejected(address: PushAddress?) =
                    db.meta().put(MetaEntry(KEY_PUSH_REJECTED, address?.serialize().orEmpty()))
                override fun renew() = PushApps.startOver(appContext)
            },
        )
        chatScope.launch { chat.waiting.collect { _waiting.value = it } }
        chatScope.launch {
            chat.unsent.collect { pending ->
                _unsent.value = pending
                // Written without a connection and then left alone: Android is
                // asked to bring this phone back when there is a network, so
                // the message leaves without the app being opened again.
                if (pending > 0 && !inForeground) FetchWorker.schedule(appContext)
            }
        }
        chatScope.launch { chat.pushProblem.collect { _pushProblem.value = it } }
        chatScope.launch { chat.moved.collect { _moved.emit(it) } }
        val created = RelayConnection(
            relayUrl = relayUrl,
            identity = identity,
            device = DeviceKey(appContext),
            scope = scope,
            isRegistered = { (_account.value as? AccountState.Present)?.registered == true },
            onLoggedIn = ::markRegistered,
            onOnline = {
                chat.onOnline()
                _ownVerified.value = chat.link?.ready?.verified == true
                refreshBadges()
                scope.launch { pictures?.sync() }
                val ready = chat.link?.ready
                scope.launch { onLoggedInAs(db, chat, ready?.email, ready?.name) }
                val relayKey = PushAddress.keyForPushApp(ready?.relayKey)
                // Talks to another app; not something to do from inside the connection.
                if (relayKey != null) scope.launch { Background.onRelayKey(appContext, relayKey) }
            },
            onFrame = chat::onFrame,
            onOffline = chat::onOffline,
            accessToken = { accessToken },
            awaitingApproval = { _awaiting.value },
            phone = { phoneReport.toJson() },
        )
        chat.link = created
        chat.onCall = calls::onSignal
        val transfers = FileTransfers(
            context = appContext,
            engine = chat,
            dao = db.chat(),
            api = FileApi(
                relayUrl = relayUrl,
                selfId = identity.id,
                token = { created.ready?.filesToken },
                onBanned = { scope.launch { onBanned() } },
            ),
            scope = chatScope,
            // A transfer asked for while the connection is still coming up waits for it a little.
            whenOnline = { withTimeoutOrNull(ONLINE_WAIT_MILLIS) { created.state.first { it == RelayState.Online } } },
        )
        files = transfers
        val avatars = ProfilePictures(
            context = appContext,
            api = AvatarApi(
                relayUrl = relayUrl,
                selfId = identity.id,
                token = { created.ready?.filesToken },
                onBanned = { scope.launch { onBanned() } },
            ),
            meta = db.meta(),
            contacts = { _contacts.value.map { it.id } },
            ask = { peer -> chat.askAvatar(peer) },
            send = { peer, picture -> chat.sendAvatar(peer, picture) },
            whenOnline = { withTimeoutOrNull(ONLINE_WAIT_MILLIS) { created.state.first { it == RelayState.Online } } },
            onChanged = { _avatarChanges.update { it + 1 } },
        )
        pictures = avatars
        places = LocationApi(
            relayUrl = relayUrl,
            selfId = identity.id,
            token = { created.ready?.filesToken },
            onBanned = { scope.launch { onBanned() } },
        )
        chat.myAvatar = { avatars.mine() }
        chatScope.launch { avatars.load() }
        chatScope.launch { avatars.sync() }
        chatScope.launch { chat.avatars.collect { (peer, picture) -> avatars.onSignal(peer, picture) } }
        chatScope.launch { transfers.progress.collect { _fileProgress.value = it } }
        chatScope.launch { transfers.problem.collect { _fileProblem.value = it } }
        chatScope.launch { transfers.sweep() }
        synchronized(this) {
            engine = chat
            engineScope = chatScope
            connection = created
            // A share that was still running when the app was last closed carries on.
            chatScope.launch { resumeRunningShares() }
            // Publish the account only after the engine and file loader exist.
            // Otherwise the first conversation can try to load a preview too early.
            _account.value = AccountState.Present(
                code = ContactCode(identity.id, ContactCode.relayName(relayUrl)),
                relayUrl = row.relayUrl,
                registered = row.registered,
            )
            connectionWatcher = scope.launch {
                created.state.collect { state ->
                    _relay.value = state
                    when (state) {
                        RelayState.Banned -> onBanned()
                        // The ticket has been handed over; from here on the relay knows this phone.
                        RelayState.Pending -> {
                            setAccessToken(null)
                            setAwaiting(true)
                        }
                        RelayState.Online -> setAwaiting(false)
                        is RelayState.LoginNeeded -> {
                            // A ticket that was shown and not taken is of no further use,
                            // and a request the administrator refused is over.
                            if (state.problem != null) setAccessToken(null)
                            setAwaiting(false)
                        }
                        else -> Unit
                    }
                }
            }
            if (wanted()) created.start()
        }
    }

    private suspend fun markRegistered() {
        val current = _account.value as? AccountState.Present ?: return
        if (current.registered) return
        withContext(Dispatchers.IO) { database?.account()?.markRegistered() }
        triggerFirstLoginPermissionSetup()
        _account.value = current.copy(registered = true)
    }

    private fun triggerFirstLoginPermissionSetup() {
        if (settingsPrefs.getBoolean(KEY_PERMISSIONS_SETUP_DONE, false)) return
        settingsPrefs.edit().putBoolean(KEY_PERMISSIONS_SETUP_PENDING, true).apply()
        _needsPermissionSetup.value = true
    }

    fun completePermissionSetup() {
        settingsPrefs.edit()
            .putBoolean(KEY_PERMISSIONS_SETUP_PENDING, false)
            .putBoolean(KEY_PERMISSIONS_SETUP_DONE, true)
            .apply()
        _needsPermissionSetup.value = false
    }

    private fun closeConnection() = synchronized(this) {
        connectionWatcher?.cancel()
        connectionWatcher = null
        connection?.stop()
        connection = null
        engineScope?.cancel()
        engineScope = null
        engine = null
        files = null
        _relay.value = RelayState.Offline
        _waiting.value = emptyMap()
        // Nothing is waiting to go once there is nothing to send it with.
        _unsent.value = 0
    }

    // ---- When to be connected --------------------------------------------------

    private fun wanted() =
        wantsConnection(_banned.value, _signedOut.value, inForeground, fetches, stayingConnected, calls.busy)

    // ---- Member-only relays ----------------------------------------------------

    private suspend fun setAccessToken(token: String?) {
        accessToken = token
        withContext(Dispatchers.IO) { database?.meta()?.put(MetaEntry(KEY_ACCESS_TOKEN, token.orEmpty())) }
    }

    private suspend fun setAwaiting(on: Boolean) {
        if (_awaiting.value == on) return
        _awaiting.value = on
        withContext(Dispatchers.IO) { database?.meta()?.put(MetaEntry(KEY_AWAITING, if (on) "1" else "")) }
    }

    /** What the login screen does next. */
    sealed interface LoginStep {
        /** The relay is being shown the ticket; the connection state says how that went. */
        data object Done : LoginStep

        /** A new member: a username has to be picked first. */
        data object NeedsUsername : LoginStep

        /**
         * This email address already has a Maun ID ([id], called [username] on
         * the relay), and this phone is not it. Nothing has been changed yet.
         * [hasRecovery] says whether that ID left a sealed backup that can be
         * opened with its passphrase; if it did not, the only way on is to say
         * on purpose that a new ID is to take its place.
         */
        data class ExistingAccount(
            val id: String,
            val username: String?,
            val hasRecovery: Boolean,
        ) : LoginStep

        /** How restoring the existing ID went. [problem] is null when it worked. */
        data class Restored(val problem: String? = null) : LoginStep

        /** How starting a new ID over the existing one went. */
        data class Replaced(val problem: String? = null) : LoginStep

        data class Problem(val message: String) : LoginStep
    }

    /** Where this account stands with a sealed backup, for the profile screen. */
    data class RecoveryUi(val on: Boolean = false, /** When the last backup was left on the relay, in millis. */ val at: Long = 0)

    /** Asks the relay to send a login code to [email]. Returns why it could not, or null. */
    suspend fun requestLoginCode(email: String): String? =
        (auth ?: return NOT_READY).start(email.trim())

    /** Hands in the code from the email. */
    suspend fun submitLoginCode(email: String, code: String): LoginStep {
        val api = auth ?: return LoginStep.Problem(NOT_READY)
        return when (val answer = api.verify(email.trim(), code.filter { it.isDigit() })) {
            is AuthApi.Verified.Problem -> LoginStep.Problem(answer.message)
            is AuthApi.Verified.Ok -> {
                val own = (_account.value as? AccountState.Present)?.code?.id
                when {
                    // The address has an ID, and this phone is not that ID. It
                    // is offered the sealed backup, and nothing else happens
                    // until the person answers: no account is touched, no
                    // mailbox is retired, nothing waiting is thrown away.
                    answer.id != null && answer.id != own -> {
                        restoreTicket = answer.ticket
                        LoginStep.ExistingAccount(answer.id, answer.username, answer.hasRecovery)
                    }
                    answer.username == null -> {
                        unnamedTicket = answer.ticket
                        LoginStep.NeedsUsername
                    }
                    else -> {
                        presentTicket(answer.ticket)
                        LoginStep.Done
                    }
                }
            }
        }
    }

    /**
     * Restores the ID this address already has, from the sealed backup on the
     * relay, and signs in as it.
     *
     * The relay is never asked to hand the ID over: what is fetched is
     * ciphertext, and the passphrase is tried here, on the phone. Only if the
     * backup opens *and* is the ID the relay named for this address is it
     * installed; anything else is refused, and this phone stays as it was.
     */
    suspend fun restoreAccount(passphrase: CharArray): LoginStep {
        val api = auth ?: return LoginStep.Restored(NOT_READY)
        val ticket = restoreTicket ?: return LoginStep.Restored("That login has run out. Please start again.")
        val found = api.recovery(ticket)
        val backup = when (found) {
            is AuthApi.Found.Problem -> return LoginStep.Restored(found.message)
            AuthApi.Found.NoBackup -> return LoginStep.Restored(
                "There is no recovery backup for this account, so its keys cannot be brought to this phone.",
            )
            is AuthApi.Found.Ok -> found.backup
        }
        val contents = try {
            Recovery.open(passphrase, backup.salt, backup.blob, backup.id)
        } catch (_: Recovery.WrongPassphrase) {
            return LoginStep.Restored("That passphrase does not open this backup.")
        } catch (_: Recovery.NotMine) {
            return LoginStep.Restored("That backup belongs to a different account.")
        }
        installIdentity(contents)
        restoreTicket = null
        // The relay is shown the ticket again, this time from the old ID: the
        // account, its username, its badge and its picture are all still its.
        presentTicket(ticket)
        return LoginStep.Restored()
    }

    /**
     * Says on purpose that a new ID is to take the place of the one this
     * address has. Everything the old ID had — its username, its badge, its
     * picture, anything waiting for it — goes with it, and the people who write
     * to it are told it is gone. Nothing here can bring its messages back.
     */
    suspend fun startNewIdentity(): LoginStep {
        val api = auth ?: return LoginStep.Replaced(NOT_READY)
        val ticket = restoreTicket ?: return LoginStep.Replaced("That login has run out. Please start again.")
        api.replace(ticket)?.let { return LoginStep.Replaced(it) }
        restoreTicket = null
        presentTicket(ticket)
        return LoginStep.Replaced()
    }

    /**
     * Puts a restored identity in place of the one this phone made for itself.
     * The database is empty at this point (the account is being set up), so the
     * sessions and pre-keys made for the new ID are thrown away with it.
     */
    private suspend fun installIdentity(contents: Recovery.Contents) = setup.withLock {
        val db = database ?: throw RelayException(NOT_READY)
        val current = _account.value
        check(current is AccountState.Present) { NOT_READY }
        closeConnection()
        val row = AccountEntity(
            relayUrl = current.relayUrl,
            identityKeyPair = contents.identity,
            registered = false,
            createdAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            db.inTransaction {
                db.signal().clearSessions()
                db.signal().clearPreKeys()
                db.signal().clearKyberUses()
            }
            db.meta().put(MetaEntry(KEY_REGISTRATION_ID, contents.registrationId.toString()))
            // A mailbox this phone has not been woken on yet.
            db.meta().put(MetaEntry(KEY_PUSH_UPLOADED, ""))
            // The picture this account already has on the relay: its record is
            // what opens it, and it came inside the backup.
            db.meta().put(MetaEntry(ProfilePictures.KEY_SELF, contents.avatar))
            db.account().put(row)
        }
        open(db, row)
    }

    // ---- Sealed identity backup ------------------------------------------------

    private val _recovery = MutableStateFlow(RecoveryUi())

    /** Whether this account can be recovered on another phone, and when it last could. */
    val recovery: StateFlow<RecoveryUi> = _recovery.asStateFlow()

    /**
     * Leaves a sealed copy of this account on the relay. The passphrase is used
     * here and nowhere else: what the relay gets is ciphertext, and it is told
     * nothing that would let it open it. Returns why it could not, or null.
     */
    suspend fun enableRecovery(passphrase: CharArray): String? {
        if (!Recovery.isStrongEnough(passphrase)) {
            return "Use a passphrase of at least ${Recovery.MIN_PASSPHRASE} characters. It is the only thing " +
                "that can open this backup."
        }
        val db = database ?: return NOT_READY
        val account = _account.value as? AccountState.Present ?: return NOT_READY
        val contents = withContext(Dispatchers.IO) {
            Recovery.Contents(
                maunId = account.code.id,
                identity = db.account().get()?.identityKeyPair ?: return@withContext null,
                registrationId = db.meta().get(KEY_REGISTRATION_ID)?.toIntOrNull() ?: 0,
                avatar = db.meta().get(ProfilePictures.KEY_SELF).orEmpty(),
            )
        } ?: return NOT_READY
        if (contents.identity.isEmpty()) return NOT_READY
        val sealed = Recovery.seal(passphrase, contents)
        val encoder = java.util.Base64.getEncoder()
        val problem = engine?.putRecovery(
            encoder.encodeToString(sealed.salt),
            sealed.kdf,
            encoder.encodeToString(sealed.blob),
        )
        if (problem != null) return problem
        val at = System.currentTimeMillis()
        withContext(Dispatchers.IO) { db.meta().put(MetaEntry(KEY_RECOVERY_SEALED_AT, at.toString())) }
        _recovery.value = RecoveryUi(on = true, at = at)
        return null
    }

    /** Has the relay throw the sealed copy away: this account is not recoverable any more. */
    suspend fun disableRecovery(): String? {
        val db = database ?: return NOT_READY
        val problem = engine?.dropRecovery()
        if (problem != null) return problem
        withContext(Dispatchers.IO) { db.meta().put(MetaEntry(KEY_RECOVERY_SEALED_AT, "")) }
        _recovery.value = RecoveryUi()
        return null
    }

    /** A new member's choice of username. */
    suspend fun chooseUsername(typed: String): LoginStep {
        val api = auth ?: return LoginStep.Problem(NOT_READY)
        val ticket = unnamedTicket ?: return LoginStep.Problem("That login has run out. Please start again.")
        val name = Usernames.clean(typed)
        if (!Usernames.isValid(name)) return LoginStep.Problem("A username has ${Usernames.RULE}")
        api.name(ticket, name)?.let { return LoginStep.Problem(it) }
        unnamedTicket = null
        _username.value = name
        presentTicket(ticket)
        return LoginStep.Done
    }

    /** What looking a username up came to. */
    sealed interface UserSearch {
        data class Found(val id: String, val name: String) : UserSearch
        data class Problem(val message: String) : UserSearch
    }

    /** Asks the relay which ID has the username [typed]. Only member-only relays have usernames. */
    suspend fun findUser(typed: String): UserSearch {
        val name = Usernames.clean(typed)
        if (!Usernames.isValid(name)) return UserSearch.Problem("A username has ${Usernames.RULE}")
        val own = (_account.value as? AccountState.Present)?.code?.id
        val answer = try {
            (engine?.link ?: return UserSearch.Problem(NOT_READY)).request(JSONObject().put("t", "find").put("name", name))
        } catch (e: RelayException) {
            return UserSearch.Problem("Not connected to the relay. Try again in a moment.")
        }
        val id = answer.optString("user")
        return when {
            answer.optString("t") != "found" || !ContactCode.isId(id) -> UserSearch.Problem("Nobody here has the username @$name.")
            id == own -> UserSearch.Problem("That is your own username.")
            else -> {
                val existing = withContext(Dispatchers.IO) { database?.contacts()?.get(id) }
                if (existing != null) UserSearch.Problem("@$name is already in your contacts as ${existing.name}.")
                else UserSearch.Found(id, answer.optString("name").ifEmpty { name })
            }
        }
    }

    /** The username of the member with this ID, or null when the relay knows none. */
    suspend fun usernameOf(id: String): String? = try {
        val answer = engine?.link?.request(JSONObject().put("t", "find").put("user", id))
        answer?.takeIf { it.optString("t") == "found" }?.optString("name")?.ifEmpty { null }
    } catch (_: RelayException) {
        null
    }

    /**
     * The email login has produced a ticket. Stores it and connects again,
     * this time showing it to the relay. Returns once the relay is being
     * asked, so that the screen does not fall back to the login for a moment.
     */
    private suspend fun presentTicket(ticket: String) {
        setAccessToken(ticket)
        synchronized(this) {
            connection?.stop()
            if (wanted()) connection?.start()
        }
        withTimeoutOrNull(TICKET_SHOWN_WAIT_MILLIS) { _relay.first { it !is RelayState.LoginNeeded } }
    }

    /**
     * Runs after every login. On a member-only relay ([email] is set) the
     * ticket has done its job and is deleted. (Which phone this is, the
     * relay was told with the login itself: see [phoneReport].)
     */
    private suspend fun onLoggedInAs(db: AppDatabase, chat: ChatEngine, email: String?, name: String?) {
        if (_memberEmail.value != email) {
            _memberEmail.value = email
            withContext(Dispatchers.IO) { db.meta().put(MetaEntry(KEY_MEMBER_EMAIL, email.orEmpty())) }
        }
        if (_username.value != name) {
            _username.value = name
            withContext(Dispatchers.IO) { db.meta().put(MetaEntry(KEY_USERNAME, name.orEmpty())) }
        }
        if (email == null) return
        if (accessToken != null) {
            triggerFirstLoginPermissionSetup()
            setAccessToken(null)
        }
    }

    /**
     * The relay says this member is banned (a closed connection with the ban
     * code, or a 403 from the file routes). Disconnects, forgets the login
     * token, and deletes every message and file on this phone. Contacts and
     * the ID stay, so that lifting the ban restores the account.
     */
    private suspend fun onBanned() {
        val wasBanned = _banned.value
        _banned.value = true
        synchronized(this) {
            pendingStop?.cancel()
            pendingStop = null
            connection?.stop()
        }
        setAccessToken(null)
        withContext(Dispatchers.Main) { calls.hangUp() }
        engine?.wipeMessages()
        files?.wipe()
        notifications.clearMessages()
        if (!wasBanned) withContext(Dispatchers.IO) { database?.meta()?.put(MetaEntry(KEY_BANNED, "1")) }
    }

    /** Asks the relay once more. If the ban still stands, [banned] is set again within moments. */
    fun checkBanAgain() {
        scope.launch {
            withContext(Dispatchers.IO) { database?.meta()?.put(MetaEntry(KEY_BANNED, "")) }
            _banned.value = false
            synchronized(this@Messenger) {
                connection?.stop()
                if (wanted()) connection?.start()
            }
        }
    }

    // ---- Profile pictures -------------------------------------------------------

    /**
     * Keeps the picture at [uri] as this phone's own and tells its contacts,
     * now or at the next connection. Returns why a picture could not be read.
     */
    suspend fun setAvatar(uri: Uri): String? =
        (pictures ?: return "Maun is not ready yet. Try again in a moment.").publish(uri)

    /** Takes this phone's picture down, here and from everyone who was shown it. */
    suspend fun removeAvatar() {
        pictures?.remove()
    }

    /** Whether this phone's picture is still waiting for a connection to be shared. */
    fun avatarAwaitingUpload(): Boolean = pictures?.awaitingUpload == true

    /** Asks for the pictures of every contact this phone has not been told about. */
    fun syncAvatars() {
        scope.launch { pictures?.sync() }
    }

    // ---- Files -----------------------------------------------------------------

    /** Encrypts the file at [uri] and starts sending it to [peer]. Returns why it could not be, or null. */
    // ---- Live location -------------------------------------------------------

    /**
     * Starts sharing where this phone is with [peer], for [minutes] (fifteen
     * or sixty), and says so in the conversation. Returns why it could not, or
     * null when the share is running.
     */
    suspend fun startLiveShare(peer: String, minutes: Int): String? {
        val api = places ?: return "Not connected to the relay yet."
        val engine = engine ?: return "Not connected to the relay yet."
        if (!mayShareLocation()) return "Maun needs to be allowed to use this phone's location."
        val started = try {
            withContext(Dispatchers.IO) { api.start(peer, minutes) }
        } catch (e: LocationException) {
            return e.message ?: "The relay could not start the share."
        } catch (_: IOException) {
            return "The connection was lost. Try again."
        }
        val (share, until) = started
        // The key is made here and travels only inside the encrypted message
        // below; the points kept on the relay are opened by it and nothing else.
        val key = LiveSeal.newKey()
        engine.addLiveShare(peer, share, key, minutes, until)
        LocationService.start(appContext, peer, share, key, until, nameOf(peer))
        return null
    }

    /**
     * Sends where this phone is, once, to [peer]. Returns why it could not, or
     * null when it went.
     *
     * This is the other half of the feature: a share keeps saying where the
     * phone is until its time runs out, and this says it once and stops. No
     * relay row is made, no service starts, no notification appears and
     * nothing is left running behind it — the place is a message, and the
     * only thing that happens after it is that it arrives.
     */
    suspend fun sendCurrentLocation(peer: String): String? {
        val engine = engine ?: return "Not connected to the relay yet."
        if (!mayShareLocation()) return "Maun needs to be allowed to use this phone's location."
        if (!locationSwitchedOn(appContext)) return "This phone's location is switched off."
        val fix = currentPlace()
            ?: return "The phone could not say where it is. A window or the open sky helps."
        // What the place is called, if OpenStreetMap can say. Asked once, from
        // this phone, with the answer kept: the phone that receives the place
        // learns the name from the message and never asks anybody anything.
        val label = withTimeoutOrNull(4_000L) { addresses()?.of(fix.latitude, fix.longitude) }.orEmpty()
        val place = fix.copy(label = label)
        return try {
            engine.sendPlace(peer, place)
            null
        } catch (e: IllegalStateException) {
            e.message ?: "That message could not be sent."
        }
    }

    /**
     * One fix, and no more. Best effort: a phone indoors may take the whole
     * [FIX_TIMEOUT_MS] to answer, and a phone with nothing to say says nothing.
     * Nothing here is wanted badly enough to keep the radio on for it.
     */
    @SuppressLint("MissingPermission")
    private suspend fun currentPlace(): Place? = withContext(Dispatchers.IO) {
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return@withContext null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) return@withContext null
        // The newest fix of the ones that come in while it is asked, not a
        // remembered one from however long ago: a place that is not where the
        // phone is now is worse than no place at all.
        val best = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val current = best.get()
                if (current == null || location.time >= current.time) best.set(location)
            }

            @Deprecated("Required by LocationListener")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        providers.forEach { provider ->
            runCatching { manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper()) }
        }
        // A last known fix is a fair answer while the new one is looked for, so
        // that a phone indoors says where it was rather than nothing at all.
        val remembered = providers.firstNotNullOfOrNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            while (best.get() == null) delay(200)
        }
        runCatching { manager.removeUpdates(listener) }
        val fix = best.get() ?: remembered
        // A fix with no time on it, or one from somewhere that is not a place,
        // is no fix: the card would have nothing true to say.
        fix?.let { Place.of(it.latitude, it.longitude, it.accuracy.toDouble(), it.time) }
    }

    /**
     * The share is over, whoever ended it: the button on the card, the one in
     * the notification, the minute running out, or the relay saying so. The
     * relay is told, [peer] is told, and the card says so.
     */
    fun finishLiveShare(peer: String, share: String) {
        LocationService.stop(appContext, share)
        scope.launch {
            places?.let { api -> runCatching { withContext(Dispatchers.IO) { api.stop(share) } } }
            engine?.endLiveShare(peer, share)
        }
    }

    /** This phone opened [peer]'s share: they are told, and the card starts asking for points. */
    suspend fun watchLiveShare(peer: String, share: String) {
        engine?.watchLive(peer, share)
    }

    /**
     * What [peer]'s share looks like now. Null when the relay could not be
     * asked: the card keeps the point it has rather than claiming the share
     * has ended.
     */
    suspend fun liveView(peer: String, share: String, key: String): LiveView? {
        val api = places ?: return null
        val remote = try {
            withContext(Dispatchers.IO) { api.peek(peer, share) }
        } catch (_: IOException) {
            return null
        }
        return when {
            remote.gone -> LiveView(LiveState.GONE, remote.until, null)
            remote.ended -> LiveView(LiveState.ENDED, remote.until, null)
            // A point that will not open is not a point this phone can use.
            else -> LiveView(LiveState.LIVE, remote.until, remote.sealed?.let { LiveSeal.open(key, share, it) })
        }
    }

    /**
     * Hands one fix to the relay, sealed. Called by [LocationService] while a
     * share runs. False only when the relay says the share is over; a lost
     * network is not an end, and the next fix tries again.
     */
    suspend fun liveFix(share: String, key: String, point: LivePoint): Boolean {
        val api = places ?: return false
        val sealed = LiveSeal.seal(key, share, point)
        return try {
            withContext(Dispatchers.IO) { api.point(share, sealed) }
        } catch (_: IOException) {
            true
        }
    }

    private fun mayShareLocation(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The geocoder, made the first time a place is sent. */
    private fun addresses(): Addresses = addressLookup ?: Addresses(appContext).also { addressLookup = it }

    private var addressLookup: Addresses? = null

    /** How long a single fix may take before the phone is taken at its word. */
    private val FIX_TIMEOUT_MS = 12_000L

    /** What to call [peer] where a notification may be read on a locked screen. */
    private fun nameOf(peer: String): String =
        _contacts.value.firstOrNull { it.id == peer }?.name ?: "One of your contacts"

    /** Picks up any share this phone started that is still running. Called by [LocationService] too. */
    fun resumeLiveShares() {
        scope.launch { resumeRunningShares() }
    }

    /** A share still running when the app opens carries on, notification and all. */
    private suspend fun resumeRunningShares() {
        val engine = engine ?: return
        val now = System.currentTimeMillis()
        for (message in engine.runningShares()) {
            val record = LiveShare.fromLocal(message.text) ?: continue
            if (record.phase(outgoing = true, now = now) != LivePhase.SHARING) continue
            LocationService.start(appContext, message.peer, record.share, record.key, record.until, nameOf(message.peer))
        }
    }

    suspend fun sendFile(peer: String, uri: Uri): String? {
        val transfers = files ?: return "Maun is not ready yet. Try again in a moment."
        return transfers.send(peer, uri)
    }

    /**
     * Encrypts and starts sending several files picked at once, one after the
     * other. Returns why the first of them could not be sent, or null.
     */
    suspend fun sendFiles(peer: String, uris: List<Uri>): String? {
        val transfers = files ?: return "Maun is not ready yet. Try again in a moment."
        var problem: String? = null
        for (uri in uris) {
            val why = transfers.send(peer, uri)
            if (why != null && problem == null) problem = why
        }
        return problem
    }

    /** Encrypts a voice message that was just recorded and starts sending it. [recording] is deleted. */
    suspend fun sendVoice(peer: String, recording: java.io.File, millis: Long): String? {
        val transfers = files
        if (transfers == null) {
            recording.delete()
            return "Maun is not ready yet. Try again in a moment."
        }
        return transfers.sendVoice(peer, recording, millis)
    }

    /** The sound of the voice message [seq], decrypted into memory; null when it is not on this phone. */
    suspend fun voiceBytes(seq: Long, file: Attachment): ByteArray? = files?.bytes(seq, file, MAX_VOICE_BYTES)

    /** Sends or fetches the file of message [seq], whichever it still needs. */
    fun startFile(seq: Long) {
        files?.start(seq)
    }

    suspend fun image(seq: Long, file: Attachment, maxPixels: Int): Bitmap? = files?.image(seq, file, maxPixels)

    /** The same picture without waiting, if it has been shown since the app started. */
    fun cachedImage(seq: Long, maxPixels: Int): Bitmap? = files?.cachedImage(seq, maxPixels)

    suspend fun saveFileToDevice(seq: Long, file: Attachment): String? = files?.saveToDevice(seq, file)

    /**
     * A `content://` address for the file of message [seq], for the app on this
     * phone that shows files of that kind. Null when the file is not here.
     */
    suspend fun fileUri(seq: Long, file: Attachment): Uri? = files?.openTarget(seq, file)

    suspend fun saveFile(seq: Long, file: Attachment, target: Uri): Boolean = files?.saveTo(seq, file, target) == true

    fun clearFileProblem() {
        files?.clearProblem()
    }

    /**
     * Connects if there is a reason to be connected, disconnects if not. With
     * [linger] the connection is kept a little longer first. Call with the
     * lock held.
     */
    private fun connectOrDrop(linger: Boolean = false) {
        pendingStop?.cancel()
        pendingStop = null
        when {
            // A relay waiting for the email login is not asked again until
            // there is a token to show it: see presentTicket.
            wanted() -> if (_relay.value !is RelayState.LoginNeeded) connection?.start()
            linger -> pendingStop = scope.launch {
                delay(BACKGROUND_LINGER_MILLIS)
                synchronized(this@Messenger) { if (!wanted()) connection?.stop() }
            }
            else -> connection?.stop()
        }
    }

    /** The app came on screen. */
    fun onForeground() = synchronized(this) {
        inForeground = true
        // The user is looking at the app; there is nothing left to announce.
        notifications.clearMessages()
        connectOrDrop()
    }

    /**
     * The app left the screen. The connection lingers briefly, so turning the
     * phone or answering a permission prompt does not reconnect.
     */
    fun onBackground() {
        val pending = synchronized(this) {
            inForeground = false
            connectOrDrop(linger = true)
            _unsent.value
        }
        // Leaving the screen with something unsent: the connection is about to
        // go, so a wake-up is asked for rather than waited for.
        if (pending > 0) scope.launch { FetchWorker.schedule(appContext) }
    }

    /** "Stay connected" was switched on or off: [ConnectedService][org.maun.messenger.push.ConnectedService] started or stopped. */
    fun onStayConnected(on: Boolean) = synchronized(this) {
        stayingConnected = on
        connectOrDrop()
    }

    /**
     * Connects, takes in every message that is waiting, confirms them, and
     * lets go of the connection again. Returns false when that could not be
     * finished within [timeoutMillis]; whatever was not fetched stays on the
     * relay. Used when the phone is woken up while Maun is closed.
     */
    /**
     * The phone has a network again. A connection that is waiting out a retry
     * tries now instead: that wait was measured while there was no network at
     * all, so it says nothing about how quickly the relay would answer now.
     * Nothing is started if nothing was running.
     */
    fun onNetworkAvailable() {
        synchronized(this) { connection }?.nudge()
    }

    /** Present an authenticated chat locally even when a new relay connection cannot be made. */
    internal fun receiveChatPush(part: ChatPushPart, backup: java.util.UUID?) {
        scope.launch(Dispatchers.IO) {
            // A failed save leaves both the scheduled copy and the relay queue available.
            try {
                saveChatPush(part)
                backup?.let { androidx.work.WorkManager.getInstance(appContext).cancelWorkById(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The expedited worker gets its own attempt.
            }
        }
    }

    internal suspend fun saveChatPush(part: ChatPushPart) = withContext(Dispatchers.IO) {
        val current = account.first { it !is AccountState.Loading }
        if (current !is AccountState.Present || current.code.id != part.recipient || !current.registered) return@withContext
        val db = database ?: return@withContext
        val chat = engine ?: return@withContext
        val body = db.inTransaction {
            if (_signedOut.value || _banned.value || _awaiting.value || db.chat().isBlocked(part.sender)) null
            else part.assemble(db.chatParts())
        }
        if (body != null && engine === chat && !_signedOut.value && !_banned.value && !_awaiting.value) {
            chat.receiveChatPush(part.sender, part.kind, body) {
                engine === chat && !_signedOut.value && !_banned.value && !_awaiting.value
            }
            presentUnread(db)
            part.saved(db.chatParts())
        }
    }

    /** Starts fetching right away, without waiting for anything to be scheduled. For the moment a wake-up arrives. */
    fun fetchSoon() {
        synchronized(this) { connection }?.nudge()
        scope.launch { fetch() }
    }

    /**
     * A notification was tapped: the conversation it was about, until the screen
     * has gone there. A conversation always opens at its newest messages, so
     * there is nothing else to carry: no message ID, no place to scroll to, and
     * nothing that can arrive after the list has already been drawn.
     */
    private val _openChat = MutableStateFlow<String?>(null)
    val openChat: StateFlow<String?> = _openChat.asStateFlow()

    fun requestChat(peer: String?) {
        _openChat.value = peer
    }

    /**
     * A notification about several conversations was tapped. There is no one
     * conversation to open, so the list is shown, whatever screen Maun was left
     * on last time.
     */
    private val _openChats = MutableStateFlow(false)
    val openChats: StateFlow<Boolean> = _openChats.asStateFlow()

    fun requestChats() {
        _openChats.value = true
    }

    fun chatsOpened() {
        _openChats.value = false
    }

    suspend fun fetch(timeoutMillis: Long = FETCH_TIMEOUT_MILLIS): Boolean {
        val current = account.first { it !is AccountState.Loading }
        if (current !is AccountState.Present || !current.registered || _banned.value || _awaiting.value ||
            _signedOut.value
        ) return true
        synchronized(this) {
            fetches++
            connectOrDrop()
        }
        try {
            return withTimeoutOrNull(timeoutMillis) {
                try {
                    engine?.awaitCaughtUp() ?: return@withTimeoutOrNull false
                } catch (_: RelayException) {
                    return@withTimeoutOrNull false
                }
                // A call that was among what arrived is handed to the main thread;
                // let that happen before looking whether one is ringing.
                withContext(Dispatchers.Main) { }
                // What was waiting may have been a call. While it rings here, stay:
                // letting go of the connection would be hanging up on it.
                calls.state.first { it == null || !it.incoming || it.phase != CallUi.Phase.Ringing }
                true
            } ?: false
        } finally {
            synchronized(this) {
                fetches--
                connectOrDrop()
            }
        }
    }

    // ---- Being woken up --------------------------------------------------------

    /**
     * The push app on this phone handed out a wake-up address, or (with null)
     * there is none any more. The relay is told the next time there is a
     * connection, and one is made for the purpose if the app is closed.
     */
    fun onPushAddress(address: PushAddress?) {
        scope.launch {
            account.first { it !is AccountState.Loading }
            val db = database ?: return@launch
            val text = address?.serialize().orEmpty()
            // Signed out, the relay is not told about this phone at all. The
            // address is kept for the day it signs back in: see resumeSession.
            if (_signedOut.value) {
                withContext(Dispatchers.IO) { db.meta().put(MetaEntry(KEY_PUSH_HELD, text)) }
                return@launch
            }
            val changed = withContext(Dispatchers.IO) {
                if (db.meta().get(KEY_PUSH_WANTED).orEmpty() == text) {
                    false
                } else {
                    db.meta().put(MetaEntry(KEY_PUSH_WANTED, text))
                    true
                }
            }
            if (address != null) _pushProblem.value = null
            if (!changed) return@launch
            engine?.pushChanged()
            if (!synchronized(this@Messenger) { wanted() }) FetchWorker.schedule(appContext)
        }
    }

    /** The push app could not give this phone a wake-up address. [reason] is for the screen. */
    fun onPushFailed(reason: String) {
        _pushProblem.value = reason
    }

    private suspend fun presentUnread(db: AppDatabase) = presentation.withLock {
        // The observer and a cold push can arrive in either order. Read inside the
        // lock so an older observer snapshot cannot clear a newer notification.
        val checkpoint = announceUnread(db.chat().observeChats().first())
        if (checkpoint != null) db.meta().put(MetaEntry(KEY_ANNOUNCED, checkpoint.toString()))
    }

    /** Tells the user about messages that arrived while they were not looking. Never says what is in them. */
    private fun announceUnread(chats: List<ChatSummary>): Long? = synchronized(this) {
        if (_signedOut.value || _banned.value || _awaiting.value) return@synchronized null
        val announcement = announce(chats, _contacts.value.associate { it.id to it.name }, showText = notifications.showsText)
        if (announcement == null) {
            notifications.clearMessages()
            return@synchronized null
        }
        val newest = newestUnread(chats)
        val posted = notifications.postedThrough(notificationEpoch)
        if (newest <= announcedThrough) {
            // An update to a dismissed notification must not put it back in the shade.
            if (!inForeground && posted != null) notifications.showMessages(announcement, false, announcedThrough, notificationEpoch)
            return@synchronized null
        }
        when {
            notifications.dismissedThrough(notificationEpoch) >= newest -> Unit
            posted != null && posted >= newest -> Unit
            inForeground -> sounds.playIncoming()
            !notifications.showMessages(announcement, true, newest, notificationEpoch) -> return@synchronized null
        }
        announcedThrough = newest
        newest
    }

    /** Tries again after the relay refused the login. */
    fun reconnect() = synchronized(this) {
        if (!inForeground) return@synchronized
        connection?.stop()
        connection?.start()
    }

    /**
     * Log out, WhatsApp's way: signing this phone out, not wiping it.
     *
     * The session and the live connection end, the relay is told to stop
     * waking this phone, and nothing is shown until it signs back in. The ID,
     * the keys, the chats, the pictures and the settings stay exactly where
     * they are, so [resumeSession] on the same install comes back to the same
     * account with its pending messages.
     *
     * This is not "Clear data" and not account deletion: neither the relay's
     * records nor the pictures on it are touched.
     */
    suspend fun logOut() {
        val db = database ?: return
        if (_signedOut.value) return
        // A call in progress belongs to the account that is leaving.
        if (calls.busy) runCatching { calls.hangUp() }
        _signedOut.value = true
        withContext(Dispatchers.IO) {
            db.chatParts().removeParts("chat-part/")
            db.meta().put(MetaEntry(KEY_SIGNED_OUT, "1"))
            // The wake-up address is put aside rather than forgotten, so that
            // signing back in does not have to wait for the push app again.
            db.meta().put(MetaEntry(KEY_PUSH_HELD, db.meta().get(KEY_PUSH_WANTED).orEmpty()))
            db.meta().put(MetaEntry(KEY_PUSH_WANTED, ""))
        }
        // Still connected at this moment: the relay is told to stop waking
        // this phone before the connection goes. If it cannot be told, the
        // wake-ups it sends in the meantime are answered and ignored.
        withTimeoutOrNull(WITHDRAW_PUSH_WAIT_MILLIS) { engine?.withdrawPush() }
        setAccessToken(null)
        synchronized(this) {
            pendingStop?.cancel()
            pendingStop = null
            connection?.stop()
        }
        FetchWorker.cancel(appContext)
        RingService.stop(appContext)
        notifications.clearIncomingCall()
        notifications.clearMessages()
        setAwaiting(false)
    }

    /**
     * Signing back in on this phone. The ID, the keys, the chats and the
     * pictures are already here, so this only clears the signed-out mark and
     * lets the phone connect again, offering its wake-up address once more.
     * On a member-only relay the relay then asks for the email login; on an
     * open one there is nothing more to prove.
     */
    suspend fun resumeSession() {
        val db = database ?: return
        if (!_signedOut.value) return
        _signedOut.value = false
        withContext(Dispatchers.IO) {
            db.meta().put(MetaEntry(KEY_SIGNED_OUT, ""))
            val held = db.meta().get(KEY_PUSH_HELD).orEmpty()
            if (held.isNotEmpty()) db.meta().put(MetaEntry(KEY_PUSH_WANTED, held))
            db.meta().put(MetaEntry(KEY_PUSH_HELD, ""))
        }
        FetchWorker.scheduleHourly(appContext)
        synchronized(this) {
            connection?.stop()
            connection?.start()
        }
    }

    /** Round trip through the logged-in connection, in milliseconds. */
    suspend fun echo(): Long = (connection ?: throw RelayException("Not connected to the relay")).echo()

    /** Decides whether scanned or typed text is a contact that can be added. */
    suspend fun checkCode(text: String, typed: Boolean): CodeCheck {
        val own = (_account.value as? AccountState.Present)?.code ?: return CodeCheck.NotACode
        val code = (if (typed) ContactCode.parseTyped(text, own.relay) else ContactCode.parse(text))
            ?: return CodeCheck.NotACode
        if (code.id == own.id) return CodeCheck.OwnCode
        // Mailboxes on different relays cannot reach each other.
        if (code.relay != own.relay) return CodeCheck.OtherRelay(code.relay)
        val existing = withContext(Dispatchers.IO) { database?.contacts()?.get(code.id) }
        if (existing != null) return CodeCheck.AlreadyAdded(existing.name)
        return CodeCheck.Ok(code)
    }

    /**
     * Asks the relay which of this phone's contacts carry the administrators'
     * tick. It is asked often rather than often enough: on login, when a
     * contact is added, and when the app comes back to the front, so that a
     * tick that was given or taken away is not left stale for long.
     *
     * A failure is not the user's problem: the answers already known stay.
     */
    fun refreshBadges() {
        val link = connection ?: return
        val ids = _contacts.value.map { it.id }
        if (ids.isEmpty()) return
        scope.launch {
            val db = database ?: return@launch
            val checked = HashMap<String, Boolean>()
            for (chunk in ids.chunked(Badges.LOOKUP_MAX)) {
                val answer = try {
                    link.request(
                        JSONObject()
                            .put("t", "verified")
                            .put("users", JSONArray(chunk)),
                    )
                } catch (_: Exception) {
                    // Offline, or the relay said no. What is known is still what was last true.
                    break
                }
                if (answer.optString("t") != "verified") break
                // A relay that only lets members in cannot answer for a stranger.
                if (!answer.optBoolean("member", true)) break
                val ok = answer.optJSONObject("ok") ?: break
                for (id in chunk) checked[id] = ok.optBoolean(id, false)
            }
            if (checked.isEmpty()) return@launch
            val merged = Badges.merge(_verified.value, checked, ids)
            _verified.value = merged
            withContext(Dispatchers.IO) { db.meta().put(MetaEntry(Badges.KEY, Badges.encode(merged))) }
        }
    }

    /**
     * Adds a contact, or accepts a message request from [id]. Adding someone
     * who was blocked unblocks them. From now on they are told when their
     * messages arrive and are read.
     */
    suspend fun saveContact(id: String, name: String, verified: Boolean, username: String? = null) {
        require(ContactCode.isId(id)) { "not a Maun ID" }
        val contact = ContactEntity(
            id = id,
            name = name.trim().take(MAX_NAME_LENGTH),
            verified = verified,
            addedAt = System.currentTimeMillis(),
            username = username,
        )
        withContext(Dispatchers.IO) {
            database?.chat()?.unblock(id)
            database?.contacts()?.put(contact)
        }
        engine?.onContactAdded(id)
        // A new contact is shown with their picture as soon as they answer,
        // and with their tick as soon as the relay has said whether they have one.
        engine?.askAvatar(id)
        refreshBadges()
    }

    /** Changes what this phone calls a contact. Nobody else is told. */
    suspend fun renameContact(id: String, name: String) {
        val trimmed = name.trim().take(MAX_NAME_LENGTH)
        if (trimmed.isEmpty()) return
        withContext(Dispatchers.IO) {
            val contacts = database?.contacts() ?: return@withContext
            val current = contacts.get(id) ?: return@withContext
            contacts.put(current.copy(name = trimmed))
        }
    }

    /** Records whether the user has checked that this ID really belongs to the person. */
    suspend fun setContactChecked(id: String, checked: Boolean) {
        withContext(Dispatchers.IO) {
            val contacts = database?.contacts() ?: return@withContext
            val current = contacts.get(id) ?: return@withContext
            contacts.put(current.copy(verified = checked))
        }
    }

    /** Removes a contact and deletes the conversation with them from this phone. */
    suspend fun removeContact(id: String) {
        engine?.forget(id)
        files?.sweep()
    }

    /** The newest [limit] messages with [peer], newest first, updated as they change. */
    fun messages(peer: String, limit: Int): Flow<List<MessageEntity>> =
        database?.chat()?.observeMessages(peer, limit) ?: emptyFlow()

    /** What each message of the conversation with [peer] carries, as it changes. */
    fun reactions(peer: String): Flow<Map<String, Reactions>> =
        engine?.observeReactions(peer) ?: emptyFlow()

    /** Puts an emoji on a message, or takes it off when [emoji] is empty. */
    suspend fun react(peer: String, messageId: String, emoji: String) {
        engine?.react(peer, messageId, emoji)
    }

    /** Deletes one message from this phone only. */
    suspend fun deleteMessage(seq: Long) {
        engine?.deleteMessage(seq)
        files?.sweep()
    }

    /** Queues a message, answering [replyTo] when given. It leaves as soon as there is a connection. */
    suspend fun sendText(peer: String, text: String, replyTo: String? = null) {
        engine?.sendText(peer, text, replyTo)
    }

    /** The conversation with [peer] is on screen. */
    suspend fun markRead(peer: String) {
        engine?.markRead(peer)
    }

    /** Deletes the conversation with [peer] and throws away anything they send from now on. */
    suspend fun block(peer: String) {
        engine?.block(peer)
        files?.sweep()
    }

    suspend fun unblock(peer: String) {
        engine?.unblock(peer)
    }

    companion object {
        const val MAX_NAME_LENGTH = 60

        /** How many messages a conversation loads at first, and each time the reader reaches the oldest. */
        const val MESSAGE_PAGE = 100

        /**
         * Whether this phone has a reason to hold a connection to the relay.
         *
         * Being signed out is the one thing that overrides everything else: not
         * the app being on screen, not a fetch in progress, not a call.
         */
        internal fun wantsConnection(
            banned: Boolean,
            signedOut: Boolean,
            inForeground: Boolean,
            fetches: Int,
            stayingConnected: Boolean,
            callBusy: Boolean,
        ): Boolean = !banned && !signedOut && (inForeground || fetches > 0 || stayingConnected || callBusy)

        private const val SETTINGS_PREFS = "settings"
        private const val KEY_PERMISSIONS_SETUP_PENDING = "permissions_setup_pending"
        private const val KEY_PERMISSIONS_SETUP_DONE = "permissions_setup_done"
        private const val KEY_REGISTRATION_ID = "signal_registration_id"
        private const val KEY_NOTIFICATION_EPOCH = "notification_epoch"
        private const val KEY_ANNOUNCED = "last_announced_message_seq"
        private const val KEY_PUSH_WANTED = "push_wanted"
        private const val KEY_PUSH_HELD = "push_held"
        private const val KEY_PUSH_REJECTED = "push_rejected"
        private const val KEY_PUSH_UPLOADED = "push_uploaded"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_MEMBER_EMAIL = "member_email"
        private const val KEY_BANNED = "banned"
        private const val KEY_USERNAME = "username"
        private const val KEY_AWAITING = "awaiting_approval"
        private const val KEY_SIGNED_OUT = "signed_out"
        private const val KEY_RECOVERY_SEALED_AT = "recovery_sealed_at"
        private const val TICKET_SHOWN_WAIT_MILLIS = 3_000L
        /** Five minutes of speech are well under a megabyte; nothing bigger is played from memory. */
        private const val MAX_VOICE_BYTES = 4_000_000L
        private const val NOT_READY = "Maun is not ready yet. Try again in a moment."
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val ONLINE_WAIT_MILLIS = 20_000L
        private const val BACKGROUND_LINGER_MILLIS = 10_000L

        /** Long enough to tell the relay to stop waking this phone before the connection goes. */
        private const val WITHDRAW_PUSH_WAIT_MILLIS = 4_000L

        /** Long enough for a few hundred waiting messages on a slow connection, or for a call to ring out. */
        private const val FETCH_TIMEOUT_MILLIS = 60_000L
    }
}
