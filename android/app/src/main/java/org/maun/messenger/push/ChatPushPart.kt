package org.maun.messenger.push

import java.nio.ByteBuffer
import java.util.Base64
import org.maun.messenger.crypto.toHex
import org.maun.messenger.data.ChatPartDao
import org.maun.messenger.data.MetaEntry

/** One Web Push record. All integers are big-endian, as in protocol.ts. */
internal class ChatPushPart private constructor(
    val recipient: String,
    val sender: String,
    val seq: Long,
    val kind: Int,
    val index: Int,
    val length: Int,
    val bytes: ByteArray,
) {
    private val prefix get() = "chat-part/$recipient/$seq/$sender/$kind/$length/"
    val count get() = (length + PART_BYTES - 1) / PART_BYTES

    /** Caller owns a Room transaction. Partial ciphertext survives an ordinary process death. */
    fun assemble(meta: ChatPartDao): ByteArray? {
        if (count == 1) return bytes
        meta.putPart(MetaEntry(prefix + index, Base64.getEncoder().encodeToString(bytes)))
        meta.trimParts()
        val parts = meta.parts(prefix)
        if (parts.size != count) return null
        val body = ByteArray(length)
        for ((i, part) in parts.withIndex()) {
            if (part.key != prefix + i) return null
            Base64.getDecoder().decode(part.value).copyInto(body, i * PART_BYTES)
        }
        return body
    }

    fun saved(meta: ChatPartDao) {
        if (count > 1) meta.removeParts(prefix)
    }

    companion object {
        const val PART_BYTES = 2048
        const val HEADER_BYTES = 80
        const val MAX_BODY = 8192
        fun parse(raw: ByteArray): ChatPushPart? {
            if (raw.size != HEADER_BYTES + PART_BYTES) return null
            val input = ByteBuffer.wrap(raw)
            if (input.int != 0x4d435001) return null
            val recipient = ByteArray(32).also { input.get(it) }.toHex()
            val sender = ByteArray(32).also { input.get(it) }.toHex()
            val seq = input.long
            val kind = input.get().toInt()
            val index = input.get().toInt()
            val length = input.short.toInt() and 0xffff
            if (seq <= 0 || sender == recipient || kind !in 2..3 || length !in 1..MAX_BODY ||
                index !in 0..((length - 1) / PART_BYTES)
            ) return null
            val bytes = ByteArray(minOf(PART_BYTES, length - index * PART_BYTES)).also { input.get(it) }
            return ChatPushPart(recipient, sender, seq, kind, index, length, bytes)
        }
    }
}
