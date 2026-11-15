package org.maun.messenger.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The email login's client against a stand-in relay that answers whatever a test tells it to. */
class AuthApiTest {

    private lateinit var server: HttpServer
    private var status = 200
    private var answer = """{"ok":true}"""
    private var seenPath = ""
    private var seenBody = JSONObject()
    private var seenType: String? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seenPath = exchange.requestURI.path
            seenType = exchange.requestHeaders.getFirst("Content-Type")
            seenBody = JSONObject(String(exchange.requestBody.readBytes()))
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun api() = AuthApi("http://127.0.0.1:${server.address.port}/".toHttpUrl())
    private val ticket = "T".repeat(43)

    @Test
    fun `asking for a code posts the address as JSON`() = runBlocking {
        assertNull(api().start("asha@example.com"))
        assertEquals("/v1/auth/start", seenPath)
        assertEquals("asha@example.com", seenBody.getString("email"))
        assertTrue(seenType!!.startsWith("application/json"))
    }

    @Test
    fun `the relay's refusals are put in words`() = runBlocking {
        status = 429
        answer = """{"error":"wait"}"""
        assertTrue(api().start("asha@example.com")!!.contains("Wait a minute"))
        status = 502
        answer = """{"error":"mail_failed"}"""
        assertTrue(api().start("asha@example.com")!!.contains("could not send"))
        status = 429
        answer = "too many requests"
        assertTrue(api().start("asha@example.com")!!.contains("Too many tries"))
    }

    @Test
    fun `the right code gives a ticket, and says whether a username is still needed`() = runBlocking {
        answer = """{"ticket":"$ticket","username":null}"""
        assertEquals(AuthApi.Verified.Ok(ticket, null), api().verify("asha@example.com", "123456"))
        assertEquals("/v1/auth/verify", seenPath)
        assertEquals("123456", seenBody.getString("code"))

        answer = """{"ticket":"$ticket","username":"asha"}"""
        assertEquals(AuthApi.Verified.Ok(ticket, "asha"), api().verify("asha@example.com", "123456"))
    }

    @Test
    fun `a wrong code, or an answer without a ticket, is a problem`() = runBlocking {
        status = 401
        answer = """{"error":"wrong_code"}"""
        assertEquals(AuthApi.Verified.Problem("That code is not right."), api().verify("asha@example.com", "000000"))
        status = 200
        answer = """{"ticket":"<script>"}"""
        assertTrue(api().verify("asha@example.com", "000000") is AuthApi.Verified.Problem)
    }

    @Test
    fun `a username is put on the ticket, or the reason it could not be is given`() = runBlocking {
        assertNull(api().name(ticket, "asha"))
        assertEquals("/v1/auth/name", seenPath)
        assertEquals(ticket, seenBody.getString("ticket"))
        assertEquals("asha", seenBody.getString("username"))
        status = 409
        answer = """{"error":"taken"}"""
        assertEquals("Somebody already has that username.", api().name(ticket, "asha"))
    }

    @Test
    fun `a relay that cannot be reached is said so`() = runBlocking {
        server.stop(0)
        assertTrue(api().start("asha@example.com")!!.contains("Could not reach"))
    }
}
