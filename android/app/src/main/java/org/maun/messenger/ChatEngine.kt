package org.maun.messenger

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maun.messenger.crypto.BadKeysException
import org.maun.messenger.crypto.ContactCode
import org.maun.messenger.crypto.DuplicateMessageError
import org.maun.messenger.crypto.PeerKeys
import org.maun.messenger.crypto.PublicPreKey
import org.maun.messenger.crypto.Sealed
import org.maun.messenger.crypto.SignalSessions
import org.maun.messenger.crypto.SignedPublicKey
import org.maun.messenger.crypto.UndecryptableException
import org.maun.messenger.data.BlockedEntity
import org.maun.messenger.data.ChatDao
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.data.OutboxEntity
import org.maun.messenger.data.ReceiptLevel
import org.maun.messenger.data.Transactor
import org.maun.messenger.network.PushState
import org.maun.messenger.network.RelayException
import org.maun.messenger.network.RelayLink

/**
 * Sends and receives chat messages for one account.
 *
 * Outgoing: a message is saved together with an outbox entry. The outbox is
 * worked through in order whenever there is a connection: encrypt once, send,
 * and delete the entry when the relay confirms it has stored the message.
 *
 * Incoming: a message is decrypted and saved in one transaction, and only
 * then acknowledged to the relay, which deletes its copy. If the app dies in
 * between, the relay delivers the message again and it is recognised as a
 * repeat. Nothing is lost and nothing shows up twice.
 */
