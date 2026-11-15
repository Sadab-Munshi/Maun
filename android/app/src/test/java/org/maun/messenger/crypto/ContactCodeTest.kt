package org.maun.messenger.crypto

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactCodeTest {

    private val id = "0123456789abcdef".repeat(4)

    @Test
    fun `a code survives being written and read`() {
        val code = ContactCode(id, "maun.example.workers.dev")
        assertEquals("maun:c1:$id:maun.example.workers.dev", code.encode())
        assertEquals(code, ContactCode.parse(code.encode()))
    }

    @Test
    fun `reading tolerates spaces, capitals and a port`() {
        assertEquals(
            ContactCode(id, "relay.example.dev"),
            ContactCode.parse("  MAUN:C1:${id.uppercase()}:Relay.Example.dev\n"),
        )
        assertEquals(ContactCode(id, "127.0.0.1:8787"), ContactCode.parse("maun:c1:$id:127.0.0.1:8787"))
        assertEquals(ContactCode(id, "[::1]:8787"), ContactCode.parse("maun:c1:$id:[::1]:8787"))
    }

    @Test
    fun `anything else is not a code`() {
        val bad = listOf(
            "",
            "https://example.com",
            id,
            "maun:c1:$id",
            "maun:c1:$id:",
            "maun:c2:$id:relay.example.dev",
            "maun:c1:${id.dropLast(1)}:relay.example.dev",
            "maun:c1:${id}0:relay.example.dev",
            "maun:c1:${"g".repeat(64)}:relay.example.dev",
            "maun:c1:$id:relay.example.dev/path",
            "maun:c1:$id:relay example",
            "maun:c1:$id:${"a".repeat(300)}",
        )
        for (text in bad) assertNull(text, ContactCode.parse(text))
    }

    @Test
    fun `typed text may be a bare ID, with or without the display spacing`() {
        val own = "maun.example.workers.dev"
        assertEquals(ContactCode(id, own), ContactCode.parseTyped(id, own))
        assertEquals(ContactCode(id, own), ContactCode.parseTyped(id.chunked(4).joinToString(" "), own))
        assertEquals(ContactCode(id, own), ContactCode.parseTyped(id.uppercase().chunked(16).joinToString("\n"), own))
        assertEquals(ContactCode(id, "other.dev"), ContactCode.parseTyped("maun:c1:$id:other.dev", own))
        assertNull(ContactCode.parseTyped("hello", own))
        assertNull(ContactCode.parseTyped(id.dropLast(2), own))
    }

    @Test
    fun `a relay is named by host, and by port only when unusual`() {
        fun name(url: String) = ContactCode.relayName(url.toHttpUrl())
        assertEquals("maun.example.workers.dev", name("https://maun.example.workers.dev/"))
        assertEquals("maun.example.workers.dev", name("https://MAUN.example.workers.dev:443/"))
        assertEquals("127.0.0.1:8787", name("http://127.0.0.1:8787/"))
        assertEquals("relay.example.dev:8443", name("https://relay.example.dev:8443/"))
        assertEquals("[::1]:8787", name("http://[::1]:8787/"))
    }

    @Test
    fun `the name of a relay round-trips through a code`() {
        for (url in listOf("https://maun.example.workers.dev/", "http://127.0.0.1:8787/", "http://[::1]:8787/")) {
            val code = ContactCode(id, ContactCode.relayName(url.toHttpUrl()))
            assertEquals(code, ContactCode.parse(code.encode()))
        }
    }
}
