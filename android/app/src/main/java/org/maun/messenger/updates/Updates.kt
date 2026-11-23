package org.maun.messenger.updates

/**
 * Where the update check looks. The repository is public, so this needs no
 * token of any kind: nothing secret goes into the app, and the release list is
 * read the same way by anyone.
 */
const val RELEASES_URL = "https://api.github.com/repos/Sadab-Munshi/Maun/releases/latest"

/** The one APK of a release that fits a phone, and what the app says about it. */
data class PublishedRelease(
    val versionName: String,
    /** Read from the APK's own file name: the number Android compares, not the tag. */
    val versionCode: Int,
    /** The release notes as written, in plain text. */
    val notes: String,
    val apkUrl: String,
    /** What GitHub says the file weighs. Zero when it did not say; used to tell a whole download from a cut-off one. */
    val size: Long = 0L,
)

/** What the check has found so far, and what can be done about it. */
sealed interface UpdateState {
    /** Nobody has asked, or the last answer has been put away. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** The installed Maun is the newest there is, or newer. */
    data class UpToDate(val versionName: String) : UpdateState

    data class Available(val release: PublishedRelease) : UpdateState

    /** [fraction] is null while the size of the file is still unknown. */
    data class Downloading(val release: PublishedRelease, val fraction: Float?) : UpdateState

    /** The APK is ready, usually while Android Settings is open for install-source approval. */
    data class ReadyToInstall(val release: PublishedRelease) : UpdateState

    /** Android is installing the update after the user's single Update action. */
    data class Installing(val release: PublishedRelease) : UpdateState

    /** Why the check or the download did not work, in words a person can act on. */
    data class Failed(val reason: String) : UpdateState
}

/** Whether a release's version code is newer than the one installed. */
fun isNewer(releaseCode: Int, installedCode: Int): Boolean = releaseCode > installedCode

/**
 * Reads GitHub's answer to "the latest release" and picks the one APK meant for
 * this phone. The version code comes from the APK's file name — Maun-0.24.0-29-
 * arm64-v8a.apk is version code 29 — because that is the number Android
 * compares, and the tag is typed by hand. A release with no APK of a shape this
 * phone can use gives null: there is nothing to offer.
 */
fun parseRelease(json: String, abis: List<String>): PublishedRelease? {
    val root = try {
        org.json.JSONObject(json)
    } catch (_: org.json.JSONException) {
        return null
    }
    if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
    val assets = root.optJSONArray("assets") ?: return null
    val notes = root.optString("body").trim()
    val candidates = buildList {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            val match = APK_NAME.matchEntire(name) ?: continue
            if (url.isEmpty()) continue
            val (version, code, abi) = match.destructured
            add(PublishedRelease(version, code.toIntOrNull() ?: continue, notes, url, asset.optLong("size", 0L)) to abi)
        }
    }
    if (candidates.isEmpty()) return null
    // This phone's own processor first, then one that runs on anything.
    val wanted = abis.map(String::lowercase)
    val chosen = wanted.firstNotNullOfOrNull { abi ->
        candidates.firstOrNull { (_, assetAbi) -> assetAbi == abi }?.first
    } ?: candidates.firstOrNull { (_, assetAbi) -> assetAbi == UNIVERSAL }?.first
    return chosen
}

/**
 * Where an interrupted download starts from. [have] bytes are already on disk;
 * [answered] is what the server said to the request that asked for the rest.
 * Only a 206 that starts exactly where the file stops may be appended to — a
 * plain 200 is the whole file again, and a range that starts anywhere else is
 * an answer to a different question.
 */
fun resumeFrom(have: Long, answered: Int, rangeStartsAt: Long?): Long = when {
    have <= 0 -> 0L
    answered == 206 && rangeStartsAt == have -> have
    else -> 0L
}

/** The first byte position in a `Content-Range: bytes 100-799/800` header; null when there is none. */
fun contentRangeStart(header: String?): Long? {
    val text = header?.trim() ?: return null
    if (!text.startsWith("bytes", ignoreCase = true)) return null
    val first = text.removePrefix("bytes").removePrefix(" ").substringBefore('-', "").trim()
    return first.toLongOrNull()
}

/**
 * Whether a download that has finished is whole. [expected] is the size GitHub
 * published; nothing can be said when it published none, and a file of no size
 * at all is never whole.
 */
fun isWhole(have: Long, expected: Long?): Boolean = when {
    have <= 0L -> false
    expected == null || expected <= 0L -> true
    else -> have == expected
}

/** Whether another try is worth making: [attempt] counts the failures so far. */
fun shouldTryAgain(attempt: Int, maxAttempts: Int): Boolean = attempt + 1 < maxAttempts

/** What the downloaded APK turned out to be, before it is offered to Android's installer. */
enum class InstallCheck {
    /** The same app, newer, signed by the same key: an update. */
    Ok,

    /** Not readable as an app at all. */
    NotAnApk,

    /** Another application's APK, or a renamed copy of something else. */
    WrongApp,

    /** Not newer than what is installed: nothing to gain, and a downgrade may lose data. */
    NotNewer,

    /** A different signing key. Android would refuse it, and it could be anything. */
    WrongSigner,
}

/**
 * Whether [apk] is an update of this app and nothing else. A download is a file
 * from the internet that is about to be handed to Android's installer, so it is
 * checked first: same application ID, a higher version code, and the same
 * signing certificate as the app running now — the last of which is the only
 * thing that makes an installed update trustworthy.
 */
fun checkUpdate(
    packageName: String?,
    versionCode: Int,
    signer: String?,
    expectedPackage: String,
    installedCode: Int,
    expectedSigner: String?,
): InstallCheck = when {
    packageName == null -> InstallCheck.NotAnApk
    packageName != expectedPackage -> InstallCheck.WrongApp
    signer == null || expectedSigner == null || !signer.equals(expectedSigner, ignoreCase = true) ->
        InstallCheck.WrongSigner
    versionCode <= installedCode -> InstallCheck.NotNewer
    else -> InstallCheck.Ok
}

/** maun-0.24.0-29-arm64-v8a.apk */
private val APK_NAME = Regex("""maun-(.+)-(\d+)-(arm64-v8a|armeabi-v7a|x86_64|universal)\.apk""")

private const val UNIVERSAL = "universal"
