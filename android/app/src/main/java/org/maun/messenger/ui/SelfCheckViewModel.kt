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
import org.maun.messenger.BuildConfig
import org.maun.messenger.crypto.DeviceKey
import org.maun.messenger.crypto.SignalSelfTest
import org.maun.messenger.data.AppDatabase
import org.maun.messenger.data.MetaEntry
import org.maun.messenger.network.RelayClient
import org.signal.libsignal.protocol.ecc.ECKeyPair

enum class CheckStatus { PENDING, RUNNING, PASSED, WARNING, FAILED }

enum class CheckId(val title: String, val purpose: String) {
    DEVICE_KEY("Device key", "A login key that cannot leave this phone"),
    DATABASE("Encrypted storage", "Chats saved on this phone are unreadable without it"),
    SIGNAL("End-to-end encryption", "Two test users exchange encrypted messages"),
    RELAY("Relay connection", "A message travels to your server and back"),
}

data class CheckState(val id: CheckId, val status: CheckStatus = CheckStatus.PENDING, val detail: String = "")

data class SelfCheckUiState(
    val relayUrl: String,
    val running: Boolean = false,
    val checks: List<CheckState> = CheckId.entries.map { CheckState(it) },
)

/** Outcome of one check: a human sentence, and whether it deserves a caution. */
private class Outcome(val detail: String, val warning: Boolean = false)

/**
 * Milestone 0 diagnostics. Each check exercises a piece the real app is built
 * on, using the same code paths, and reports the result in plain words.
 */
class SelfCheckViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val relay = RelayClient()

    private val _state = MutableStateFlow(
        SelfCheckUiState(relayUrl = settings.getString(KEY_RELAY_URL, null) ?: BuildConfig.RELAY_URL),
    )
    val state: StateFlow<SelfCheckUiState> = _state.asStateFlow()

    fun onRelayUrlChange(value: String) {
        _state.update { it.copy(relayUrl = value) }
    }

    fun runChecks() {
        if (_state.value.running) return
        val relayUrl = _state.value.relayUrl.trim()
        settings.edit { putString(KEY_RELAY_URL, relayUrl) }
        _state.update { SelfCheckUiState(relayUrl = relayUrl, running = true) }

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
        DeviceKey.ensure(getApplication())
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = DeviceKey.sign(challenge)
        check(DeviceKey.verify(challenge, signature)) { "The key's own signature did not verify" }
        check(!DeviceKey.verify(challenge.copyOf().also { it[0] = (it[0] + 1).toByte() }, signature)) {
            "A signature verified for the wrong message"
        }
        return when (DeviceKey.protection()) {
            DeviceKey.Protection.STRONGBOX -> Outcome("Held in this phone's dedicated security chip (StrongBox)")
            DeviceKey.Protection.TRUSTED_ENVIRONMENT -> Outcome("Held in the processor's secure area (TEE)")
            DeviceKey.Protection.SECURE_HARDWARE -> Outcome("Held in secure hardware")
            DeviceKey.Protection.SOFTWARE -> Outcome(
                "Works, but this phone keeps it in software only. Normal on an emulator; weaker on a real phone.",
                warning = true,
            )
            DeviceKey.Protection.UNKNOWN -> Outcome("Works, but the phone does not say where it is kept", warning = true)
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
        // A mailbox is addressed by a public key. This one is random and thrown away.
        val mailboxId = ECKeyPair.generate().publicKey.publicKeyBytes.joinToString("") { "%02x".format(it) }
        val millis = relay.echo(relayUrl, mailboxId, body = "maun self-check")
        return Outcome("Reached the relay and got the echo back in $millis ms")
    }

    private companion object {
        const val KEY_RELAY_URL = "relay_url"
        const val SELF_CHECK_KEY = "self_check"
        val PLAIN_SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
