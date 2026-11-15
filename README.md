# Maun

A private one-to-one messenger for Android with zero tracking: no ads, no
analytics, no phone number, and no chat history on any server.

- What it is and is not: [PROJECT.md](PROJECT.md)
- Original technical spec: [TECHNICAL.md](TECHNICAL.md)
- Build plan, decisions and progress: [PLAN.md](PLAN.md)

## Status: Milestone 1 (identity and login)

There is no messaging yet. What works:

| Piece | Where |
|---|---|
| A private ID made on the phone, with no phone number, email or password | `android/.../crypto/Identity.kt` |
| A login key that cannot leave the phone (Android Keystore) | `android/.../crypto/DeviceKey.kt` |
| Registration and challenge-response login on every connection | `backend/src/mailbox.ts`, `android/.../network/RelayConnection.kt` |
| Rate limits on connections and sign-ups | `backend/src/index.ts`, `backend/wrangler.toml` |
| "My code" QR screen and scan-to-add contact | `android/.../ui/` |
| CI: tests the relay, runs the app's connection code against it, builds the APK | `.github/workflows/` |

The relay stores one row per account: the public half of the login key. It has
no names, no phone numbers and no contact lists.

## Try it

### 1. Deploy the relay

You need a free Cloudflare account.

**From the dashboard (no tools needed).** In Cloudflare: Workers & Pages >
Create application > connect GitHub > choose this repository. Set the root
directory to `backend` and the deploy command to `npx wrangler deploy`. The
Worker's name must match `name` in `backend/wrangler.toml`. Every push to
`main` is then deployed automatically.

Only `main` is deployed. Under the Worker's Settings > Build you can turn off
builds for other branches; they are not needed.

**Or from a terminal** (Node.js 22.12 or newer):

```sh
cd backend
npm ci
npx wrangler login
npx wrangler deploy
```

Either way the relay gets an address like
`https://maun.<your-subdomain>.workers.dev`. Opening `<address>/health` in a
browser should show `{"ok":true,"service":"maun-relay","proto":1}`.

### 2. Install the app

Download `maun-debug-apk` from the latest run of the **Android** workflow on
GitHub (Actions tab), unzip it, and install the APK on a phone running
Android 10 or newer. Android will ask you to allow installing from this source.

### 3. Create an ID and add a contact

On each of two phones:

1. Open **Maun (debug)**, type the relay address, and tap **Create my ID**.
   The home screen should show "Connected and signed in".
2. On one phone tap **My code**; on the other tap **Add contact** and point
   the camera at the code. Give the contact a name.
3. Repeat the other way round, so both phones have each other.

**Setup check** at the bottom of the home screen re-runs the hardware,
storage, encryption and relay checks at any time.

An ID lives only on the phone that made it. Uninstalling the app, or clearing
its data, destroys the ID for good.

## Continuous integration

Both workflows run without any configuration. Optional settings, under
**Settings > Secrets and variables > Actions**:

| Kind | Name | Value |
|---|---|---|
| Variable | `MAUN_RELAY_URL` | Your relay address. Pre-fills it in the app, so nobody has to type it. |
| Secret | `CLOUDFLARE_API_TOKEN` | Only if you deploy from GitHub Actions instead of the Cloudflare dashboard connection. Made from Cloudflare's "Edit Cloudflare Workers" template. |
| Secret | `CLOUDFLARE_ACCOUNT_ID` | As above. |

Use one way of deploying, not both.

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

The connection tests need a relay: start `npm run dev` in `backend/` and run
the tests with `MAUN_TEST_RELAY_URL=http://127.0.0.1:8787`. Without it they
are skipped. CI always runs them.

To bake a relay address into the build, pass `-Pmaun.relayUrl=https://...` or
set `MAUN_RELAY_URL`.

Debug builds are signed with `android/debug.keystore`, which is committed on
purpose so that each debug build installs over the last one. It protects
nothing. Release signing keys never go in the repository.

## Licence

AGPL-3.0-only. See [LICENSE](LICENSE). Maun uses
[libsignal](https://github.com/signalapp/libsignal), which is also AGPL-3.0.
