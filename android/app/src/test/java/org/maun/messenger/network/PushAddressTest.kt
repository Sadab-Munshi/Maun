package org.maun.messenger.network

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PushAddressTest {

    // The receiver's keys from the worked example in RFC 8291.
    private val key = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    private val auth = "BTBZMqHH6r4Tts7J_aSIgg"
    private val url = "https://ntfy.sh/upA1b2C3d4E5f6?up=1"

    @Test
    fun turnsWhatAPushAppGivesIntoWhatTheRelayExpects() {
        val address = PushAddress.fromWebPush(url, key, auth)
        assertNotNull(address)
        assertEquals(url, address!!.url)
        // Same bytes, in the standard alphabet with padding.
        assertArrayEquals(Base64.getUrlDecoder().decode(key), Base64.getDecoder().decode(address.key))
        assertArrayEquals(Base64.getUrlDecoder().decode(auth), Base64.getDecoder().decode(address.auth))
        assertEquals(65, Base64.getDecoder().decode(address.key).size)
        assertEquals(16, Base64.getDecoder().decode(address.auth).size)
    }

    @Test
    fun acceptsPaddedKeys() {
        assertEquals(PushAddress.fromWebPush(url, key, auth), PushAddress.fromWebPush(url, "$key=", "$auth=="))
    }

    @Test
    fun refusesWhatIsNotWebPush() {
        assertNull(PushAddress.fromWebPush("http://ntfy.sh/up1", key, auth))
        assertNull(PushAddress.fromWebPush("not an address", key, auth))
        assertNull(PushAddress.fromWebPush("", key, auth))
        assertNull(PushAddress.fromWebPush("https://ntfy.sh/" + "a".repeat(1100), key, auth))
        assertNull(PushAddress.fromWebPush(url, key.dropLast(4), auth))
        assertNull(PushAddress.fromWebPush(url, "A" + key.drop(1), auth))
        assertNull(PushAddress.fromWebPush(url, key, auth.dropLast(3)))
        assertNull(PushAddress.fromWebPush(url, "***", auth))
        assertNull(PushAddress.fromWebPush(url, key, ""))
    }

    @Test
    fun survivesStorage() {
        val address = PushAddress.fromWebPush(url, key, auth)!!
        assertEquals(address, PushAddress.parse(address.serialize()))
        assertNull(PushAddress.parse(null))
        assertNull(PushAddress.parse(""))
        assertNull(PushAddress.parse("{}"))
        assertNull(PushAddress.parse("nonsense"))
    }
}
