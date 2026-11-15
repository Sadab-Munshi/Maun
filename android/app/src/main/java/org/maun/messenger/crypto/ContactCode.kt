package org.maun.messenger.crypto

import okhttp3.HttpUrl

/**
 * What a "My ID" QR code holds: the person's ID and the relay their mailbox
 * lives on. Text form: `maun:c1:<64 hex digits>:<relay host>`.
 */
data class ContactCode(val id: String, val relay: String) {

    fun encode(): String = "$PREFIX$id:$relay"

    companion object {
        private const val PREFIX = "maun:c1:"
        private val ID = Regex("[0-9a-f]{64}")
        private val RELAY = Regex("[a-z0-9.\\-\\[\\]:]{1,253}")

        fun isId(text: String): Boolean = ID.matches(text)

        /** Reads a scanned or pasted code. Returns null for anything else. */
        fun parse(text: String): ContactCode? {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX, ignoreCase = true)) return null
            val rest = trimmed.substring(PREFIX.length)
            // The relay may itself contain colons (a port), so split only once.
            val separator = rest.indexOf(':')
            if (separator < 0) return null
            val id = rest.substring(0, separator).lowercase()
            val relay = rest.substring(separator + 1).lowercase()
            if (!ID.matches(id) || !RELAY.matches(relay)) return null
            return ContactCode(id, relay)
        }

        /**
         * Reads what a person typed or pasted: a full code, or a bare ID, which
         * is taken to be on [ownRelay].
         */
        fun parseTyped(text: String, ownRelay: String): ContactCode? {
            parse(text)?.let { return it }
            val bare = text.filterNot { it.isWhitespace() }.lowercase()
            return if (ID.matches(bare)) ContactCode(bare, ownRelay) else null
        }

        /** How a relay is named inside a code: its host, plus the port if unusual. */
        fun relayName(url: HttpUrl): String {
            val host = if (':' in url.host) "[${url.host}]" else url.host
            return if (url.port == HttpUrl.defaultPort(url.scheme)) host else "$host:${url.port}"
        }
    }
}
