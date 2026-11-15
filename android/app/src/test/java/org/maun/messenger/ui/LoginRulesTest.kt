package org.maun.messenger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maun.messenger.network.Usernames

class LoginRulesTest {

    @Test
    fun `only something shaped like an email address is sent on`() {
        assertTrue(looksLikeEmail("asha@example.com"))
        assertTrue(looksLikeEmail("  first.last+tag@mail.example.co.in "))
        assertFalse(looksLikeEmail("asha"))
        assertFalse(looksLikeEmail("asha@example"))
        assertFalse(looksLikeEmail("@example.com"))
        assertFalse(looksLikeEmail("a@b@example.com"))
        assertFalse(looksLikeEmail("asha @example.com"))
        assertFalse(looksLikeEmail("asha@example."))
    }

    @Test
    fun `a username follows the relay's rule`() {
        for (good in listOf("abc", "sadab", "a_b_c", "user2026", "a".repeat(20))) assertTrue(good, Usernames.isValid(good))
        for (bad in listOf("", "ab", "_abc", "Abc", "a-b", "a.b", "1abc", "a".repeat(21), "admin", "maun")) {
            assertFalse(bad, Usernames.isValid(bad))
        }
    }

    @Test
    fun `what is typed is tidied before it is judged`() {
        assertEquals("sadab", Usernames.clean("  @Sadab "))
        assertEquals("rohan_7", Usernames.clean("Rohan_7"))
        assertTrue(Usernames.isValid(Usernames.clean("@Asha")))
    }
}
