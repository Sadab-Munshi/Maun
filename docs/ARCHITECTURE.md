# How Maun is put together

The shape of the system, the pieces and their responsibilities, and the path a
piece of data takes from one phone to another. This is the map, not the manual:
the deep explanations of a feature live in their own documents, linked below.

Everything here was read out of the code at `acc3494` (app 0.29.0, relay
protocol 2). Where the code proves a claim the file is named; where a claim
would need a phone, it says so.

## The two halves

| Part | What it is | Runs on | Language |
|---|---|---|---|
| `backend/` | The relay: one stateless Worker, one Durable Object per account, D1 and R2 | Cloudflare Workers | TypeScript |
| `android/` | The app: one Gradle module, Jetpack Compose | Android 10 (API 29) and newer | Kotlin |

There is no third part. The Durable Object has a bounded, expiring queue of
ciphertext for messages not yet collected, but there is no server-side chat
history after delivery, no analytics service, no admin backend beyond the
relay's own page, and no Firebase project.

## The boundaries, in one picture

```
  phone A                        Cloudflare                        phone B
 ┌──────────┐                                                    ┌──────────┐
 │ ChatEngine│                                                    │ ChatEngine│
 │ Sessions  │                                                    │ Sessions  │
 └────┬─────┘                                                    └────▲─────┘
      │ RelayConnection (one WebSocket, TLS)                          │
      ▼                                                               │
 ┌──────────────────────┐   send    ┌──────────────────────┐         │
 │ Mailbox(A) DO        │──────────▶│ Mailbox(B) DO        │─────────┘
 │ queue, push row,     │◀──────────│ queue, push row,     │  msg frame
 │ shares, vapid        │  stored   │ shares, vapid        │
 └──────┬───────────────┘           └──────┬───────────────┘
        │ wake (Web Push, sealed)          │ wake
        ▼                                  ▼
   push service (Google's or Mozilla's) ──▶ WakeService → FetchWorker
```

Everything crossing a boundary is either TLS or sealed end to end. What each
party along the way can see is in [SECURITY.md](SECURITY.md).

## Data flows

### 1. A message, both phones online

1. `ChatEngine.send` builds a `Payload` (`android/.../Payload.kt`), seals it with
   the conversation's Signal session (`crypto/Sessions.kt`, libsignal), and
   writes one row to the `outbox` table with the sealed bytes kept, so a retry
   re-sends the same bytes.
2. `network/RelayConnection.kt` sends a `send` frame to the recipient's mailbox.
   The mailbox writes it to `queue` and answers `stored`.
3. The recipient's mailbox pushes the message down the recipient's socket as a
   `msg` frame. Nothing is deleted until the recipient answers `ack`.
4. The recipient decrypts, saves, and sends a receipt (an ordinary encrypted
   message) which sets the sender's ticks.

The frame shapes are `backend/src/protocol.ts`; the delivery logic is
`backend/src/mailbox.ts`.

### 2. A message, app closed

After storing the message, the mailbox sends the same Signal ciphertext over Web Push to a capable receiver and pumps any connected socket. The inline payload is also encrypted to the receiving installation. Android can save and announce the chat before reconnecting. The socket reconciles the retained queue row and receipts. Older installations use the one-word wake and fetch path. [Notification delivery](NOTIFICATIONS.md)

### 3. A file

1. The sender seals the file in 64 KB pieces (`crypto/FileCipher.kt`, `CHUNK`),
   one key per file.
2. `network/FileApi.kt` POSTs the ciphertext to `/v1/files` with the `files`
   token from `ready`. The Worker checks the sender and the length and streams
   it to R2 under a random 64-hex key.
3. The key and the name travel inside an ordinary encrypted message
   (`Payload.File`). The relay never has the key.
4. The receiver GETs `/v1/files/<key>`, unseals, and saves it. The relay deletes
   the object after 24 hours (`FILE_TTL_MS`) and refuses it after that; an
   hourly cron sweeps what is left (`backend/wrangler.toml`, `src/index.ts`).

### 4. A call

1. `webrtc/CallManager.kt` asks the relay for `ice` (frame `turn`) and gets
   short-lived TURN credentials (`backend/src/turn.ts`, four-hour TTL, 60 sets a
   day per account).
2. Offer, answer and candidates travel as ordinary encrypted messages
   (`Payload.Call`, `Payload.Candidate`), the offer marked urgent so it wakes a
   phone that was woken a moment ago.
3. The connection is built with `IceTransportsType.RELAY`
   (`CallManager.kt`), so neither phone learns the other's address and the
   relay never carries the media.
4. Ringing, the foreground services and the Telecom hand-off are
   `webrtc/RingService.kt`, `webrtc/CallService.kt`, `webrtc/CallAudio.kt`.

### 5. Live location

Two separate things share one screen; the whole story is
[LOCATION.md](LOCATION.md).

- **A place sent once** is an ordinary encrypted message (`Place` in
  `LiveLocation.kt`), named from Nominatim by the sending phone
  (`network/Addresses.kt`). The relay is not involved.
- **A live share** is a row in the sharing phone's Durable Object (`shares`),
  written and read over `/v1/location/...` (`network/LocationApi.kt`), with
  `push/LocationService.kt` sending a sealed point while the share lasts. The
  viewer asks only while its card is on screen.

### 6. Identity, registration and login

1. `crypto/Identity.kt` makes a Curve25519 identity key pair; its public half is
   the Maun ID — there is no user table to look it up in.
