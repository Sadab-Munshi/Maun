package org.maun.messenger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessLoginTest {

    private val login = AccessLogin.loginUrl("https://maun.example.workers.dev/")!!
    private val token = "eyJhbGciOiJSUzI1NiJ9.eyJlbWFpbCI6ImFAYi5jIn0.c2lnbmF0dXJl-_x"

    @Test
    fun `the login page is a fixed path on the relay`() {
        assertEquals("https://maun.example.workers.dev/v1/access/login", login.toString())
        assertEquals(login, AccessLogin.loginUrl("https://maun.example.workers.dev/anything?x=1"))
        assertNull(AccessLogin.loginUrl("not an address"))
    }

    @Test
    fun `only the relay's own login page counts as having got through`() {
        assertTrue(AccessLogin.isLoginPage("https://maun.example.workers.dev/v1/access/login", login))
        assertTrue(AccessLogin.isLoginPage("https://maun.example.workers.dev/v1/access/login?x=1", login))
        assertFalse(AccessLogin.isLoginPage("https://team.cloudflareaccess.com/cdn-cgi/access/login/maun.example.workers.dev", login))
        assertFalse(AccessLogin.isLoginPage("https://maun.example.workers.dev/v1/access/other", login))
        assertFalse(AccessLogin.isLoginPage("http://maun.example.workers.dev/v1/access/login", login))
        assertFalse(AccessLogin.isLoginPage("https://evil.example/v1/access/login", login))
        assertFalse(AccessLogin.isLoginPage(null, login))
    }

    @Test
    fun `the login may only go to the relay and to Cloudflare's login pages`() {
        assertTrue(AccessLogin.mayVisit("https://maun.example.workers.dev/cdn-cgi/access/authorized?x", login))
        assertTrue(AccessLogin.mayVisit("https://myteam.cloudflareaccess.com/cdn-cgi/access/login/x", login))
        assertFalse(AccessLogin.mayVisit("https://example.com/", login))
        assertFalse(AccessLogin.mayVisit("https://cloudflareaccess.com.evil.example/", login))
        assertFalse(AccessLogin.mayVisit("http://myteam.cloudflareaccess.com/", login))
        assertFalse(AccessLogin.mayVisit("intent://something", login))
        assertFalse(AccessLogin.mayVisit(null, login))
    }

    @Test
    fun `the token is read from the page, or from the cookie Cloudflare set`() {
        assertEquals(token, AccessLogin.tokenFrom("\"$token\"", null))
        assertEquals(token, AccessLogin.tokenFrom("null", "other=1; CF_Authorization=$token; more=2"))
        // The page wins when both are there.
        assertEquals(token, AccessLogin.tokenFrom("\"$token\"", "CF_Authorization=aaa.bbb.ccc"))
    }

    @Test
    fun `things that are not a token are not taken for one`() {
        assertNull(AccessLogin.tokenFrom("null", null))
        assertNull(AccessLogin.tokenFrom("\"\"", ""))
        assertNull(AccessLogin.tokenFrom("\"only.two\"", null))
        assertNull(AccessLogin.tokenFrom("\"a.b.<script>\"", null))
        assertNull(AccessLogin.tokenFrom(null, "CF_AppSession=abc; other=a.b.c"))
        assertNull(AccessLogin.tokenFrom("\"${"a".repeat(5000)}.b.c\"", null))
    }

    @Test
    fun `an email address cannot break out of the script that fills it in`() {
        val script = AccessLogin.fillEmailScript("a\"+alert(1)+\"@example.com")
        assertTrue(script.contains("\\\"+alert(1)+\\\""))
        assertFalse(script.contains("=\"a\"+alert"))
    }

    @Test
    fun `only something shaped like an email address is sent on`() {
        assertTrue(AccessLogin.looksLikeEmail("asha@example.com"))
        assertTrue(AccessLogin.looksLikeEmail("  first.last+tag@mail.example.co.in "))
        assertFalse(AccessLogin.looksLikeEmail("asha"))
        assertFalse(AccessLogin.looksLikeEmail("asha@example"))
        assertFalse(AccessLogin.looksLikeEmail("@example.com"))
        assertFalse(AccessLogin.looksLikeEmail("a@b@example.com"))
        assertFalse(AccessLogin.looksLikeEmail("asha @example.com"))
        assertFalse(AccessLogin.looksLikeEmail("asha@example."))
    }
}
