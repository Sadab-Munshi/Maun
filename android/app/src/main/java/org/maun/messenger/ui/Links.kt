package org.maun.messenger.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Web addresses in a message, so that they can be tapped.
 *
 * Only `http` and `https` are ever picked out and only those two are ever
 * opened; anything else a message happens to contain — `mailto:`, a phone
 * number, a made-up `app://` — stays plain text. Nothing here fetches
 * anything: a link is a run of characters and a tap, no previews and no
 * requests of our own, because the relay and anyone watching the network
 * should not learn from this phone that a link was read.
 */
internal data class WebLink(val start: Int, val end: Int, val url: String)

/** The schemes a tap will open. Anything else is left as it is. */
private val WEB_SCHEMES = listOf("https://", "http://")

/** Characters that end an address inside running text. */
private fun isSpace(c: Char): Boolean = c.isWhitespace() || c == '\u00a0'

/** Punctuation that is almost always the sentence's, not the address's. */
private const val TRAILING = ".,;:!?'\"…’”“»"

private fun countBetween(text: String, start: Int, end: Int, wanted: Char): Int {
    var total = 0
    for (i in start until end) if (text[i] == wanted) total++
    return total
}

/**
 * Where the address that starts at [start] ends, with the punctuation that
 * belongs to the sentence trimmed off. Brackets are only dropped when there
 * are more of them at the end than at the beginning, so that a Wikipedia
 * address with brackets in it keeps them.
 */
private fun addressEnd(text: String, start: Int): Int {
    var end = start
    while (end < text.length && !isSpace(text[end]) && text[end] !in "<>\"`") end++
    var stop = end
    while (stop > start) {
        val last = text[stop - 1]
        val drop = when (last) {
            in TRAILING -> true
            ')' -> countBetween(text, start, stop, '(') < countBetween(text, start, stop, ')')
            ']' -> countBetween(text, start, stop, '[') < countBetween(text, start, stop, ']')
            '}' -> countBetween(text, start, stop, '{') < countBetween(text, start, stop, '}')
            else -> false
        }
        if (!drop) break
        stop--
    }
    return stop
}

/** Whether what follows the scheme could be a place on a network at all. */
private fun hasHost(text: String, schemeEnd: Int, end: Int): Boolean {
    if (schemeEnd >= end) return false
    var hostEnd = end
    for (i in schemeEnd until end) {
        if (text[i] == '/' || text[i] == '?' || text[i] == '#') {
            hostEnd = i
            break
        }
    }
    if (hostEnd <= schemeEnd) return false
    val host = text.substring(schemeEnd, hostEnd)
    // A user name before the host (someone@example.org) is not needed here.
    val address = host.substringAfterLast('@')
    val bare = address.substringBefore(':')
    if (bare.isEmpty() || bare.length > 253) return false
    if (!bare.contains('.')) return false
    if (bare.startsWith('.') || bare.endsWith('.') || bare.contains("..")) return false
    return bare.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
}

/**
 * Every web address in [text], in the order they appear, without overlaps.
 * Embedded addresses and several addresses in one line all come out; a
 * half-typed or broken one is left as text.
 */
internal fun findWebLinks(text: String): List<WebLink> {
    if (text.isEmpty()) return emptyList()
    val links = ArrayList<WebLink>(2)
    var at = 0
    while (at < text.length) {
        var found = -1
        var scheme = ""
        for (candidate in WEB_SCHEMES) {
            val index = text.indexOf(candidate, at, ignoreCase = true)
            if (index >= 0 && (found < 0 || index < found)) {
                found = index
                scheme = candidate
            }
        }
        if (found < 0) break
        // "xhttps://…" is one word, not a link.
        if (found > 0 && text[found - 1].isLetterOrDigit()) {
            at = found + 1
            continue
        }
        val end = addressEnd(text, found)
        val hostAt = found + scheme.length
        if (!hasHost(text, hostAt, end) || end <= hostAt) {
            at = found + scheme.length
            continue
        }
        links += WebLink(found, end, text.substring(found, end))
        at = end
    }
    return links
}

/**
 * [text] with its web addresses marked as links in [style]. The link is a
 * range of characters, not a widget: the bubble still wraps, still copies,
 * and still answers a long press the way it did before.
 */
internal fun linkify(
    text: String,
    links: List<WebLink>,
    style: SpanStyle,
    onOpen: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(text)
    for (link in links) {
        addLink(
            LinkAnnotation.Clickable(
                tag = link.url,
                styles = TextLinkStyles(style = style),
                linkInteractionListener = { onOpen(link.url) },
            ),
            start = link.start,
            end = link.end,
        )
    }
}

/** [text] ready to draw: every web address in it tappable, everything else plain. */
internal fun linkedText(text: String, style: SpanStyle, onOpen: (String) -> Unit): AnnotatedString =
    linkify(text, findWebLinks(text), style, onOpen)

/**
 * Opens [url] in whichever browser this phone uses. Returns false when the
 * address is not one a tap may open, or when nothing on the phone takes it.
 * A browser is a browser: no in-app view, no page fetched by Maun itself.
 */
internal fun openWebLink(context: Context, url: String): Boolean {
    val uri = try {
        Uri.parse(url)
    } catch (_: Exception) {
        return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (uri.host.isNullOrEmpty()) return false
    val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(view)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