2. `crypto/DeviceKey.kt` makes a P-256 key inside Android Keystore that cannot
   leave the phone.
3. On the first connection the phone registers: identity key, device key, and a
   signature over a transcript both sides compute identically
   (`crypto/AuthTranscript.kt` and `registerTranscript` in `protocol.ts`).
4. Every later connection is a fresh `hello` nonce signed with the device key
   (`loginTranscript`). No password, no long-lived token to steal.
5. Member-only relays add an email login on top, once per phone
   (`backend/src/auth.ts`, `backend/src/members.ts`), and check the member list
   at every login and every `MEMBER_CHECK_MS` after.

### 7. Recovery

A member-only relay can hold one sealed identity backup per account
(`recovery` table in D1). The phone seals it with PBKDF2-HMAC-SHA256 over a
passphrase the relay never sees (`crypto/Recovery.kt`). Restoring is a
first-run path, and starting a new ID instead is a separate, explicit call
(`POST /v1/auth/replace`). What survives what, and what is deliberately not
built, is [RECOVERY.md](RECOVERY.md).

## Module responsibilities

### Android (`android/app/src/main/java/org/maun/messenger/`)

| Path | Responsibility |
|---|---|
| `Messenger.kt` | The long-lived object behind every screen: account, connection, fetch, restore, recovery, sign-out. The seam the UI talks to. |
| `ChatEngine.kt` | The conversation logic: encrypt/decrypt, outbox, receipts, requests, blocking, moves, reactions, association of files. |
| `Payload.kt` | The wire payload format between phones, and its validation. |
| `crypto/` | Identity, Keystore device key, Signal sessions and storage, file cipher, safety numbers, recovery, QR and contact codes. |
| `network/` | The relay connection and its back-off, HTTP APIs (files, avatars, location, auth), push address, Nominatim lookups, network watch. |
| `push/` | Wake handling, fetch work, the hourly fallback, notifications and channels, the live-location service, the connected service, the delivery trace. |
| `webrtc/` | Call setup, audio routing, ringing, the call service, Telecom. |
| `data/` | Room entities and DAOs, the SQLCipher key, the Signal key tables. |
| `ui/` | Every screen, the view model, and the theme. |
| `security/` | The phone fingerprint used for phone bans. |
| `voice/` | Recorder and player for voice messages. |
| `updates/` | The update check and the verified download. |
| `AppLock.kt`, `MediaSaver.kt`, `MessageSounds.kt`, `ProfilePictures.kt`, `Avatars.kt`, `Badges.kt`, `Places.kt`, `Attachment.kt`, `FileTransfers.kt` | One concern each, as named. |

### Relay (`backend/src/`)

| File | Responsibility |
|---|---|
| `index.ts` | The stateless front door: `/health`, the WebSocket upgrade, files, avatars, location routes, the calls check. Runs no storage of its own. |
| `mailbox.ts` | One account's Durable Object: registration and login, the queue, pre-keys, wake-ups and their retry chain, push address, shares, call credentials, retirement. |
| `protocol.ts` | Every frame shape, limit, timing and close code the two halves share. The canonical wire reference. |
| `crypto/webpush.ts`, `crypto/signatures.ts` | RFC 8291 encryption and VAPID signing of wake-ups; signature verification inside the Worker. |
| `auth.ts`, `members.ts`, `mail.ts` | Member-only mode: the email login, usernames, approvals, bans, the sealed backup, and the mail provider. |
| `admin-page.ts` | The administrator's page as one self-contained document. |
| `turn.ts` | Short-lived TURN credentials. |

## What the relay is not allowed to do

These are enforced by code, not by policy, and each has a test:

- It has no key for a message, a file, a picture, a live point or a backup —
  only the phone that owns it does.
- It cannot delete what it has never stored: uncollected messages expire after
  30 days, files after one day, live points the moment the share ends.
- It cannot answer about someone else's mail: a queue is only ever read by the
  logged-in owner of that mailbox, and a share only by the person it was started
  for (`test/location.test.ts` → "tells anyone else only that there is no such
  share"; `test/relay.test.ts` covers the login path).

## Deliberately absent

Groups, multi-device (a member has one ID per install), phone-to-phone
transfer, sealed sender, disappearing messages, voice/video conferencing and an
iOS app. Chat-history backup is designed but not built ([RECOVERY.md](RECOVERY.md)
§3.3). Nothing in this document should be read as a claim that any of these
exist.

## Where to read next

| Question | Document |
|---|---|
| What is this product, and what is it not? | [../PROJECT.md](../PROJECT.md) |
| How do I deploy it and use it? | [../README.md](../README.md) |
| How is a sleeping phone woken, and why is it shaped that way? | [NOTIFICATIONS.md](NOTIFICATIONS.md) |
| How do sessions, receipts, reactions and requests behave? | [CHAT.md](CHAT.md) |
| What does live location keep, and what does it refuse to? | [LOCATION.md](LOCATION.md) |
| What survives a reinstall? | [RECOVERY.md](RECOVERY.md) |
| What can each party see? | [SECURITY.md](SECURITY.md) |
| What is stored, where, and for how long? | [DATABASE.md](DATABASE.md) |
| Which route, which frame, which error? | [API.md](API.md) |
| How is any of it tested? | [TESTING.md](TESTING.md) |
| How do I work on it? | [../AGENTS.md](../AGENTS.md) |
| Why was it built this way originally? | [../TECHNICAL.md](../TECHNICAL.md), [../PLAN.md](../PLAN.md) |
