package org.maun.messenger.network

import kotlinx.coroutines.runBlocking
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

    /**
     * Talks to a real relay. Skipped unless MAUN_TEST_RELAY_URL is set, e.g. to
     * http://127.0.0.1:8787 while `npm run dev` is running in /backend.
     */
    @Test
    fun `echo round trip against a running relay`() = runBlocking {
        val relayUrl = System.getenv("MAUN_TEST_RELAY_URL")
        assumeTrue("MAUN_TEST_RELAY_URL not set", !relayUrl.isNullOrBlank())

        val millis = RelayClient().echo(relayUrl!!, "c".repeat(64), body = "नमस्ते from the JVM")
        assertTrue(millis >= 0)
        Unit
    }

    @Test
    fun `a bad mailbox id is reported as a relay error`() = runBlocking {
        val relayUrl = System.getenv("MAUN_TEST_RELAY_URL")
        assumeTrue("MAUN_TEST_RELAY_URL not set", !relayUrl.isNullOrBlank())

        val error = assertThrows(RelayException::class.java) {
            runBlocking { RelayClient().echo(relayUrl!!, "not-a-key", body = "x") }
        }
        assertTrue(error.message, error.message!!.contains("400"))
        Unit
    }

    @Test
    fun `an unreachable address is reported clearly`() {
        val error = assertThrows(RelayException::class.java) {
            runBlocking { RelayClient().echo("http://127.0.0.1:9", "c".repeat(64), body = "x") }
        }
        assertTrue(error.message, error.message!!.startsWith("Could not reach the relay"))
    }
}
