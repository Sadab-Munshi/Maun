# Play Protect and the release build

## What was reported

Installing the signed release APK (`maun-0.24.0-29-*.apk`, from the GitHub
release page) shows a Play Protect warning. Debug builds installed the same way
did not.

## What the two builds actually differ in

A real comparison of the two APKs, not a guess:

| | debug (`app-arm64-v8a-debug.apk`) | release (`maun-0.24.0-29-arm64-v8a.apk`) |
| --- | --- | --- |
| application ID | `org.maun.messenger.debug` | `org.maun.messenger` |
| signing key | `~/.android/debug.keystore`, generated per machine | the permanent release key (SHA-256 `35564CDE…C5B41FD`) |
| debuggable | yes | no |
| permissions | the same list | the same list |
| services and receivers | the same | the same |

The permission, service and receiver lists are identical; the only manifest
differences are the `.debug` package name, the FileProvider authority,
androidx-startup's initializer and the debug-only
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. So nothing was added to the release
build that could explain a warning by itself. What is different is that the
release APK is **signed with a key Google has never seen**, is **new**, and is
installed **from a file rather than from Google Play**.

## Telling the warnings apart

Play Protect shows several different messages, and they mean different things.
Google's own guidance for developers (developers.google.com/android/play-protect/warning-dev-guidance,
read 2026-11-16) lists the exact wording:

| Wording on the phone | What Google says it means | Can an appeal change it |
| --- | --- | --- |
| "Play Protect hasn't seen this app before…" / "App scan recommended" | the app is simply unknown to Play Protect | no — Google states appeals are not relevant and will not remove it |
| "This app is unknown to Play Protect… send it to Google for a security check" | same: unknown, low prevalence | no |
| "Harmful app blocked" | the app has been classified as a Potentially Harmful Application | yes, if the classification is wrong |
| "This app can request access to sensitive data…" | internet-sideloaded **and** declares `RECEIVE_SMS`, `READ_SMS`, a notification listener or an accessibility service | only by removing the cause |
| "built for an older version of Android" | `targetSdkVersion` more than two versions behind the phone | no, we target API 36 |

Maun declares **none** of the permissions in the fourth row. It has no SMS
access, no notification listener and no accessibility service — the three that
Google blocks automatically for internet-sideloaded apps. The permissions it
does declare, and why:

| Permission | Why |
| --- | --- |
| `INTERNET` | the relay |
| `ACCESS_NETWORK_STATE` | read only: to hear that a network came back, so a retry does not wait out a backoff |
| `CAMERA` | scanning another person's code, asked for when the scanner opens |
| `RECORD_AUDIO` | the voice-message button, and calls |
| `POST_NOTIFICATIONS` | saying that a message arrived |
| `VIBRATE`, `WAKE_LOCK`, `MODIFY_AUDIO_SETTINGS`, `USE_FULL_SCREEN_INTENT`, `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE(_MICROPHONE/_PHONE_CALL/_CAMERA/_REMOTE_MESSAGING)` | calls: ringing over the lock screen, the earpiece, the call continuing off screen |
| `RECEIVE_BOOT_COMPLETED` | registering the wake-up address after a restart |
| `USE_BIOMETRIC` | the optional app lock; Android does the check, Maun only learns whether it matched |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | the user asking the phone not to put Maun to sleep, from Settings |
| `REQUEST_INSTALL_PACKAGES` | "Check for updates" hands a downloaded APK to Android's own installer |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=28`) | saving a received file on Android 9 and older only |

No advertising SDK, no analytics, no dynamic code loading, `allowBackup=false`,
`usesCleartextTraffic=false`, `debuggable=false`, one ABI per APK, target SDK 36.

## The permission that carries the most weight

`REQUEST_INSTALL_PACKAGES` is the one permission here that a scanner weighs
heavily, because an app that can install apps is how one sideloaded APK becomes
five. Google's Play policy restricts it, and for apps distributed on Play a
self-updater is not an accepted use. Maun is not on Play, and "Check for
updates" is the only thing the permission is for: the user taps it, Maun
downloads the APK from the project's own GitHub release, and Android asks. Maun
never installs anything itself and there is no silent update.

That said, the risk it represents is real, and it is now bounded in code: before
anything is handed to the installer the downloaded file is read as an app and
must be **this** application ID, a **higher** version code, and **signed by the
same certificate** as the Maun that is running (`updates/Updates.kt`,
`checkUpdate`; `updates/Updater.kt`, `checkDownload`). A corrupted, substituted
or unrelated APK is refused with a message and never reaches the installer.

If the warning turns out to be the install-packages heuristic, the remaining
legitimate option is to drop the permission and hand "Check for updates" to the
browser: the same download, installed by the user from the phone's own file
manager. That is a product decision, not a security fix, and it makes the
update flow worse for the user, so it is not done here.

## What is still needed from the phone

The message on the screen decides which of the two situations this is, and it
has to be read off the device. Record, for the report:

- the exact wording, and whether the button offered is **Install anyway**,
  **Scan app** or **OK** (see the table above);
- the phone model and Android version;
- whether the APK was opened from the browser's download, from a file manager,
  or from Maun's own "Check for updates";
- whether the same APK on a second phone shows the same message.

With that answer:

- **unknown-app wording** → an appeal is not the mechanism and would change
  nothing. What changes it is prevalence and reputation: the same key signing
  every future release, more installs, and — where the project wants it — a
  closed testing track on Play with the same package name, which is how an
  unfamiliar key becomes a known one.
- **"Harmful app blocked"** → review the checklist above, then appeal with the
  file. Google asks for the APK or a link to it, the package name and a
  description:
  <https://support.google.com/googleplay/android-developer/contact/protectappeals>

## Verifying a download before installing it

Every release publishes both APKs. These are the checksums of the two files
attached to the newest release, taken from the published assets:

```
29dbe9f10a41833be78b835c7d26813f640bdf9fa9fb3821a13021a3a1f724f0  maun-0.24.1-30-arm64-v8a.apk
fb832c2e749550ac5d43d358a7b0b1d61e5344212c51b1c19c9652eb5d46863d  maun-0.24.1-30-armeabi-v7a.apk
```

(0.24.0, still installable from its own release page:
`2119e617…2c470cc8` for `maun-0.24.0-29-arm64-v8a.apk` and `b7bd40a6…a32d8ce`
for `-armeabi-v7a`.)

On a phone, a file manager that shows a checksum, or `sha256sum` on a computer,
confirms the file is the one the project published. The signing certificate is
the same for every release:

```
35564CDE8213C64B4354421DC57523E7591979A466FD5E0EA81CF4D91C5B41FD
```

`apksigner verify --print-certs maun-<version>-<code>-<abi>.apk` prints it.

## What will not be done

- Telling users to turn Play Protect off, or to tap through an "unsafe" screen
  as the documented way to install Maun.
- Repackaging, re-signing or renaming the APK to dodge a scan.
- Adding an exclusion or a "trust this app" instruction.
- Claiming the warning is a false positive before its wording has been read.

A warning is information for the user. Whatever it turns out to be, the answer
is to make the app verifiably what it says it is, and to have Google's
classification corrected if it is wrong — not to make the warning invisible.
