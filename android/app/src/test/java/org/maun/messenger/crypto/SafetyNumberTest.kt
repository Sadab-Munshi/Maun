package org.maun.messenger.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNumberTest {
    private val asha = Identity.generate().id
    private val bala = Identity.generate().id
    private val chitra = Identity.generate().id

    @Test
    fun `both people see the same sixty digits`() {
        val seenByAsha = SafetyNumber.of(asha, bala)!!
        val seenByBala = SafetyNumber.of(bala, asha)!!

        assertEquals(seenByAsha, seenByBala)
        assertTrue(Regex("[0-9]{60}").matches(seenByAsha))
    }

    @Test
    fun `a different person gives a different number`() {
        assertNotEquals(SafetyNumber.of(asha, bala), SafetyNumber.of(asha, chitra))
    }

    @Test
    fun `the number does not change from one look to the next`() {
        assertEquals(SafetyNumber.of(asha, bala), SafetyNumber.of(asha, bala))
    }

    @Test
    fun `something that is not an ID has no number`() {
        assertNull(SafetyNumber.of(asha, "not an id"))
        assertNull(SafetyNumber.of("", bala))
    }

    @Test
    fun `it is shown as three lines of four groups`() {
        val text = SafetyNumber.grouped("123456789012345678901234567890123456789012345678901234567890")

        assertEquals(
            "12345 67890 12345 67890\n12345 67890 12345 67890\n12345 67890 12345 67890",
            text,
        )
    }
}
