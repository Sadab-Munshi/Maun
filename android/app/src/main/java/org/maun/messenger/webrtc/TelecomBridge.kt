package org.maun.messenger.webrtc

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells Android's own call system (Telecom) about Maun's calls, so that it
 * treats them as calls: a headset's or a car's buttons answer and hang up,
 * an ordinary phone call does not talk over one, and Android decides with
 * the user where the sound goes.
 *
 * It is an addition, not a foundation. A phone without Telecom (many
 * tablets), a Telecom that turns a call down, or the switch in Settings being
 * off, all leave the call to run as it did before, with Maun handling the
 * sound itself. Everything here happens on the main thread.
 */
class TelecomBridge(context: Context) {

    interface Listener {
        /** The call was answered somewhere else: a watch, a headset button, a car. */
        fun onAnswer()

        /** Android, or something connected to the phone, ended the call. */
        fun onHangUp()

        /** Where the sound goes, and where else it could; [current] is null while that is not known. */
        fun onRoutes(current: AudioRoute?, all: List<AudioRoute>)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: CallsManager? = null
    private var tried = false

    private var job: Job? = null
    private var control: CallControlScope? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var current: CallEndpointCompat? = null
    private var ending = false

    /** Android has taken the call that is going on, and steers its sound. */
    val inCharge: Boolean get() = control != null

    private fun manager(): CallsManager? {
        if (!enabled(appContext)) return null
        if (tried) return manager
        tried = true
        val packages = appContext.packageManager
        val supported = packages.hasSystemFeature("android.software.telecom") || packages.hasSystemFeature("android.software.connectionservice")
        if (!supported) return null
        manager = try {
            CallsManager(appContext).also {
                it.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE or CallsManager.CAPABILITY_SUPPORTS_VIDEO_CALLING)
            }
        } catch (_: Throwable) {
            null
        }
        return manager
    }

    /** Hands a call that is starting, or ringing here, to Android. Returns at once; [inCharge] says later whether it was taken. */
    fun begin(name: String, incoming: Boolean, video: Boolean, listener: Listener) {
        end(DisconnectCause.LOCAL)
        val calls = manager() ?: return
        ending = false
        val attributes = try {
            CallAttributesCompat(
                displayName = name,
                // Android wants an address. Nothing that identifies anyone goes into it.
                address = Uri.fromParts("sip", "maun", null),
                direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
                callType = if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            )
        } catch (_: Throwable) {
            return
        }
        job = scope.launch {
            try {
                calls.addCall(
                    attributes,
                    onAnswer = { listener.onAnswer() },
                    onDisconnect = { if (!ending) listener.onHangUp() },
                    onSetActive = { },
                    onSetInactive = { },
                ) {
                    val taken = this
                    control = taken
                    launch {
                        availableEndpoints.collect {
                            endpoints = it
                            if (control === taken) listener.onRoutes(current?.let(::routeOf), routes())
                        }
                    }
                    launch {
                        currentCallEndpoint.collect {
                            current = it
                            if (control === taken) listener.onRoutes(routeOf(it), routes())
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Android would not take the call. It goes on without.
            } finally {
                control = null
                endpoints = emptyList()
                current = null
            }
        }
    }

    /** The call that was ringing here has been taken. */
    fun answered(video: Boolean) = act {
        it.answer(if (video) CallAttributesCompat.CALL_TYPE_VIDEO_CALL else CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
    }

    /** The other side has taken the call that went out from here. */
    fun active() = act { it.setActive() }

    /** The call is over. [cause] is one of [DisconnectCause]'s codes. */
    fun end(cause: Int) {
        val running = job ?: return
        val taken = control
        ending = true
        job = null
        scope.launch {
            if (taken != null) runCatching { taken.disconnect(DisconnectCause(cause)) }
            // Should Android not let go by itself, the call is taken away from it.
            delay(1500)
            running.cancel()
        }
    }

    /** Asks Android to send the sound to [route]. False when Android is not in charge or has no such place. */
    fun setRoute(route: AudioRoute): Boolean {
        val taken = control ?: return false
        val endpoint = endpoints.firstOrNull { routeOf(it) == route } ?: return false
        scope.launch { runCatching { taken.requestEndpointChange(endpoint) } }
        return true
    }

    private fun act(what: suspend (CallControlScope) -> Unit) {
        val taken = control ?: return
        scope.launch { runCatching { what(taken) } }
    }

    private fun routes(): List<AudioRoute> = endpoints.mapNotNull(::routeOf).distinct().sortedBy { it.ordinal }

    private fun routeOf(endpoint: CallEndpointCompat): AudioRoute? = when (endpoint.type) {
        CallEndpointCompat.TYPE_EARPIECE -> AudioRoute.Earpiece
        CallEndpointCompat.TYPE_SPEAKER -> AudioRoute.Speaker
        CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRoute.Wired
        CallEndpointCompat.TYPE_BLUETOOTH -> AudioRoute.Bluetooth
        else -> null
    }

    companion object {
        private const val KEY = "telecom_calls"

        /** Whether Maun's calls are handed to Android's call system. On unless the user turned it off. */
        fun enabled(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        }
    }
}
