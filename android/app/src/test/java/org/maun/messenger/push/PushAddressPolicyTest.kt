package org.maun.messenger.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a look at what wakes this phone is allowed to do to the address the
 * relay holds.
 *
 * The address is the only way a closed phone hears about a message or a call.
 * A look that never answered — the push app not running yet after a reboot,
 * or it throwing while it starts — says nothing about whether the address
 * still works, and must not take it away. Only an answer that there is
 * nothing on this phone to wake it, or a push app saying the address is gone,
 * may.
 */
class PushAddressPolicyTest {

    @Test
    fun `a look that failed keeps the address that works`() {
        assertFalse("an answer that never came took the address away", forgetAddressOn(PushLookup.Trouble))
    }

    @Test
    fun `a phone with no push app loses its address`() {
        assertTrue(forgetAddressOn(PushLookup.NoPushApp))
    }

    @Test
    fun `a push app that answered keeps the address`() {
        assertFalse(forgetAddressOn(PushLookup.Ready))
    }
}
