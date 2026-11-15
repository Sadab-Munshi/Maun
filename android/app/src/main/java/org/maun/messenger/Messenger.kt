package org.maun.messenger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.maun.messenger.crypto.ContactCode
import org.maun.messenger.crypto.DeviceKey
import org.maun.messenger.crypto.Identity
import org.maun.messenger.crypto.SignalSessions
import org.maun.messenger.data.AccountEntity
import org.maun.messenger.data.AppDatabase
import org.maun.messenger.data.BlockedEntity
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.DatabaseKey
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MetaEntry
import org.maun.messenger.data.RoomSignalStorage
import org.maun.messenger.network.RelayClient
import org.maun.messenger.network.RelayConnection
import org.maun.messenger.network.RelayException
import org.maun.messenger.network.RelayState

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val relayClient = RelayClient()

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

    private val _waiting = MutableStateFlow<Map<String, String>>(emptyMap())

    /** For each person whose messages are stuck: why, in words for the screen. */
    val waiting: StateFlow<Map<String, String>> = _waiting.asStateFlow()

    private val setup = Mutex()

    @Volatile
    private var database: AppDatabase? = null

    @Volatile
    private var engine: ChatEngine? = null
    private var engineScope: CoroutineScope? = null
    private var connection: RelayConnection? = null
    private var connectionWatcher: Job? = null
    private var pendingStop: Job? = null
    private var inForeground = false

    init {
        scope.launch { load() }
    }

    private suspend fun load() = setup.withLock {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.open(appContext)
                val row = db.account().get()
                database = db
                scope.launch { db.contacts().observeAll().collect { _contacts.value = it } }
                scope.launch { db.chat().observeChats().collect { _chats.value = it } }
                scope.launch { db.chat().observeBlocked().collect { _blocked.value = it } }
                if (row == null) {
                    _account.value = AccountState.None
                } else {
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
        val chatScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val chat = ChatEngine(
            selfId = identity.id,
            signal = SignalSessions(identity, registrationId, RoomSignalStorage(db.signal())),
            dao = db.chat(),
            tx = db,
            scope = chatScope,
        )
        chatScope.launch { chat.waiting.collect { _waiting.value = it } }
        _account.value = AccountState.Present(
            code = ContactCode(identity.id, ContactCode.relayName(relayUrl)),
            relayUrl = row.relayUrl,
            registered = row.registered,
        )
        val created = RelayConnection(
            relayUrl = relayUrl,
            identity = identity,
            device = DeviceKey(appContext),
            scope = scope,
            isRegistered = { (_account.value as? AccountState.Present)?.registered == true },
            onLoggedIn = ::markRegistered,
            onOnline = chat::onOnline,
            onFrame = chat::onFrame,
        )
        chat.link = created
        synchronized(this) {
            engine = chat
            engineScope = chatScope
            connection = created
            connectionWatcher = scope.launch { created.state.collect { _relay.value = it } }
            if (inForeground) created.start()
        }
    }

    private suspend fun markRegistered() {
        val current = _account.value as? AccountState.Present ?: return
        if (current.registered) return
        withContext(Dispatchers.IO) { database?.account()?.markRegistered() }
        _account.value = current.copy(registered = true)
    }

    private fun closeConnection() = synchronized(this) {
        connectionWatcher?.cancel()
        connectionWatcher = null
        connection?.stop()
        connection = null
        engineScope?.cancel()
        engineScope = null
        engine = null
        _relay.value = RelayState.Offline
        _waiting.value = emptyMap()
    }

    /** The app came on screen: connect, or keep the connection that is about to be dropped. */
    fun onForeground() = synchronized(this) {
        inForeground = true
        pendingStop?.cancel()
        pendingStop = null
        connection?.start()
    }

    /**
     * The app left the screen. The connection lingers briefly, so turning the
     * phone or answering a permission prompt does not reconnect. Staying
     * connected in the background arrives with push delivery.
     */
    fun onBackground() = synchronized(this) {
        inForeground = false
        pendingStop?.cancel()
        pendingStop = scope.launch {
            delay(BACKGROUND_LINGER_MILLIS)
            synchronized(this@Messenger) { if (!inForeground) connection?.stop() }
        }
    }

    /** Tries again after the relay refused the login. */
    fun reconnect() = synchronized(this) {
        if (!inForeground) return@synchronized
        connection?.stop()
        connection?.start()
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
     * Adds a contact, or accepts a message request from [id]. Adding someone
     * who was blocked unblocks them. From now on they are told when their
     * messages arrive and are read.
     */
    suspend fun saveContact(id: String, name: String, verified: Boolean) {
        require(ContactCode.isId(id)) { "not a Maun ID" }
        val contact = ContactEntity(
            id = id,
            name = name.trim().take(MAX_NAME_LENGTH),
            verified = verified,
            addedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) {
            database?.chat()?.unblock(id)
            database?.contacts()?.put(contact)
        }
        engine?.onContactAdded(id)
    }

    /** Removes a contact and deletes the conversation with them from this phone. */
    suspend fun removeContact(id: String) {
        engine?.forget(id)
    }

    /** The conversation with [peer], newest message first, updated as it changes. */
    fun messages(peer: String): Flow<List<MessageEntity>> =
        database?.chat()?.observeMessages(peer, MESSAGE_WINDOW) ?: emptyFlow()

    /** Queues a message. It leaves as soon as there is a connection. */
    suspend fun sendText(peer: String, text: String) {
        engine?.sendText(peer, text)
    }

    /** The conversation with [peer] is on screen. */
    suspend fun markRead(peer: String) {
        engine?.markRead(peer)
    }

    /** Deletes the conversation with [peer] and throws away anything they send from now on. */
    suspend fun block(peer: String) {
        engine?.block(peer)
    }

    suspend fun unblock(peer: String) {
        engine?.unblock(peer)
    }

    companion object {
        const val MAX_NAME_LENGTH = 60

        /** How many of a conversation's newest messages the screen loads. */
        const val MESSAGE_WINDOW = 500

        private const val KEY_REGISTRATION_ID = "signal_registration_id"
        private const val BACKGROUND_LINGER_MILLIS = 10_000L
    }
}
