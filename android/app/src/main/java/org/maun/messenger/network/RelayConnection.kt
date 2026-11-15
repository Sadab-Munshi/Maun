package org.maun.messenger.network

import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import org.maun.messenger.crypto.AuthTranscript
import org.maun.messenger.crypto.DeviceSigner
import org.maun.messenger.crypto.Identity

sealed interface RelayState {
    /** Not trying to connect (the app is in the background, or was never started). */
    data object Offline : RelayState

    data object Connecting : RelayState

    /** Logged in. */
    data object Online : RelayState

    /** The last attempt failed for [reason]; another attempt follows by itself. */
    data class Retrying(val reason: String) : RelayState

    /** The relay refused this phone for [reason]. Retrying will not help. */
    data class Rejected(val reason: String) : RelayState

    /**
     * The relay is member-only and does not know this ID: its owner has to be
     * shown an approved email address first. [problem] says why a login that
     * was just tried did not count, if one was.
     */
    data class LoginNeeded(val problem: String? = null) : RelayState

    /** The relay knows who this is, and its administrator has not approved them yet. */
    data object Pending : RelayState

    /** The relay's owner has shut this member out. */
    data object Banned : RelayState
}

/** What the relay said about this account when it logged in. */
data class Ready(
    /** The account was created by this login. */
    val fresh: Boolean,
    /** The relay has this phone's pre-keys. */
    val hasKeys: Boolean,
    /** One-time pre-keys the relay has left to hand out. */
    val oneTimeKeys: Int,
    /** The relay has a wake-up address for this phone. */
    val hasPush: Boolean = false,
    /**
     * What the push service answered to the relay's last wake-up for this
     * phone: an HTTP status, 0 if it could not be reached, -1 if the attempt
     * never finished. Null when none has been sent to the current address.
     */
    val pushStatus: Int? = null,
    /**
     * The public key this account's mailbox signs its wake-ups with, in
     * standard base64. The phone's push app is told it, for push services that
     * only take wake-ups from a sender named in advance.
     */
    val relayKey: String? = null,
    /** How many messages were waiting at the moment of login. They follow right away. */
    val queued: Int = 0,
    /** On a member-only relay, the email address this ID is tied to. */
    val email: String? = null,
    /** On a member-only relay, this account's username. */
    val name: String? = null,
    /** The credential for the relay's file routes, until the next login. */
    val filesToken: String? = null,
)

/** What the messaging code needs from a connection. */
interface RelayLink {
    /** Set while logged in, null otherwise. */
    val ready: Ready?

    /**
     * Sends [frame] with a fresh `id` and waits for the frame that answers it.
     * Throws [RelayException] when there is no connection, it is lost while
     * waiting, or no answer comes in time.
     */
    suspend fun request(frame: JSONObject, timeoutMillis: Long = 15_000): JSONObject

    /** Sends [frame] and expects no answer. Returns false when there is no connection. */
    fun send(frame: JSONObject): Boolean
}

/**
 * The lasting, logged-in connection to this account's mailbox on the relay.
 *
 * Every connection starts the same way: the relay sends a fresh nonce, and the
 * phone answers with a signature from its device key. The first time, it also
 * creates the device key and registers it, signed by the identity key.
 *
 * [isRegistered] says whether this account has ever been accepted by the
 * relay; [onLoggedIn] is called after every successful login, before the state
 * becomes [RelayState.Online], and [onOnline] right after it has. [onOffline]
 * is called when a connection that had logged in has ended.
 *
 * On a member-only relay, [accessToken] supplies the ticket from the email
 * login while the relay does not know this ID yet. Without one the connection
 * stops at [RelayState.LoginNeeded], unless [awaitingApproval] says the ticket
 * was handed over on an earlier connection. A member the administrator has
 * not approved yet stops at [RelayState.Pending], a banned one at
 * [RelayState.Banned]. None of these is retried until [start] is called again.
 *
 * [onFrame] receives the messages the relay delivers, one at a time and in
 * order. It runs on the connection's own loop, so it must not wait for an
 * answer from the relay: none can arrive until it returns.
 */
