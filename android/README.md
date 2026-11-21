# The app

Kotlin and Jetpack Compose, one module (`app`), Android 10 and newer. Signal
protocol sessions come from libsignal; calls are WebRTC through Cloudflare's
TURN service; the database is encrypted on the phone.

How the app fits together, and what each package is for, is
[../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md); what is stored and how it
migrates is [../docs/DATABASE.md](../docs/DATABASE.md); what is tested and what
needs a handset is [../docs/TESTING.md](../docs/TESTING.md).

**Build and test this in GitHub Actions, not here.** The Android workflow runs
the unit tests, lint, the debug APK, the size check and the Room schema check on
every change; the Release workflow builds the signed APK. Pushing a branch and
reading the run is the build. See [../AGENTS.md](../AGENTS.md).

## What is where

| Path | What lives there |
|---|---|
| `app/src/main/java/org/maun/messenger/ChatEngine.kt`, `Messenger.kt` | The centre: accounts, sessions, sending, receiving, receipts, notification decisions |
| `.../crypto/` | Identity, device key, Signal sessions, file cipher, safety numbers, recovery |
| `.../network/` | The relay connection, HTTP APIs, push addresses, backoff |
| `.../push/` | Being woken: the push service, fetch work, notifications, "stay connected" |
| `.../webrtc/` | Calls: setup, audio routing, the ringing service, Telecom hand-off |
| `.../ui/` | Every screen and view model |
| `.../security/`, `.../voice/`, `.../updates/` | App lock and fingerprints; recording and playing; the update check |
| `app/src/test/java/...` | Robolectric and plain unit tests, in packages that mirror the above |
| `app/schemas/org.maun.messenger.data.AppDatabase/` | Room schema JSON, committed (versions 2 to 7); upgrades are generated from it |
| `debug.keystore` | Signs debug builds only, on purpose, so one installs over the last. Protects nothing. |

## Gradle tasks

```sh
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:lintDebug            # lint; errors fail the build
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # signed only when the key is provided
```

The connection and messaging tests need a relay: start
`../backend/scripts/test-relay.sh`, then run with
`MAUN_TEST_RELAY_URL=http://127.0.0.1:8787`. Skipped without it — which is why
CI starts one first and then checks that the tests really ran.

To look at the screens without a phone:
`./gradlew :app:testDebugUnitTest -Pmaun.screens=true`, pictures in
`app/build/screens`. The same thing runs in the **Screens** workflow.

## Versions and releases

`versionCode` and `versionName` in `app/build.gradle.kts` are the only version.
`versionCode` must go up for phones to accept an update; the in-app check reads
the repository's latest published release and compares against it.

Release signing uses `MAUN_KEYSTORE_FILE`, `MAUN_KEYSTORE_PASSWORD`,
`MAUN_KEY_ALIAS` and `MAUN_KEY_PASSWORD` from GitHub secrets, and the workflow
refuses an APK signed with any certificate but Maun's permanent one. Every
installed copy depends on that certificate staying the same. Nothing here can
produce an APK that installs over an existing one.

The relay address is baked in at build time: `-Pmaun.relayUrl=https://...` or
`MAUN_RELAY_URL`, and an empty value builds an app that asks for one.
