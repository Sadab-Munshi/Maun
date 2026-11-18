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
    fun `only a higher version code is an update`() {
        assertTrue(isNewer(30, 29))
        assertFalse(isNewer(29, 29))
        // An older release on the page is not a reason to go backwards.
        assertFalse(isNewer(28, 29))
    }
}
