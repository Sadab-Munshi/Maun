package org.maun.messenger.push

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.data.AppDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ChatPushPartTest {
    // Construct the documented wire record independently of the Kotlin parser.
    private fun record(index: Int, body: ByteArray): ByteArray {
        val start = index * 2048
        return ByteBuffer.allocate(2128).apply {
            put(byteArrayOf(77, 67, 80, 1))
            put(ByteArray(32) { 1 })
            put(ByteArray(32) { 2 })
            putLong(123456789L)
            put(3.toByte())
            put(index.toByte())
            putShort(body.size.toShort())
            put(body, start, minOf(2048, body.size - start))
        }.array()
    }

    @Test
    fun reorderedAndRepeatedPartsSurviveReopeningTheDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, "parts-test.db")
            .allowMainThreadQueries().build()
        val body = ByteArray(8192) { (it * 73).toByte() }
        val parts = (0..3).map { ChatPushPart.parse(record(it, body))!! }
        assertEquals("01".repeat(32), parts.first().recipient)
        assertEquals("02".repeat(32), parts.first().sender)
        assertEquals(123456789L, parts.first().seq)
        var db = open()
        try {
            for (i in listOf(3, 1, 1)) assertNull(db.inTransaction { parts[i].assemble(db.chatParts()) })
            db.close()
            db = open()
            assertNull(db.inTransaction { parts[0].assemble(db.chatParts()) })
            assertArrayEquals(body, db.inTransaction { parts[2].assemble(db.chatParts()) })
            // A crash after assembly but before commit must also permit replay.
            assertArrayEquals(body, db.inTransaction { parts[2].assemble(db.chatParts()) })
            parts[2].saved(db.chatParts())
            assertEquals(0, db.chatParts().parts("chat-part/").size)
        } finally {
            db.close()
            context.deleteDatabase("parts-test.db")
        }
    }

    @Test
    fun rejectsInvalidHeadersAndStripsPaddingOnShortRecords() {
        val body = byteArrayOf(7, 8, 9)
        val raw = record(0, body)
        assertArrayEquals(body, ChatPushPart.parse(raw)!!.bytes)
        assertNull(ChatPushPart.parse(raw.copyOf(80)))
        assertNull(ChatPushPart.parse(raw.clone().apply { this[3] = 2 }))
        assertNull(ChatPushPart.parse(raw.clone().apply { this[76] = 4 }))
        assertNull(ChatPushPart.parse(raw.clone().apply { this[77] = 1 }))
        assertNull(ChatPushPart.parse(raw.clone().apply { this[78] = 33 }))
        assertNull(ChatPushPart.parse(raw.clone().apply { fill(0, 68, 76) }))
        assertNull(ChatPushPart.parse(raw.clone().apply { fill(1, 36, 68) }))
    }
}
