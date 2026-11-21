# Technical Specification & Build Plan

> **This is the original specification, kept for the reasons behind the design.**
> It was written before any code existed, and parts of it are not what was
> built. Where the two differ, the code is right — the current description is
> [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the behaviour documents it
> links. Section 0 lists what changed and why; the rest of the file is as it was
> written (only §A.5 and §B.4, which describe features added later, have been
> added to since).

## 0. What was built differently

Each line is a claim above that the code no longer makes, and where the code
now stands. This list is not a criticism of the plan: most of these were
decided while building, and the reasoning is in [PLAN.md](PLAN.md) §"Changed
while building".

| Section | The plan said | What was built |
|---|---|---|
| Stack, push | UnifiedPush (`ntfy`) wakes the app | UnifiedPush is one option. The default is a standard Web Push address from Google Play services, asked for on the phone, with no Firebase project and no `FirebaseMessagingService`; the app includes UnifiedPush's embedded-FCM distributor to obtain the address. Any UnifiedPush distributor (Sunup, Mozilla's service) can be used instead. See [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md). |
| §A.3 push trigger | an empty ping | A Web Push message, RFC 8291-encrypted to the push app's own keys and VAPID-signed, whose plaintext names nobody: `message`, or `call` for a ringing call. Wakes are repeated, capped and retried (`backend/src/protocol.ts`). |
| §A.4 files | presigned R2 URLs | Neither upload nor download is presigned. The phone POSTs the ciphertext to `POST /v1/files` with the credential handed out at login; the Worker names the object (32 random bytes) and streams it to R2 under an exact-length check. `GET /v1/files/<key>` hands it back. See [docs/API.md](docs/API.md). |
| §A.4 storage | a 24-hour R2 lifecycle rule | No bucket rule is configured. The 24-hour life is the relay's own: `FILE_TTL_MS` refuses an old object and an hourly cron (`scheduled` in `backend/src/index.ts`) sweeps them. Confirmed in `backend/wrangler.toml`, which has no lifecycle setting. |
| §A.1 identity | a key registry mapping an identity key to a mailbox | There is no registry. The mailbox's Durable Object name *is* the identity public key, and the Durable Object holds the account row. On a member-only relay, D1 holds the email ↔ ID link; nothing else does. |
| §B.1 sessions | X3DH | PQXDH: libsignal's post-quantum handshake, with Kyber pre-keys beside the Curve25519 ones (`KYBER_KEY_BYTES` in `protocol.ts`, `signal_kyber_used` on the phone). |
| §B.2 storage | a passphrase protected by Keystore | A random 32-byte key, stored wrapped by a Keystore AES key in `noBackupFilesDir/db.key`, used in SQLCipher's raw-key form. WAL is on, as planned. |
| §B.3 network | OkHttp WebSocket | OkHttp, as planned, with its own back-off capped at 60 s (`network/Backoff.kt`); signalling travels as ordinary encrypted messages, not as separate frames. |
| §B.5 calling | `org.webrtc`, relay-only ICE | `io.getstream:stream-webrtc-android` 1.3.10, whose classes are still `org.webrtc.*`, with `IceTransportsType.RELAY`. |
| §C.2 CI | `build-android.yml` builds and publishes on version tags | `build-android.yml` tests and builds unsigned debug APKs only. Signing and publishing live in a separate `release.yml`, which runs on any `android/**` change to `main` and publishes by hand (`publish` input). There are also `screens.yml` and `classpath.yml`. |
| §3 tree | `com/example/privatechat/` | `org.maun.messenger` (`applicationId`; debug builds add `.debug`). The tree also has `push/`, `voice/`, `updates/`, `security/` and `AppLock`, `MediaSaver`, `Payload` beside the packages listed. |
| §3 backend | `index.ts` holds the presigner and TURN | `index.ts` is a stateless front door; `mailbox.ts` holds the Durable Object; `protocol.ts` holds every frame and limit; `auth.ts`, `members.ts`, `mail.ts`, `admin-page.ts` are the member-only relay; `turn.ts` mints call credentials; `crypto/` holds Web Push and signature code. |

Two things in the plan that were never built and are not expected to be:
groups, and any server-side chat history. Two that were added later: live
location ([docs/LOCATION.md](docs/LOCATION.md)) and the sealed identity backup
([docs/RECOVERY.md](docs/RECOVERY.md)).

## 1. Existing Resources & Stack Selection
Based on our available infrastructure and experimental scope, the entire stack runs on **$0/month free tiers** with zero cold starts:

* **Mobile App:** Native **Kotlin** + **Jetpack Compose** (Android)
* **Backend Runtime:** **Cloudflare Workers** (TypeScript) — handles routing, file upload links, and call credentials with ~0ms wake-up time.
* **Real-Time & Offline Queue:** **Cloudflare Durable Objects (SQLite-backed)** — manages live WebSocket connections via the WebSocket Hibernation API and temporarily holds encrypted offline messages until delivered.
* **Voice & File Relay Storage:** **Cloudflare R2** (10 GB free) — stores client-encrypted blobs with a 24-hour auto-delete lifecycle rule.
* **Call Relay (IP Privacy):** **Cloudflare Realtime TURN** — relays WebRTC audio/video traffic so peer IP addresses remain hidden.
* **Background Push Notifications:** **UnifiedPush (`ntfy`)** — wakes up the Android app without using Google Firebase (FCM) tracking.
* **Local On-Device Cache:** **Room + SQLCipher** — AES-256 encrypted local SQLite database locked with Android Keystore.
* **CI/CD Automation:** **GitHub Actions** — auto-deploys the Cloudflare backend and compiles signed release APKs.

