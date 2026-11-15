package org.maun.messenger.ui

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maun.messenger.AccountState
import org.maun.messenger.BuildConfig
import org.maun.messenger.MaunApp
import org.maun.messenger.crypto.DeviceKey
import org.maun.messenger.crypto.SignalSelfTest
import org.maun.messenger.data.AppDatabase
import org.maun.messenger.data.MetaEntry
import org.maun.messenger.network.RelayClient
import org.maun.messenger.network.RelayException
import org.maun.messenger.network.RelayState

enum class CheckStatus { PENDING, RUNNING, PASSED, WARNING, FAILED }

enum class CheckId(val title: String, val purpose: String) {
    DEVICE_KEY("Device key", "A login key that cannot leave this phone"),
    DATABASE("Encrypted storage", "Chats saved on this phone are unreadable without it"),
    SIGNAL("End-to-end encryption", "Two test users exchange encrypted messages"),
    RELAY("Relay connection", "This phone reaches your server"),
}

data class CheckState(val id: CheckId, val status: CheckStatus = CheckStatus.PENDING, val detail: String = "")

data class SelfCheckUiState(
    val relayUrl: String,
    /** True once this phone has an ID: the relay is then fixed and cannot be edited here. */
    val relayFixed: Boolean = false,
    val running: Boolean = false,
    val checks: List<CheckState> = CheckId.entries.map { CheckState(it) },
)

/** Outcome of one check: a human sentence, and whether it deserves a caution. */
private class Outcome(val detail: String, val warning: Boolean = false)

/**
 * Diagnostics. Each check exercises a piece the app is built on, using the
 * same code paths, and reports the result in plain words.
 */
class SelfCheckViewModel(application: Application) : AndroidViewModel(application) {

    private val messenger = (application as MaunApp).messenger
    private val settings = application.getSharedPreferences(MainViewModel.SETTINGS, Context.MODE_PRIVATE)
    private val relay = RelayClient()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<SelfCheckUiState> = _state.asStateFlow()

    private fun initialState(): SelfCheckUiState {
        // Until the relay has accepted the ID, the address can still be changed.
        val account = (messenger.account.value as? AccountState.Present)?.takeIf { it.registered }
        return SelfCheckUiState(
            relayUrl = account?.relayUrl
                ?: settings.getString(MainViewModel.KEY_RELAY_URL, null)
                ?: BuildConfig.RELAY_URL,
            relayFixed = account != null,
        )
    }

    fun onRelayUrlChange(value: String) {
        if (_state.value.relayFixed) return
        _state.update { it.copy(relayUrl = value) }
    }

    fun runChecks() {
        if (_state.value.running) return
        val fresh = initialState()
        val relayUrl = if (fresh.relayFixed) fresh.relayUrl else _state.value.relayUrl.trim()
        if (!fresh.relayFixed) settings.edit { putString(MainViewModel.KEY_RELAY_URL, relayUrl) }
        _state.update { fresh.copy(relayUrl = relayUrl, running = true) }

        viewModelScope.launch {
            runCheck(CheckId.DEVICE_KEY) { checkDeviceKey() }
            runCheck(CheckId.DATABASE) { checkDatabase() }
            runCheck(CheckId.SIGNAL) { checkSignal() }
            runCheck(CheckId.RELAY) { checkRelay(relayUrl) }
            _state.update { it.copy(running = false) }
        }
    }

    private suspend fun runCheck(id: CheckId, block: suspend () -> Outcome) {
        setCheck(CheckState(id, CheckStatus.RUNNING))
        val result = try {
            val outcome = withContext(Dispatchers.IO) { block() }
            CheckState(id, if (outcome.warning) CheckStatus.WARNING else CheckStatus.PASSED, outcome.detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable on purpose: a missing native library is an Error, not an Exception.
            CheckState(id, CheckStatus.FAILED, e.message ?: e.javaClass.simpleName)
        }
        setCheck(result)
    }

    private fun setCheck(check: CheckState) {
        _state.update { state -> state.copy(checks = state.checks.map { if (it.id == check.id) check else it }) }
    }

    private fun checkDeviceKey(): Outcome {
        // A throwaway key made the same way as the login key, so the check
        // never touches the key the account depends on.
        val key = DeviceKey(getApplication(), DeviceKey.SELF_CHECK_ALIAS)
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            key.create(challenge)
            val signature = key.sign(challenge)
            check(key.verify(challenge, signature)) { "The key's own signature did not verify" }
            check(!key.verify(challenge.copyOf().also { it[0] = (it[0] + 1).toByte() }, signature)) {
                "A signature verified for the wrong message"
            }
            return when (key.protection()) {
                DeviceKey.Protection.STRONGBOX -> Outcome("Held in this phone's dedicated security chip (StrongBox)")
                DeviceKey.Protection.TRUSTED_ENVIRONMENT -> Outcome("Held in the processor's secure area (TEE)")
                DeviceKey.Protection.SECURE_HARDWARE -> Outcome("Held in secure hardware")
                DeviceKey.Protection.SOFTWARE -> Outcome(
                    "Works, but this phone keeps it in software only. Normal on an emulator; weaker on a real phone.",
                    warning = true,
                )
                DeviceKey.Protection.UNKNOWN ->
                    Outcome("Works, but the phone does not say where it is kept", warning = true)
            }
        } finally {
            runCatching { key.delete() }
        }
    }

    private suspend fun checkDatabase(): Outcome {
        val context = getApplication<Application>()
        val marker = UUID.randomUUID().toString()

        AppDatabase.open(context).run {
            try {
                meta().put(MetaEntry(SELF_CHECK_KEY, marker))
            } finally {
                close()
            }
        }
        // A fresh connection must unlock the same file with the same key.
        val readBack = AppDatabase.open(context).run {
            try {
                meta().get(SELF_CHECK_KEY)
            } finally {
                close()
            }
        }
        check(readBack == marker) { "A value written to the database did not come back" }

        val file = context.getDatabasePath(AppDatabase.FILE_NAME)
        val header = ByteArray(PLAIN_SQLITE_HEADER.size)
        val read = file.inputStream().use { it.read(header) }
        check(read == header.size) { "The database file on disk is empty" }
        check(!header.contentEquals(PLAIN_SQLITE_HEADER)) { "The database file on disk is not encrypted" }
        return Outcome("Written, reopened and read back; the file on disk is encrypted")
    }

    private fun checkSignal(): Outcome {
        val result = SignalSelfTest.run()
        return Outcome("Session set up and a message decrypted each way (protocol v${result.sessionVersion})")
    }

    private suspend fun checkRelay(relayUrl: String): Outcome {
        check(relayUrl.isNotEmpty()) { "Enter your relay address above, then run the checks again" }
        val url = RelayClient.normalizeRelayUrl(relayUrl) ?: throw RelayException("\"$relayUrl\" is not a web address")
        relay.checkHealth(url)

        val account = messenger.account.value as? AccountState.Present
        if (account == null || !account.registered) {
            return Outcome("Reached the relay, and it speaks this app's protocol")
        }
        // With an ID, also prove the signed-in connection carries a frame both ways.
        return when (val state = messenger.relay.value) {
            RelayState.Online -> Outcome("Signed in, and a frame went there and back in ${messenger.echo()} ms")
            is RelayState.Rejected -> throw RelayException(state.reason)
            is RelayState.Retrying -> throw RelayException(state.reason)
            else -> Outcome("Reached the relay, but this phone is not signed in right now", warning = true)
        }
    }

    private companion object {
        const val SELF_CHECK_KEY = "self_check"
        val PLAIN_SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
