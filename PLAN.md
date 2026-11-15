# Build Plan: Maun, a zero-tracking private messenger (Android APK)

Based on `PROJECT.md` and `TECHNICAL.md`.
App name: **Maun** (Hindi/Sanskrit for "silence"). Package ID: `org.maun.messenger`.

## Progress

| Milestone | State |
|---|---|
| M0. Foundations and risk checks | **Done.** Setup check passed on a real phone against the deployed relay. |
| M1. Identity and login | **Done.** Two phones registered and added each other by QR. |
| M2. Text messaging | **Done.** Two phones exchanged messages both ways, with Sent, Delivered and Read. |
| M3. Background delivery | **Built, tested in CI and deployed (app 0.3.1, relay still protocol 2). "Stay connected" works on a phone. Waking through Google's push service is waiting for the two-phone check.** |
| App 0.4.0: look and small features | **Built and tested in CI.** New look (chat list, bubbles, ticks, day lines, light and dark); delete and copy a message; older messages load on scrolling back; rename a contact; safety number; app lock; screenshot blocking; a Settings screen. No change to the relay or the database. App lock, safety number and screenshot blocking were planned for M6 and are done early. Checked on rendered pictures of the screens (the Screens workflow), not yet on a phone. |
| M4. Files | **Files built, tested in CI and deployed (app 0.5.0).** Paperclip, encryption in 64 KB pieces with a key per file, upload and download with progress, pictures shown in the conversation, save to the phone. Relay: 25 MB limit enforced before reading, daily allowance, refusal after 24 hours, hourly sweep. Checked against the deployed relay: a 26 MB upload is refused (413), 25 MB is accepted, a stored file comes back byte for byte. Voice messages followed in 0.8.0. |
| Member-only mode (asked for by the owner; not in the original spec) | **Built, tested in CI and deployed, switched off (app 0.6.0).** The relay sends its own email codes through Resend (or Brevo), members pick a unique username, a new member waits for the administrator, who approves, bans and removes people on a web page at `/admin`. Phone fingerprint recorded. The app's whole login path was run once against a local member-only relay (register, wait, approve, find by username, ban, remove) and the admin page was driven in a real browser. Not yet tried with a real mail key or on a phone: it needs the owner's Resend settings. See "Changed in app 0.6.0". |
| M4. Voice messages (app 0.8.0) | **Built, tested in CI.** Hold to record (Opus in Ogg, mono, about 20 kbit/s), slide left to cancel, five minutes at most; sent as an encrypted file with its length; fetched automatically; played in the conversation from memory. Also in 0.8.0: a fresh install makes its ID without the welcome screen, and files and pictures get read ticks. Recording and playing cannot be run here (no phone, no emulator): checked by compiling, by the unit tests around them, and on rendered pictures. |
| M5. Calls | Not started. Needs a Cloudflare Realtime TURN key from the owner. |
| App 0.7.0 and relay: starting over, phone bans, admin page | **Built, tested in CI and deployed.** Contacts follow a member to their new ID after a reinstall; members who write are added by username; phones are reported with the login and can be banned; the admin page needs a password as well as the email code, can have its own host name, and shows a searchable table. Run once end to end against a local member-only relay (two phones, two reinstalls, a phone ban) and in a real browser. Database version 4. Restoring old messages after a reinstall is **not** built: see "Open: encrypted backup". |
| App 0.6.0: no loading flicker | **Built, tested in CI.** Chats at the top of the list are read ahead and recent ones kept in memory; pictures get a small encrypted copy and keep their room in the list; "Connecting…" appears only after three seconds. Not measured on a phone. |
| M6. Hardening and release | Not started |

### M3 results

Checked automatically, on every push:

- Relay: 100 tests pass in Cloudflare's local runtime. The 24 new ones cover: the wake-up encryption producing exactly the worked example of RFC 8291; a wake-up being readable only with the phone's keys; one wake-up when a message arrives for someone offline, carrying nothing but the word "wake" and no ID of anyone; no repeat until the phone has been, except one every ten minutes; none while the phone is connected; a wake-up for a message lost as the phone disconnected, but not over and over for one it never accepts; the daily cap of 200; forgetting an address the push service reports dead, but not one it handed out minutes ago, which is tried again instead; retrying after a failure; the signature on each wake-up verifying against the key the phone was told, with a different key per mailbox; and refusing addresses that are not public https names (private networks, IP numbers, other ports).
- App and relay together: 16 tests. The 4 new ones cover a phone woken to fetch: it takes in everything that was waiting, sends the delivery receipts, and can disconnect at once with nothing left behind in either direction; and the wake-up address reaching the relay once, again when it changes, and being withdrawn when the push app is gone.
- What a notification says is tested not to contain message text or anyone's ID.
- Lint reports 0 errors. The debug APK is 72 MB (66 MB before M3).

Checked by hand, from a development machine:

