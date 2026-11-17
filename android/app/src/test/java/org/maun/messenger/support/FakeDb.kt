package org.maun.messenger.support

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.maun.messenger.crypto.SignalStorage
import org.maun.messenger.data.BlockedEntity
import org.maun.messenger.data.ChatDao
import org.maun.messenger.data.MessageStatus
import org.maun.messenger.data.ContactEntity
import org.maun.messenger.data.ChatSummary
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.data.MessageKind
import org.maun.messenger.data.OutboxEntity
import org.maun.messenger.data.ReactionEntity
import org.maun.messenger.data.MetaDao
import org.maun.messenger.data.MetaEntry
import org.maun.messenger.data.Transactor

/**
 * Stands in for the phone's database in JVM tests: the same operations, kept
 * in memory, with transactions that really roll back.
 */
class FakeDb : ChatDao, SignalStorage, Transactor {

    private data class State(
        val messages: List<MessageEntity> = emptyList(),
        val outbox: List<OutboxEntity> = emptyList(),
        val reactions: List<ReactionEntity> = emptyList(),
        val contacts: Map<String, ContactEntity> = emptyMap(),
        val blocked: Map<String, Long> = emptyMap(),
        val sessions: Map<String, ByteArray> = emptyMap(),
        val preKeys: Map<Pair<Int, Int>, ByteArray> = emptyMap(),
        val kyberUses: Set<String> = emptySet(),
        val lastMessageSeq: Long = 0,
        val lastOutboxSeq: Long = 0,
        val meta: Map<String, String> = emptyMap(),
    )

    private val lock = ReentrantLock()
    private var state = State()
    private var depth = 0

    /** When set, the next message insert throws, as a full disk or a killed process would. */
    @Volatile
    var failNextMessageInsert = false

    override fun <T> inTransaction(block: () -> T): T = lock.withLock {
        if (depth > 0) return block()
        val before = state
        depth++
        try {
            block()
        } catch (e: Throwable) {
            state = before
            throw e
        } finally {
            depth--
        }
    }

    /** The small key/value store beside the messages: registration, tokens, picture versions. */
    fun meta(): MetaDao = object : MetaDao {
        override suspend fun get(key: String): String? = lock.withLock { state.meta[key] }

        override suspend fun put(entry: MetaEntry) = lock.withLock {
            state = state.copy(meta = state.meta + (entry.key to entry.value))
        }
    }

    // ---- Test helpers ------------------------------------------------------

    fun addContact(id: String, name: String = id.take(6), username: String? = null) =
        putContact(ContactEntity(id = id, name = name, verified = true, addedAt = 0, username = username))

    fun hasSession(peer: String): Boolean = lock.withLock { peer in state.sessions }

    fun preKeyCount(kind: Int): Int = lock.withLock { state.preKeys.keys.count { it.first == kind } }

    // ---- ChatDao -----------------------------------------------------------

    override fun insertMessage(message: MessageEntity): Long = lock.withLock {
        if (failNextMessageInsert) {
            failNextMessageInsert = false
            throw IllegalStateException("simulated storage failure")
        }
        val clash = state.messages.any {
            it.peer == message.peer && it.outgoing == message.outgoing && it.id == message.id
        }
        if (clash) return -1
        val seq = state.lastMessageSeq + 1
        state = state.copy(messages = state.messages + message.copy(seq = seq), lastMessageSeq = seq)
        seq
    }

    override fun observeMessages(peer: String, limit: Int): Flow<List<MessageEntity>> = notInTests()

    override fun messagesWith(peer: String): List<MessageEntity> =
        lock.withLock { state.messages.filter { it.peer == peer }.sortedBy { it.seq } }

    override fun raiseStatus(peer: String, id: String, status: Int): Int = update {
        it.peer == peer && it.outgoing && it.id == id && it.status < status
    }.let { (matches, _) -> apply(matches) { it.copy(status = status) } }

    override fun markSeen(peer: String): Int =
        update { it.peer == peer && it.unread }.let { (matches, _) -> apply(matches) { it.copy(unread = false) } }

    override fun owedReceipts(peer: String, level: Int): List<String> = lock.withLock {
        state.messages
            .filter { it.peer == peer && !it.outgoing && (it.kind == MessageKind.TEXT || it.kind == MessageKind.FILE) && it.receipt < level }
            .sortedBy { it.seq }
            .map { it.id }
    }

    override fun raiseReceipts(peer: String, level: Int): Int =
        update { it.peer == peer && !it.outgoing && it.receipt < level }
            .let { (matches, _) -> apply(matches) { it.copy(receipt = level) } }

    override fun deleteMessages(peer: String): Int = lock.withLock {
        val kept = state.messages.filter { it.peer != peer }
        val removed = state.messages.size - kept.size
        state = state.copy(messages = kept)
        removed
    }

