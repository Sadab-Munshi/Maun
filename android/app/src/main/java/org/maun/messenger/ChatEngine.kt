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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.network.Usernames
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

    private val _unsent = MutableStateFlow(0)

    /**
     * How many things are saved and waiting to go to the relay: messages
     * written, receipts owed. Kept in storage, not in memory, so it means the
     * same thing after the app has been closed and opened again; anything
     * above zero is a reason for the phone to be woken when it has a network.
     */
    val unsent: StateFlow<Int> = _unsent.asStateFlow()

    private val _pushProblem = MutableStateFlow<String?>(null)

    /** Why the relay cannot wake this phone, in words for the screen. Null when it can, or need not. */
    val pushProblem: StateFlow<String?> = _pushProblem.asStateFlow()

    private val _moved = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

    /** A contact's ID changed, from the first to the second: they set Maun up again. */
    val moved: SharedFlow<Pair<String, String>> = _moved.asSharedFlow()

    private val _avatars = MutableSharedFlow<Pair<String, Payload.Avatar>>(extraBufferCapacity = 16)

    /**
     * A contact has said something about their picture: that it is now this,
     * that they have none, or that they would like to know which one this
     * phone has. Kept by [ProfilePictures], not here: the engine only passes
     * it on, because the picture itself is not a message.
     */
    val avatars: SharedFlow<Pair<String, Payload.Avatar>> = _avatars.asSharedFlow()

    /**
     * What this phone should answer when a contact asks which picture they
     * have. Null (or nothing) while this phone has no picture to share.
     */
    @Volatile
    var myAvatar: (() -> Payload.Avatar?)? = null

    /** Senders being looked up right now. */
    private val meeting = ConcurrentHashMap.newKeySet<String>()

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

    /** One thing at a time is encrypted and handed to the relay, whether from the outbox or for a call. */
    private val sending = Mutex()

    /**
     * Told about every step of a call that arrives from a contact: who it is
     * from, the step, and how long it waited on the relay in milliseconds.
     */
    @Volatile
    var onCall: ((from: String, call: Payload.Call, ageMillis: Long) -> Unit)? = null

    /** The call step in the message being handled right now, if it is one. */
    private var arrivedCall: Payload.Call? = null

    @Volatile
    private var relayHasPush = false

    init {
        // What a previous run left in the outbox counts from the first moment.
        scope.launch { quietly { noteUnsent() } }
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
                // A pass that was cut short still says how much is left.
                quietly { noteUnsent() }
                // Anything asked for while this pass ran gets a pass of its own.
                outboxRuns.compareAndSet(asked, 0)
            }
        }
    }

    /** Notes how much is waiting to go, for whoever keeps the phone trying. */
    private suspend fun noteUnsent() {
        _unsent.value = withContext(io) { dao.outboxSize() }
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
                quietly { learnUsernames() }
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
        arrivedCall = null
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
        arrivedCall?.let { call ->
            arrivedCall = null
            onCall?.invoke(from, call, frame.optLong("age", 0))
        }
        // On a relay of members, somebody who writes without being a contact
        // is still somebody the relay can name.
        if (memberRelay && body != null && !withContext(io) { dao.isContact(from) }) meet(from)
    }

    // ---- Calls ----------------------------------------------------------------
    //
    // A call is set up with ordinary encrypted messages, so the relay learns no
    // more than that two people wrote to each other. They are not kept in the
    // outbox: a step of a call that cannot leave now is of no use later.

    /**
     * Sends one step of a call to [peer] right away. [urgent] asks the relay
     * to wake their phone even if it was woken a moment ago. Returns false
     * when it could not be handed to the relay.
     */
    suspend fun sendCall(peer: String, call: Payload.Call, urgent: Boolean): Boolean {
        val link = link ?: return false
        if (link.ready == null) return false
        val item = OutboxEntity(peer = peer, messageId = null, payload = Payload.encode(call))
        return sending.withLock { deliver(link, item, urgent) } == Outcome.Done
    }

    /** One of the servers a call is carried through. */
    class CallServer(val urls: List<String>, val username: String, val credential: String)

    /** Asks the relay for the servers of one call. Throws [RelayException] with the reason when there are none. */
    suspend fun callServers(): List<CallServer> {
        val answer = (link ?: throw RelayException("Not connected to the relay.")).request(JSONObject().put("t", "turn"))
        if (answer.optString("t") != "ice") {
            throw RelayException(
                when (answer.optString("code")) {
                    "no_calls" -> "Calls are not set up on this relay."
                    "calls_refused" -> "The relay's call service refused. The relay's owner has to check its TURN key."
                    "rate_limited" -> "Too many calls today. Try again tomorrow."
                    else -> "The relay could not set up a call just now."
                },
            )
        }
        val listed = answer.optJSONArray("servers") ?: throw RelayException("The relay could not set up a call just now.")
        val servers = (0 until listed.length()).mapNotNull { i ->
            val entry = listed.optJSONObject(i) ?: return@mapNotNull null
            val urls = entry.optJSONArray("urls") ?: return@mapNotNull null
            CallServer(
                urls = (0 until urls.length()).map { urls.optString(it) }.filter { it.startsWith("turn:") || it.startsWith("turns:") },
                username = entry.optString("username"),
                credential = entry.optString("credential"),
            ).takeIf { it.urls.isNotEmpty() && it.username.isNotEmpty() }
        }
        if (servers.isEmpty()) throw RelayException("The relay could not set up a call just now.")
        return servers
    }

    /** Puts a line into the conversation with [peer]: how a call went. [missed] makes it count as unread. */
    suspend fun addNotice(peer: String, text: String, missed: Boolean) {
        withContext(io) {
            tx.inTransaction {
                if (!dao.isContact(peer)) return@inTransaction
                dao.insertMessage(
                    MessageEntity(
                        peer = peer,
                        id = "notice-${UUID.randomUUID()}",
                        outgoing = false,
                        kind = MessageKind.NOTICE,
                        text = text,
                        at = now(),
                        unread = missed,
                        receipt = ReceiptLevel.READ,
                    ),
                )
            }
        }
    }

    // ---- Members who set Maun up again ----------------------------------------
    //
    // On a relay with usernames, a member who deletes the app's data or moves
    // to another phone logs in again and gets a new ID; the username stays.
    // The relay answers "gone" for the old ID. This phone then asks which ID
    // the username has now, and carries the contact, the conversation and
    // whatever was still to be sent over to it. The relay is trusted for that
    // answer exactly as it is when a contact is added by username: the
    // contact counts as unchecked again, and the conversation says so.

    /** Whether the relay has usernames: it told this phone its own. */
    private val memberRelay: Boolean get() = link?.ready?.name != null

    /** Asks the relay for a member, by "name" or by "user" (an ID). Returns the ID and the username. */
    private suspend fun find(by: String, value: String): Pair<String, String>? {
        val answer = try {
            (link ?: return null).request(JSONObject().put("t", "find").put(by, value))
        } catch (_: RelayException) {
            return null
        }
        if (answer.optString("t") != "found") return null
        val id = answer.optString("user")
        val name = answer.optString("name")
        return if (ContactCode.isId(id) && name.isNotEmpty()) id to name else null
    }

    /**
     * The relay says [peer]'s ID is gone. Finds where their username leads
     * now and moves the contact there. Returns false when there is nowhere to
     * follow them to.
     */
    private suspend fun follow(peer: String): Boolean {
        if (!memberRelay) return false
        val contact = withContext(io) { dao.contact(peer) } ?: return false
        // A contact from before usernames were kept was named after theirs when it was added by username.
        val username = contact.username ?: Usernames.clean(contact.name).takeIf(Usernames::isValid) ?: return false
        val (id, name) = find("name", username) ?: return false
        if (id == peer || id == selfId) return false
        movePeer(peer, id, name)
        return true
    }

    /** Looks up someone who wrote without being a contact, and makes them one. */
    private fun meet(from: String) {
        if (!meeting.add(from)) return
        scope.launch {
            try {
                quietly {
                    val (_, username) = find("user", from) ?: return@quietly
                    val known = withContext(io) { dao.contactByUsername(username) }
                    // A contact only named like them is theirs if its own ID is nobody's any more.
                    val same = known != null && known.id != from && (known.username != null || find("user", known.id) == null)
                    if (known != null && same) {
                        movePeer(known.id, from, username)
                    } else if (known == null || known.id != from) {
                        val added = withContext(io) {
                            tx.inTransaction {
                                if (dao.isBlocked(from) || dao.isContact(from)) return@inTransaction false
                                dao.putContact(ContactEntity(id = from, name = username, verified = false, addedAt = now(), username = username))
                                true
                            }
                        }
                        if (added) onContactAdded(from)
                    }
                }
            } finally {
                meeting.remove(from)
            }
        }
    }

    /** Fills in the usernames of contacts that have none yet, and follows those whose ID has gone. */
    private suspend fun learnUsernames() {
        if (!memberRelay) return
        for (contact in withContext(io) { dao.contactsWithoutUsername(USERNAMES_PER_LOGIN) }) {
            val found = find("user", contact.id)
            if (found != null) {
                withContext(io) { tx.inTransaction { dao.contact(contact.id)?.let { dao.putContact(it.copy(username = found.second)) } } }
            } else if (link?.ready != null) {
                follow(contact.id)
            }
        }
    }

    /**
     * Carries a contact over from the ID [old] to [new]: the contact itself,
     * the conversation, and what was waiting to be sent. Messages the relay
     * had taken for the old ID and never handed over are sent again.
     */
    internal suspend fun movePeer(old: String, new: String, username: String?) {
        val moved = withContext(io) {
            tx.inTransaction {
                val contact = dao.contact(old) ?: return@inTransaction false
                val already = dao.contact(new)
                // Whoever checked the old ID has not checked this one.
                dao.putContact((already ?: contact.copy(id = new, verified = false)).copy(username = username ?: contact.username))
                dao.deleteContact(old)
                dao.moveMessages(old, new)
                dao.deleteMessages(old)
                // What was still waiting to go out is put behind what has to be
                // sent again, so that everything arrives in the order it was
                // written. Receipts are dropped: they answer messages the new
                // setup never sent.
                dao.moveOutbox(old, new)
                val waiting = dao.outboxFor(new).filter { it.messageId != null }
                dao.deleteOutboxFor(new)
                for (message in dao.undelivered(new, now() - RESEND_WINDOW_MILLIS)) {
                    val payload = when (message.kind) {
                        MessageKind.TEXT -> Payload.Text(message.id, message.at, message.text)
                        else -> Attachment.fromLocal(message.text)?.takeIf { it.key.isNotEmpty() }
                            ?.let { Payload.File(message.id, message.at, it) }
                    } ?: continue
                    dao.setStatus(message.seq, MessageStatus.PENDING)
                    dao.insertOutbox(OutboxEntity(peer = new, messageId = message.id, payload = Payload.encode(payload)))
                }
                for (item in waiting) dao.insertOutbox(OutboxEntity(peer = new, messageId = item.messageId, payload = item.payload))
                dao.insertMessage(
                    MessageEntity(
                        peer = new,
                        id = "notice-${UUID.randomUUID()}",
                        outgoing = false,
                        kind = MessageKind.NOTICE,
                        text = "${contact.name} set up Maun again. Messages now go to the new setup, " +
                            "and the safety number has changed.",
                        at = now(),
                        // Nothing is owed for it, and it does not count as unread.
                        receipt = ReceiptLevel.READ,
                    ),
                )
                signal.endSession(old)
                true
            }
        }
        if (!moved) return
        _waiting.value -= old
        _moved.tryEmit(old to new)
        kick()
    }

    /** The phone's wake-up address has changed. Tells the relay if there is a connection. */
    fun pushChanged() {
        scope.launch { quietly { syncPush() } }
    }

    /**
     * Tells the relay to stop waking this phone, and returns once it has said
     * so (or at once if it cannot be reached). Called when signing out: a
     * phone that is not signed in has no reason to be woken.
     */
    suspend fun withdrawPush(): Boolean = syncPush()

    /**
     * Leaves the sealed copy of this account's identity on the relay, or (with
     * null) has the relay throw away the one it has. Returns why it could not,
     * in words for the screen, or null.
     *
     * The relay is given ciphertext and nothing else: it cannot open it, check
     * the passphrase, or tell whether what it was handed is a backup at all.
     */
    suspend fun putRecovery(salt: String, kdf: String, blob: String): String? = recoveryFrame(
        JSONObject().put("t", "put_recovery").put("salt", salt).put("kdf", kdf).put("blob", blob),
    )

    suspend fun dropRecovery(): String? = recoveryFrame(JSONObject().put("t", "drop_recovery"))

    private suspend fun recoveryFrame(frame: JSONObject): String? {
        val link = link ?: return "Maun is not connected to the relay. Try again in a moment."
        return try {
            val answer = link.request(frame)
            if (answer.optString("t") == "saved") {
                null
            } else {
                when (answer.optString("code")) {
                    "bad_recovery" -> "The relay would not take that backup: it is not the shape of one."
                    "no_members" -> "This relay has no accounts to recover."
                    else -> "The relay could not keep the backup just now."
                }
            }
        } catch (e: Exception) {
            e.message ?: "The relay could not keep the backup just now."
        }
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
        noteUnsent()
        kick()
    }

    /**
     * Adds a file [peer] is about to be sent, before it has been uploaded.
     * Nothing leaves the phone yet: see [sendFile]. Returns the new row.
     */
    suspend fun addFile(peer: String, file: Attachment): MessageEntity {
        val message = MessageEntity(
            peer = peer,
            id = UUID.randomUUID().toString(),
            outgoing = true,
            kind = MessageKind.FILE,
            text = file.toLocal(),
            at = now(),
            status = MessageStatus.PENDING,
        )
        val seq = withContext(io) {
            tx.inTransaction {
                check(dao.isContact(peer)) { "not a contact" }
                dao.insertMessage(message)
            }
        }
        return message.copy(seq = seq)
    }

    /**
     * The file of message [seq] is on the relay as [file]: queues the message
     * that tells its reader where, and with which key. Does nothing when the
     * message was deleted in the meantime.
     */
    suspend fun sendFile(seq: Long, file: Attachment) {
        val queued = withContext(io) {
            tx.inTransaction {
                val message = dao.message(seq) ?: return@inTransaction false
                dao.setText(seq, file.copy(state = Attachment.READY).toLocal())
                val payload = Payload.File(message.id, message.at, file)
                dao.insertOutbox(OutboxEntity(peer = message.peer, messageId = message.id, payload = Payload.encode(payload)))
                true
            }
        }
        if (queued) {
            noteUnsent()
            kick()
        }
    }

    /** The file record of message [seq], or null when there is no such message or it has no file. */
    suspend fun fileOf(seq: Long): Attachment? = withContext(io) {
        dao.message(seq)?.takeIf { it.kind == MessageKind.FILE }?.let { Attachment.fromLocal(it.text) }
    }

    /** Notes where the file of message [seq] is now. */
    suspend fun setFileState(seq: Long, state: Int) {
        withContext(io) {
            tx.inTransaction {
                val message = dao.message(seq)?.takeIf { it.kind == MessageKind.FILE } ?: return@inTransaction
                val file = Attachment.fromLocal(message.text) ?: return@inTransaction
                if (file.state != state) dao.setText(seq, file.copy(state = state).toLocal())
            }
        }
    }

    /** Deletes every message and everything waiting to be sent. Contacts stay. */
    suspend fun wipeMessages() {
        withContext(io) {
            tx.inTransaction {
                dao.deleteAllOutbox()
                dao.deleteAllMessages()
            }
        }
        noteUnsent()
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

    /** Queues a profile-picture message for [peer] and starts sending at once. */
    suspend fun sendAvatar(peer: String, picture: Payload.Avatar) {
        withContext(io) {
            tx.inTransaction {
                dao.insertOutbox(OutboxEntity(peer = peer, messageId = null, payload = Payload.encode(picture)))
            }
        }
        kick()
    }

    /** Asks [peer] which picture they have, so that a new contact is shown with one. */
    suspend fun askAvatar(peer: String) = sendAvatar(peer, Payload.Avatar(Payload.AVATAR_ASK))

    /**
     * Deletes one message from this phone. If it had not left yet, it never
     * will; if it had, the other person keeps their copy.
     */
    suspend fun deleteMessage(seq: Long) {
        withContext(io) {
            tx.inTransaction {
                val message = dao.message(seq) ?: return@inTransaction
                if (message.outgoing) dao.deleteOutboxOf(message.peer, message.id)
                dao.deleteMessage(seq)
            }
        }
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
                    is Payload.Text -> saveIncoming(from, payload.id, payload.sentAt, MessageKind.TEXT, payload.text)
                    is Payload.File ->
                        saveIncoming(from, payload.id, payload.sentAt, MessageKind.FILE, payload.file.toLocal())
                    is Payload.Receipt -> {
                        payload.delivered.forEach { dao.raiseStatus(from, it, MessageStatus.DELIVERED) }
                        payload.read.forEach { dao.raiseStatus(from, it, MessageStatus.READ) }
                        false
                    }
                    is Payload.Avatar -> {
                        // Only people one has added hear about a picture, or are told about one.
                        if (!dao.isContact(from)) return@inTransaction false
                        if (payload.version == Payload.AVATAR_ASK) {
                            val mine = myAvatar?.invoke() ?: Payload.Avatar(Payload.AVATAR_NONE)
                            dao.insertOutbox(
                                OutboxEntity(peer = from, messageId = null, payload = Payload.encode(mine)),
                            )
                            // Something to send, so the outbox is gone over at once.
                            true
                        } else {
                            _avatars.tryEmit(from to payload)
                            false
                        }
                    }
                    is Payload.Call -> {
                        // Only people one has added can make the phone ring.
                        if (dao.isContact(from)) arrivedCall = payload
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

    private fun saveIncoming(from: String, id: String, sentAt: Long, kind: Int, text: String): Boolean {
        val contact = dao.isContact(from)
        val inserted = dao.insertMessage(
            MessageEntity(
                peer = from,
                id = id,
                outgoing = false,
                kind = kind,
                text = text,
                // Their clock, but never later than ours.
                at = minOf(sentAt, now()),
                unread = true,
                receipt = if (contact) ReceiptLevel.DELIVERED else ReceiptLevel.NONE,
            ),
        ) != -1L
        if (!inserted || !contact) return false
        dao.insertOutbox(receipt(from, Payload.Receipt(delivered = listOf(id))))
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

        /** This person has a new ID now and what was for them has been readdressed; go over the outbox again. */
        data object Moved : Outcome
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
            // A receipt has nothing to show anyone: it must not wake the other phone.
            when (val outcome = sending.withLock { deliver(link, item, quiet = item.messageId == null) }) {
                Outcome.Done -> Unit
                // Their items now carry the new ID; this pass ends and another begins.
                Outcome.Moved -> {
                    kick()
                    return
                }
                is Outcome.Hold -> held[item.peer] = outcome.reason
                Outcome.Stop -> {
                    if (link.ready != null) retryLater()
                    return
                }
            }
        }
        _waiting.value = held
        noteUnsent()
        if (held.isNotEmpty()) retryLater()
    }

    private suspend fun deliver(link: RelayLink, item: OutboxEntity, urgent: Boolean = false, quiet: Boolean = false): Outcome {
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
                    "failed" -> return refused(peer, answer.optString("code"))
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
                    .put("body", Base64.getEncoder().encodeToString(sealed.body))
                    .apply {
                        if (urgent) put("urgent", true)
                        if (quiet) put("quiet", true)
                    },
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
            "failed" -> refused(peer, answer.optString("code"))
            else -> Outcome.Stop
        }
    }

    /** The relay would not take something for [peer]. If their ID is gone, tries to find where they went. */
    private suspend fun refused(peer: String, code: String): Outcome =
        if (code == "gone" && follow(peer)) Outcome.Moved else Outcome.Hold(describe(code))

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
        "gone" -> "They are no longer on this relay under this ID. If they set Maun up again, add them again."
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
        /** Contacts whose username is asked for at one login. */
        private const val USERNAMES_PER_LOGIN = 20

        /** How far back undelivered messages are sent again to someone who set Maun up again. */
        private const val RESEND_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
        /** PUSH_UNFINISHED in backend/src/protocol.ts. */
        private const val WAKE_UP_UNFINISHED = -1

        /** Top up when the relay has fewer one-time pre-keys than this. */
        const val MIN_ONE_TIME_KEYS = 20

        /** Must not exceed MAX_ONE_TIME_KEYS_PER_FRAME in backend/src/protocol.ts. */
        const val ONE_TIME_KEY_BATCH = 50

        private const val BAD_KEYS = "The relay handed out keys that do not belong to this ID. Nothing was sent."
    }
}
