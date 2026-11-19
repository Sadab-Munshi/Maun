package org.maun.messenger.push

import org.maun.messenger.MaunApp
import org.maun.messenger.Messenger
import org.maun.messenger.webrtc.RingService
import org.maun.messenger.network.PushAddress
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Hears from what wakes this phone: Google's push service, or a push app
 * (UnifiedPush; Sunup is such an app).
 *
 * It hands Maun an address at which wake-ups for this phone can be left, and
 * keys to encrypt them with. Maun gives both to the relay. When the relay has
 * a message for this phone and the phone is not connected, it leaves a wake-up
 * there; the push service passes it on, and Maun connects and fetches.
 */
/**
 * Where the answers from what wakes this phone go. The app takes them; a test
 * can take them instead, so that what Maun does with an answer can be asked
 * without a whole app behind it.
 */
internal interface WakeReporter {
    /** An address for wake-ups, or null when there is none any more. */
    fun address(address: PushAddress?)

    /** Something the user should be told about what wakes this phone. */
    fun problem(reason: String)
}

class WakeService : PushService() {

    /** Where answers go. Null in the app, where they go to the [Messenger]. */
    internal var reporter: WakeReporter? = null
    private companion object {
        /** What the relay's wake-up for a ringing call says. Anything else is a message. */
        const val WAKE_CALL = "call"
    }


    private val messenger
        get() = (application as MaunApp).messenger

    /** Where answers go: the app, unless a test has said where. */
    private fun telling(): WakeReporter = reporter ?: object : WakeReporter {
        override fun address(address: PushAddress?) = messenger.onPushAddress(address)
        override fun problem(reason: String) = messenger.onPushFailed(reason)
    }

    /** What wakes this phone, as the start of a sentence. */
    private val waker: String
        get() = PushApps.current(this)?.takeIf { it.google }?.label ?: "Your push app"

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        took(endpoint.pubKeySet?.let { PushAddress.fromWebPush(endpoint.url, it.pubKey, it.auth) })
    }

    /**
     * What a new endpoint from what wakes this phone means for the address the
     * relay holds. Null is an endpoint Maun cannot use — and an address it
     * cannot use is not a reason to forget one that works: the relay goes on
     * waking this phone at the address it already has, and the phone goes on
     * hearing about messages and calls while the push app sorts itself out.
     */
    internal fun took(address: PushAddress?) {
        val telling = telling()
        if (address == null) {
            telling.problem("${waker} gave Maun an address it cannot use.")
            return
        }
        telling.address(address)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // Only the relay holds the keys to encrypt a wake-up. Anything that
        // did not decrypt came from someone who merely knows the address, and
        // must not be able to make this phone do work.
        if (!message.decrypted) return
        // The relay says which it is. For a call, be awake as of this moment:
        // right after an urgent wake-up Android allows the service that keeps
        // Maun up while the call is fetched and rings. For a message, nothing
        // of Maun stays running beyond the fetch.
        val kind = if (String(message.content, Charsets.UTF_8) == WAKE_CALL) {
            RingService.prepare(this)
            DeliveryTrace.CALL
        } else {
            DeliveryTrace.WAKE
        }
        DeliveryTrace.began(this, kind)
        // Start at once: every second counts for a call that is ringing. The
        // scheduled job does the same work, and is what Android lets finish.
        messenger.fetchSoon()
        FetchWorker.schedule(this)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        telling().problem(
            when (reason) {
                FailedReason.NETWORK -> "$waker could not be reached. Maun asks again the next time it is opened."
                FailedReason.ACTION_REQUIRED -> "$waker needs your attention before it can wake Maun. Open it once."
                FailedReason.VAPID_REQUIRED -> "$waker can be used once Maun has connected to the relay. Open Maun with a connection."
                else -> "$waker reported an error, so it cannot wake Maun yet. Maun asks again the next time it is opened."
            },
        )
    }

    override fun onUnregistered(instance: String) {
        // What wakes this phone says the address is gone. That much is certain.
        telling().address(null)
    }
}