    override fun message(seq: Long): MessageEntity? = lock.withLock { state.messages.firstOrNull { it.seq == seq } }

    override fun deleteMessage(seq: Long): Int = lock.withLock {
        val kept = state.messages.filter { it.seq != seq }
        val removed = state.messages.size - kept.size
        state = state.copy(messages = kept)
        removed
    }

    override fun setText(seq: Long, text: String): Int =
        apply({ it.seq == seq }) { it.copy(text = text) }

    override fun markSavedToGallery(seq: Long): Int =
        apply({ it.seq == seq }) { it.copy(savedToGallery = true) }

    override fun fileMessages(): List<Long> =
        lock.withLock { state.messages.filter { it.kind == MessageKind.FILE }.map { it.seq } }

    override fun deleteAllMessages(): Int = lock.withLock {
        val removed = state.messages.size
        state = state.copy(messages = emptyList())
        removed
    }

    override fun deleteAllOutbox(): Int = removeOutbox { true }

    override fun observeChats(): Flow<List<ChatSummary>> = notInTests()

    override fun insertOutbox(item: OutboxEntity): Long = lock.withLock {
        val seq = state.lastOutboxSeq + 1
        state = state.copy(outbox = state.outbox + item.withSeq(seq), lastOutboxSeq = seq)
        seq
    }

    override fun nextOutbox(after: Long): OutboxEntity? =
        lock.withLock { state.outbox.filter { it.seq > after }.minByOrNull { it.seq } }

    override fun setCipher(seq: Long, kind: Int, cipher: ByteArray): Int = lock.withLock {
        var changed = 0
        state = state.copy(
            outbox = state.outbox.map {
                if (it.seq != seq) it
                else OutboxEntity(it.seq, it.peer, it.messageId, it.payload, kind, cipher).also { changed++ }
            },
        )
        changed
    }

    override fun deleteOutbox(seq: Long): Int = removeOutbox { it.seq == seq }

    override fun deleteOutboxFor(peer: String): Int = removeOutbox { it.peer == peer }

    override fun deleteOutboxOf(peer: String, messageId: String): Int =
        removeOutbox { it.peer == peer && it.messageId == messageId }

    override fun outboxFor(peer: String): List<OutboxEntity> = lock.withLock { state.outbox.filter { it.peer == peer } }

    override fun outboxSize(): Int = lock.withLock { state.outbox.size }

    // ---- Reactions ----------------------------------------------------------

    override fun reactionsOf(peer: String, id: String): List<ReactionEntity> = lock.withLock {
        state.reactions.filter { it.messageId == id && hasMessage(peer, id) }
    }

    override fun observeReactions(peer: String): Flow<List<ReactionEntity>> {
        val ids = lock.withLock { state.messages.filter { it.peer == peer }.map { it.id }.toSet() }
        return MutableStateFlow(lock.withLock { state.reactions.filter { it.messageId in ids } })
    }

    override fun reaction(id: String, mine: Boolean): ReactionEntity? = lock.withLock {
        state.reactions.firstOrNull { it.messageId == id && it.mine == mine }
    }

    override fun insertReaction(reaction: ReactionEntity) = lock.withLock {
        state = state.copy(
            reactions = state.reactions.filterNot { it.messageId == reaction.messageId && it.mine == reaction.mine } +
                reaction,
        )
    }

    override fun deleteReaction(id: String, mine: Boolean): Int = lock.withLock {
        val before = state.reactions.size
        state = state.copy(reactions = state.reactions.filterNot { it.messageId == id && it.mine == mine })
        before - state.reactions.size
    }

    override fun deleteReactions(id: String): Int = lock.withLock {
        val before = state.reactions.size
        state = state.copy(reactions = state.reactions.filterNot { it.messageId == id })
        before - state.reactions.size
    }

    override fun deleteReactionsFor(peer: String): Int = lock.withLock {
        val ids = state.messages.filter { it.peer == peer }.map { it.id }.toSet()
        val before = state.reactions.size
        state = state.copy(reactions = state.reactions.filterNot { it.messageId in ids })
        before - state.reactions.size
    }

    override fun deleteAllReactions(): Int = lock.withLock {
        val before = state.reactions.size
        state = state.copy(reactions = emptyList())
        before
    }

    override fun seqOf(peer: String, id: String): Long? = lock.withLock {
        state.messages.firstOrNull { it.peer == peer && it.id == id }?.seq
    }

    private fun hasMessage(peer: String, id: String): Boolean =
        state.messages.any { it.peer == peer && it.id == id }

    override fun isContact(id: String): Boolean = lock.withLock { id in state.contacts }

    override fun deleteContact(id: String): Int = lock.withLock {
        val had = id in state.contacts
        state = state.copy(contacts = state.contacts - id)
        if (had) 1 else 0
    }

