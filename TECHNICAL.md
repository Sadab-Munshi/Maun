# Technical Specification & Build Plan

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