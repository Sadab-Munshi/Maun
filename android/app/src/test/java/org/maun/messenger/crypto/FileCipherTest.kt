package org.maun.messenger.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class FileCipherTest {

    private val key = FileCipher.newKey()

    private fun bytes(size: Int, seed: Long = 7): ByteArray = ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun encrypt(plain: ByteArray, with: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        assertEquals(plain.size.toLong(), FileCipher.encrypt(ByteArrayInputStream(plain), out, with))
        return out.toByteArray()
    }

    private fun decrypt(sealed: ByteArray, with: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        FileCipher.decrypt(ByteArrayInputStream(sealed), out, with)
        return out.toByteArray()
    }

    private val sizes = listOf(
        0, 1, 100, FileCipher.CHUNK - 1, FileCipher.CHUNK, FileCipher.CHUNK + 1,
        2 * FileCipher.CHUNK, 3 * FileCipher.CHUNK + 12_345,
    )

    @Test
    fun `a file of any size comes back as it went in`() {
        for (size in sizes) {
            val plain = bytes(size)
            val sealed = encrypt(plain)
            assertEquals("size $size", FileCipher.encryptedSize(size.toLong()), sealed.size.toLong())
            assertArrayEquals("size $size", plain, decrypt(sealed))
        }
    }

    @Test
    fun `the encrypted form shows nothing of the file`() {
        val plain = ByteArray(4096) { 'A'.code.toByte() }
        val sealed = encrypt(plain)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("AAAA"))
        // The same file under another key looks different.
        assertFalse(sealed.contentEquals(encrypt(plain, FileCipher.newKey())))
    }

    @Test
    fun `it works when the source hands out a few bytes at a time`() {
        val plain = bytes(FileCipher.CHUNK + 5000)
        val trickle = object : InputStream() {
            private val source = ByteArrayInputStream(plain)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 777))
        }
        val out = ByteArrayOutputStream()
        FileCipher.encrypt(trickle, out, key)
        assertArrayEquals(plain, decrypt(out.toByteArray()))
    }

    @Test
    fun `it can be read as a stream, a byte or a block at a time`() {
        val plain = bytes(2 * FileCipher.CHUNK + 99)
        val stream = FileCipher.decrypting(ByteArrayInputStream(encrypt(plain)), key)
        val out = ByteArrayOutputStream()
        out.write(stream.read())
        val block = ByteArray(1000)
        while (true) {
            val read = stream.read(block, 0, block.size)
            if (read < 0) break
            out.write(block, 0, read)
        }
        assertEquals(-1, stream.read())
        assertArrayEquals(plain, out.toByteArray())
    }

    @Test
    fun `the wrong key opens nothing`() {
        val sealed = encrypt(bytes(5000))
        assertThrows(FileCipher.DamagedException::class.java) { decrypt(sealed, FileCipher.newKey()) }
    }

    @Test
    fun `a changed byte is noticed, wherever it is`() {
        val sealed = encrypt(bytes(2 * FileCipher.CHUNK + 500))
        for (position in listOf(0, 100, FileCipher.CHUNK + 20, sealed.size - 1)) {
            val changed = sealed.copyOf()
            changed[position] = (changed[position].toInt() xor 1).toByte()
            assertThrows("byte $position", FileCipher.DamagedException::class.java) { decrypt(changed) }
        }
    }

    @Test
    fun `a file cut short is noticed, even at a boundary between pieces`() {
        val sealed = encrypt(bytes(3 * FileCipher.CHUNK))
        val piece = FileCipher.CHUNK + 16
        for (length in listOf(0, 10, piece - 1, piece, 2 * piece, sealed.size - 1)) {
            assertThrows("cut to $length", FileCipher.DamagedException::class.java) { decrypt(sealed.copyOf(length)) }
        }
    }

    @Test
    fun `pieces cannot be swapped or repeated`() {
        val sealed = encrypt(bytes(3 * FileCipher.CHUNK))
        val piece = FileCipher.CHUNK + 16
        val first = sealed.copyOfRange(0, piece)
        val second = sealed.copyOfRange(piece, 2 * piece)
        val third = sealed.copyOfRange(2 * piece, 3 * piece)
        assertThrows(FileCipher.DamagedException::class.java) { decrypt(second + first + third) }
        assertThrows(FileCipher.DamagedException::class.java) { decrypt(first + first + third) }
        assertThrows(FileCipher.DamagedException::class.java) { decrypt(first + second + third + third) }
    }

    @Test
    fun `reading stops as soon as the file is over the limit`() {
        val plain = bytes(3 * FileCipher.CHUNK)
        assertThrows(FileCipher.TooLargeException::class.java) {
            FileCipher.encrypt(ByteArrayInputStream(plain), ByteArrayOutputStream(), key, maxBytes = 2L * FileCipher.CHUNK)
        }
        // Exactly the limit is fine.
        val out = ByteArrayOutputStream()
        assertEquals(plain.size.toLong(), FileCipher.encrypt(ByteArrayInputStream(plain), out, key, maxBytes = plain.size.toLong()))
    }

    @Test
    fun `the largest file fits under what the relay accepts`() {
        // 25 MB of file must stay under the relay's 25,000,000 + 65,536.
        assert(FileCipher.encryptedSize(25_000_000) <= 25_000_000L + 65_536L)
    }
}
