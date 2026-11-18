package org.maun.messenger.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
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
 * Nothing is installed by Maun: the installer is Android's, with Android's
 * confirmation, and the user can cancel it there. Nothing is checked without
 * being asked either — the check runs when the reader taps it, and not before.
 */
class Updater(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http: OkHttpClient = RelayClient.defaultHttp.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
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
            } catch (_: IOException) {
                target.delete()
                _state.value = UpdateState.Failed("The download did not finish. Try again when the connection is better.")
                return@launch
            } catch (_: Exception) {
                target.delete()
                _state.value = UpdateState.Failed("The download failed. Try again.")
                return@launch
            }
            _state.value = UpdateState.ReadyToInstall(release)
        }
    }

    private suspend fun fetchTo(release: PublishedRelease, target: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(release.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Maun/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val body = response.body ?: throw IOException("No answer")
            val total = body.contentLength().takeIf { it > 0 }
            if (total != null && total > MAX_DOWNLOAD_BYTES) throw IOException("Too large")
            target.parentFile?.mkdirs()
            // Written under another name and renamed when whole, so that a
            // download cut off halfway can never be offered to the installer.
            val part = File(target.path + ".part")
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done > MAX_DOWNLOAD_BYTES) throw IOException("Too large")
                        if (total != null) {
                            val percent = ((done * 100) / total).toInt()
                            if (percent != reported) {
                                reported = percent
                                _state.value = UpdateState.Downloading(release, percent / 100f)
                            }
                        }
                    }
                }
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not keep the download")
        }
    }

    /**
     * Hands the downloaded APK to Android's installer. When this phone does not
     * yet let Maun install apps, the screen for that is opened instead, and the
     * install is offered again afterwards.
     */
    fun install(release: PublishedRelease) {
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
            openInstallPermission()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            _state.value = UpdateState.Failed("This phone has no way to install the update. Download it from the release page instead.")
            return
        }
        _state.value = UpdateState.ReadyToInstall(release)
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
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Idle
    }

    private fun updatesDirectory(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"

        /** A release APK is about 30 MB; anything of this size is not one. */
        const val MAX_DOWNLOAD_BYTES = 120L * 1024 * 1024
    }
}
