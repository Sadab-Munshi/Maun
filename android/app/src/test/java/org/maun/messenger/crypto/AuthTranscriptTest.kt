package org.maun.messenger.crypto

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTranscriptTest {

    private val identity = ByteArray(32) { 0x01 }
    private val nonce = ByteArray(32) { 0x02 }
    private val deviceKey = ByteArray(91) { 0x03 }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /**
     * The same inputs and digests are pinned in backend/test/protocol.test.ts. If
     * either side changes the layout, one of the two tests fails.
     */
    @Test
    fun `layout matches the relay`() {
        assertEquals(REGISTER_DIGEST, sha256Hex(AuthTranscript.register(identity, nonce, deviceKey)))
        assertEquals(LOGIN_DIGEST, sha256Hex(AuthTranscript.login(identity, nonce)))
    }

    @Test
    fun `register transcript is label then identity then nonce then device key`() {
        val label = "maun:register:v1\n".toByteArray()
        assertArrayEquals(label + identity + nonce + deviceKey, AuthTranscript.register(identity, nonce, deviceKey))
        assertArrayEquals("maun:login:v1\n".toByteArray() + identity + nonce, AuthTranscript.login(identity, nonce))
    }

    @Test
    fun `a login transcript can never equal a register transcript`() {
        val login = AuthTranscript.login(identity, nonce)
        val register = AuthTranscript.register(identity, nonce, deviceKey)
        assertFalse(register.copyOf(login.size).contentEquals(login))
    }

    @Test
    fun `wrong sizes are refused`() {
        assertThrows(IllegalArgumentException::class.java) { AuthTranscript.login(ByteArray(31), nonce) }
        assertThrows(IllegalArgumentException::class.java) { AuthTranscript.login(identity, ByteArray(33)) }
        assertThrows(IllegalArgumentException::class.java) {
            AuthTranscript.register(identity, ByteArray(0), deviceKey)
        }
    }

    @Test
    fun `identity signs and restores`() {
        val identity = Identity.generate()
        assertEquals(64, identity.id.length)
        assertTrue(identity.id.matches(Regex("[0-9a-f]{64}")))

        val message = "hello".toByteArray()
        val signature = identity.sign(message)
        assertEquals(64, signature.size)
        assertTrue(identity.keyPair.publicKey.publicKey.verifySignature(message, signature))

        val restored = Identity.restore(identity.serialize())
        assertEquals(identity.id, restored.id)
        assertTrue(identity.keyPair.publicKey.publicKey.verifySignature(message, restored.sign(message)))
    }

    private companion object {
        const val REGISTER_DIGEST = "5ce8e4a95844ed9fe43cf0148e371125e1516c6b610b75c94f900abbf3d2145f"
        const val LOGIN_DIGEST = "d4ac6228d3fb0b55b4d37dfbcb508eeaede2eb95621b518af1fbf07cc7243b44"
    }
}
