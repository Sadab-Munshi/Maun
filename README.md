# Maun

A private one-to-one messenger for Android with zero tracking: no ads, no
analytics, no phone number, and no chat history on any server.

- What it is and is not: [PROJECT.md](PROJECT.md)
- Original technical spec: [TECHNICAL.md](TECHNICAL.md)
- Build plan, decisions and progress: [PLAN.md](PLAN.md)

## Status: app 0.4.0, after milestone 3 (background delivery)

Two phones can exchange end-to-end encrypted text, and are told about new
messages while the app is closed. The app can be locked behind the phone's
fingerprint or screen lock, and two people can compare a safety number to be
sure of each other. Voice notes, files and calls are not built yet.

| Piece | Where |
|---|---|
| A private ID made on the phone, with no phone number, email or password | `android/.../crypto/Identity.kt` |
| A login key that cannot leave the phone (Android Keystore) | `android/.../crypto/DeviceKey.kt` |
| Registration and challenge-response login on every connection | `backend/src/mailbox.ts`, `android/.../network/RelayConnection.kt` |
| "My code" QR screen and scan-to-add contact | `android/.../ui/` |
| Signal protocol sessions (PQXDH and Double Ratchet, via libsignal) | `android/.../crypto/Sessions.kt` |
| Sending, receiving, Sent / Delivered / Read, message requests, blocking | `android/.../ChatEngine.kt` |
| Offline queue that deletes each message once it is collected | `backend/src/mailbox.ts` |
| Wake-ups for a phone that is not connected: encrypted, signed, content-free | `backend/src/crypto/webpush.ts`, `backend/src/mailbox.ts` |
| Being woken by Google's push service or a push app, fetching, notifying | `android/.../push/` |
| "Stay connected" for phones without a push app | `android/.../push/ConnectedService.kt` |
| App lock, hiding Maun in recent apps, blocking screenshots | `android/.../AppLock.kt` |
| Safety number for checking a contact | `android/.../crypto/SafetyNumber.kt` |
| Rate limits and mailbox caps | `backend/src/index.ts`, `backend/src/protocol.ts`, `backend/wrangler.toml` |
| CI: tests the relay, runs the app's messaging code against it, builds the APK | `.github/workflows/` |

What the relay stores: per account, the public half of the login key and the
public pre-keys others need to start a conversation; per waiting message, the
encrypted bytes, the sender's ID and the arrival time, until the recipient's
phone collects it or 30 days pass; and, if the phone left one, the address at
which its push service takes wake-ups. It has no names, no phone numbers, no contact lists and
no message history.

What it can see: which ID sends to which, and when. It cannot read messages or
tell a message from a read receipt.

### Messages while the app is closed

When a message arrives for a phone that is not connected, the relay sends a
wake-up: encrypted, signed, and saying nothing but "wake". Maun then connects,
fetches, and shows a notification without the message text. Who carries the
wake-up to the phone:

- **Google's push service (the default).** The one most Android apps use.
  Nothing to install, nothing of Maun running. Maun asks Google Play services
  on the phone for a standard Web Push address; there is no Firebase project
  and no Google code in the app. Google learns that the phone has Maun, that
  it was woken, and when; not who wrote or what. "Do not use Google" on the
  home screen turns it off.
- **A push app instead of Google.** Install [Sunup](https://f-droid.org/packages/org.unifiedpush.distributor.sunup/)
  from F-Droid, open it once and allow it to run in the background, then open
  Maun. Maun uses it in place of Google, and Mozilla's push service carries
  the wake-up.
- **Stay connected.** A switch on Maun's home screen. Maun keeps its
  connection open behind a permanent notification. Nobody else is involved; it
  uses more battery.

Without any of these, Maun looks for messages about once an hour.

ntfy's public server (ntfy.sh) does not work with a relay on Cloudflare: it
turns away the addresses all Cloudflare Workers share. ntfy with your own
server does.

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
browser should show `{"ok":true,"service":"maun-relay","proto":2}`.

### 2. Install the app

Download `maun-debug-apk` from the latest run of the **Android** workflow on
GitHub (Actions tab), unzip it, and install the APK on a phone running
Android 10 or newer. Android will ask you to allow installing from this source.

### 3. Create an ID, add a contact, and write

On each of two phones:

1. Open **Maun (debug)**, type the relay address, and tap **Create my ID**.
   The home screen opens; it says so only when it is *not* connected.
2. On one phone tap the code symbol at the top; on the other tap
   **Add contact** and point the camera at the code. Give the contact a name.
3. Repeat the other way round, so both phones have each other.
4. Tap the contact and write. Next to the time of each of your messages is a
   clock (not sent yet), one tick (the relay has it), two ticks (their phone
   has it) or two blue ticks (they opened the conversation).
5. Hold a message to copy it or to delete it from this phone.
6. Tap the name at the top of a conversation to rename the contact or to
   compare the **safety number** with them. Both phones show the same 60
   digits; if they match, nobody swapped the ID on the way.

If only one of you has added the other, the message shows up on the other
phone as a **message request**, which can be accepted or blocked.

Messages arrive while the app is open. If the other phone is off or the app is
closed, the relay keeps the encrypted message until that phone opens Maun.

The gear at the top opens **Settings**: how messages arrive while Maun is
closed, **App lock** (the phone's fingerprint, face or screen lock every time
Maun is opened; it is a door, the messages are encrypted on the phone either
way), **Block screenshots**, blocked people, and the **Setup check**, which
re-runs the hardware, storage, encryption and relay checks at any time.

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

The connection and messaging tests need a relay: start
`backend/scripts/test-relay.sh` and run the tests with
`MAUN_TEST_RELAY_URL=http://127.0.0.1:8787`. Without it they are skipped. CI
always runs them. (The script is the normal relay with the per-address
connection limits lifted, because every test phone connects from one address.)

To look at the screens without a phone, the **Screens** workflow draws the
main ones with made-up people and keeps the pictures as the artifact
`screens`. It runs when anything under `ui/` changes. The same locally:
`./gradlew :app:testDebugUnitTest -Pmaun.screens=true`, pictures in
`app/build/screens`. Nothing is compared, so it cannot fail on looks.

When the database changes, the build writes a new file under
`android/app/schemas/`. Commit it: upgrades are generated from those files.

To bake a relay address into the build, pass `-Pmaun.relayUrl=https://...` or
set `MAUN_RELAY_URL`.

Debug builds are signed with `android/debug.keystore`, which is committed on
purpose so that each debug build installs over the last one. It protects
nothing. Release signing keys never go in the repository.

## Licence

AGPL-3.0-only. See [LICENSE](LICENSE). Maun uses
[libsignal](https://github.com/signalapp/libsignal), which is also AGPL-3.0.
