package org.maun.messenger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Signing out stops this phone from connecting, and nothing talks it round:
 * not the app being on screen, not a fetch, not a call still ringing.
 */
class SignOutTest {

    @Test
    fun aSignedOutPhoneDoesNotConnect() {
        assertFalse(Messenger.wantsConnection(banned = false, signedOut = true, inForeground = true, fetches = 0, stayingConnected = false, callBusy = false))
        assertFalse(Messenger.wantsConnection(banned = false, signedOut = true, inForeground = true, fetches = 3, stayingConnected = true, callBusy = true))
        // Being on screen is what normally keeps the connection open.
        assertFalse(Messenger.wantsConnection(banned = true, signedOut = false, inForeground = true, fetches = 0, stayingConnected = false, callBusy = false))
    }

    @Test
    fun aSignedInPhoneConnectsForTheReasonsItAlwaysDid() {
        assertTrue(Messenger.wantsConnection(banned = false, signedOut = false, inForeground = true, fetches = 0, stayingConnected = false, callBusy = false))
        // A wake-up while the app is closed.
        assertTrue(Messenger.wantsConnection(banned = false, signedOut = false, inForeground = false, fetches = 1, stayingConnected = false, callBusy = false))
        assertTrue(Messenger.wantsConnection(banned = false, signedOut = false, inForeground = false, fetches = 0, stayingConnected = true, callBusy = false))
        // A call still ringing is not hung up on.
        assertTrue(Messenger.wantsConnection(banned = false, signedOut = false, inForeground = false, fetches = 0, stayingConnected = false, callBusy = true))
        // Closed, not fetching, no call: nothing to hold.
        assertFalse(Messenger.wantsConnection(banned = false, signedOut = false, inForeground = false, fetches = 0, stayingConnected = false, callBusy = false))
    }
}
