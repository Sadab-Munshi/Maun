package org.maun.messenger.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFingerprintTest {

    private val phone = listOf("Google", "Pixel 8", "google/shiba/shiba:16/AB1.2/3:user/release-keys", "1080x2400@420", "9f1c2e", "2", "1|Bosch|Accel|1", "4|Bosch|Gyro|1")

    @Test
    fun `the same phone gives the same hash, in the form the relay expects`() {
        val hash = DeviceFingerprint.hash(phone)
        assertEquals(hash, DeviceFingerprint.hash(phone.toList()))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hash))
    }

    @Test
    fun `any difference gives another hash`() {
        val hash = DeviceFingerprint.hash(phone)
        for (i in phone.indices) {
            val other = phone.toMutableList().also { it[i] = it[i] + "x" }
            assertNotEquals("part $i", hash, DeviceFingerprint.hash(other))
        }
        assertNotEquals(hash, DeviceFingerprint.hash(phone.dropLast(1)))
    }

    @Test
    fun `parts cannot be shifted into each other`() {
        assertNotEquals(DeviceFingerprint.hash(listOf("ab", "c")), DeviceFingerprint.hash(listOf("a", "bc")))
        assertNotEquals(DeviceFingerprint.hash(listOf("ab")), DeviceFingerprint.hash(listOf("a", "b")))
        assertNotEquals(DeviceFingerprint.hash(listOf("")), DeviceFingerprint.hash(emptyList()))
    }

    @Test
    fun `the parts are not readable in the hash`() {
        assertTrue("Pixel" !in DeviceFingerprint.hash(phone))
    }
}
