# Releasing Maun

Everything about putting a new Maun into people's hands: the key it is signed
with, the workflow that builds it, the number in its name, and what an update
does to the data already on the phone.

## The identity of the app

| Thing | Value | May it ever change? |
| --- | --- | --- |
| Application ID | `org.maun.messenger` | **No.** A different one installs as a second app. |
| Signing certificate (SHA-256) | `35:56:4C:DE:82:13:C6:4B:43:54:42:1D:C5:75:23:E7:59:19:79:A4:66:FD:5E:0E:A8:1C:F4:D9:1C:5B:41:FD` | **No.** A different key cannot install over an existing copy. |
| Key algorithm and size | RSA 4096, SHA384withRSA, PKCS12 keystore | — |
| Valid until | 2056-11-10 | Beyond Android's 2033 requirement. |
| Debug build | `org.maun.messenger.debug`, signed with the committed debug key | A separate app, side by side. |

The release workflow checks both the first two on every run and fails if either
has moved: `EXPECTED_CERT_SHA256` in `.github/workflows/release.yml`, and the
`package:` line from `aapt2 dump badging`.

## Where the key lives

Never in the repository, and never in an APK. The keystore is base64 in the
repository secret `MAUN_KEYSTORE_BASE64`; the passwords are
`MAUN_KEYSTORE_PASSWORD` and `MAUN_KEY_PASSWORD`; the alias is `MAUN_KEY_ALIAS`
(`maun`). The workflow writes the keystore to a temporary file for the length of
one job and hands the build four environment variables:
`MAUN_KEYSTORE_FILE`, `MAUN_KEYSTORE_PASSWORD`, `MAUN_KEY_ALIAS`,
`MAUN_KEY_PASSWORD`.

`android/app/build.gradle.kts` signs the release APK with them when they are
present, and builds it unsigned when they are not — which is a compile check,
not a release. Losing the keystore means every installed copy of Maun has to be
removed before a new build will install, and every chat is gone with it. Keep a
copy of the file and its password somewhere that is not a phone.

## Making a release

1. Raise `versionCode` (always — Android refuses an equal or lower one) and
   `versionName` in `android/app/build.gradle.kts`.
2. Push to `main`. The **Release** workflow runs on any change under `android/`:
   unit tests, lint, signed release APK, signature and size checks, and the APK
   as an artifact called `maun-release-apk`. It publishes nothing.
3. Look at the checks: the certificate digest must be the one above, the size
   must be under 40 MB (a release APK is about 30 MB), and the tests must be
   green.
4. When it is right, run **Release → Run workflow** on `main` with `publish`
   ticked. That creates the GitHub Release `v<versionName>` with the APKs
   attached and marks it the latest.
5. Write release notes in the release body: they are what the in-app update
   check shows.

The APKs are named `maun-<versionName>-<versionCode>-<abi>.apk`, for example
`maun-0.25.0-30-arm64-v8a.apk`. `arm64-v8a` is nearly every phone made since
2016; `armeabi-v7a` is the 32-bit fallback. The build produces one file per
architecture so nobody downloads a copy of both native libraries.

## Checking for updates inside the app

Settings → About → **Check for updates**. It reads
`https://api.github.com/repos/Sadab-Munshi/Maun/releases/latest` — a public
endpoint, so the app carries no token of any kind and there is nothing to leak —
picks the APK whose name matches this phone's `Build.SUPPORTED_ABIS`, and
compares that APK's version code with `BuildConfig.VERSION_CODE`.

- The version code comes from the APK's file name, not the release tag: a tag
  can be typed wrongly, the file name is written by the build that made the APK.
- Same version or older: "You're up to date."
- Newer: the version, the release notes, and an **Update** button.
- The download goes to the app's cache, under a temporary name, and is renamed
  only when it is whole; a half-downloaded file is never offered.
- Installing is Android's own installer, with Android's confirmation, and the
  user can cancel there. Maun never installs silently, and a cancelled install
  leaves the download in place so it can be tried again.
- A phone that does not yet allow Maun to install apps is sent to that setting
  first.
- No connection, a refused connection, a 404, a draft or a pre-release: each
  says so in a line the reader can act on.

The check runs only when it is tapped. Nothing is fetched in the background, and
nothing asks GitHub anything on a schedule.

## What an update keeps

An update installed over the same application ID with the same certificate
keeps everything: the SQLCipher database and its message history, the identity
and encryption keys in the Android Keystore, contacts, settings, downloaded
media, and the login session. Nothing in the app logs in again or re-registers.

That relies on the two invariants above. It also relies on the database
migrations: every schema version's `android/app/schemas/…/<version>.json` is
committed, and Room's migration is written and checked in CI before the build
is allowed to pass.

### Debug to release is not seamless

The debug APK is a different app: its ID ends in `.debug`, and it is signed with
the public debug key. A release APK cannot install over it, and its data — chats,
keys, session — belongs to the debug app and does not follow.

- Nothing uninstalls the debug app automatically, and nothing should.
- Installing the release alongside it is safe: two apps, two sets of data.
- Moving to the release means starting a new Maun ID there, with a new safety
  number for contacts. Copy anything worth keeping out of the debug app first.
- This is the same reason the debug key is committed and the release key is not:
  the debug app is for testing, the release app is for keeping.

## What a device test should check

None of this can be established on a build machine:

| Test | What to look for |
| --- | --- |
| Tap a notification, app closed | Maun opens on that conversation, at the newest message |
| Tap a notification, app open in another chat | It switches to that conversation, at the newest message |
| Tap a notification, app in the background | Same, from the recent-apps list |
| Notification covering several chats | The list of conversations opens, not one of them |
| Scroll back in a chat, then tap its notification | The chat opens at the bottom again |
| Settings → About → Check for updates, with a newer release published | Version, notes, Update button |
| …with the newest release published | "You're up to date" |
| …in aeroplane mode | A line saying it could not reach GitHub |
| Update, then cancel at Android's prompt | No change; the download stays, Install can be tapped again |
| Update, then confirm | The app restarts with the same chats, contacts, settings and login |
| Old release installed, new release installed over it | Everything above survives; no login |
