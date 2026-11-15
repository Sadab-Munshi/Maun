package org.maun.messenger.ui

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maun.messenger.BuildConfig
import org.maun.messenger.CodeCheck
import org.maun.messenger.MaunApp
import org.maun.messenger.data.MessageEntity
import org.maun.messenger.network.RelayException
import org.maun.messenger.push.Background
import org.maun.messenger.push.Notifications
import org.maun.messenger.push.PushApps

/** What the welcome screen needs beyond the account and relay state. */
data class OnboardingUiState(
    val relayUrl: String,
    /** True while the address is being checked and the identity created. */
    val working: Boolean = false,
    val error: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val messenger = (application as MaunApp).messenger
    private val settings = application.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)

    val account = messenger.account
    val relay = messenger.relay
    val contacts = messenger.contacts
    val blocked = messenger.blocked
    val waiting = messenger.waiting
    val pushProblem = messenger.pushProblem

    private val _delivery = MutableStateFlow(DeliveryUi())

    /** How messages arrive while Maun is closed. Call [refreshDelivery] to bring it up to date. */
    val delivery: StateFlow<DeliveryUi> = _delivery.asStateFlow()

    val home: StateFlow<HomeLists> = combine(messenger.contacts, messenger.chats, ::homeLists)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeLists())

    private val _onboarding = MutableStateFlow(
        OnboardingUiState(relayUrl = settings.getString(KEY_RELAY_URL, null) ?: BuildConfig.RELAY_URL),
    )
    val onboarding: StateFlow<OnboardingUiState> = _onboarding.asStateFlow()

    fun onRelayUrlChange(value: String) {
        _onboarding.update { it.copy(relayUrl = value, error = null) }
    }

    fun createAccount() {
        if (_onboarding.value.working) return
        val relayUrl = _onboarding.value.relayUrl.trim()
        if (relayUrl.isEmpty()) {
            _onboarding.update { it.copy(error = "Enter your relay address first.") }
            return
        }
        settings.edit { putString(KEY_RELAY_URL, relayUrl) }
        _onboarding.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            val error = try {
                messenger.createAccount(relayUrl)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayException) {
                e.message
            } catch (e: Exception) {
                "Could not create your ID: ${e.message ?: e.javaClass.simpleName}"
            }
            _onboarding.update { it.copy(working = false, error = error) }
        }
    }

    fun reconnect() = messenger.reconnect()

    /** Reads the things that change outside the app: permissions, installed push apps. */
    fun refreshDelivery() {
        val context = getApplication<Application>()
        val pushApp = PushApps.current(context)
        _delivery.value = DeliveryUi(
            notificationsAllowed = Notifications(context).allowed,
            pushApp = pushApp?.label,
            pushChoices = if (pushApp == null) PushApps.installed(context) else emptyList(),
            stayConnected = Background.stayConnected(context),
            unrestricted = Background.unrestricted(context),
        )
    }

    fun choosePushApp(packageName: String) {
        PushApps.choose(getApplication(), packageName)
        refreshDelivery()
    }

    fun setStayConnected(on: Boolean) {
        Background.setStayConnected(getApplication(), on)
        refreshDelivery()
    }

    suspend fun checkCode(text: String, typed: Boolean): CodeCheck = messenger.checkCode(text, typed)

    fun saveContact(id: String, name: String, verified: Boolean) {
        viewModelScope.launch { messenger.saveContact(id, name, verified) }
    }

    fun removeContact(id: String) {
        viewModelScope.launch { messenger.removeContact(id) }
    }

    /** The conversation with [peer], newest first. */
    fun messages(peer: String): Flow<List<MessageEntity>> = messenger.messages(peer)

    fun sendText(peer: String, text: String) {
        viewModelScope.launch {
            try {
                messenger.sendText(peer, text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The screen only offers Send for text that can be sent to a
                // contact; a race with removing the contact ends up here.
            }
        }
    }

    fun markRead(peer: String) {
        viewModelScope.launch { messenger.markRead(peer) }
    }

    fun block(peer: String) {
        viewModelScope.launch { messenger.block(peer) }
    }

    fun unblock(peer: String) {
        viewModelScope.launch { messenger.unblock(peer) }
    }

    companion object {
        const val SETTINGS = "settings"
        const val KEY_RELAY_URL = "relay_url"
    }
}
