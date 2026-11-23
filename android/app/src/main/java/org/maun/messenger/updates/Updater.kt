package org.maun.messenger.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.maun.messenger.BuildConfig
import org.maun.messenger.network.RelayClient

/**
 * Asks GitHub for the newest release of Maun and, when it is newer than the one
 * installed, downloads the APK for this phone and hands it to Android's own
 * installer.
 *
 * Maun checks only after the person asks. The APK is verified against Maun's
 * package, version and signing certificate, then Android's PackageInstaller
 * installs it. On supported Android versions it can complete the self-update
 * without a second confirmation; Android can still require user action.
 */
class Updater(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    @Volatile private var waitingForInstallPermission = false

    /**
     * For the small API call: a minute is generous for one JSON answer.
     */
    private val http: OkHttpClient = RelayClient.defaultHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * For the APK itself, which is 28 MB: no call timeout at all. A call
     * timeout covers reading the body as well, so the one above ended every
     * download that took longer than a minute — which on a phone network is an
     * ordinary download of this size, not a broken one. The read timeout still
     * catches a connection that has genuinely gone quiet.
     */
    private val files: OkHttpClient = RelayClient.defaultHttp.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Asks whether there is a newer Maun than this one. */
    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        scope.launch {
            val installed = BuildConfig.VERSION_CODE
            val found = try {
                fetchRelease()
            } catch (_: IOException) {
                _state.value = UpdateState.Failed("Could not reach GitHub. Check the connection and try again.")
                return@launch
            } catch (_: Exception) {
                _state.value = UpdateState.Failed("The update check did not answer. Try again in a moment.")
                return@launch
            }
            if (found == null) {
                _state.value = UpdateState.Failed("No released version of Maun was found.")
                return@launch
            }
            _state.value = if (isNewer(found.versionCode, installed)) {
                UpdateState.Available(found)
            } else {
                UpdateState.UpToDate(BuildConfig.VERSION_NAME)
            }
        }
    }

    private fun fetchRelease(): PublishedRelease? {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Maun/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub answered ${response.code}")
            }
            val body = response.body?.string() ?: return null
            return parseRelease(body, Build.SUPPORTED_ABIS.toList())
        }
    }

    /** Fetches the APK of [release], and leaves it ready for the installer. */
    fun download(release: PublishedRelease) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(release, null)
        scope.launch {
            val target = File(updatesDirectory(), "maun-${release.versionCode}.apk")
            try {
                fetchTo(release, target)
            } catch (e: IOException) {
                target.delete()
                _state.value = UpdateState.Failed(downloadFailure(e))
                return@launch
            } catch (_: Exception) {
                target.delete()
                _state.value = UpdateState.Failed("The download failed. Try again.")
                return@launch
            }
            install(release)
        }
    }

    /**
     * Downloads the APK, carrying on from what is already on disk when an
     * earlier try was cut off. The file is written under another name and only
     * renamed when it is whole and the size is the one GitHub published, so
     * half a download can never be offered to the installer; when a try fails
     * the part that arrived stays, and the next one begins there.
     *
     * A few tries, each after a short wait, because a phone that changes from
     * Wi-Fi to mobile data in the middle of 28 MB is ordinary and should not
     * cost the whole download.
     */
    private suspend fun fetchTo(release: PublishedRelease, target: File) = withContext(Dispatchers.IO) {
        val part = File(target.path + ".part")
        var attempt = 0
        var failure: IOException? = null
        while (true) {
            try {
                val wrote = oneAttempt(release, part)
                val length = part.length()
                if (!wrote || !isWhole(length, release.size)) {
                    throw IOException("The download stopped at $length of ${release.size} bytes")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Could not keep the download")
                Log.i(TAG, "stage=download-whole bytes=$length attempt=$attempt")
                return@withContext
            } catch (e: IOException) {
                // Only what is worth another try gets one: a file that is not
                // an APK, a phone with no room, or a refusal from GitHub will
                // not answer differently in a moment.
                failure = e
                Log.w(TAG, "stage=download-failed attempt=$attempt have=${part.length()} error=$e")
                val noRoom = e.message.orEmpty().contains("ENOSPC")
                if (e is PermanentFailure || noRoom || !shouldTryAgain(attempt, MAX_ATTEMPTS)) break
                attempt++
                delay(RETRY_WAIT_MILLIS * attempt)
            }
        }
        throw failure ?: IOException("The download failed")
    }

    /**
     * One attempt, appended to whatever is already in [part]. Returns whether
     * the body ended where the size of the file says it should; the caller
     * checks that against the size GitHub published.
     */
    private fun oneAttempt(release: PublishedRelease, part: File): Boolean {
        val have = part.length()
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Maun/${BuildConfig.VERSION_NAME}")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        files.newCall(request).execute().use { response ->
            val code = response.code
            // 416: the file on disk is already as long as the one on the
            // other end, or longer. Whatever it is, it is not the rest of it.
            if (code == 416) {
                part.delete()
                throw IOException("GitHub answered 416; the download starts again")
            }
            if (code in 400..499) throw PermanentFailure("GitHub answered $code")
            if (!response.isSuccessful) throw IOException("GitHub answered $code")
            val from = resumeFrom(have, code, contentRangeStart(response.header("Content-Range")))
            val body = response.body ?: throw IOException("No answer")
            val bodyLength = body.contentLength().takeIf { it > 0 }
            val total = when {
                from > 0 && bodyLength != null -> from + bodyLength
                from == 0L -> bodyLength
                else -> null
            }
            if (total != null && total > MAX_DOWNLOAD_BYTES) {
                part.delete()
                throw PermanentFailure("That file is $total bytes, which is not an APK of Maun's")
            }
            part.parentFile?.mkdirs()
            Log.i(TAG, "stage=download-start from=$from total=$total http=$code")
            var done = from
            body.byteStream().use { input ->
                FileOutputStream(part, from > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var reported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done > MAX_DOWNLOAD_BYTES) {
                            part.delete()
                            throw PermanentFailure("That file is larger than any Maun APK")
                        }
                        val size = total
                        if (size != null) {
                            val percent = ((done * 100) / size).toInt()
                            if (percent != reported) {
                                reported = percent
                                _state.value = UpdateState.Downloading(release, percent / 100f)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            return total == null || done == total
        }
    }

    /** A failure that another try will not change: a refusal, a file that is not Maun's, a full phone. */
    private class PermanentFailure(message: String) : IOException(message)

    /**
     * Why the download did not work, in words that say what to do about it.
     * The part that arrived is kept, so trying again carries on rather than
     * starting over.
     */
    private fun downloadFailure(e: IOException): String {
        val text = e.message.orEmpty()
        return when {
            text.contains("ENOSPC") || text.contains("space", ignoreCase = true) ->
                "There is no room on this phone for the update. Free some space and try again."
            text.contains("not an APK of Maun's") || text.contains("larger than any Maun APK") ->
                "The file GitHub offered is not an APK of Maun's. Nothing was downloaded."
            text.matches(Regex("GitHub answered [45]\\d\\d.*")) ->
                "GitHub would not give the file ($text). Try again later."
            else ->
                "The download stopped partway ($text). Trying again carries on from where it stopped."
        }
    }

    /** Installs a verified download after the user has chosen Update. */
    fun install(release: PublishedRelease) {
        if (_state.value is UpdateState.Installing) return
        _state.value = UpdateState.Installing(release)
        scope.launch { installDownloaded(release) }
    }

    private fun installDownloaded(release: PublishedRelease) {
        val file = File(updatesDirectory(), "maun-${release.versionCode}.apk")
        if (!file.isFile || file.length() == 0L) {
            _state.value = UpdateState.Failed("The download is gone. Download it again.")
            return
        }
        when (checkDownload(file)) {
            InstallCheck.Ok -> Unit
            InstallCheck.NotAnApk -> {
                _state.value = UpdateState.Failed("The download is not a usable app. Try again.")
                file.delete()
                return
            }
            InstallCheck.WrongApp -> {
                _state.value = UpdateState.Failed("That file is not Maun. Nothing was installed.")
                file.delete()
                return
            }
            InstallCheck.WrongSigner -> {
                _state.value = UpdateState.Failed(
                    "That file is not signed with the key this copy of Maun was installed with, so it was " +
                        "not offered to Android. Download the update from Maun's own release page.",
                )
                return
            }
            InstallCheck.NotNewer -> {
                _state.value = UpdateState.Failed("That download is not newer than the Maun on this phone.")
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = true
            _state.value = UpdateState.ReadyToInstall(release)
            openInstallPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installWithSession(file, release)
        } else {
            installWithSystemInstaller(file, release)
        }
    }

    private fun installWithSession(file: File, release: PublishedRelease) {
        val installer = context.packageManager.packageInstaller
        var sessionId: Int? = null
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0L, file.length()).use { output ->
                    FileInputStream(file).use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val statusIntent = Intent(context, InstallStatusReceiver::class.java)
                    .setAction(InstallStatusReceiver.ACTION)
                val statusPendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(statusPendingIntent.intentSender)
            }
            waitingForInstallPermission = false
            _state.value = UpdateState.Installing(release)
        } catch (e: Exception) {
            sessionId?.let { runCatching { installer.abandonSession(it) } }
            Log.w(TAG, "Could not start the update install session", e)
            _state.value = UpdateState.Failed("Android could not start the update. Try again.")
        }
    }

    /** Older Android versions still use Android's installer UI; they cannot skip its confirmation. */
    private fun installWithSystemInstaller(file: File, release: PublishedRelease) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            waitingForInstallPermission = false
            _state.value = UpdateState.ReadyToInstall(release)
        } catch (_: Exception) {
            _state.value = UpdateState.Failed("This phone has no way to install the update. Download it from the release page instead.")
        }
    }

    /** Continue automatically after the person grants Maun install access in Android Settings. */
    fun resumePendingInstall() {
        val error = context.getSharedPreferences(UPDATE_INSTALL_PREFS, Context.MODE_PRIVATE)
            .getString(UPDATE_INSTALL_ERROR, null)
        if (error != null) {
            context.getSharedPreferences(UPDATE_INSTALL_PREFS, Context.MODE_PRIVATE).edit().remove(UPDATE_INSTALL_ERROR).apply()
            _state.value = UpdateState.Failed(error)
            return
        }
        if (!waitingForInstallPermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val release = (_state.value as? UpdateState.ReadyToInstall)?.release ?: return
        if (context.packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = false
            install(release)
        }
    }

    /**
     * Reads the downloaded file as an app and compares it with the one running:
     * the same application ID, a higher version code, and the same signing
     * certificate. Nothing is handed to Android's installer that has not passed
     * this, so a corrupted or substituted download cannot be installed, and the
     * only APKs this ever offers are Maun's own.
     */
    private fun checkDownload(file: File): InstallCheck {
        val packages = context.packageManager
        val own = try {
            packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: Exception) {
            null
        } ?: return InstallCheck.NotAnApk
        val archive = try {
            packages.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: Exception) {
            null
        }
        return checkUpdate(
            packageName = archive?.packageName,
            versionCode = archive?.longVersionCode?.toInt() ?: 0,
            signer = archive?.signingInfo?.apkContentsSigners?.firstOrNull()?.let(::digest),
            expectedPackage = own.packageName,
            installedCode = own.longVersionCode.toInt(),
            expectedSigner = own.signingInfo?.apkContentsSigners?.firstOrNull()?.let(::digest),
        )
    }

    private fun digest(signature: android.content.pm.Signature): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Some phones keep this elsewhere; the Settings app is the way in.
            _state.value = UpdateState.Failed("Allow Maun to install apps in this phone's settings, then tap Update again.")
        }
    }

    /** Puts the answer away, so Settings goes back to offering the check. */
    fun dismiss() {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        _state.value = UpdateState.Idle
    }

    private fun updatesDirectory(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private companion object {
        const val TAG = "MaunUpdate"
        const val APK_MIME = "application/vnd.android.package-archive"

        /** A release APK is about 30 MB; anything of this size is not one. */
        const val MAX_DOWNLOAD_BYTES = 120L * 1024 * 1024

        /** How many times a download that broke off is picked up again, from where it stopped. */
        const val MAX_ATTEMPTS = 3

        /** Longer after each failure: the second try waits this long, the third twice as long. */
        const val RETRY_WAIT_MILLIS = 2_000L
    }
}
