package org.maun.messenger.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** How far an outgoing message has got. Values only ever go up. */
object MessageStatus {
    /** Saved on this phone, not yet accepted by the relay. */
    const val PENDING = 0

    /** The relay has stored it for the recipient. */
    const val SENT = 1

    /** It has reached the recipient's phone. */
    const val DELIVERED = 2

    /** The recipient has opened the conversation. */
    const val READ = 3
}

/** The highest receipt this phone has sent back for an incoming message. */
object ReceiptLevel {
    const val NONE = 0
    const val DELIVERED = 1
    const val READ = 2
}

object MessageKind {
    const val TEXT = 0

    /** A message arrived that this phone could not decrypt. There is no text. */
    const val UNREADABLE = 1

    /** A file. The text is its record: see [org.maun.messenger.Attachment]. */
    const val FILE = 2

    /** A line the app itself put into the conversation, such as "set up Maun again". */
    const val NOTICE = 3
}

/**
 * One chat message, sent or received. The only copy of the text is here, in
 * the encrypted database on this phone.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["peer", "outgoing", "id"], unique = true)],
)
data class MessageEntity(
    /** Local order: the order in which this phone learned of the messages. */
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    /** The other person's ID. */
    val peer: String,
    /** Chosen by the sender; receipts refer to it. */
    val id: String,
    val outgoing: Boolean,
    val kind: Int = MessageKind.TEXT,
    val text: String,
    /** When it was written, by the writer's clock. */
    val at: Long,
    /** Outgoing only: a [MessageStatus]. */
    val status: Int = MessageStatus.PENDING,
    /**
     * The ID of the message this one answers, when it answers one. Only the
     * ID travels; the quote is drawn from this phone's own copy.
     */
    @ColumnInfo(name = "reply_to") val replyTo: String? = null,
    /** Incoming only: not yet shown on screen. */
    val unread: Boolean = false,
    /** Incoming only: a [ReceiptLevel]. */
    val receipt: Int = ReceiptLevel.NONE,
    /** Whether this file or media message has already been saved to Gallery or Downloads/Maun. */
    @ColumnInfo(defaultValue = "0")
    val savedToGallery: Boolean = false,
)

/**
 * Something waiting to go to the relay: a chat message or a receipt.
 *
 * [payload] is the plaintext. It is encrypted once, the result is kept in
 * [cipher], and from then on exactly those bytes are sent until the relay
 * confirms them. Sending the same bytes twice is harmless; encrypting twice
 * would not be.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["peer"])])
class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val peer: String,
    /** The chat message this carries, or null for a receipt. */
    @ColumnInfo(name = "message_id") val messageId: String?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    @ColumnInfo(name = "cipher_kind") val cipherKind: Int? = null,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val cipher: ByteArray? = null,
    /**
     * Whether this entry should wake the other phone. Receipts should not, and
     * they are the entries without a message; a reaction has no message of its
     * own either but must still be noticed, so it says so here.
     */
    @ColumnInfo(name = "wakes", defaultValue = "0") val wakes: Boolean = false,
)

/**
 * One emoji on one message, as this phone knows it: [mine] when this phone put
 * it there, otherwise the other person's. One row per side and message, so a
 * second answer replaces the first rather than piling up; [at] is when the
 * phone that chose it did so, and only a later one may replace an earlier one,
 * which is what settles reactions that arrive out of order.
 */
@Entity(tableName = "reactions", primaryKeys = ["message_id", "mine"])
data class ReactionEntity(
    @ColumnInfo(name = "message_id") val messageId: String,
    val mine: Boolean,
    /** Empty never reaches storage: taking a reaction off deletes the row. */
    val emoji: String,
    val at: Long,
)

/** What one message carries: the emoji on it from each side, where there is one. */
data class Reactions(val mine: String? = null, val theirs: String? = null) {
    val isEmpty: Boolean get() = mine == null && theirs == null

    /** Both, for the pill under the bubble: this phone's first. */
    fun shown(): String = listOfNotNull(mine, theirs).distinct().joinToString("")
}

/** Someone whose messages are thrown away unread. */
@Entity(tableName = "blocked")
data class BlockedEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "blocked_at") val blockedAt: Long,
)

