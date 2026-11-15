package org.maun.messenger.push

import org.maun.messenger.MaunApp
import org.maun.messenger.network.PushAddress
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Hears from the push app on this phone (UnifiedPush; ntfy is one such app).
 *
 * The push app hands Maun an address at which wake-ups for this phone can be
 * left, and keys to encrypt them with. Maun gives both to the relay. When the
 * relay has a message for this phone and the phone is not connected, it leaves
 * a wake-up there; the push app passes it on, and Maun connects and fetches.
 */
class WakeService : PushService() {

    private val messenger
        get() = (application as MaunApp).messenger

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val address = endpoint.pubKeySet?.let { PushAddress.fromWebPush(endpoint.url, it.pubKey, it.auth) }
        if (address == null) {
            messenger.onPushFailed("Your push app gave Maun an address it cannot use.")
            messenger.onPushAddress(null)
        } else {
            messenger.onPushAddress(address)
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // Only the relay holds the keys to encrypt a wake-up. Anything that
        // did not decrypt came from someone who merely knows the address, and
        // must not be able to make this phone do work.
        if (!message.decrypted) return
        FetchWorker.schedule(this)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        messenger.onPushFailed(
            when (reason) {
                FailedReason.NETWORK -> "Your push app could not reach its server. It will try again by itself."
                FailedReason.ACTION_REQUIRED -> "Your push app needs your attention before it can wake Maun. Open it once."
                FailedReason.VAPID_REQUIRED -> "Your push app's server asks for something Maun does not support yet."
                else -> "Your push app reported an error."
            },
        )
    }

    override fun onUnregistered(instance: String) {
        messenger.onPushAddress(null)
    }
}
