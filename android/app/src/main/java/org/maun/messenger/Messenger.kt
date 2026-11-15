package org.maun.messenger

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.maun.messenger.crypto.ContactCode
import org.maun.messenger.crypto.DeviceKey
import org.maun.messenger.crypto.Identity
import org.maun.messenger.data.AccountEntity
import org.maun.messenger.data.AppDatabase
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.DatabaseKey
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
 * the relay, and the contact list. Created once per app process.
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

    private val setup = Mutex()
    private var database: AppDatabase? = null
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
                if (row == null) {
                    _account.value = AccountState.None
                } else {
                    open(row)
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
        withContext(Dispatchers.IO) { db.account().put(row) }
        open(row)
    }

    /** Publishes the account and starts its connection if the app is on screen. */
    private fun open(row: AccountEntity) {
        val identity = Identity.restore(row.identityKeyPair)
        val relayUrl = row.relayUrl.toHttpUrl()
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
        )
        synchronized(this) {
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
        _relay.value = RelayState.Offline
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

    suspend fun saveContact(id: String, name: String, verified: Boolean) {
        require(ContactCode.isId(id)) { "not a Maun ID" }
        val contact = ContactEntity(
            id = id,
            name = name.trim().take(MAX_NAME_LENGTH),
            verified = verified,
            addedAt = System.currentTimeMillis(),
        )
        withContext(Dispatchers.IO) { database?.contacts()?.put(contact) }
    }

    suspend fun removeContact(id: String) {
        withContext(Dispatchers.IO) { database?.contacts()?.delete(id) }
    }

    companion object {
        const val MAX_NAME_LENGTH = 60
        private const val BACKGROUND_LINGER_MILLIS = 10_000L
    }
}