- **Deployed relay to Google's push service and back:** a script standing in for Google Play services on a phone asked Google for a Web Push address for the relay's key; a message was sent to an offline account on the deployed relay; the wake-up arrived through Google and decrypted to "wake". 6 times out of 6 with the relay as it is now: five within half a second, one after 15 seconds (see "a new address" below). Before that fix, 3 out of 4.
- **A phone that is offline when the message is sent:** the stand-in connected to Google 25 seconds after the message; the wake-up Google had kept arrived 0.3 seconds later.
- **Deployed relay to Mozilla's push service and back, 4 times out of 4:** a script standing in for the Sunup app registered with Mozilla's push service using the relay's key; a message was sent to an offline account on the deployed relay; the wake-up arrived through Mozilla about 0.8 seconds later and decrypted to "wake".
- The same through ntfy.sh works from a relay on a development machine and **failed from the deployed relay 4 times out of 4**, see below.
- These checks left about 30 unused test accounts on the relay, each with one undelivered test message that expires after 30 days.

Checked on a phone:

- **"Stay connected" delivers with the app closed.** Reported from a phone on 0.3.0.

Not checked yet, because it needs phones:

- **The Android side of being woken by a push service has never run on a phone:** asking Google Play services (or a push app) for an address, being woken through it, WorkManager starting the fetch with the app closed, starting again after a restart. It compiles, passes lint, and the logic underneath is tested, but phone makers' battery savers are exactly what breaks this kind of feature. The stand-in used above talks to Google the way Chrome does, not the way Play services on a phone does.
- The delay between sending and the notification on a phone, and the battery cost of "Stay connected" over 24 hours.

### Open: encrypted backup

The owner asked whether chats and files could be synced to the cloud, end-to-end encrypted, so that a reinstall loses nothing. The original overview excludes cloud backups, and 0.7.0 does not add one: it makes a reinstall harmless for everyone else (contacts follow the new ID) and leaves the reinstalled phone empty.

A backup that the relay cannot read needs a secret that only the user has and that survives deleting the app: a recovery password (or a long recovery code written down). The email login cannot be that secret, or the relay's owner could read every backup. With it, the phone would upload its identity key, contacts and messages encrypted under a key stretched from the password; after a reinstall, email code plus recovery password would bring back the same ID and the chats. Forgetting the password would mean starting over as now. Files are a separate question: the relay deletes them after a day, so backing them up means storing them for as long as the account lives. To be decided by the owner before it is built.

### Changed in app 0.7.0

