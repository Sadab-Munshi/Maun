package org.maun.messenger.crypto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a file with a key made for that one file.
 *
 * The file is cut into pieces of [CHUNK] bytes and each piece is sealed with
 * AES-256-GCM on its own, so a file of any size passes through a small, fixed
 * amount of memory and can be read from the start before all of it is there.
 * A piece's position and whether it is the last one are part of what is
 * sealed: pieces cannot be reordered, dropped or cut off the end unnoticed.
 *
 * The key is used for one file only, which is what makes counting the pieces
 * a safe way to tell them apart.
 */
object FileCipher {
    const val KEY_BYTES = 32
    const val CHUNK = 64 * 1024
    private const val TAG_BYTES = 16
    private const val NONCE_BYTES = 12

    /** The file itself was altered, cut short, or the key is not its key. */
    class DamagedException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    /** How long the encrypted form of [plainBytes] bytes is. */
    fun encryptedSize(plainBytes: Long): Long {
        require(plainBytes >= 0)
        val chunks = if (plainBytes == 0L) 1 else (plainBytes + CHUNK - 1) / CHUNK
        return plainBytes + chunks * TAG_BYTES
    }

    /**
     * Reads [input] to its end and writes the encrypted form to [output].
     * Returns how many bytes were read. Stops with [TooLargeException] as
     * soon as more than [maxBytes] have been.
     */
    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray, maxBytes: Long = Long.MAX_VALUE): Long {
        require(key.size == KEY_BYTES)
        val secret = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var buffer = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        var have = readFully(input, buffer)
        var total = 0L
        var index = 0L
        while (true) {
            total += have
            if (total > maxBytes) throw TooLargeException()
            // The piece in hand is the last one when nothing follows it.
            val following = if (have == CHUNK) readFully(input, next) else 0
            val last = following == 0
            cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BYTES * 8, nonce(index, last)))
            output.write(cipher.doFinal(buffer, 0, have))
            if (last) return total
            val swap = buffer
            buffer = next
            next = swap
            have = following
            index++
        }
    }

    class TooLargeException : IOException("file too large")

    /** Reads the encrypted [input] to its end and writes the file to [output]. Returns its size. */
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray): Long {
        val plain = decrypting(input, key)
        val buffer = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val read = plain.read(buffer)
            if (read < 0) return total
            output.write(buffer, 0, read)
            total += read
        }
    }

    /** The file inside the encrypted [input], as a stream. Closing it closes [input]. */
    fun decrypting(input: InputStream, key: ByteArray): InputStream {
        require(key.size == KEY_BYTES)
        return Decrypting(input, SecretKeySpec(key, "AES"))
    }

    private class Decrypting(private val source: InputStream, private val secret: SecretKeySpec) : InputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var sealed = ByteArray(CHUNK + TAG_BYTES)
        private var next = ByteArray(CHUNK + TAG_BYTES)
        private var have = readFully(source, sealed)
        private var index = 0L
        private var plain = ByteArray(0)
        private var position = 0
        private var finished = false

        private fun fill(): Boolean {
            if (finished) return false
            if (have < TAG_BYTES) throw DamagedException("The file is incomplete.")
            val following = if (have == sealed.size) readFully(source, next) else 0
            val last = following == 0
            plain = try {
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BYTES * 8, nonce(index, last)))
                cipher.doFinal(sealed, 0, have)
            } catch (e: GeneralSecurityException) {
                throw DamagedException("The file is damaged or incomplete.", e)
            }
            position = 0
            if (last) {
                finished = true
            } else {
                val swap = sealed
                sealed = next
                next = swap
                have = following
                index++
            }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position >= plain.size) if (!fill()) return -1
            val count = minOf(length, plain.size - position)
            plain.copyInto(target, offset, position, position + count)
            position += count
            return count
        }

        override fun close() = source.close()
    }

    /** Flag for the last piece, then the piece's number. */
    private fun nonce(index: Long, last: Boolean): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        nonce[0] = if (last) 1 else 0
        for (i in 0 until 8) nonce[NONCE_BYTES - 1 - i] = (index ushr (8 * i)).toByte()
        return nonce
    }

    /** Fills [buffer] unless the stream ends first. Returns how many bytes were read. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = try {
                input.read(buffer, total, buffer.size - total)
            } catch (e: EOFException) {
                -1
            }
            if (read < 0) break
            total += read
        }
        return total
    }
}
