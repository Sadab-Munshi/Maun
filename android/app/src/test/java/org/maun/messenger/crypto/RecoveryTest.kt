package org.maun.messenger.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The sealed identity backup: what it is for, and what it refuses.
 *
 * These run on the plain JVM: the derivation and the cipher are the platform's,
 * and nothing here touches the relay. What they cannot show is the whole
 * journey — a phone that lost its keys, a passphrase typed on another phone,
 * the account coming back — which needs two phones; see docs/RECOVERY.md.
 */
class RecoveryTest {

    private val identity = ByteArray(64) { (it * 7).toByte() }
    private val contents = Recovery.Contents(
        maunId = "ab12".repeat(16),
        identity = identity,
        registrationId = 4321,
        avatar = "avatar-record-with-its-key",
    )

    @Test
    fun opensWhatItSealed() {
        val passphrase = "correct horse battery".toCharArray()
        val sealed = Recovery.seal(passphrase, contents)
        val opened = Recovery.open(passphrase, sealed.salt, sealed.blob, contents.maunId)
        assertEquals(contents.maunId, opened.maunId)
        assertEquals(contents.registrationId, opened.registrationId)
        assertEquals(contents.avatar, opened.avatar)
        assertArrayEquals(contents.identity, opened.identity)
    }

    @Test
    fun doesNotWriteTheKeysDownAnywhere() {
        val passphrase = "correct horse battery".toCharArray()
        val sealed = Recovery.seal(passphrase, contents)
        val text = String(sealed.blob, Charsets.ISO_8859_1)
        // The identity key pair is nowhere in the ciphertext, and neither is the ID.
        assertFalse(text.contains(String(identity, Charsets.ISO_8859_1)))
        assertFalse(text.contains(contents.maunId))
        assertFalse(text.contains(contents.avatar))
        // Nor is it the same bytes twice for the same input.
        val again = Recovery.seal(passphrase, contents)
        assertNotEquals(sealed.blob.toList(), again.blob.toList())
        assertNotEquals(sealed.salt.toList(), again.salt.toList())
    }

    @Test
    fun refusesTheWrongPassphrase() {
        val sealed = Recovery.seal("correct horse battery".toCharArray(), contents)
        try {
            Recovery.open("correct horse batteru".toCharArray(), sealed.salt, sealed.blob, contents.maunId)
            fail("the wrong passphrase opened the backup")
        } catch (_: Recovery.WrongPassphrase) {
            // What is wanted: no way to tell a wrong passphrase from a damaged blob.
        }
    }

    @Test
    fun refusesABlobThatWasChanged() {
        val passphrase = "correct horse battery".toCharArray()
        val sealed = Recovery.seal(passphrase, contents)
        for (byte in listOf(0, 12, sealed.blob.size - 1)) {
            val changed = sealed.blob.copyOf()
            changed[byte] = (changed[byte].toInt() xor 0x01).toByte()
            try {
                Recovery.open(passphrase, sealed.salt, changed, contents.maunId)
                fail("a changed backup opened")
            } catch (_: Recovery.WrongPassphrase) {
                // A tag that does not check out is a backup that is not trusted.
            }
        }
        // A salt that was swapped out does not open it either.
        val otherSalt = Recovery.seal(passphrase, contents).salt
        try {
            Recovery.open(passphrase, otherSalt, sealed.blob, contents.maunId)
            fail("a backup opened with the wrong salt")
        } catch (_: Recovery.WrongPassphrase) {
        }
    }

    @Test
    fun refusesABackupThatBelongsToAnotherAccount() {
        // It opens, and it is somebody else's: installing it would put this
        // phone on an identity that is not the account it logged in to.
        val passphrase = "correct horse battery".toCharArray()
        val sealed = Recovery.seal(passphrase, contents)
        try {
            Recovery.open(passphrase, sealed.salt, sealed.blob, "cd34".repeat(16))
            fail("somebody else's backup was accepted")
        } catch (_: Recovery.NotMine) {
        }
    }

    @Test
    fun refusesABlobThatIsNotOne() {
        val passphrase = "correct horse battery".toCharArray()
        for (blob in listOf(ByteArray(0), ByteArray(12), ByteArray(100) { 42 })) {
            try {
                Recovery.open(passphrase, ByteArray(16), blob, contents.maunId)
                fail("a blob that is not a backup opened")
            } catch (_: Recovery.WrongPassphrase) {
            }
        }
    }

    @Test
    fun asksForAPassphraseWorthDerivingAKeyFrom() {
        assertFalse(Recovery.isStrongEnough("short".toCharArray()))
        assertFalse(Recovery.isStrongEnough("eleven char".toCharArray()))
        assertTrue(Recovery.isStrongEnough("twelve chars".toCharArray()))
    }
}