class RelayConnection(
    relayUrl: HttpUrl,
    private val identity: Identity,
    private val device: DeviceSigner,
    private val scope: CoroutineScope,
    private val isRegistered: () -> Boolean,
    private val onLoggedIn: suspend () -> Unit,
    private val http: OkHttpClient = RelayClient.defaultHttp,
    private val backoff: Backoff = Backoff(),
    private val onOnline: () -> Unit = {},
    private val onFrame: suspend (JSONObject) -> Unit = {},
    private val onOffline: () -> Unit = {},
    private val accessToken: () -> String? = { null },
    private val awaitingApproval: () -> Boolean = { false },
) : RelayLink {
    private val request = Request.Builder().url(RelayClient.socketUrl(relayUrl, identity.id)).build()

    private val _state = MutableStateFlow<RelayState>(RelayState.Offline)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private var job: Job? = null

    /** The connection [stop] ended last, which may still be closing. */
    private var stopped: Job? = null

    @Volatile
    private var live: WebSocket? = null

    @Volatile
    override var ready: Ready? = null
        private set

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    /** Starts connecting, and keeps reconnecting until [stop]. Does nothing if already running. */
    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val before = stopped
        stopped = null
        job = scope.launch {
            // Let the connection that was stopped finish tidying up first, so
            // that its last words cannot be mistaken for this one's.
            before?.join()
            run()
        }
    }

    /**
     * Drops the connection and stops reconnecting. By the time this returns,
     * [onOffline] has been called if the connection had logged in.
     */
    @Synchronized
    fun stop() {
        val running = job ?: return
        job = null
        running.cancel()
        stopped = running
        _state.value = RelayState.Offline
        // The cancelled connection reports this too, but only once it gets to
        // run again. Whoever asked for the stop must not see a stale "online".
        if (ready != null) onOffline()
    }

    /**
     * Sends a small frame through the logged-in connection and waits for the
     * relay to send it back. Returns the round-trip time in milliseconds.
     */
    suspend fun echo(timeoutMillis: Long = 10_000): Long {
        val started = System.nanoTime()
        request(JSONObject().put("t", "echo").put("body", ""), timeoutMillis)
        return (System.nanoTime() - started) / 1_000_000
    }

    override suspend fun request(frame: JSONObject, timeoutMillis: Long): JSONObject {
        val socket = live ?: throw RelayException("Not connected to the relay")
        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            frame.put("id", id)
            if (!socket.send(frame.toString())) throw RelayException("Not connected to the relay")
            return withTimeout(timeoutMillis) { reply.await() }
        } catch (e: TimeoutCancellationException) {
            throw RelayException("The relay did not answer in time", e)
        } finally {
            pending.remove(id)
        }
    }

    override fun send(frame: JSONObject): Boolean = live?.send(frame.toString()) == true

    private suspend fun run() {
        var failures = 0
        while (true) {
            _state.value = RelayState.Connecting
            val end = try {
                connectOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                End.Dropped("Something went wrong: ${e.message ?: e.javaClass.simpleName}", wasOnline = false)
            }
            when (end) {
                is End.Rejected -> {
                    _state.value = RelayState.Rejected(end.reason)
                    return
                }
                is End.LoginNeeded -> {
                    _state.value = RelayState.LoginNeeded(end.problem)
                    return
                }
                End.Banned -> {
                    _state.value = RelayState.Banned
                    return
                }
                End.Pending -> {
                    _state.value = RelayState.Pending
                    return
                }
                is End.Dropped -> {
                    // A connection that got as far as logging in starts the count again.
                    if (end.wasOnline) failures = 0
                    _state.value = RelayState.Retrying(end.reason)
                    delay(backoff.delayMillis(failures, end.rateLimited))
                    failures++
                }
            }
        }
    }

    /** Runs one connection from start to finish and says how it ended. */
    private suspend fun connectOnce(): End {
        val events = Channel<Event>(Channel.UNLIMITED)
        val socket = http.newWebSocket(request, Listener(events))
        var online = false
        var refusal: String? = null
        // Whether this attempt showed the relay a token, which it then did not take.
        var offered = false
        try {
            for (event in events) {
                when (event) {
                    is Event.Frame -> {
                        val frame = try {
                            JSONObject(event.text)
                        } catch (_: JSONException) {
                            continue
                        }
                        when (frame.optString("t")) {
                            "hello" -> when (val answer = withContext(Dispatchers.IO) { safeAnswerHello(frame) }) {
                                is Answer.Send -> {
                                    offered = answer.withToken
                                    socket.send(answer.frame)
                                }
                                is Answer.Refuse -> return End.Rejected(answer.reason)
                                Answer.NeedLogin -> return End.LoginNeeded(null)
                            }
                            "ready" -> {
                                onLoggedIn()
                                val context = currentCoroutineContext()
                                // Under the same lock as stop(): either the stop came first and
                                // nobody is told "online", or it comes after and its "offline"
                                // is the last word.
                                synchronized(this) {
                                    context.ensureActive()
                                    online = true
                                    ready = Ready(
                                        fresh = frame.optBoolean("fresh"),
                                        hasKeys = frame.optBoolean("keys"),
                                        oneTimeKeys = frame.optInt("otks"),
                                        hasPush = frame.optBoolean("push"),
                                        pushStatus = if (frame.isNull("pushStatus")) null else frame.optInt("pushStatus"),
                                        relayKey = frame.optString("vapid").ifEmpty { null },
                                        queued = frame.optInt("queued"),
                                        email = if (frame.isNull("email")) null else frame.optString("email").ifEmpty { null },
                                        name = if (frame.isNull("name")) null else frame.optString("name").ifEmpty { null },
                                        filesToken = frame.optString("files").ifEmpty { null },
                                    )
                                    live = socket
                                    _state.value = RelayState.Online
                                    onOnline()
                                }
                            }
                            "msg" -> if (online) onFrame(frame)
                            // After login it announces why the relay is about to hang up.
                            "error" -> refusal = frame.optString("code")
                            // Everything else answers a request and carries its id.
                            else -> pending.remove(frame.optString("id"))?.complete(frame)
                        }
                    }
                    is Event.Closed -> return when (event.code) {
                        CLOSE_REPLACED -> End.Rejected("A newer connection from this phone took over.")
                        CLOSE_AUTH_FAILED -> End.Rejected(describeRefusal(refusal ?: event.reason))
                        CLOSE_BANNED -> End.Banned
                        CLOSE_PENDING -> End.Pending
                        CLOSE_ACCESS_REQUIRED -> End.LoginNeeded(
                            when {
                                offered -> "That login was not accepted. It may have run out; please log in again."
                                awaitingApproval() -> "Your request to join was not accepted. You can ask again."
                                else -> null
                            },
                        )
                        else -> End.Dropped("The relay closed the connection.", online)
                    }
                    is Event.Failed -> return End.Dropped(describeFailure(event), online, event.httpCode == 429)
                }
            }
            return End.Dropped("The connection ended.", online)
        } finally {
            live = null
            ready = null
            if (online) onOffline()
            pending.values.forEach { it.completeExceptionally(RelayException("The connection was lost")) }
            // A polite close when we are the ones leaving; no effect if already closed.
            if (!socket.close(1000, null)) socket.cancel()
        }
    }

    private fun safeAnswerHello(hello: JSONObject): Answer =
        try {
            answerHello(hello)
        } catch (e: Exception) {
            // The secure hardware can fail in ways no code here can repair.
            Answer.Refuse("This phone could not sign in: ${e.message ?: e.javaClass.simpleName}")
        }

    /** Builds the reply to the relay's `hello`. Blocking: it uses the secure hardware. */
    private fun answerHello(hello: JSONObject): Answer {
        RelayClient.protocolProblem(hello.optInt("proto", -1))?.let { return Answer.Refuse(it) }
        val nonce = try {
            Base64.getDecoder().decode(hello.optString("nonce"))
        } catch (_: IllegalArgumentException) {
            return Answer.Refuse("The relay sent a greeting this app cannot read.")
        }
        if (nonce.size != AuthTranscript.NONCE_BYTES) {
            return Answer.Refuse("The relay sent a greeting this app cannot read.")
        }
        val encoder = Base64.getEncoder()
        // Shown whenever there is one: a relay that already knows this ID ignores it.
        val memberOnly = hello.optBoolean("access")
        val token = if (memberOnly) accessToken() else null

        if (hello.optBoolean("registered")) {
            if (!device.exists()) {
                return Answer.Refuse("This ID is registered to a different phone.")
            }
            val signature = device.sign(AuthTranscript.login(identity.publicKey, nonce))
            val frame = JSONObject().put("t", "login").put("sig", encoder.encodeToString(signature))
            if (token != null) frame.put("access", token)
            return Answer.Send(frame.toString(), withToken = token != null)
        }

        if (isRegistered()) {
            // Registering again would mean replacing the device key. If the
            // relay is wrong about this, the old key would be lost for nothing.
            return Answer.Refuse("The relay no longer has an account for this ID. It may have been reset.")
        }
        // A new ID on a member-only relay is turned away without a token, so
        // nothing is made or sent until there is one.
        if (memberOnly && token == null && !awaitingApproval()) return Answer.NeedLogin
        device.create(nonce)
        val deviceKey = device.publicKeySpki()
        val transcript = AuthTranscript.register(identity.publicKey, nonce, deviceKey)
        val frame = JSONObject()
            .put("t", "register")
            .put("deviceKey", encoder.encodeToString(deviceKey))
            .put("identitySig", encoder.encodeToString(identity.sign(transcript)))
            .put("deviceSig", encoder.encodeToString(device.sign(transcript)))
        if (token != null) frame.put("access", token)
        return Answer.Send(frame.toString(), withToken = token != null)
    }

    private fun describeRefusal(code: String): String = when (code) {
        "already_registered" -> "This ID is already registered to a different phone."
        "bad_signature" -> "The relay did not accept this phone's login key."
        "not_registered" -> "The relay no longer has an account for this ID. It may have been reset."
        else -> "The relay refused the login ($code)."
    }

    private fun describeFailure(failure: Event.Failed): String = when (failure.httpCode) {
        null -> "Could not reach the relay: ${failure.message}"
        429 -> "The relay is getting too many connections. Trying again shortly."
        400, 404 -> "That address is not a Maun relay (HTTP ${failure.httpCode})."
        else -> "The relay answered with HTTP ${failure.httpCode}."
    }

    private sealed interface Answer {
        class Send(val frame: String, val withToken: Boolean = false) : Answer
        class Refuse(val reason: String) : Answer
        data object NeedLogin : Answer
    }

    private sealed interface End {
        class Rejected(val reason: String) : End
        class LoginNeeded(val problem: String?) : End
        data object Banned : End
        data object Pending : End
        class Dropped(val reason: String, val wasOnline: Boolean, val rateLimited: Boolean = false) : End
    }

    private sealed interface Event {
        class Frame(val text: String) : Event
        class Closed(val code: Int, val reason: String) : Event
        class Failed(val message: String, val httpCode: Int?) : Event
    }

    private class Listener(private val events: Channel<Event>) : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            events.trySend(Event.Frame(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            events.trySend(Event.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            events.trySend(Event.Failed(t.message ?: t.javaClass.simpleName, response?.code))
        }
    }

    companion object {
        // Must match the close codes in backend/src/protocol.ts.
        const val CLOSE_REPLACED = 4001
        const val CLOSE_AUTH_FAILED = 4003
        const val CLOSE_ACCESS_REQUIRED = 4401
        const val CLOSE_PENDING = 4402
        const val CLOSE_BANNED = 4403
    }
}
