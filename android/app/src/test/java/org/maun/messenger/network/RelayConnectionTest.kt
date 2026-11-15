package org.maun.messenger.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.maun.messenger.crypto.Identity
import org.maun.messenger.crypto.SoftwareDeviceSigner

/**
 * Runs the app's real connection code against a real relay: libsignal signs
 * with the identity key and the relay's WebCrypto checks it. Skipped unless
 * MAUN_TEST_RELAY_URL is set, e.g. to http://127.0.0.1:8787 while
 * `npm run dev` is running in /backend. CI starts a relay for these tests.
 */
class RelayConnectionTest {

    private lateinit var relayUrl: HttpUrl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A phone as the connection sees it: an identity, a device key, and a "registered" flag. */
    private inner class Phone(val identity: Identity = Identity.generate()) {
        val device = SoftwareDeviceSigner()
        var registered = false
        var logins = 0

        fun connection() = RelayConnection(
            relayUrl = relayUrl,
            identity = identity,
            device = device,
            scope = scope,
            isRegistered = { registered },
            onLoggedIn = {
                registered = true
                logins++
            },
            backoff = Backoff(baseMillis = 50, maxMillis = 200),
        )
    }

    private fun RelayConnection.settle(): RelayState = runBlocking {
        withTimeout(15_000) { state.first { it is RelayState.Online || it is RelayState.Rejected } }
    }

    @Before
    fun requireRelay() {
        val configured = System.getenv("MAUN_TEST_RELAY_URL")
        assumeTrue("MAUN_TEST_RELAY_URL not set", !configured.isNullOrBlank())
        relayUrl = configured!!.toHttpUrl()
    }

    @After
    fun stopEverything() {
        scope.cancel()
    }

    @Test
    fun `a new phone registers, then logs in on the next connection`() {
        val phone = Phone()
        val first = phone.connection()
        assertEquals(RelayState.Offline, first.state.value)

        first.start()
        assertEquals(RelayState.Online, first.settle())
        assertTrue(phone.registered)
        assertEquals("the device key is created once, against the relay's nonce", 1, phone.device.challenges.size)
        assertEquals(32, phone.device.challenges.single().size)
        assertTrue(runBlocking { first.echo() } >= 0)
        first.stop()
        assertEquals(RelayState.Offline, first.state.value)

        val keyBefore = phone.device.publicKeySpki()
        val second = phone.connection()
        second.start()
        assertEquals(RelayState.Online, second.settle())
        assertEquals(2, phone.logins)
        assertArrayEquals("logging in must not replace the device key", keyBefore, phone.device.publicKeySpki())
        assertTrue(runBlocking { second.echo() } >= 0)
        second.stop()
    }

    @Test
    fun `a different phone cannot take over a registered ID`() {
        val owner = Phone()
        val ownerConnection = owner.connection()
        ownerConnection.start()
        assertEquals(RelayState.Online, ownerConnection.settle())

        // Someone who copied the identity key, but not the device key.
        val thief = Phone(identity = Identity.restore(owner.identity.serialize()))
        val thiefConnection = thief.connection()
        thiefConnection.start()
        val outcome = thiefConnection.settle()
        assertTrue("expected a refusal, got $outcome", outcome is RelayState.Rejected)
        assertFalse(thief.registered)
        assertFalse("the thief must not have created a key", thief.device.exists())

        // The owner's connection was not disturbed.
        assertEquals(RelayState.Online, ownerConnection.state.value)
        assertTrue(runBlocking { ownerConnection.echo() } >= 0)
        ownerConnection.stop()
    }

    @Test
    fun `a stolen identity key with some other device key is refused`() {
        val owner = Phone()
        val ownerConnection = owner.connection()
        ownerConnection.start()
        assertEquals(RelayState.Online, ownerConnection.settle())
        ownerConnection.stop()

        val thief = Phone(identity = Identity.restore(owner.identity.serialize()))
        thief.device.create(ByteArray(32))
        val thiefConnection = thief.connection()
        thiefConnection.start()
        val outcome = thiefConnection.settle()
        assertTrue("expected a refusal, got $outcome", outcome is RelayState.Rejected)
        assertEquals("The relay did not accept this phone's login key.", (outcome as RelayState.Rejected).reason)
    }

    @Test
    fun `a phone that was registered does not silently register again`() {
        val phone = Phone()
        phone.registered = true // This phone believes it has an account; the relay has none.
        val connection = phone.connection()
        connection.start()
        val outcome = connection.settle()
        assertTrue("expected a refusal, got $outcome", outcome is RelayState.Rejected)
        assertFalse("the device key must not be created or replaced", phone.device.exists())
    }

    @Test
    fun `echo needs a live connection`() {
        val connection = Phone().connection()
        val error = assertThrows(RelayException::class.java) { runBlocking { connection.echo() } }
        assertEquals("Not connected to the relay", error.message)
    }

    @Test
    fun `an unreachable relay is retried, not given up on`() {
        relayUrl = "http://127.0.0.1:9/".toHttpUrl()
        val connection = Phone().connection()
        connection.start()
        val state = runBlocking { withTimeout(15_000) { connection.state.first { it is RelayState.Retrying } } }
        assertTrue((state as RelayState.Retrying).reason.startsWith("Could not reach the relay"))
        // Still trying a moment later: it has not given up or stopped.
        Thread.sleep(600)
        val later = connection.state.value
        assertTrue("expected more attempts, got $later", later is RelayState.Retrying || later is RelayState.Connecting)
        connection.stop()
        assertEquals(RelayState.Offline, connection.state.value)
    }
}
