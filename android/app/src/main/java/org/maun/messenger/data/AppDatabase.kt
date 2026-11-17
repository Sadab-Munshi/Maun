package org.maun.messenger.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.Callable
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Small key-value table for app state. */
@Entity(tableName = "meta")
data class MetaEntry(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: MetaEntry)
}

/**
 * This phone's own account. There is only ever one row.
 *
 * [identityKeyPair] holds the private identity key; it exists nowhere else.
 */
@Entity(tableName = "account")
class AccountEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    @ColumnInfo(name = "relay_url") val relayUrl: String,
    @ColumnInfo(name = "identity_key_pair", typeAffinity = ColumnInfo.BLOB) val identityKeyPair: ByteArray,
    /** True once the relay has accepted the registration. */
    val registered: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val SINGLE_ROW = 0
    }
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE id = 0")
    suspend fun get(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(account: AccountEntity)

    @Query("UPDATE account SET registered = 1 WHERE id = 0")
    suspend fun markRegistered()
}

/** Someone the user added by scanning or pasting their code. */
@Entity(tableName = "contacts")
data class ContactEntity(
    /** Their ID: the identity public key in lower-case hex. */
    @PrimaryKey val id: String,
    val name: String,
    /** True when the ID was read from their screen with the camera. */
    val verified: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /**
     * Their username on a relay that has usernames. It is what stays the same
     * when they set Maun up again and get a new ID, so it is how they are
     * found again. Null where it is not known.
     */
    val username: String? = null,
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun get(id: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun delete(id: String)
}

/**
 * The on-device database: Room on top of SQLCipher, so every page on disk is
 * AES-256 encrypted with the key from [DatabaseKey].
 */
@Database(
    entities = [
        MetaEntry::class,
        AccountEntity::class,
        ContactEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        BlockedEntity::class,
        ReactionEntity::class,
        SessionEntity::class,
        PreKeyEntity::class,
        KyberUseEntity::class,
    ],
    version = 7,
    exportSchema = true,
    // Room writes these migrations itself from the committed schema files, so
    // they cannot disagree with them.
    autoMigrations = [
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class AppDatabase : RoomDatabase(), Transactor {

    abstract fun meta(): MetaDao

    abstract fun account(): AccountDao

    abstract fun contacts(): ContactDao

    abstract fun chat(): ChatDao

    abstract fun signal(): SignalDao

    override fun <T> inTransaction(block: () -> T): T = runInTransaction(Callable { block() })

    companion object {
        const val FILE_NAME = "maun.db"

        fun open(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val appContext = context.applicationContext
            val loaded = DatabaseKey.loadOrCreate(appContext)
            if (loaded.created) {
                // A database left over from before this key existed cannot be read.
                appContext.deleteDatabase(FILE_NAME)
            }
            return Room.databaseBuilder(appContext, AppDatabase::class.java, FILE_NAME)
                .openHelperFactory(SupportOpenHelperFactory(rawKeySpec(loaded.key)))
                // Write-ahead logging lets background sync write while the UI reads.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Version 1 was the setup-check build and held nothing worth
                // keeping. From version 2 on the database holds the identity
                // key, so every later change needs a real migration. There is
                // deliberately no destructive fallback for those.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                .build()
        }

        /**
         * SQLCipher's "raw key" form, x'<64 hex digits>'. The key is already
         * random, so this skips the slow password-stretching step on every open.
         */
        private fun rawKeySpec(key: ByteArray): ByteArray {
            require(key.size == 32) { "database key must be 32 bytes" }
            val hex = key.joinToString("") { "%02x".format(it) }
            return "x'$hex'".toByteArray(Charsets.US_ASCII)
        }
    }
}
