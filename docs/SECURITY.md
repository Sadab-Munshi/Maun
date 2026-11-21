# What is protected, and what is not

A plain statement of the security properties this code actually has, what each
one rests on, and where the limits are. It is written from the code at
`acc3494` (app 0.29.0, protocol 2). It is not an audit, and nothing here has
been reviewed by an outside party.

Read the limits section before repeating any claim from the top half: several
of the strongest-sounding properties have an honest exception next to them.

## The design in one paragraph

A Maun ID is the public half of a Curve25519 identity key; there is no user
table to look it up in. Two phones agree a Signal session (PQXDH, with Kyber
pre-keys) and everything after that is sealed phone to phone. The relay stores
ciphertext it has no key for, for as short a time as it can, and learns only
what addressing requires. Files, pictures, live-location points and the identity
backup are each sealed separately, with keys that travel only inside an
end-to-end encrypted message. The phone's own database is encrypted with a key
it cannot export.

## What is encrypted, with what, and where the key lives

| Thing | Sealed with | Where the key is | Code |
|---|---|---|---|
| Every message: text, receipt, reaction, reply, call offer and candidates, a sent place, a share invitation, a file's name and key | The Signal session for that pair (PQXDH + Double Ratchet, via libsignal) | On the two phones, in `signal_sessions` | `crypto/Sessions.kt`, `data/SignalTables.kt` |
| A file or voice note | AES-256-GCM, in 64 KB pieces, one key per file | Inside the encrypted message that carries it | `crypto/FileCipher.kt` |
| A profile picture | The same, one key per picture | Inside the encrypted message that carries it | `ProfilePictures.kt` |
| A live-location point | AES-256-GCM, one key per share, a fresh 96-bit IV per point | Inside the encrypted message that starts the share | `LiveLocation.kt` |
| The whole phone database | SQLCipher, AES-256, every page | A random 32-byte key in `noBackupFilesDir/db.key`, itself wrapped by an AES key in Android Keystore | `data/AppDatabase.kt`, `data/DatabaseKey.kt` |
| The identity backup | AES-256-GCM, key derived by PBKDF2-HMAC-SHA256, 600 000 rounds, over a passphrase the relay never sees; the Maun ID is bound in as additional authenticated data | Nowhere but the member's head | `crypto/Recovery.kt` |
| A wake-up | RFC 8291 encryption to the push app's own keys, signed with a VAPID key per mailbox | The push app has the private key; the relay keeps only the public one and the address | `crypto/webpush.ts` |
| Proving this is the same phone | A P-256 signing key generated through Android Keystore; the app never exports the private key | It stays behind the Keystore API. StrongBox / secure-hardware backing is attempted where available, but a software-backed fallback is allowed | `crypto/DeviceKey.kt` |

Transport is TLS everywhere (Cloudflare terminates it; the phone speaks `wss://`
and `https://` only).

## Identity and trust

- **Your ID is your key.** Two phones can compare a safety number out loud and
  be certain no one is in the middle (`crypto/SafetyNumber.kt`).
- **A login cannot be forged.** The relay issues a nonce; the phone signs it with
  its hardware device key over a transcript both sides build independently
  (`crypto/AuthTranscript.kt`, `loginTranscript` in `backend/src/protocol.ts`).
  There is no password and no long-lived bearer token on the socket.
- **An added contact's name is only as good as how it was added.** Scanning the
  code from the other person's screen proves the ID; typing a username does not,
  which is why `ContactEntity.verified` records which one happened.
- **The Worker names the sender from the authenticated mailbox, not from the
  `send` frame.** `test/messages.test.ts` → "names the real sender, whatever
  the frame claims" checks that behavior. This is not protection against a
  malicious or compromised relay; message authenticity still depends on the
  phone's Signal session and the identity the two people verified.
- **The badge means the administrator vouched for the address**, nothing more;
  it is read from the member list and cannot be granted by a phone
  (`verified` frame, `backend/src/mailbox.ts`).

## Who sees what

| Party | Sees | Does not see |
|---|---|---|
| Cloudflare, carrying the relay | IP addresses while a request is in flight; mailbox IDs; which mailbox sends to which; arrival times; sizes; the push endpoint a phone left | Any message, file, picture, point or backup in the clear. The Worker itself writes no log (`backend/src/index.ts` has no `console.` call) |
| The relay's administrator (member-only mode) | The email address ↔ Maun ID link, usernames, phone models, app versions, login counts, who is awaiting approval | Messages, or the passphrase that opens a backup |
| The phone's push service (Google's, Sunup, Mozilla's) | That a wake-up arrived, when, and for which app | What it was for. The payload is encrypted to the push app |
| The mail provider | The address and the login code, once per login | Anything else |
| Cloudflare's call service | That credentials were issued, and the media it relays while a call runs | The conversation itself; both phones use `IceTransportsType.RELAY` |
| OpenFreeMap (map tiles) | The area being looked at, by IP | Who is looking or who they are talking to |
| Nominatim (place names) | The coordinates being named, one request a second at most | Whose they are |
| Whoever holds the phone unlocked | Everything | — |

