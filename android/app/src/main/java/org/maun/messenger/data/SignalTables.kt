package org.maun.messenger.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.maun.messenger.crypto.SignalStorage

/** The encryption session with one contact, as libsignal serializes it. */
@Entity(tableName = "signal_sessions")
class SessionEntity(
    @PrimaryKey val peer: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val record: ByteArray,
)

/** The private half of one of this phone's pre-keys. [kind] is a [SignalStorage] constant. */
@Entity(tableName = "signal_prekeys", primaryKeys = ["kind", "key_id"])
class PreKeyEntity(
    val kind: Int,
    @ColumnInfo(name = "key_id") val keyId: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val record: ByteArray,
)

/** One accepted session start, kept so that a replay of it is refused. */
@Entity(tableName = "signal_kyber_used", primaryKeys = ["kyber_id", "signed_id", "base_key"])
class KyberUseEntity(
    @ColumnInfo(name = "kyber_id") val kyberId: Int,
    @ColumnInfo(name = "signed_id") val signedId: Int,
    @ColumnInfo(name = "base_key") val baseKey: String,
)

@Dao
interface SignalDao {
    @Query("SELECT record FROM signal_sessions WHERE peer = :peer")
    fun session(peer: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSession(session: SessionEntity)

    @Query("DELETE FROM signal_sessions WHERE peer = :peer")
    fun deleteSession(peer: String)

    @Query("SELECT record FROM signal_prekeys WHERE kind = :kind AND key_id = :id")
    fun preKey(kind: Int, id: Int): ByteArray?

    @Query("SELECT record FROM signal_prekeys WHERE kind = :kind ORDER BY key_id")
    fun preKeys(kind: Int): List<ByteArray>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putPreKey(key: PreKeyEntity)

    @Query("DELETE FROM signal_prekeys WHERE kind = :kind AND key_id = :id")
    fun deletePreKey(kind: Int, id: Int)

    @Query("SELECT COALESCE(MAX(key_id), 0) FROM signal_prekeys WHERE kind = :kind")
    fun highestPreKeyId(kind: Int): Int

    /** Returns -1 when this use was already recorded. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertKyberUse(use: KyberUseEntity): Long

    @Query("DELETE FROM signal_sessions")
    fun clearSessions()

    @Query("DELETE FROM signal_prekeys")
    fun clearPreKeys()

    @Query("DELETE FROM signal_kyber_used")
    fun clearKyberUses()
}

/** [SignalStorage] on top of the encrypted database. */
class RoomSignalStorage(private val dao: SignalDao) : SignalStorage {
    override fun loadSession(peer: String): ByteArray? = dao.session(peer)

    override fun storeSession(peer: String, record: ByteArray) = dao.putSession(SessionEntity(peer, record))

    override fun deleteSession(peer: String) = dao.deleteSession(peer)

    override fun loadPreKey(kind: Int, id: Int): ByteArray? = dao.preKey(kind, id)

    override fun loadPreKeys(kind: Int): List<ByteArray> = dao.preKeys(kind)

    override fun storePreKey(kind: Int, id: Int, record: ByteArray) = dao.putPreKey(PreKeyEntity(kind, id, record))

    override fun removePreKey(kind: Int, id: Int) = dao.deletePreKey(kind, id)

    override fun highestPreKeyId(kind: Int): Int = dao.highestPreKeyId(kind)

    override fun recordKyberUse(kyberId: Int, signedId: Int, baseKey: String): Boolean =
        dao.insertKyberUse(KyberUseEntity(kyberId, signedId, baseKey)) != -1L
}
