package org.maun.messenger.ui

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maun.messenger.BuildConfig
import org.maun.messenger.CodeCheck
import org.maun.messenger.MaunApp
import org.maun.messenger.network.RelayException

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

    suspend fun checkCode(text: String, typed: Boolean): CodeCheck = messenger.checkCode(text, typed)

    fun saveContact(id: String, name: String, verified: Boolean) {
        viewModelScope.launch { messenger.saveContact(id, name, verified) }
    }

    fun removeContact(id: String) {
        viewModelScope.launch { messenger.removeContact(id) }
    }

    companion object {
        const val SETTINGS = "settings"
        const val KEY_RELAY_URL = "relay_url"
    }
}