Nothing outside that table is collected by this project: there is no analytics,
no crash reporting and no advertising identifier. The optional Google push path
goes through UnifiedPush's embedded-FCM distributor rather than a Firebase
project of Maun's own (`unifiedpush-fcm` in `android/gradle/libs.versions.toml`);
which push app or distributor is actually carrying wake-ups on a given phone is
something the phone reports, not something this repository can prove.

## Deletion, and what actually goes away

| Action | What happens |
|---|---|
| Log out | The database, its key, cached media and the wake-up address all go; the relay is told to drop the address and the pre-keys |
| Leave (member-only) | The mailbox is retired: queue, pre-keys, shares and wake-up address deleted at once, and friends' phones are told `gone` |
| Banned | The same retirement, plus the phone's fingerprint on the banned list |
| A message collected | Deleted from the queue on `ack` |
| A message never collected | Deleted after 30 days |
| A file | Deleted after 24 hours, swept hourly |
| A live share | The point goes the moment it ends; the row is forgotten a day later |
| A picture | Stays until its owner replaces or deletes it; leaving or banning the account does not remove it |

## The limits, stated plainly

- **The relay knows who talks to whom, when, and how much.** It has to: it
  addresses the messages. There is no sealed-sender design here, and adding one
  would be a protocol change, not a setting.
- **The relay can refuse, delay or drop.** It cannot read, but it can withhold.
  Nothing in the protocol detects a relay that silently drops one message.
- **A member-only administrator can link an email to an ID**, and can see
  usernames and phone models. That is what running a closed relay costs.
- **A phone ban is a deterrent, not a wall.** It rests on a fingerprint of the
  device (`security/DeviceFingerprint.kt`); a determined person with root access
  or a rebuilt ROM can present something else.
- **The relay does not verify Android Key Attestation.** `DeviceKey.kt` asks the
  platform to embed a challenge in a key when it can, but no attestation
  certificate is sent to or validated by `backend/src/`. The relay proves only
  possession of the signing key. Whether a particular key is backed by
  StrongBox, another secure area, or software is therefore **unverified by the
  relay**. Android Key Attestation was planned in `PLAN.md` §M6 and remains
  unbuilt.
- **The recovery passphrase is only as strong as the member makes it.** The
  relay holds the ciphertext, so an offline guessing attack is possible; 600 000
  rounds of PBKDF2 makes each guess expensive, not impossible.
- **The wake-up address is a real identifier to the push service.** It reveals
  the phone to that provider, the way any app's notifications do.
- **A compromised phone loses everything.** The database key cannot leave the
  Keystore, but an attacker running code as Maun can ask Keystore to unwrap it.
  App lock and `FLAG_SECURE` (block screenshots) raise the effort; neither stops
  malware.
- **Not audited.** None of this has had an independent review. Every release
  also carries whatever Play Protect says about it — see
  [PLAY_PROTECT.md](PLAY_PROTECT.md).
- **The app never installs anything itself.** An update is handed to Android's
  installer, and the release's signing certificate is checked against the one
  compiled in (`updates/Updater.kt`, `EXPECTED_CERT_SHA256` in
  `release.yml`).

## If you are changing something security-sensitive

Treat any change here as requiring a reviewer who reads the whole path, not just
the diff:

- `crypto/` — anything at all.
- `Payload.kt` and `backend/src/protocol.ts` — a field, a limit, a frame.
- `backend/src/mailbox.ts` — authentication, the queue, wake-ups, shares.
- `backend/src/auth.ts`, `backend/src/members.ts` — the member list, codes,
  the sealed backup, bans.
- `data/DatabaseKey.kt`, `data/AppDatabase.kt` — never log a key, never widen a
  destructive fallback past version 1.
- `network/` — anything that decides where a request goes or what it carries.
- `updates/` — the certificate check and the download verification.
- `push/Notifications.kt` — what a locked screen is allowed to say.

Rules that must hold:

1. No plaintext, file name, key or passphrase ever reaches the relay.
2. Nothing that identifies a person is written to a log, and the Worker writes
   no logs at all.
3. A limit is a constant in `protocol.ts` with a test beside it, not a literal
   in a handler.
4. A new frame is parsed strictly: `fromBase64` with a bound, a shape check, an
   error code. Never trust a length, an id or a signature you have not checked.
5. If a change makes an old phone unable to talk, bump `PROTOCOL_VERSION` and
   say so in the release notes.

## Reporting something

Open an issue in `github.com/Sadab-Munshi/Maun` with the app version, the
Android version, and what happened. Do not paste a message, a key, a passphrase
or a screenshot of a conversation. There is no paid bounty or private channel;
if you need one, say so in the issue text and a way to reply privately will be
arranged before any detail is shared.