/** The newest message of one conversation, for the chat list. */
data class ChatSummary(
    val peer: String,
    val seq: Long,
    val kind: Int,
    val text: String,
    val outgoing: Boolean,
    val status: Int,
    val at: Long,
    val unread: Int,
    /** The newest message's own ID, as its sender chose it. */
    val id: String = "",
)

/**
 * Messages, the outbox and the block list.
 *
 * The plain functions block and are meant to be called inside a transaction,
 * off the main thread. The `observe` functions feed the screens.
 */
@Dao
interface ChatDao {

    // ---- Messages ----------------------------------------------------------

    /** Returns -1 when this message is already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMessage(message: MessageEntity): Long

    /** Newest first. */
    @Query("SELECT * FROM messages WHERE peer = :peer ORDER BY seq DESC LIMIT :limit")
    fun observeMessages(peer: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE peer = :peer ORDER BY seq")
    fun messagesWith(peer: String): List<MessageEntity>

    /** Moves an outgoing message forward to [status]; never backwards. Returns rows changed. */
    @Query("UPDATE messages SET status = :status WHERE peer = :peer AND outgoing = 1 AND id = :id AND status < :status")
    fun raiseStatus(peer: String, id: String, status: Int): Int

    @Query("UPDATE messages SET unread = 0 WHERE peer = :peer AND unread = 1")
    fun markSeen(peer: String): Int

    /** Incoming messages from [peer], text and files alike, that have not had a receipt of [level] yet, oldest first. */
    @Query(
        "SELECT id FROM messages WHERE peer = :peer AND outgoing = 0 AND kind IN (0, 2) AND receipt < :level ORDER BY seq",
    )
    fun owedReceipts(peer: String, level: Int): List<String>

    @Query("UPDATE messages SET receipt = :level WHERE peer = :peer AND outgoing = 0 AND receipt < :level")
    fun raiseReceipts(peer: String, level: Int): Int

    @Query("DELETE FROM messages WHERE peer = :peer")
    fun deleteMessages(peer: String): Int

    @Query("SELECT * FROM messages WHERE seq = :seq")
    fun message(seq: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE seq = :seq")
    fun deleteMessage(seq: Long): Int

    @Query(
        """
        SELECT m.peer AS peer, m.seq AS seq, m.kind AS kind, m.text AS text, m.outgoing AS outgoing,
               m.status AS status, m.at AS at, m.id AS id,
               (SELECT COUNT(*) FROM messages u WHERE u.peer = m.peer AND u.unread = 1) AS unread
        FROM messages m
        WHERE m.seq IN (SELECT MAX(seq) FROM messages GROUP BY peer)
        ORDER BY m.seq DESC
        """,
    )
    fun observeChats(): Flow<List<ChatSummary>>

    @Query("UPDATE messages SET text = :text WHERE seq = :seq")
    fun setText(seq: Long, text: String): Int

    @Query("UPDATE messages SET savedToGallery = 1 WHERE seq = :seq")
    fun markSavedToGallery(seq: Long): Int

    /** Every message that has a file, for telling which stored files still belong to one. */
    @Query("SELECT seq FROM messages WHERE kind = 2")
    fun fileMessages(): List<Long>

    @Query("DELETE FROM messages")
    fun deleteAllMessages(): Int

    /** Hands the conversation with [old] over to [new]. A message [new] already has stays where it is. */
    @Query("UPDATE OR IGNORE messages SET peer = :new WHERE peer = :old")
    fun moveMessages(old: String, new: String): Int

    /** What is waiting to be sent to [old] now goes to [new], and is encrypted afresh for them. */
    @Query("UPDATE outbox SET peer = :new, cipher = NULL, cipher_kind = NULL WHERE peer = :old")
    fun moveOutbox(old: String, new: String): Int

    /** Own messages the relay took for [peer] and never handed over, oldest first. */
    @Query("SELECT * FROM messages WHERE peer = :peer AND outgoing = 1 AND status = 1 AND kind IN (0, 2) AND at >= :since ORDER BY seq LIMIT 100")
    fun undelivered(peer: String, since: Long): List<MessageEntity>

    @Query("UPDATE messages SET status = :status WHERE seq = :seq")
    fun setStatus(seq: Long, status: Int): Int

    // ---- Contacts, as the messaging code needs them ----------------------------

    @Query("SELECT * FROM contacts WHERE id = :id")
    fun contact(id: String): ContactEntity?

    /** The contact with this username, or failing that one named like it whose username is not known yet. */
    @Query("SELECT * FROM contacts WHERE username = :username OR (username IS NULL AND LOWER(name) = :username) ORDER BY username IS NULL LIMIT 1")
    fun contactByUsername(username: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE username IS NULL ORDER BY added_at DESC LIMIT :limit")
    fun contactsWithoutUsername(limit: Int): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putContact(contact: ContactEntity)

    @Query("DELETE FROM outbox")
    fun deleteAllOutbox(): Int

    // ---- Reactions ----------------------------------------------------------

    /** What is on the message [id] of one conversation, from each side. */
    @Query(
        """
        SELECT r.* FROM reactions r
        JOIN messages m ON m.id = r.message_id
        WHERE m.peer = :peer AND r.message_id = :id
        """,
    )
    fun reactionsOf(peer: String, id: String): List<ReactionEntity>

    @Query(
        """
        SELECT r.* FROM reactions r
        JOIN messages m ON m.id = r.message_id
        WHERE m.peer = :peer
        """,
    )
    fun observeReactions(peer: String): Flow<List<ReactionEntity>>

    @Query("SELECT * FROM reactions WHERE message_id = :id AND mine = :mine")
    fun reaction(id: String, mine: Boolean): ReactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertReaction(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE message_id = :id AND mine = :mine")
    fun deleteReaction(id: String, mine: Boolean): Int

    @Query("DELETE FROM reactions WHERE message_id = :id")
    fun deleteReactions(id: String): Int

    @Query("DELETE FROM reactions WHERE message_id IN (SELECT id FROM messages WHERE peer = :peer)")
    fun deleteReactionsFor(peer: String): Int

    @Query("DELETE FROM reactions")
    fun deleteAllReactions(): Int

    /** The local order of the message with [id] from [peer], if this phone has it. */
    @Query("SELECT seq FROM messages WHERE peer = :peer AND id = :id LIMIT 1")
    fun seqOf(peer: String, id: String): Long?

    // ---- Outbox ------------------------------------------------------------

    @Insert
    fun insertOutbox(item: OutboxEntity): Long

    /** The oldest item after position [after], or null. */
    @Query("SELECT * FROM outbox WHERE seq > :after ORDER BY seq LIMIT 1")
    fun nextOutbox(after: Long): OutboxEntity?

    @Query("UPDATE outbox SET cipher_kind = :kind, cipher = :cipher WHERE seq = :seq")
    fun setCipher(seq: Long, kind: Int, cipher: ByteArray): Int

    @Query("DELETE FROM outbox WHERE seq = :seq")
    fun deleteOutbox(seq: Long): Int

    @Query("DELETE FROM outbox WHERE peer = :peer")
    fun deleteOutboxFor(peer: String): Int

    @Query("SELECT * FROM outbox WHERE peer = :peer ORDER BY seq")
    fun outboxFor(peer: String): List<OutboxEntity>

    /** Takes back a chat message that has not left yet. Receipts are not touched. */
    @Query("DELETE FROM outbox WHERE peer = :peer AND message_id = :messageId")
    fun deleteOutboxOf(peer: String, messageId: String): Int

    @Query("SELECT COUNT(*) FROM outbox")
    fun outboxSize(): Int

    // ---- People ------------------------------------------------------------

    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE id = :id)")
    fun isContact(id: String): Boolean

    @Query("DELETE FROM contacts WHERE id = :id")
    fun deleteContact(id: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM blocked WHERE id = :id)")
    fun isBlocked(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun block(entry: BlockedEntity)

    @Query("DELETE FROM blocked WHERE id = :id")
    fun unblock(id: String): Int

    @Query("SELECT * FROM blocked ORDER BY blocked_at DESC, id")
    fun observeBlocked(): Flow<List<BlockedEntity>>
}

/** Runs a block as one all-or-nothing database transaction. */
interface Transactor {
    fun <T> inTransaction(block: () -> T): T
}
