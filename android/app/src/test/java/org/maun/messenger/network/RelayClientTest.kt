package org.maun.messenger.network

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RelayClientTest {

    @Test
    fun `relay address typed by a person is cleaned up`() {
        fun normal(input: String) = RelayClient.normalizeRelayUrl(input)?.toString()

        assertEquals("https://relay.example.dev/", normal("relay.example.dev"))
        assertEquals("https://relay.example.dev/", normal("  https://relay.example.dev/  "))
        assertEquals("https://relay.example.dev/", normal("https://relay.example.dev/health?x=1#top"))
        assertEquals("https://relay.example.dev/", normal("HTTPS://Relay.Example.dev"))
        assertEquals("http://127.0.0.1:8787/", normal("http://127.0.0.1:8787"))
        assertNull(normal(""))
        assertNull(normal("   "))
        assertNull(normal("ftp://relay.example.dev"))
        assertNull(normal("https://"))
    }

    @Test
    fun `the socket address is built from the relay and the ID`() {
        val id = "ab".repeat(32)
        assertEquals(
            "https://relay.example.dev/v1/ws/$id",
            RelayClient.socketUrl("https://relay.example.dev/".toHttpUrl(), id).toString(),
        )
    }

    @Test
    fun `a protocol mismatch says which side to update`() {
        assertNull(RelayClient.protocolProblem(RelayClient.PROTOCOL_VERSION))
        assertTrue(RelayClient.protocolProblem(RelayClient.PROTOCOL_VERSION - 1)!!.contains("Update the relay"))
        assertTrue(RelayClient.protocolProblem(RelayClient.PROTOCOL_VERSION + 1)!!.contains("Update the app"))
    }

    @Test
    fun `an unreachable address is reported clearly`() {
        val error = assertThrows(RelayException::class.java) {
            runBlocking { RelayClient().checkHealth("http://127.0.0.1:9/".toHttpUrl()) }
        }
        assertTrue(error.message, error.message!!.startsWith("Could not reach the relay"))
    }

    /**
     * Talks to a real relay. Skipped unless MAUN_TEST_RELAY_URL is set, e.g. to
     * http://127.0.0.1:8787 while `npm run dev` is running in /backend.
     */
    @Test
    fun `a running relay passes the health check`() = runBlocking {
        val relayUrl = System.getenv("MAUN_TEST_RELAY_URL")
        assumeTrue("MAUN_TEST_RELAY_URL not set", !relayUrl.isNullOrBlank())
        RelayClient().checkHealth(relayUrl!!.toHttpUrl())
    }
}