---

## 2. What Needs to Be Built

### A. Backend (`/backend` — Cloudflare Worker + Durable Object)
1. **Auth & Identity Registry:** Lightweight key registration mapping a user's public identity key to their Durable Object mailbox.
2. **Mailbox Durable Object:**
   - Accepts persistent WebSocket connections using WebSocket Hibernation.
   - Routes live messages immediately; stores offline encrypted payloads in Durable Object SQLite.
   - **Hard-deletes** stored payloads immediately upon receiving an `ACK` frame from the recipient.
3. **Push Wake-Up Trigger:** Sends an empty HTTP wake-up ping to the recipient's UnifiedPush endpoint when a message arrives while offline.
4. **Presigned R2 Upload Endpoint:** Generates short-lived upload/download URLs enforcing a strict **25 MB max file size**.
5. **Profile Pictures (`/v1/avatars`):** One sealed picture per account,
   stored in R2 under `avatar/<mailbox id>` with a version in its metadata.
   `PUT` replaces it (256 KB at most), `GET /v1/avatars/<id>` hands it to any
   logged-in account, `DELETE` removes it. Pictures are exempt from the
   24-hour file sweep: only their owner removes them. The bytes are sealed by
   the phone with a key of its own, and that key travels only inside
   end-to-end encrypted messages, so the relay stores a face it cannot open.
6. **TURN Credential Endpoint:** Fetches short-lived ICE/TURN tokens from Cloudflare Realtime when a user initiates a call.

### B. Android Client (`/android` — Kotlin)
1. **Encryption Engine (`libsignal-client`):**
   - Handles key generation, X3DH session setup, and Double Ratchet message encryption/decryption on-device.
   - Encrypts voice notes and files locally using one-time AES-256-GCM keys before uploading to R2.
2. **Local Encrypted Storage (`Room` + `SQLCipher`):**
   - Generates a random database passphrase on first boot and secures it inside **Android Keystore**.
   - Enables `WAL` (Write-Ahead Logging) mode so background sync never freezes the UI.
   - Stores text/metadata in SQLite and decrypted media files in app-private sandbox storage with automatic cache cleanup.
3. **Network & Sync Layer:**
   - **OkHttp WebSocket Client** with automatic reconnection for foreground chat and WebRTC signaling (`OFFER`, `ANSWER`, `ICE`).
   - **UnifiedPush Receiver + WorkManager** to fetch and decrypt messages silently in the background.
4. **Profile Pictures:** The picture is shrunk (512 px) on the phone, sealed
   with `FileCipher`, and put on the relay; contacts hear of a new version in
   a `Payload.Avatar` message (`ver`, `key`, `size`), ask for one when a
   contact is added (`ver` empty), and are told when it is taken down (`ver`
   = `none`). A picture that cannot be uploaded waits for the next
   connection. A photo kept for a contact on this phone only lives in a file
   of its own and is never sent anywhere; the picture a contact chose can only
   be replaced on their own phone.
5. **Media & Calling:**
   - **Opus Audio Recorder/Player** for compact voice messages.
   - **WebRTC (`org.webrtc`)** configured with `iceTransportPolicy = RELAY` (using Cloudflare TURN) for private voice and video calls.

### C. CI/CD Pipelines (`/.github/workflows`)
1. **`deploy-backend.yml`:** Triggers on push to `main` (`backend/**`) and runs `npx wrangler deploy` to update Cloudflare Workers.
2. **`build-android.yml`:** Triggers on version tags (`v*`), decodes the signing keystore from GitHub Secrets, builds `./gradlew assembleRelease`, and attaches the signed `.apk` to GitHub Releases.

---

## 3. Repository Structure

```text
/
├── PROJECT.md
├── TECHNICAL.md
├── .github/
│   └── workflows/
│       ├── deploy-backend.yml
│       └── build-android.yml
├── backend/                  # Cloudflare Worker + Durable Object (TypeScript)
│   ├── src/
│   │   ├── index.ts          # HTTP routes, R2 presigner, TURN token generator
│   │   └── mailbox.ts        # Durable Object WebSocket + SQLite ephemeral queue
│   ├── wrangler.toml
│   └── package.json
└── android/                  # Native Kotlin + Jetpack Compose App
    └── app/src/main/java/com/example/privatechat/
        ├── crypto/           # Signal Protocol & AES file encryption
        ├── data/             # Room + SQLCipher entities, DAOs, Keystore helper
        ├── network/          # OkHttp WebSocket, R2 uploader, UnifiedPush receiver
        ├── webrtc/           # Audio/video call manager
        └── ui/               # Compose screens (Chats, Conversation, Call)
```