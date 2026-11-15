# Build Plan: Maun, a zero-tracking private messenger (Android APK)

Based on `PROJECT.md` and `TECHNICAL.md`.
App name: **Maun** (Hindi/Sanskrit for "silence"). Package ID: `org.maun.messenger`.

## Progress

| Milestone | State |
|---|---|
| M0. Foundations and risk checks | **Done.** Setup check passed on a real phone against the deployed relay. |
| M1. Identity and login | **Done.** Two phones registered and added each other by QR. |
| M2. Text messaging | **Built, tested in CI and deployed (app 0.2.0, relay protocol 2). Waiting for the two-phone check.** |
| M3. Background delivery | Next |
| M4 to M6 | Not started |

### M2 results

Checked automatically, on every push:

- Relay: 76 tests pass in Cloudflare's local runtime. The 22 new ones cover the milestone's finish line (a message for someone offline is kept, delivered when they connect, and the queue table is empty once they acknowledge it) and also: redelivery after a connection that never acknowledged, a backlog delivered in order 50 at a time, the relay naming the real sender whatever the frame claims, nothing stored for an unregistered address, the per-sender and whole-mailbox caps, the send rate limit, expiry after 30 days, and each one-time key going to one asker only.
- App and relay together: 12 tests run two or three complete copies of the app's messaging code (encryption, storage logic, outbox, connection) against a local relay. Sent, Delivered and Read arrive in that order and only when true. A delivery whose save fails is not acknowledged and comes again exactly once. The same delivery twice is saved once. A stranger's message becomes a request and they learn nothing. A blocked sender is dropped unread. The longest allowed message fits through the relay as a conversation's first message.
- Encryption: libsignal's key sizes match what the relay accepts, sessions survive being saved and reloaded, a damaged message is refused and leaves the session usable, and a key that does not match the sender's ID is refused.
- Lint reports 0 errors. The debug APK is 66 MB.

Checked by hand:

- One of those tests, run from a development machine against the **deployed** relay: two new accounts, a message sent to one that was offline, then Sent, Delivered and Read. It left two unused test accounts on the relay.

Not checked yet, because it needs phones:

- The new screens have **never been run on a phone**: chat list, conversation, message request, blocked list.
- **Upgrading an existing installation.** The database goes from version 2 to 3 by a migration Room generates from the two committed schema files. It only adds tables, but it has not been run on a device.
- The storage layer under the messaging code. Tests use an in-memory stand-in with the same transaction behaviour; the real one is Room on SQLCipher.

### Changed while building M2

- **Version 0.1.0 of the app stops working.** The relay now speaks protocol 2 and the app refuses a relay on a different version. Both phones need 0.2.0. It installs over 0.1.0 and keeps the ID and the contacts.
- **Your ID is your encryption key.** A session is accepted only if the other side's identity key equals the ID you added, so there is no separate "safety number" to compare, and no "safety number changed" warning. Scanning someone's code is the verification.
- **Post-quantum key agreement (PQXDH) from the first message**, as the pinned libsignal requires.
- **Message length is hidden in steps.** Every message is padded to a multiple of 160 bytes before encryption. One message carries up to 4,096 bytes of text.
- **Receipts go only to contacts.** Someone who is not in your contacts cannot tell whether their message arrived, was read, or was thrown away. Accepting a request adds them as a contact and only then tells them their messages arrived.
- **You can only write to contacts**, and both sides must add each other for a normal conversation. If only one side has, the other sees a message request.
- **Removing a contact deletes the conversation** from this phone. Blocking does too, and also drops whatever they send later.
- **Relay limits:** a message is at most 8 KB encrypted; a mailbox holds 1,000 waiting messages, at most 300 from one sender; 120 sends and key requests a minute per account; uncollected messages are deleted after 30 days.
- **The relay does not check pre-key signatures.** The receiving phone does, against the ID it already holds, so a relay that swapped keys would only cause a refused session.
- **Still visible to the relay** (as section 2.5 says): who sends to whom, when, and roughly how much. Message text, receipts and which is which are not.
- **Messages arrive only while the app is open**, and for 10 seconds after leaving it. Background delivery is M3.
- **Known limit, for M6:** if the connection drops in the instant between creating a batch of one-time keys and uploading it, those 50 keys stay on the phone unused. Nothing breaks, but they are never cleaned up.
- **Known limit:** the conversation screen shows the newest 500 messages. Older ones stay on the phone but there is no way to scroll to them yet.
- The database is at version 3. `android/app/schemas/3.json` is produced by the build; CI fails if the committed copy differs.

### M1 results

Checked automatically, on every push:

