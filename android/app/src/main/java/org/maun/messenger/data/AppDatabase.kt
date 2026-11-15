package org.maun.messenger.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Small key-value table for app state. Chats and keys get their own tables later. */
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
 * The on-device database: Room on top of SQLCipher, so every page on disk is
 * AES-256 encrypted with the key from [DatabaseKey].
 */
@Database(entities = [MetaEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun meta(): MetaDao

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
