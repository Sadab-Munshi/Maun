package org.maun.messenger

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maun.messenger.ui.findWebLinks
import org.maun.messenger.ui.linkedText
import org.maun.messenger.ui.openWebLink
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Addresses in a message: which runs of characters are links, where each one
 * ends, and which of them a tap is allowed to open. A link Maun gets wrong is
 * either a tap that does nothing or a tap that goes somewhere it should not,
 * so the punctuation and the half-typed cases matter as much as the easy ones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinksTest {

    private fun urls(text: String) = findWebLinks(text).map { it.url }

    @Test
    fun `one address in an ordinary sentence`() {
        assertEquals(
            listOf("https://maun.example.org"),
            urls("Read this: https://maun.example.org — it is short."),
        )
    }

    @Test
    fun `two addresses in one message are both found, in order`() {
        assertEquals(
            listOf("https://first.example.org/a", "http://second.example.org/b?x=1"),
            urls("https://first.example.org/a and http://second.example.org/b?x=1"),
        )
    }

    @Test
    fun `an address inside brackets is a link on its own`() {
        val text = "see (https://example.org/page) for more"
        val links = findWebLinks(text)
        assertEquals(1, links.size)
        assertEquals(5, links[0].start)
        assertEquals("https://example.org/page", links[0].url)
        assertEquals(text.substring(links[0].start, links[0].end), links[0].url)
    }

    @Test
    fun `a long address is one link, however it wraps on screen`() {
        val url = "https://example.org/a/very/long/path/with/many/parts/and-a-name-here?first=1&second=2#place"
        assertEquals(listOf(url), urls("Here: $url"))
    }

    @Test
    fun `the punctuation of the sentence stays out of the address`() {
        assertEquals(listOf("https://example.org"), urls("Go to https://example.org."))
        assertEquals(listOf("https://example.org"), urls("Is it https://example.org, or not?"))
        assertEquals(listOf("https://example.org"), urls("\"https://example.org\""))
        assertEquals(listOf("https://example.org/x?a=1"), urls("(https://example.org/x?a=1)"))
    }

    @Test
    fun `brackets inside an address are kept`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Maun_(app)"),
            urls("https://en.wikipedia.org/wiki/Maun_(app)"),
        )
    }

    @Test
    fun `an upper case scheme is still a link`() {
        assertEquals(listOf("HTTPS://Example.org/Path"), urls("HTTPS://Example.org/Path"))
    }

    @Test
    fun `what is not a web address stays plain text`() {
        assertEquals(emptyList<String>(), urls("no address here at all"))
        assertEquals(emptyList<String>(), urls("http://"))
        assertEquals(emptyList<String>(), urls("https:// now a space"))
        assertEquals(emptyList<String>(), urls("ftp://example.org/file"))
        assertEquals(emptyList<String>(), urls("mailto:asha@example.org"))
        assertEquals(emptyList<String>(), urls("javascript:alert(1)"))
        assertEquals(emptyList<String>(), urls("example.org without a scheme"))
        assertEquals(emptyList<String>(), urls("xhttps://example.org"))
        assertEquals(emptyList<String>(), urls("https:///no-host"))
        assertEquals(emptyList<String>(), urls("https://.org"))
    }

    @Test
    fun `the same address twice is found twice`() {
        assertEquals(
            listOf("https://example.org", "https://example.org"),
            urls("https://example.org then https://example.org"),
        )
    }

    @Test
    fun `a link is a range of the text, and the text is unchanged`() {
        val text = "before https://example.org after"
        val opened = mutableListOf<String>()
        val drawn = linkedText(text, SpanStyle(), onOpen = { opened += it })
        assertEquals(text, drawn.text)
        val links = drawn.getLinkAnnotations(0, drawn.length)
        assertEquals(1, links.size)
        assertEquals("https://example.org", text.substring(links.first().start, links.first().end))
        assertEquals(7, links.first().start)
        assertEquals(26, links.first().end)
        // Nothing is opened by drawing the text: only a tap does that.
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `text without an address has no links at all`() {
        val drawn = linkedText("nothing to tap", SpanStyle(), onOpen = {})
        assertEquals(0, drawn.getLinkAnnotations(0, drawn.length).size)
        assertEquals("nothing to tap", drawn.text)
    }

    @Test
    fun `only a web address can be opened`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(openWebLink(context, "mailto:asha@example.org"))
        assertFalse(openWebLink(context, "file:///etc/passwd"))
        assertFalse(openWebLink(context, "javascript:alert(1)"))
        assertFalse(openWebLink(context, "ftp://example.org"))
        assertFalse(openWebLink(context, "not a url at all"))
        assertFalse(openWebLink(context, "https://"))
    }
}