- Relay: 54 tests pass in Cloudflare's local runtime. They cover the three cases the milestone names (a forged signature, a replayed challenge, a second registration for the same ID) and also: an address claimed with someone else's identity key, two registrations racing, a registration signature reused as a login, one attempt per connection, a stranger being unable to disconnect the owner, and login surviving the mailbox being evicted from memory.
- App and relay together: GitHub Actions starts the relay locally and runs the app's real connection code against it. libsignal signs with the identity key and the relay's WebCrypto accepts it. A new phone registers, logs in again on the next connection without replacing its device key, and a second phone holding a copy of the identity key is refused.
- The bytes that get signed are pinned by the same test vector on both sides, so the app and the relay cannot drift apart unnoticed.
- QR codes: a code is painted into a camera-shaped image and read back, including row padding.
- Lint reports 0 errors. The debug APK is 65 MB.

Checked by hand:

- The deployed relay (`/health` reports protocol 1) greets a new connection with a fresh nonce.

Checked on two phones (reported with screenshots):

- Creating an ID, registering with a device key made inside Android Keystore, and logging in.
- The milestone's finish line: two phones register and add each other by QR.

### Changed while building M1

- **Pre-key upload and fetch moved to M2.** Nothing uses pre-keys until the first message is encrypted, and building them next to the code that consumes them avoids guessing at the format.
- **Login needs only the device key.** The identity key signs once, at registration, to prove the address belongs to the phone. After that every connection is a fresh nonce signed inside the phone's secure hardware.
- **A stranger cannot knock you offline.** In M0 any new connection replaced the old one. Now only a connection that has logged in does, and at most three sockets may wait at the login step of one mailbox.
- **Nothing is stored for an address nobody registered.** An account is one row: the device public key, the challenge it was created against, and the day (not the time) it was created.
- **Rate limits** (Cloudflare's rate-limiting binding, free, nothing stored by us): 120 connections a minute per IP address, 20 a minute per mailbox, 10 a minute per IP address to addresses that have no account. The per-address numbers are generous because Indian mobile networks put many people behind one address.
- **Contacts must be on the same relay.** The QR code carries the relay's name, and the app refuses a code from another relay with an explanation.
- **A contact added by camera is marked "Scanned"; one pasted as text is marked "Typed"**, because pasted text could have been swapped on the way.
- **Limit: no re-registration.** If the relay's data is ever wiped, a phone that was registered is refused rather than silently replacing its device key. Starting again means clearing the app's data. A proper "delete my ID and start again" belongs with account deletion in M6.
- The database is now at version 2 and its layout is recorded in `android/app/schemas/`. Version 1 (the setup-check build) is discarded on upgrade. Every later change needs a real migration, because the database now holds the identity key.

### M0 results

- Relay: tests pass in Cloudflare's local runtime, including a socket that keeps working after the mailbox is evicted from memory (hibernation).
- Signature checks for both key types pass against vectors produced by libsignal and the JDK. Cost is about 0.7 ms of CPU for both together, measured in Node; the limit is 10 ms.
- libsignal: 8 tests pass on the JVM. Row 6 below is settled: the only `PreKeyBundle` constructor requires a Kyber pre-key.
- On a real phone, against the deployed relay: device key held in the processor's secure area (TEE); encrypted storage written, reopened and read back; Signal session set up and a message decrypted each way; relay round trip in 260 ms.

### Found while building M0

- libsignal's Maven package includes desktop builds of its native library, a second "testing" copy for Android, and unstripped debug symbols. Left alone, the APK was 496 MB. The build now excludes the extras and strips symbols with the NDK; CI fails if the APK exceeds 100 MB.
- Current AndroidX libraries need `compileSdk 37`, and libsignal needs core library desugaring.
- The build workspace is too small to compile the app reliably. From M1 on, the app is compiled and tested by GitHub Actions only.

The stack in the spec is sound and I would keep it. This plan does three things:

1. Fixes the places where the spec will not work as written (section 1).
2. Fills in the parts the spec leaves undefined: login, device binding, key exchange (section 2).
3. Orders the work into milestones that each end with something you can test on two real phones (section 3).

---

## 1. Changes to the spec before we build

Each row is something I checked against current documentation. Sources are at the end.

| # | Spec says | Problem | Plan |
|---|---|---|---|
| 1 | Presigned R2 URLs enforce the 25 MB limit | R2 does not support presigned POST, so a presigned URL cannot carry a size policy. | Upload through the Worker using the R2 binding. The Worker checks login and `Content-Length` and rejects anything over the cap. No R2 API keys needed. |
| 2 | R2 lifecycle rule deletes files after 24 hours | Lifecycle rules work in whole days, and objects are "typically removed within 24 hours" *after* they expire. Real lifetime is 24 to 48 hours. | Worker refuses downloads older than 24 h; an hourly scheduled job deletes expired objects. The lifecycle rule stays only as a backstop. |
| 3 | `org.webrtc` for calls | Google's prebuilt `org.webrtc:google-webrtc` artifact no longer resolves. | Use `io.getstream:stream-webrtc-android` (same `org.webrtc.*` classes). |
| 4 | UnifiedPush (ntfy) wakes the app | UnifiedPush only works if the user installs a separate distributor app such as ntfy. ntfy.sh also caps messages at 250 per day. | Support two modes: UnifiedPush when a distributor is installed, otherwise an opt-in foreground service that keeps the WebSocket open. Send one wake-up per burst, not per message. |
| 5 | "Auth & Identity Registry: lightweight key registration" | No login method, no device binding, and no place to store the pre-keys that Signal session setup needs. | Defined in section 2. No central registry at all: each mailbox is addressed directly by the user's public key. |
| 6 | X3DH session setup | libsignal 0.86.5 sets up sessions with PQXDH (X3DH plus a post-quantum Kyber pre-key). X3DH-only bundles cannot be built: confirmed in M0. | Use PQXDH. Every user publishes a Kyber pre-key. |
| 7 | `libsignal-client` as the crypto engine | It is licensed AGPLv3, and Signal states "use outside of Signal is unsupported", so its API can change without notice. | The app's source is published under AGPL-3.0 (agreed). Pin one version and keep all libsignal calls behind our own `crypto/` interface. |
| 8 | Store offline messages; delete on ACK | Messages for a user who never returns are kept forever. A message sent "live" is lost if the socket drops before it arrives. | Always store first, forward, delete on ACK. Add a 30-day expiry and a per-mailbox cap. |
| 9 | WebRTC signaling (`OFFER`, `ANSWER`, `ICE`) over the WebSocket | If these travel in the clear, the server could alter them and intercept a call. | Send signaling as normal Signal-encrypted messages with a 60-second expiry. |
| 10 | "$0/month" | True for usage, but R2 needs a payment method on file, and free limits are hard daily stops: when one is hit the service fails until 00:00 UTC. | Add per-user quotas so one account cannot exhaust the daily budget. Budget in section 4. |
| 11 | "Opus Audio Recorder/Player" | No library named. | Android's built-in recorder produces Ogg/Opus from Android 10. Set minimum Android version to 10 (API 29) and avoid bundling a codec. |
| 12 | Package `com.example.privatechat` | A package ID cannot be changed after people install the app. | Decided: Maun, `org.maun.messenger`. Debug builds use `org.maun.messenger.debug` so they can sit beside a release build. |
| 13 | (not mentioned) | Android's automatic cloud backup would copy app data to Google Drive. | Disable backup in the manifest. |

---

## 2. Design decisions the spec leaves open

### 2.1 Identity, login and device binding

- **Account ID = the user's public identity key.** Generated on the phone at first launch. Shared with contacts by QR code or link. No usernames in version 1, so there is no directory to search, scrape or squat.
- **No central registry.** The Worker maps a public key straight to that user's Mailbox Durable Object. There is no table of users.
- **Two keys per install:**
  - *Identity key* (Curve25519, used by Signal encryption). Stored in the SQLCipher database.
  - *Device key* (P-256, created inside Android Keystore, StrongBox when the phone has it). It cannot be exported. This is what binds the account to the physical device.
- **Registration:** the phone sends identity key, device key, pre-keys, and a signature proving it owns the identity key. The mailbox accepts the first valid registration and rejects later ones for the same ID.
- **Login:** on every connect the mailbox sends a random challenge and the phone signs it with the device key. No password, no token to steal.
- **Real-device proof:** Android Key Attestation, added in Milestone 6. Accounts that fail are rate-limited harder rather than blocked, so custom-ROM users are not locked out. Google Play Integrity is not used.
- **App lock:** fingerprint or PIN to open the app. In version 1 this guards the screen, not the keys, because background message decryption must work while the phone is locked.
- **Lost phone = new ID in version 1.** No recovery phrase and no transfer yet (**decision needed**).

### 2.2 Message flow

1. Sender encrypts with the Signal session and sends an envelope over its WebSocket.
2. Sender's mailbox passes it to the recipient's mailbox, which writes it to SQLite and answers "stored". Sender shows **Sent**.
3. Recipient's mailbox forwards it if a socket is open, otherwise sends one push wake-up.
4. Recipient decrypts, saves locally, sends `ACK`. Mailbox hard-deletes the row.
5. Recipient's app sends an encrypted receipt back. Sender shows **Delivered**, later **Read**.

Receipts are ordinary encrypted messages, so the server cannot tell a receipt from a chat message.

### 2.3 Files and voice notes

- Encrypt on the phone with a one-time AES-256-GCM key, upload the ciphertext under a random object name, send the name and key inside a Signal message.
- Server enforces 25 MB and 24 hours. It also deletes the object as soon as the recipient confirms download.
- Voice notes: Ogg/Opus, mono, about 16 to 24 kbps (roughly 150 KB per minute).

### 2.4 Calls

- WebRTC with relay-only ICE through Cloudflare TURN. Short-lived TURN credentials from the Worker, issued only to a logged-in user.
- Audio first, then video.

### 2.5 What "zero tracking" can honestly promise

State these in the app's privacy text; do not hide them.

- **Cloudflare sees every user's IP address**, because it hosts the Worker, the mailbox and the call relay. Callers are hidden from each other, not from Cloudflare.
- **The server can observe who messages whom and when**, although it stores none of it. Hiding the sender from the server (Signal's "sealed sender") is not in version 1.
- **The push provider (for example ntfy.sh) sees the phone's IP and the timing of wake-ups**, never content.
- **Decrypted media on the phone** is protected by Android's own storage encryption, not by SQLCipher.

---

## 3. Milestones

Order: prove the risky pieces first, then text, then background delivery, then media, then calls. Calls come last because they depend on everything else and carry the most Android-specific risk. Size is relative effort, not a date.

### M0. Foundations and risk checks (small)

- Repo layout as in the spec, plus `PLAN.md`. Real package ID.
- Backend: Worker and one Durable Object that accepts a WebSocket and echoes.
- Android: empty Compose app that connects and shows the echo.
- CI: backend tests and deploy; Android build that uploads a debug APK on every push to `main`.
- Risk checks, each a small automated test:
  - libsignal: two in-memory users set up a session and exchange a message (settles row 6).
  - Room + SQLCipher opens with a Keystore-wrapped passphrase on a real phone.
  - Signature verification in the Worker fits the free plan's 10 ms CPU limit.

**Done when:** CI is green, the Worker is deployed, and the APK from CI installs and echoes on a phone.

### M1. Identity and login (medium)

- Android: key generation, Keystore device key, registration, challenge-response login, reconnect with back-off.
- The device key is created with a server-issued challenge baked in, so the same key can be attested in M6 without making users register again.
- Backend: registration, challenge-response, basic per-IP and per-account rate limits.
- UI: onboarding, "My code" QR screen, scan-to-add contact.

**Done when:** two phones register and add each other by QR. Tests show a forged signature, a replayed challenge and a second registration for the same ID are all rejected.

### M2. Text messaging (large)

- Android: pre-key generation and upload, Signal session setup, encrypt and decrypt, local database, chat list and conversation screens, Sent/Delivered/Read.
- Backend: pre-key storage and fetch, store, forward, ACK delete, 30-day expiry, mailbox cap.
- UI: message requests (accept or block an unknown sender).

**Done when:** A sends to B while B is offline; B receives it on reconnect; a test confirms the mailbox table is empty after ACK. Killing the app mid-delivery loses nothing and duplicates nothing.

### M3. Background delivery (medium)

- UnifiedPush receiver plus WorkManager fetch; fallback foreground service; notifications that show no content on the lock screen by default.
- Backend: wake-up ping with debounce; validation of the push URL.
- Check whether the UnifiedPush library now requires encrypted (Web Push) payloads rather than an empty ping.

**Done when:** with the app force-closed, a message produces a notification in both modes. Measure the delay and battery cost of the fallback over 24 hours.

### M4. Files and voice notes (medium)

- Android: file picker, chunked encryption, upload and download with progress, voice recorder and player.
- Backend: upload and download routes, size cap, 24-hour refusal, hourly delete job, daily per-user upload quota.

**Done when:** a 26 MB upload is rejected by the server (not just the app); a test with a moved clock shows the object is unreachable after 24 hours; a voice note records and plays on both phones.

### M5. Calls (large)

- Backend: TURN credential route.
- Android: call manager, encrypted signaling, audio then video, incoming-call screen, foreground service, Bluetooth and speaker routing.

**Done when:** a call works between Wi-Fi and mobile data; WebRTC statistics show only relay candidates in use; an incoming call rings with the app closed.

### M6. Hardening and release (medium)

- Key Attestation at registration; full rate limits and quotas.
- App lock; safety-number screen so two users can verify each other's keys; screenshot blocking option.
- Signed release workflow on version tags; privacy text; short threat-model document.

**Done when:** a tagged build produces a signed APK on GitHub Releases, and a fresh install passes an end-to-end checklist covering every feature in the overview.

### Not in version 1

Groups, usernames, multi-device, recovery phrase, phone-to-phone transfer, sealed sender, iOS, disappearing messages.

---

## 4. Free-tier budget

Limits are from current Cloudflare and ntfy documentation. "What it allows" is my estimate.

| Service | Free limit | What it allows |
|---|---|---|
| Workers | 100,000 requests/day, 10 ms CPU each | Not the bottleneck. |
| Durable Objects requests | 100,000/day; incoming WebSocket messages count 20 to 1 | About 100,000 envelopes/day. |
| Durable Objects SQLite writes | 100,000 rows/day | **The real ceiling.** Store plus delete is 2 writes per envelope, and each chat message causes about 3 envelopes (message, delivered, read): roughly 15,000 chat messages/day. |
| Durable Objects duration | 13,000 GB-s/day | Fine with WebSocket Hibernation; idle sockets cost nothing. |
| Durable Objects storage | 5 GB total | Fine with expiry and caps. |
| R2 | 10 GB stored | With 24-hour retention, about 400 full-size files in flight at once. |
| TURN | 1,000 GB/month outbound | Roughly 500 to 1,000 hours of video calls. Audio is negligible. |
| ntfy.sh | 250 messages/day | Enough only if wake-ups are debounced. Must be tested in M3. |
| GitHub Actions | Free for public repositories | Fits, since AGPL makes the repo public anyway. |

This is comfortable for a test group of up to a few hundred light users. Beyond that, Workers Paid is $5 per month.

---

## 5. Main risks

| Risk | Effect | How the plan handles it |
|---|---|---|
| libsignal API changes or drops something we use | Rework of the crypto layer | Pinned version, single wrapper module, tested in M0. |
| Push is unreliable on phones with aggressive battery saving (Xiaomi, Oppo, Vivo, Samsung) | Late messages, missed calls | Two delivery modes; test on at least one such phone in M3. |
| Incoming calls with the app closed | Android 14+ restricts full-screen call screens for apps not installed from Play | Left to M5; may need the user to grant a permission. |
| Signature and attestation checks exceed 10 ms CPU | Registration fails on the free plan | Measured in M0; fallback is Workers Paid. |
| Anonymous accounts are free to create | Spam, free-tier exhaustion | Rate limits from M1, message requests in M2, attestation and quotas in M6. |
| One person abusing uploads or calls | R2 or TURN allowance used up | Per-user daily quotas; the Worker stops issuing TURN credentials near the monthly cap. |

---

## 6. How implementation will run

- **Backend:** I write it and test it with Cloudflare's local runtime. Every push to `main` is deployed by Cloudflare, which is connected to the GitHub repository.
- **Android:** I write the code; GitHub Actions compiles it, runs the unit tests and lint, and publishes the APK.
- **Real-device testing** (camera, microphone, push, calls) can only be done by you on two phones. Each milestone's "Done when" is the checklist.

### Needed from you

1. For M1: install the new APK on **two** phones (Android 10 or newer), create an ID on each, and add each other by QR. Ideally one of the phones is from Xiaomi, Oppo, Vivo or Samsung.
2. Before M4: a payment method on the Cloudflare account (R2 requires one even on the free tier). Before M5: the same for TURN.

Step-by-step instructions are in `README.md`.

### Can be decided later

- Recovery: none (default), recovery phrase, or phone-to-phone transfer.
- Whether to require ntfy or keep the foreground-service fallback.

---

## Sources

- Durable Objects pricing and limits: https://developers.cloudflare.com/durable-objects/platform/pricing/
- R2 presigned URLs (POST not supported): https://developers.cloudflare.com/r2/api/s3/presigned-urls/
- R2 object lifecycles: https://developers.cloudflare.com/r2/buckets/object-lifecycles/
- Cloudflare Realtime TURN pricing and credentials: https://developers.cloudflare.com/realtime/llms-full.txt
- libsignal repository (licence, support statement): https://github.com/signalapp/libsignal
- libsignal-android on Maven Central: https://central.sonatype.com/artifact/org.signal/libsignal-android
- UnifiedPush overview: https://f-droid.org/en/2022/12/18/unifiedpush.html
- ntfy limits: https://docs.ntfy.sh/publish/
- WebRTC Android library status: https://www.forasoft.com/blog/article/android-webrtc-screen-sharing
