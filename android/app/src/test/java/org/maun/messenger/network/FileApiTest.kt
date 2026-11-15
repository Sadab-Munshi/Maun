package org.maun.messenger.network

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The file routes' client against a stand-in relay that answers whatever a test tells it to. */
class FileApiTest {

    private lateinit var server: HttpServer
    private lateinit var folder: File

    private var status = 201
    private var answer = """{"key":"${"ab".repeat(32)}","size":5}"""
    private var seenAuthorization: String? = null
    private var seenBody = ByteArray(0)
    private val bans = AtomicInteger()

    @Before
    fun start() {
        folder = File.createTempFile("maun", "files").apply {
            delete()
            mkdirs()
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/files") { exchange ->
            seenAuthorization = exchange.requestHeaders.getFirst("Authorization")
            seenBody = exchange.requestBody.readBytes()
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        folder.deleteRecursively()
    }

    private val self = "9f".repeat(32)

    private fun api(token: String? = "tok-en_123") = FileApi(
        relayUrl = "http://127.0.0.1:${server.address.port}/".toHttpUrl(),
        selfId = self,
        token = { token },
        onBanned = { bans.incrementAndGet() },
    )

    private fun file(content: String): File = File(folder, "up.enc").apply { writeText(content) }

    @Test
    fun `an upload carries the account's credential and the file, and returns the relay's name for it`() {
        var last = 0L to 0L
        val key = api().upload(file("hello")) { done, total -> last = done to total }
        assertEquals("ab".repeat(32), key)
        assertEquals("Bearer $self.tok-en_123", seenAuthorization)
        assertEquals("hello", String(seenBody))
        assertEquals(5L to 5L, last)
        assertEquals(0, bans.get())
    }

    @Test
    fun `nothing is sent as a credential before the first login`() {
        status = 401
        answer = """{"error":"denied"}"""
        val error = assertThrows(FileException::class.java) { api(token = null).upload(file("x")) { _, _ -> } }
        assertEquals(null, seenAuthorization)
        assertTrue(error.message!!.contains("Not connected"))
    }

    @Test
    fun `a download lands in the file it was given`() {
        status = 200
        answer = "encrypted bytes"
        val target = File(folder, "down.part")
        api().download("cd".repeat(32), target) { _, _ -> }
        assertEquals("encrypted bytes", target.readText())
        assertEquals("Bearer $self.tok-en_123", seenAuthorization)
    }

    @Test
    fun `a banned answer is reported at once, on uploads and downloads alike`() {
        status = 403
        answer = """{"error":"banned"}"""
        assertThrows(FileException::class.java) { api().upload(file("x")) { _, _ -> } }
        assertEquals(1, bans.get())
        assertThrows(FileException::class.java) { api().download("cd".repeat(32), File(folder, "d")) { _, _ -> } }
        assertEquals(2, bans.get())
    }

    @Test
    fun `other refusals are not mistaken for a ban`() {
        for ((code, body) in listOf(403 to """{"error":"other"}""", 403 to "Forbidden", 401 to """{"error":"banned"}""", 500 to "")) {
            status = code
            answer = body
            assertThrows(FileException::class.java) { api().upload(file("x")) { _, _ -> } }
        }
        assertEquals(0, bans.get())
    }

    @Test
    fun `a file the relay no longer has is told apart from a failure`() {
        status = 404
        answer = """{"error":"gone"}"""
        val gone = assertThrows(FileException::class.java) { api().download("cd".repeat(32), File(folder, "d")) { _, _ -> } }
        assertTrue(gone.gone)
        status = 500
        val failed = assertThrows(FileException::class.java) { api().download("cd".repeat(32), File(folder, "d")) { _, _ -> } }
        assertFalse(failed.gone)
    }

    @Test
    fun `the relay's limits are explained in words`() {
        status = 413
        answer = """{"error":"too_large"}"""
        assertTrue(assertThrows(FileException::class.java) { api().upload(file("x")) { _, _ -> } }.message!!.contains("25 MB"))
        status = 429
        answer = """{"error":"quota"}"""
        assertTrue(assertThrows(FileException::class.java) { api().upload(file("x")) { _, _ -> } }.message!!.contains("allowance"))
    }

    @Test
    fun `an answer that names no file is not taken for one`() {
        answer = """{"key":"../../etc/passwd"}"""
        assertThrows(FileException::class.java) { api().upload(file("x")) { _, _ -> } }
        answer = "not json"
        assertThrows(FileException::class.java) { api().upload(file("x")) { _, _ -> } }
    }
}
