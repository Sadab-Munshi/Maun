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

    /** Downloaded and handed to Android's installer, which asks the user itself. */
    data class ReadyToInstall(val release: PublishedRelease) : UpdateState

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
            add(PublishedRelease(version, code.toIntOrNull() ?: continue, notes, url) to abi)
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