- **The relay now refuses messages for an ID nobody will read**, answering `gone`, where it used to store them. On a member-only relay this costs one look at the member list per recipient every ten minutes.
- **The relay is trusted to say where a username went.** A contact is moved to a new ID on the relay's word, marked unchecked, and the conversation says so. This is the same trust as adding by username.
- **Members who write are added as contacts automatically** on a member-only relay, where the administrator has approved everyone. On an open relay strangers still arrive as requests.
- **The phone report travels with the login**, so that a banned phone is refused at the door. It gains a lasting phone ID (unchanged by system updates and by deleting the app's data) and the app's version.
- **The admin page has a password.** The owner asked for more than the email code. Both are needed now.

### Changed in app 0.6.0

- **Cloudflare Access is gone; the relay runs its own email login.** The owner found the Access setup too hard and asked for a mail service instead. The relay sends a six-digit code (Resend, or Brevo where there is no domain), exchanges it for a one-time ticket, and the phone shows the ticket once. Codes and tickets are stored hashed, a code has five tries and ten minutes, and sending is capped per address and per day.
- **Anyone may ask to join; the administrator approves each one.** Chosen by the owner. A waiting member cannot log in to the relay at all, so they cannot write to anyone or be found.
- **Usernames, and finding people by them.** The original overview excludes public discovery. Usernames exist only on a member-only relay and only approved members can look one up. The relay is trusted to map a username to the right ID; safety numbers remain the check.
- **An admin page at `/admin`.** A single page with no outside resources, a strict content policy, and a session cookie that scripts cannot read and other sites cannot send.

### Changed in app 0.5.0

Asked for by the project's owner, and each a departure from the original overview ("no phone number, SIM or email", "no analytics"):

- **A relay can be member-only.** An email address is then tied to each ID and kept on the relay with the phone's make, model and a fingerprint. The owner of such a relay knows which person writes to which. It is a mode of the relay, off unless `ADMIN_EMAIL` and a mail key are set; an open relay still knows nobody.
- **The email login is used once per phone, not on every request.** The request was to attach a login token to every request. Such tokens expire and a closed app cannot do an email login, so messages would stop arriving until the user logged in again. Instead the login ties the address to the ID once and its ticket is then deleted; every connection is still signed by the phone's hardware key, and the relay asks its member list at every login and at most a minute late on an open connection. Chosen by the owner when asked.
- **Files go through the Worker, not to a presigned R2 link.** A presigned link cannot cap the size on the server and needs R2 API keys kept as secrets. The Worker checks the sender and the size, then streams to the bucket. Chosen by the owner when asked.
- **HTTP requests carry a token from the relay login.** `ready` hands out a `files` token, valid until the next login; the app's OkHttp interceptor adds it and watches for `403 {"error":"banned"}`.
- **Messages are still fetched over the WebSocket after a wake-up**, not over an HTTP API: that part was already built and delivers in order with acknowledgements.
- **The fingerprint is recorded, not enforced.** It includes the system build, so an Android update changes it; blocking on a change would lock members out. `users.device_changed_at` shows the change to the owner.
- **A ban deletes messages and files on the phone, not contacts or the ID**, so that it can be undone.

### Changed while building M3

- **Google's push service wakes the phone where no push app is installed (0.3.1).** This reverses the spec's "without Google Firebase (FCM)". Reason: the alternatives each ask something of the user, a second app to install or a permanent notification, and the request was for delivery that works like WhatsApp's with nothing running. How it is done keeps most of what the spec was after:
  - No Firebase project, no Google library in the app, no Google account or key for the relay. The app asks Google Play services, which the phone already runs, for a standard Web Push address. The relay sends it the same encrypted, signed wake-up it would send any push service.
  - **What Google learns:** that this phone has Maun, that it was woken, and when. Not who wrote, not the message, not the phone's Maun ID.
  - It is used only when no push app is installed, and "Do not use Google" on the home screen turns it off. A phone without Google Play services is unaffected.
- **A new address may be refused for a few seconds.** Google answered "gone" (HTTP 410) for 8 of 20 addresses when a wake-up followed straight after creating them, and accepted every one within 10 seconds. The relay used to drop an address on "gone". For ten minutes after an address is left, it now tries again after 5, 10 and 15 seconds and keeps the address. Whether Play services on a phone shows the same delay is not known.
- **Known limit:** every wake-up is sent as urgent, including those for delivery and read receipts, which show no notification. Android may slow down urgent wake-ups for an app that often shows nothing for them. Not observed; to watch on phones.
- **The public ntfy.sh server does not work with this relay.** ntfy.sh limits its free service per sending address, and all Cloudflare Workers share a few. The deployed relay's wake-ups to ntfy.sh timed out all four times they were tried; others report the same ([ntfy issue 1963](https://github.com/binwiederhier/ntfy/issues/1963)). The spec's "UnifiedPush (ntfy)" therefore becomes **UnifiedPush with Sunup** (Mozilla's push service) as the recommended push app. ntfy still works with a self-hosted ntfy server, or a relay that is not on Cloudflare.
- **Wake-ups are signed (VAPID), with a key per mailbox.** Mozilla's push service only takes wake-ups from a sender the phone named in advance. Each mailbox makes its own key pair; the phone learns the public half at login and passes it to the push app. A relay-wide key would have needed a secret configured by hand and would have let the push service see which phones share a relay.
- **Wake-ups are encrypted even though they say nothing.** UnifiedPush now requires Web Push encryption. The content is always the word "wake".
- **What the push service learns:** that this phone was woken, when, and from Cloudflare. Not by whom, and not how many messages are waiting: there is one wake-up until the phone has collected, however many arrive.
- **Ways to get messages with the app closed**, in the order the app picks them: a push app wakes Maun (no extra battery; needs Sunup or similar installed); Google's push service wakes Maun (no extra battery, nothing to install; see above); "Stay connected" keeps the connection open behind a permanent notification (no other app or server involved; uses more battery; off until turned on); and an hourly check that always runs, as a safety net.
- **Notifications never contain the message.** They say that a message arrived and, on an unlocked phone, the contact's name. Android stores notification text outside the app's encrypted storage and lets other apps with notification access read it.
- **The relay tells the phone what became of its last wake-up**, so a push service that turns the relay away shows up on the home screen instead of looking like silence.
- **No protocol bump.** The relay still speaks protocol 2 and 0.2.0 keeps working against it, without background delivery.
- **Known limit:** a wake-up address on a port other than 443 is refused, which rules out some self-hosted push servers.
- **Known limit:** tapping the notification opens the chat list, not the conversation.
- **Known limit:** the ping that keeps the connection alive goes out every 30 seconds, also in "Stay connected". Its battery cost is unmeasured.

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
- **The push provider (Google, or Mozilla with Sunup) sees the phone's IP and the timing of wake-ups**, never content. Google also sees that the phone has Maun installed.
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
- Check whether the UnifiedPush library now requires encrypted (Web Push) payloads rather than an empty ping. (It does. See "Changed while building M3".)

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
- ~~App lock; safety-number screen so two users can verify each other's keys; screenshot blocking option.~~ Done in app 0.4.0.
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
