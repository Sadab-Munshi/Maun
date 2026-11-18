package org.maun.messenger.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the in-app update check makes of the answer GitHub gives it. */
class UpdatesTest {

    private fun release(
        tag: String = "v0.24.0",
        body: String = "Fixes and a calmer Settings.",
        assets: List<Pair<String, String>>,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ): String {
        val list = assets.joinToString(",") { (name, url) ->
            """{"name":"$name","browser_download_url":"$url"}"""
        }
        return """{"tag_name":"$tag","body":"$body","draft":$draft,"prerelease":$prerelease,"assets":[$list]}"""
    }

    private val arm64 = "maun-0.24.0-29-arm64-v8a.apk" to "https://example.org/a.apk"
    private val arm32 = "maun-0.24.0-29-armeabi-v7a.apk" to "https://example.org/b.apk"

    @Test
    fun `the APK for this phone's processor is chosen`() {
        // Nearly every phone is 64-bit, and gets the 64-bit file.
        val sixtyFour = parseRelease(release(assets = listOf(arm64, arm32)), listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("https://example.org/a.apk", sixtyFour!!.apkUrl)
        assertEquals(29, sixtyFour.versionCode)
        assertEquals("0.24.0", sixtyFour.versionName)
        assertEquals("Fixes and a calmer Settings.", sixtyFour.notes)

        // An older 32-bit phone gets the other one, and never the wrong one.
        val thirtyTwo = parseRelease(release(assets = listOf(arm64, arm32)), listOf("armeabi-v7a"))
        assertEquals("https://example.org/b.apk", thirtyTwo!!.apkUrl)
    }

    @Test
    fun `a universal APK is the last resort`() {
        val both = "maun-0.24.0-29-universal.apk" to "https://example.org/u.apk"
        val found = parseRelease(release(assets = listOf(both)), listOf("arm64-v8a"))
        assertEquals("https://example.org/u.apk", found!!.apkUrl)
    }

    @Test
    fun `the version is read from the APK's name, not from the tag`() {
        // A tag typed by hand can say anything. The number Android compares is
        // in the file name, put there by the build that made it.
        val found = parseRelease(
            release(tag = "v9.9.9", assets = listOf("maun-0.24.0-29-arm64-v8a.apk" to "https://example.org/a.apk")),
            listOf("arm64-v8a"),
        )
        assertEquals(29, found!!.versionCode)
        assertEquals("0.24.0", found.versionName)
    }

    @Test
    fun `a release with nothing to install offers nothing`() {
        assertNull(parseRelease(release(assets = emptyList()), listOf("arm64-v8a")))
        // Notes only, no APK.
        assertNull(parseRelease(release(assets = listOf("notes.txt" to "https://example.org/n.txt")), listOf("arm64-v8a")))
        // A file for a processor this phone does not have, and no universal one.
        val sideways = "maun-0.24.0-29-x86_64.apk" to "https://example.org/x.apk"
        assertNull(parseRelease(release(assets = listOf(sideways)), listOf("arm64-v8a")))
        // Not a release at all.
        assertNull(parseRelease("not json", listOf("arm64-v8a")))
    }

    @Test
    fun `a draft or a pre-release is never offered`() {
        assertNull(parseRelease(release(assets = listOf(arm64), draft = true), listOf("arm64-v8a")))
        assertNull(parseRelease(release(assets = listOf(arm64), prerelease = true), listOf("arm64-v8a")))
    }

    @Test
    fun `a download is only offered to the installer when it is this app, newer, and signed by the same key`() {
        val pkg = "org.maun.messenger"
        val signer = "35564cde8213c64b4354421dc57523e7591979a466fd5e0ea81cf4d91c5b41fd"

        assertEquals(InstallCheck.Ok, checkUpdate(pkg, 30, signer, pkg, 29, signer.uppercase()))
        // Another app's APK, however it is named.
        assertEquals(InstallCheck.WrongApp, checkUpdate("org.example.other", 30, signer, pkg, 29, signer))
        // The right app signed by somebody else: Android would refuse it, and it
        // is not something Maun should hand to the installer in the first place.
        assertEquals(InstallCheck.WrongSigner, checkUpdate(pkg, 30, "00".repeat(32), pkg, 29, signer))
        assertEquals(InstallCheck.WrongSigner, checkUpdate(pkg, 30, null, pkg, 29, signer))
        // Same version, or older: not an update.
        assertEquals(InstallCheck.NotNewer, checkUpdate(pkg, 29, signer, pkg, 29, signer))
        assertEquals(InstallCheck.NotNewer, checkUpdate(pkg, 28, signer, pkg, 29, signer))
        // Not an app at all.
        assertEquals(InstallCheck.NotAnApk, checkUpdate(null, 0, null, pkg, 29, signer))
    }

    @Test
    fun `a download that broke off carries on where it stopped`() {
        // 206 with the range that was asked for: the rest of the file, appended.
        assertEquals(1000L, resumeFrom(have = 1000, answered = 206, rangeStartsAt = 1000))
        // A plain 200 is the whole file again, whatever is on disk.
        assertEquals(0L, resumeFrom(have = 1000, answered = 200, rangeStartsAt = null))
        // A range that starts somewhere else is an answer to another question.
        assertEquals(0L, resumeFrom(have = 1000, answered = 206, rangeStartsAt = 0))
        assertEquals(0L, resumeFrom(have = 1000, answered = 206, rangeStartsAt = null))
        // Nothing on disk: nothing to carry on from.
        assertEquals(0L, resumeFrom(have = 0, answered = 200, rangeStartsAt = null))
    }

    @Test
    fun `the range header is read for what it says`() {
        assertEquals(1000L, contentRangeStart("bytes 1000-2999/3000"))
        assertEquals(0L, contentRangeStart("bytes 0-2999/3000"))
        assertNull(contentRangeStart(null))
        assertNull(contentRangeStart("items 1-2/3"))
        assertNull(contentRangeStart("bytes */3000"))
    }

    @Test
    fun `a download is only whole when it is the size GitHub published`() {
        assertTrue(isWhole(3000, 3000))
        assertFalse(isWhole(2999, 3000))
        assertTrue("GitHub said nothing: nothing to compare", isWhole(3000, null))
        assertTrue(isWhole(3000, 0))
        assertFalse("an empty file is never an update", isWhole(0, null))
    }

    @Test
    fun `a download is tried a few times, then not for ever`() {
        assertTrue(shouldTryAgain(attempt = 0, maxAttempts = 3))
        assertTrue(shouldTryAgain(attempt = 1, maxAttempts = 3))
        assertFalse(shouldTryAgain(attempt = 2, maxAttempts = 3))
        assertFalse(shouldTryAgain(attempt = 3, maxAttempts = 3))
    }

    @Test
    fun `the size of the file is read from the release`() {
        val json = """{"tag_name":"v0.24.1","body":"x","assets":[""" +
            """{"name":"maun-0.24.1-30-arm64-v8a.apk","browser_download_url":"https://example.org/a.apk","size":29806857}]}"""
        val found = parseRelease(json, listOf("arm64-v8a"))
        assertEquals(29806857L, found!!.size)
        // A release that does not say: zero, and the size check then says nothing.
        val quiet = parseRelease(release(assets = listOf(arm64)), listOf("arm64-v8a"))
        assertEquals(0L, quiet!!.size)
    }

    @Test
    fun `only a higher version code is an update`() {
        assertTrue(isNewer(30, 29))
        assertFalse(isNewer(29, 29))
        // An older release on the page is not a reason to go backwards.
        assertFalse(isNewer(28, 29))
    }
}