    override fun contact(id: String): ContactEntity? = lock.withLock { state.contacts[id] }

    override fun contactByUsername(username: String): ContactEntity? = lock.withLock {
        state.contacts.values.firstOrNull { it.username == username }
            ?: state.contacts.values.firstOrNull { it.username == null && it.name.lowercase() == username }
    }

    override fun contactsWithoutUsername(limit: Int): List<ContactEntity> =
        lock.withLock { state.contacts.values.filter { it.username == null }.take(limit) }

    override fun putContact(contact: ContactEntity) =
        lock.withLock { state = state.copy(contacts = state.contacts + (contact.id to contact)) }

    override fun moveMessages(old: String, new: String): Int = lock.withLock {
        var moved = 0
        val taken = state.messages.filter { it.peer == new }.map { it.outgoing to it.id }.toSet()
        state = state.copy(
            messages = state.messages.map {
                if (it.peer == old && (it.outgoing to it.id) !in taken) it.copy(peer = new).also { moved++ } else it
            },
        )
        moved
    }

    override fun moveOutbox(old: String, new: String): Int = lock.withLock {
        var moved = 0
        state = state.copy(
            outbox = state.outbox.map {
                if (it.peer == old) OutboxEntity(it.seq, new, it.messageId, it.payload).also { moved++ } else it
            },
        )
        moved
    }

    override fun undelivered(peer: String, since: Long): List<MessageEntity> = lock.withLock {
        state.messages.filter {
            it.peer == peer && it.outgoing && it.status == MessageStatus.SENT && it.kind != MessageKind.NOTICE &&
                it.kind != MessageKind.UNREADABLE && it.at >= since
        }
    }

    override fun setStatus(seq: Long, status: Int): Int = apply({ it.seq == seq }) { it.copy(status = status) }

    override fun isBlocked(id: String): Boolean = lock.withLock { id in state.blocked }

    override fun block(entry: BlockedEntity) =
        lock.withLock { state = state.copy(blocked = state.blocked + (entry.id to entry.blockedAt)) }

    override fun unblock(id: String): Int = lock.withLock {
        val had = id in state.blocked
        state = state.copy(blocked = state.blocked - id)
        if (had) 1 else 0
    }

    override fun observeBlocked(): Flow<List<BlockedEntity>> = notInTests()

    // ---- SignalStorage -----------------------------------------------------

    override fun loadSession(peer: String): ByteArray? = lock.withLock { state.sessions[peer] }

    override fun storeSession(peer: String, record: ByteArray) =
        lock.withLock { state = state.copy(sessions = state.sessions + (peer to record)) }

    override fun deleteSession(peer: String) = lock.withLock { state = state.copy(sessions = state.sessions - peer) }

    override fun loadPreKey(kind: Int, id: Int): ByteArray? = lock.withLock { state.preKeys[kind to id] }

    override fun loadPreKeys(kind: Int): List<ByteArray> =
        lock.withLock { state.preKeys.filterKeys { it.first == kind }.toSortedMap(compareBy { it.second }).values.toList() }

    override fun storePreKey(kind: Int, id: Int, record: ByteArray) =
        lock.withLock { state = state.copy(preKeys = state.preKeys + ((kind to id) to record)) }

    override fun removePreKey(kind: Int, id: Int) =
        lock.withLock { state = state.copy(preKeys = state.preKeys - (kind to id)) }

    override fun highestPreKeyId(kind: Int): Int =
        lock.withLock { state.preKeys.keys.filter { it.first == kind }.maxOfOrNull { it.second } ?: 0 }

    override fun recordKyberUse(kyberId: Int, signedId: Int, baseKey: String): Boolean = lock.withLock {
        val use = "$kyberId/$signedId/$baseKey"
        if (use in state.kyberUses) return false
        state = state.copy(kyberUses = state.kyberUses + use)
        true
    }

    // ---- Plumbing ----------------------------------------------------------

    private fun update(matches: (MessageEntity) -> Boolean): Pair<(MessageEntity) -> Boolean, Unit> = matches to Unit

    private fun apply(matches: (MessageEntity) -> Boolean, change: (MessageEntity) -> MessageEntity): Int = lock.withLock {
        var changed = 0
        state = state.copy(
            messages = state.messages.map { if (matches(it)) change(it).also { changed++ } else it },
        )
        changed
    }

    private fun removeOutbox(matches: (OutboxEntity) -> Boolean): Int = lock.withLock {
        val kept = state.outbox.filterNot(matches)
        val removed = state.outbox.size - kept.size
        state = state.copy(outbox = kept)
        removed
    }

    private fun OutboxEntity.withSeq(seq: Long) = OutboxEntity(seq, peer, messageId, payload, cipherKind, cipher)

    private fun notInTests(): Nothing = throw UnsupportedOperationException("screens are not part of these tests")
}
