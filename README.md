# Maun

A private one-to-one messenger for Android with zero tracking: no ads, no
analytics, no phone number, and no chat history on any server.

- What it is and is not: [PROJECT.md](PROJECT.md)
- Original technical spec: [TECHNICAL.md](TECHNICAL.md)
- Build plan, decisions and progress: [PLAN.md](PLAN.md)

## Status: Milestone 0

There is no messaging yet. This milestone proves the pieces everything else
stands on:

| Piece | Where |
|---|---|
| Relay: Cloudflare Worker and a hibernating WebSocket mailbox | `backend/` |
| Signature checks for identity keys and device keys | `backend/src/crypto/` |
| Android app with a setup-check screen | `android/` |
| CI: test and deploy the relay, test and build the APK | `.github/workflows/` |

The app's single screen runs four checks on the phone: the hardware-bound
device key, the encrypted database, Signal encryption, and a round trip to
your relay.

## Try it

### 1. Deploy the relay

You need a free Cloudflare account and Node.js 22.12 or newer.

```sh
cd backend
npm ci
npx wrangler login
npx wrangler deploy
```

The last command prints the relay's address, like
`https://maun-relay.<your-subdomain>.workers.dev`. Opening
`<address>/health` in a browser should show `{"ok":true,...}`.

### 2. Install the app

Build it (see below) or download `maun-debug-apk` from the latest run of the
**Android** workflow on GitHub, then install the APK on a phone running
Android 10 or newer. Android will ask you to allow installing from this source.

### 3. Run the setup check

Open **Maun (debug)**, type the relay address, and tap **Run checks**. All four
rows should turn green. A yellow device-key row is expected on an emulator.

## Continuous integration

Push this repository to GitHub, then in **Settings > Secrets and variables >
Actions** add:

| Kind | Name | Value |
|---|---|---|
| Secret | `CLOUDFLARE_API_TOKEN` | A token made from Cloudflare's "Edit Cloudflare Workers" template |
| Secret | `CLOUDFLARE_ACCOUNT_ID` | Your Cloudflare account ID |
| Variable | `MAUN_RELAY_URL` | The relay address from step 1 (optional; pre-fills the app) |

Every push to `main` then tests and deploys the relay, and builds a debug APK.
Without the secrets, the tests still run and the deploy step is skipped.

## Development

### Relay

```sh
cd backend
npm ci
npm test            # runs inside Cloudflare's local runtime
npm run typecheck
npm run bench       # CPU cost of the signature checks
npm run dev         # local relay on http://127.0.0.1:8787
```

### Android

Needs JDK 21 and the Android SDK. Install the NDK version named in
`android/app/build.gradle.kts` as well: it is used only to strip debug symbols
from the prebuilt native libraries, and without it the APK is about 250 MB.

```sh
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

To run the app's relay client against a local relay, start `npm run dev` in
`backend/` and run the tests with `MAUN_TEST_RELAY_URL=http://127.0.0.1:8787`.

To bake a relay address into the build, pass `-Pmaun.relayUrl=https://...` or
set `MAUN_RELAY_URL`.

Debug builds are signed with `android/debug.keystore`, which is committed on
purpose so that each debug build installs over the last one. It protects
nothing. Release signing keys never go in the repository.

## Licence

AGPL-3.0-only. See [LICENSE](LICENSE). Maun uses
[libsignal](https://github.com/signalapp/libsignal), which is also AGPL-3.0.