class ChatEngine(
    private val selfId: String,
    private val signal: SignalSessions,
    private val dao: ChatDao,
    private val tx: Transactor,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = 30_000,
    private val push: PushState? = null,
) {
    /** The connection to use. Set once, before the connection is started. */
    @Volatile
    var link: RelayLink? = null

    private val _waiting = MutableStateFlow<Map<String, String>>(emptyMap())

    /** For each person whose messages are stuck: why, in words for the screen. */
    val waiting: StateFlow<Map<String, String>> = _waiting.asStateFlow()

    private val _pushProblem = MutableStateFlow<String?>(null)

    /** Why the relay cannot wake this phone, in words for the screen. Null when it can, or need not. */
    val pushProblem: StateFlow<String?> = _pushProblem.asStateFlow()

    private val wakeUps = Channel<Unit>(Channel.CONFLATED)
    private var retry: Job? = null

    // What is still to do before this phone has caught up with the relay:
    // messages that were waiting at login and have not been handled (-1 while
    // there is no connection), the work done right after login, and passes
    // over the outbox that have been asked for and not finished.
    private val backlog = MutableStateFlow(-1)
    private val settingUp = MutableStateFlow(false)
    private val outboxRuns = MutableStateFlow(0)

    private val pushLock = Mutex()

    @Volatile
    private var relayHasPush = false

    init {
        scope.launch {
            for (wakeUp in wakeUps) {
                val asked = outboxRuns.value
                try {
                    drainOutbox()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A storage error. The entries are still there for the next attempt.
                    retryLater()
                }
                // Anything asked for while this pass ran gets a pass of its own.
                outboxRuns.compareAndSet(asked, 0)
            }
        }
    }

    /** Asks for a pass over the outbox. */
    private fun kick() {
        outboxRuns.update { it + 1 }
        wakeUps.trySend(Unit)
    }

    /**
     * Returns once this phone is connected, has taken in every message that
     * was waiting for it, and has sent everything it had to send, receipts
     * included. Used when the phone was woken up to fetch its messages: after
     * this, the connection can be dropped without losing anything.
     */
    suspend fun awaitCaughtUp() {
        combine(backlog, settingUp, outboxRuns) { waiting, busy, runs -> waiting == 0 && !busy && runs == 0 }
            .first { it }
    }

    // ---- Called by the connection --------------------------------------------

    /** The connection has just logged in. */
    fun onOnline() {
        val ready = link?.ready
        relayHasPush = ready?.hasPush == true
        settingUp.value = true
        backlog.value = ready?.queued ?: 0
        scope.launch {
            try {
                // Each is tried again at the next login. Sending does not depend on either.
                quietly { publishKeys() }
                quietly {
                    // What became of the last wake-up only matters if it went
                    // to the address this phone still uses.
                    if (syncPush()) describeWakeUp(ready?.pushStatus)?.let { _pushProblem.value = it }
                }
            } finally {
                kick()
                settingUp.value = false
            }
        }
    }

    /** The connection that had logged in is gone. */
    fun onOffline() {
        backlog.value = -1
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** A message delivered by the relay. Called for one frame at a time, in order. */
    suspend fun onFrame(frame: JSONObject) {
        if (frame.optString("t") != "msg") return
        try {
            handle(frame)
        } finally {
            backlog.update { if (it > 0) it - 1 else it }
        }
    }

    private suspend fun handle(frame: JSONObject) {
        val seq = frame.optLong("seq", -1)
        if (seq <= 0) return
        val from = frame.optString("from")
        val body = try {
            Base64.getDecoder().decode(frame.optString("body"))
        } catch (_: IllegalArgumentException) {
            null
        }
        val replies = try {
            withContext(io) { receive(from, frame.optInt("kind"), body) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // It could not be saved. Without an acknowledgement the relay
            // keeps it and delivers it again on the next connection.
            return
        }
        link?.send(JSONObject().put("t", "ack").put("seq", seq))
        if (replies) kick()
    }

    /** The phone's wake-up address has changed. Tells the relay if there is a connection. */
    fun pushChanged() {
        scope.launch { quietly { syncPush() } }
    }

    // ---- Called by the app ---------------------------------------------------

    /** Queues [text] for [peer], who must be a contact. Returns as soon as it is saved. */
    suspend fun sendText(peer: String, text: String) {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "nothing to send" }
        require(trimmed.toByteArray(Charsets.UTF_8).size <= Payload.MAX_TEXT_BYTES) { "message too long" }
        val id = UUID.randomUUID().toString()
        val at = now()
        withContext(io) {
            tx.inTransaction {
                check(dao.isContact(peer)) { "not a contact" }
                dao.insertMessage(
                    MessageEntity(peer = peer, id = id, outgoing = true, text = trimmed, at = at, status = MessageStatus.PENDING),
                )
                dao.insertOutbox(OutboxEntity(peer = peer, messageId = id, payload = Payload.encode(Payload.Text(id, at, trimmed))))
            }
        }
        kick()
    }

    /**
     * The conversation with [peer] is on screen. Clears the unread count and,
     * for a contact, tells them their messages were read. Someone who is not a
     * contact learns nothing.
     */
    suspend fun markRead(peer: String) {
        val replies = withContext(io) {
            tx.inTransaction {
                dao.markSeen(peer)
                dao.isContact(peer) && sendReceipts(peer, ReceiptLevel.READ)
            }
        }
        if (replies) kick()
    }

    /**
     * [peer] has just become a contact. Messages they sent before that were
     * kept quiet; now they are told the messages arrived.
     */
    suspend fun onContactAdded(peer: String) {
        val replies = withContext(io) {
            tx.inTransaction { dao.isContact(peer) && sendReceipts(peer, ReceiptLevel.DELIVERED) }
        }
        if (replies) kick()
    }

    /** Deletes the conversation with [peer] from this phone and forgets them as a contact. */
    suspend fun forget(peer: String) {
        withContext(io) {
            tx.inTransaction {
                dao.deleteContact(peer)
                dao.deleteMessages(peer)
                dao.deleteOutboxFor(peer)
            }
        }
        _waiting.value -= peer
    }

    /** As [forget], and from now on anything [peer] sends is thrown away unread. */
    suspend fun block(peer: String) {
        withContext(io) {
            tx.inTransaction {
                dao.block(BlockedEntity(peer, now()))
                dao.deleteContact(peer)
                dao.deleteMessages(peer)
                dao.deleteOutboxFor(peer)
            }
        }
        _waiting.value -= peer
    }

    suspend fun unblock(peer: String) {
        withContext(io) { dao.unblock(peer) }
    }

    // ---- Receiving -----------------------------------------------------------

    /**
     * Handles one delivered message. Returning normally means it is dealt with
     * for good and can be acknowledged; the result says whether a receipt was
     * queued. Throws only when storage failed.
     */
    private fun receive(from: String, kind: Int, body: ByteArray?): Boolean {
        // Nothing the relay should ever send; acknowledge it so it goes away.
        if (body == null || from == selfId || !ContactCode.isId(from)) return false
        return try {
            tx.inTransaction {
                if (dao.isBlocked(from)) return@inTransaction false
                when (val payload = Payload.decode(signal.decrypt(from, kind, body))) {
                    is Payload.Text -> saveIncoming(from, payload)
                    is Payload.Receipt -> {
                        payload.delivered.forEach { dao.raiseStatus(from, it, MessageStatus.DELIVERED) }
                        payload.read.forEach { dao.raiseStatus(from, it, MessageStatus.READ) }
                        false
                    }
                    Payload.Unknown -> false
                }
            }
        } catch (_: DuplicateMessageError) {
            // Saved during an earlier connection that ended before the acknowledgement.
            false
        } catch (_: UndecryptableException) {
            noteUnreadable(from)
            false
        }
    }

    private fun saveIncoming(from: String, payload: Payload.Text): Boolean {
        val contact = dao.isContact(from)
        val inserted = dao.insertMessage(
            MessageEntity(
                peer = from,
                id = payload.id,
                outgoing = false,
                text = payload.text,
                // Their clock, but never later than ours.
                at = minOf(payload.sentAt, now()),
                unread = true,
                receipt = if (contact) ReceiptLevel.DELIVERED else ReceiptLevel.NONE,
            ),
        ) != -1L
        if (!inserted || !contact) return false
        dao.insertOutbox(receipt(from, Payload.Receipt(delivered = listOf(payload.id))))
        return true
    }

    /** Leaves a visible trace in a contact's conversation. Strangers get no such courtesy. */
    private fun noteUnreadable(from: String) {
        tx.inTransaction {
            if (dao.isBlocked(from) || !dao.isContact(from)) return@inTransaction
            dao.insertMessage(
                MessageEntity(
                    peer = from,
                    id = UUID.randomUUID().toString(),
                    outgoing = false,
                    kind = MessageKind.UNREADABLE,
                    text = "",
                    at = now(),
                    unread = true,
                    receipt = ReceiptLevel.READ,
                ),
            )
        }
    }

    /** Queues receipts of [level] for everything from [peer] that has not had one. In a transaction. */
    private fun sendReceipts(peer: String, level: Int): Boolean {
        val ids = dao.owedReceipts(peer, level)
        if (ids.isEmpty()) return false
        ids.chunked(Payload.MAX_RECEIPT_IDS).forEach { chunk ->
            val payload = if (level == ReceiptLevel.READ) Payload.Receipt(read = chunk) else Payload.Receipt(delivered = chunk)
            dao.insertOutbox(receipt(peer, payload))
        }
        dao.raiseReceipts(peer, level)
        return true
    }

    private fun receipt(peer: String, payload: Payload.Receipt) =
        OutboxEntity(peer = peer, messageId = null, payload = Payload.encode(payload))

    // ---- Sending -------------------------------------------------------------

    private sealed interface Outcome {
        data object Done : Outcome

        /** This person cannot be reached right now; carry on with the others. */
        class Hold(val reason: String) : Outcome

        /** The connection is gone or not answering; stop until it is back. */
        data object Stop : Outcome
    }

    private suspend fun drainOutbox() {
        val link = link ?: return
        if (link.ready == null) return
        val held = LinkedHashMap<String, String>()
        var after = 0L
        while (true) {
            val item = withContext(io) { dao.nextOutbox(after) } ?: break
            after = item.seq
            if (item.peer in held) continue
            when (val outcome = deliver(link, item)) {
                Outcome.Done -> Unit
                is Outcome.Hold -> held[item.peer] = outcome.reason
                Outcome.Stop -> {
                    if (link.ready != null) retryLater()
                    return
                }
            }
        }
        _waiting.value = held
        if (held.isNotEmpty()) retryLater()
    }

    private suspend fun deliver(link: RelayLink, item: OutboxEntity): Outcome {
        val peer = item.peer
        var sealed = if (item.cipher != null && item.cipherKind != null) Sealed(item.cipherKind, item.cipher) else null
        if (sealed == null) {
            if (!withContext(io) { tx.inTransaction { signal.hasSession(peer) } }) {
                val answer = try {
                    link.request(JSONObject().put("t", "get_keys").put("of", peer))
                } catch (_: RelayException) {
                    return Outcome.Stop
                }
                val keys = when (answer.optString("t")) {
                    "keys" -> parseKeys(answer) ?: return Outcome.Hold(BAD_KEYS)
                    "failed" -> return Outcome.Hold(describe(answer.optString("code")))
                    else -> return Outcome.Stop
                }
                try {
                    withContext(io) { tx.inTransaction { signal.startSession(peer, keys) } }
                } catch (_: BadKeysException) {
                    return Outcome.Hold(BAD_KEYS)
                }
            }
            sealed = withContext(io) {
                tx.inTransaction {
                    signal.encrypt(peer, item.payload).also { dao.setCipher(item.seq, it.kind, it.body) }
                }
            }
        }

        val answer = try {
            link.request(
                JSONObject()
                    .put("t", "send")
                    .put("to", peer)
                    .put("kind", sealed.kind)
                    .put("body", Base64.getEncoder().encodeToString(sealed.body)),
            )
        } catch (_: RelayException) {
            return Outcome.Stop
        }
        return when (answer.optString("t")) {
            "stored" -> {
                withContext(io) {
                    tx.inTransaction {
                        dao.deleteOutbox(item.seq)
                        item.messageId?.let { dao.raiseStatus(peer, it, MessageStatus.SENT) }
                    }
                }
                Outcome.Done
            }
            "failed" -> Outcome.Hold(describe(answer.optString("code")))
            else -> Outcome.Stop
        }
    }

    @Synchronized
    private fun retryLater() {
        if (retry?.isActive == true) return
        retry = scope.launch {
            delay(retryMillis)
            kick()
        }
    }

    private fun describe(code: String): String = when (code) {
        "no_account" -> "This ID is not registered on the relay."
        "no_keys" -> "They need to open a newer version of Maun once before messages can reach them."
        "mailbox_full" -> "Their mailbox is full until they come online."
        "rate_limited" -> "Sending too fast. Maun will carry on in a moment."
        else -> "The relay could not take the message just now."
    }

    // ---- Pre-keys ------------------------------------------------------------

    /** Makes sure the relay has what others need to start a session with this phone. */
    private suspend fun publishKeys() {
        val link = link ?: return
        val ready = link.ready ?: return
        val (keys, created) = withContext(io) { tx.inTransaction { signal.publishedKeys(now()) } }
        if (created || !ready.hasKeys) {
            val frame = JSONObject()
                .put("t", "put_keys")
                .put("regId", keys.registrationId)
                .put("spk", wire(keys.signedPreKey))
                .put("kpk", wire(keys.kyberPreKey))
            if (link.request(frame).optString("t") != "saved") return
        }
        if (ready.oneTimeKeys < MIN_ONE_TIME_KEYS) {
            // The private halves are saved before the public ones leave the phone.
            val fresh = withContext(io) { tx.inTransaction { signal.newOneTimeKeys(ONE_TIME_KEY_BATCH) } }
            val list = JSONArray()
            fresh.forEach { list.put(JSONObject().put("id", it.id).put("key", encode(it.key))) }
            link.request(JSONObject().put("t", "put_otks").put("keys", list))
        }
    }

    // ---- Wake-up address -------------------------------------------------------

    /**
     * Makes sure the relay has this phone's current wake-up address, or none.
     * Returns true when the relay already had the address this phone wants.
     */
    private suspend fun syncPush(): Boolean = pushLock.withLock {
        val state = push ?: return@withLock false
        val link = link ?: return@withLock false
        if (link.ready == null) return@withLock false
        val wanted = withContext(io) { state.wanted() }
        if (wanted == null) {
            _pushProblem.value = null
            if (relayHasPush && link.request(JSONObject().put("t", "drop_push")).optString("t") == "saved") {
                relayHasPush = false
                withContext(io) { state.setUploaded(null) }
            }
            return@withLock false
        }
        // The relay forgets an address the push service says is dead, so its
        // word counts as well as this phone's memory.
        if (relayHasPush && withContext(io) { state.uploaded() } == wanted) {
            _pushProblem.value = null
            return@withLock true
        }
        val answer = link.request(
            JSONObject().put("t", "put_push").put("url", wanted.url).put("key", wanted.key).put("auth", wanted.auth),
        )
        when (answer.optString("t")) {
            "saved" -> {
                relayHasPush = true
                withContext(io) { state.setUploaded(wanted) }
                _pushProblem.value = null
            }
            "failed" -> _pushProblem.value =
                if (answer.optString("code") == "bad_push") {
                    "The relay does not accept the wake-up address this phone was given."
                } else {
                    "The relay could not take this phone's wake-up address just now."
                }
            // A relay from before wake-ups existed.
            else -> _pushProblem.value = "This relay cannot wake phones yet. It needs an update."
        }
        false
    }

    /** Says why the relay's last wake-up did not get through, or null if it did or nothing is known. */
    private fun describeWakeUp(status: Int?): String? = when {
        status == null || status == WAKE_UP_UNFINISHED || status in 200..299 -> null
        status == 0 -> "The relay could not reach the push service, so the last wake-up was lost."
        status == 429 ->
            "The push service turned the relay's last wake-up away: too many requests. " +
                "Messages may arrive late until it accepts them again."
        else -> "The push service refused the relay's last wake-up (HTTP $status)."
    }

    private fun wire(key: SignedPublicKey) =
        JSONObject().put("id", key.id).put("key", encode(key.key)).put("sig", encode(key.signature))

    private fun parseKeys(frame: JSONObject): PeerKeys? = try {
        val oneTime = frame.optJSONObject("otk")
        PeerKeys(
            registrationId = frame.getInt("regId"),
            signedPreKey = signedKey(frame.getJSONObject("spk")),
            kyberPreKey = signedKey(frame.getJSONObject("kpk")),
            oneTimePreKey = oneTime?.let { PublicPreKey(it.getInt("id"), decode(it.getString("key"))) },
        )
    } catch (_: Exception) {
        null
    }

    private fun signedKey(json: JSONObject) =
        SignedPublicKey(json.getInt("id"), decode(json.getString("key")), decode(json.getString("sig")))

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    companion object {
        /** PUSH_UNFINISHED in backend/src/protocol.ts. */
        private const val WAKE_UP_UNFINISHED = -1

        /** Top up when the relay has fewer one-time pre-keys than this. */
        const val MIN_ONE_TIME_KEYS = 20

        /** Must not exceed MAX_ONE_TIME_KEYS_PER_FRAME in backend/src/protocol.ts. */
        const val ONE_TIME_KEY_BATCH = 50

        private const val BAD_KEYS = "The relay handed out keys that do not belong to this ID. Nothing was sent."
    }
}
