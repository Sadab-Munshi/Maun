# If Play Protect blocks the release APK: what to do

This is the follow-up sheet to `docs/PLAY_PROTECT.md`. It is for the one thing
code cannot do: asking Google to correct a classification, and recording what
the phone actually said.

## First: which file is on the phone

The two workflows leave two different APKs behind, and they are not
interchangeable:

| Artifact | Contains | Package id | Signed with |
| --- | --- | --- | --- |
| `maun-release-apk` (Release workflow) | `maun-0.24.0-29-arm64-v8a.apk`, `…-armeabi-v7a.apk` | `org.maun.messenger` | the permanent release key |
| `maun-debug-apk` (Android workflow) | `app-arm64-v8a-debug.apk`, `app-armeabi-v7a-debug.apk` | `org.maun.messenger.debug` | a debug key made on the build machine |

Both artifacts are **zip files**. The `.apk` has to be extracted first; handing
Android a `.zip` fails with a parse/verification error that looks like a Play
Protect failure but is not one.

A debug APK is a different application, signed with a throwaway key, and it is
not the build that is published. If the warning came from `maun-debug-apk`, the
file being tested is not the release at all.

To tell what a file is, on a computer:

```
apksigner verify --print-certs maun-0.24.0-29-arm64-v8a.apk   # the certificate
sha256sum maun-0.24.0-29-arm64-v8a.apk                        # compare with PLAY_PROTECT.md
unzip -l maun-release-apk.zip                                 # what is inside the artifact
```

On the phone, Settings → Apps → Maun shows the version; a debug install appears
as a separate entry, Maun (debug).

A release-workflow APK built from a commit after the newest release still
carries that release's `versionCode`, because the version is only raised when a
release is made. It is a change in content, not in version, so nothing on the
phone can tell it apart from the published build — and Play Protect will treat
it exactly the same way. That is expected, and it is why a fresh artifact does
not change the warning.

## Second: read the message

Record, before touching anything:

- the exact sentence, and the buttons offered (**Install anyway**, **Scan
  app**, or **OK**);
- phone model and Android version;
- whether the APK was opened from the browser's downloads, a file manager, or
  Maun's own "Check for updates";
- whether the same APK behaves the same way on a second phone.

`docs/PLAY_PROTECT.md` has the table that maps each wording to what Google says
it means and whether an appeal can change it. In short: **unknown-app** wording
is not appealable; **harmful app blocked** is.

## Third: the appeal, if it is one

The form is Google's, it needs a Google account, and only the developer can
submit it: <https://support.google.com/googleplay/android-developer/contact/protectappeals>
(reached from the "File a Play Protect appeal" button in Google's developer
guidance). Google does not commit to a time.

Facts to paste, all of which are true of this repository:

```
App name:      Maun
Package name:  org.maun.messenger
Version:       0.24.1 (versionCode 30), targetSdkVersion 36, minSdkVersion 29
Binary:        https://github.com/Sadab-Munshi/Maun/releases/download/v0.24.1/maun-0.24.1-30-arm64-v8a.apk
               sha256 29dbe9f10a41833be78b835c7d26813f640bdf9fa9fb3821a13021a3a1f724f0
Source:        https://github.com/Sadab-Munshi/Maun
Signing cert  35564CDE8213C64B4354421DC57523E7591979A466FD5E0EA81CF4D91C5B41FD
              (the same key for every release; apksigner verify --print-certs)
On Google Play: no
```

A description that states the facts without arguing:

> Maun is an end-to-end encrypted messenger, distributed by its author from
> GitHub releases; it is not on Google Play. Users install it from the project's
> release page. The app requests no SMS, notification-listener or accessibility
> permission. It has no advertising or analytics SDK, no dynamic code loading,
> `allowBackup=false` and `usesCleartextTraffic=false`, and it is not debuggable.
> It declares `REQUEST_INSTALL_PACKAGES` for one purpose only: the in-app "Check
> for updates" button downloads the APK from the project's own GitHub release
> and hands it to Android's installer, which asks the user. The app never
> installs anything itself. Before that hand-off the downloaded file is verified
> to be the same package id, a higher version code, and the same signing
> certificate as the running app. The APK attached to this appeal is the exact
> file published, and its signing certificate is unchanged since the first
> release.

Attach the APK or give the release URL, and say which phone showed which
message.

## What will change the answer, and what will not

| Lever | Effect | Cost |
| --- | --- | --- |
| Same key for every release (already the case) | the certificate becomes familiar over time; a key that changes looks new again | none |
| More installs, and a link from somewhere Google's crawler can see | prevalence is one of the inputs | time |
| A closed testing track on Play with the **same** package id and the **same** key | the strongest lever: the package stops being unknown | a Play developer account, policy compliance review, and Play's rules apply from then on |
| Dropping `REQUEST_INSTALL_PACKAGES` and letting the browser do the download | removes the permission a scanner weighs most heavily for a sideloaded app | a worse update flow: the user leaves Maun to download and install |
| Repackaging, re-signing, obfuscating to change the file's fingerprint | none that lasts, and it is the behaviour scanners exist to catch | it is not done here |
| Telling users to switch Play Protect off | it works for one install and removes a protection layer for everything else they do | never done here |
