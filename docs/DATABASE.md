# What is stored, where, and for how long

Four stores in three places. Nothing else holds anything: no analytics, no
crash service, no log of requests (the Worker never logs — the comment at the
top of `backend/src/index.ts` says so, and there is no `console.` call in it).

| Store | Lives on | Holds | Read by |
|---|---|---|---|
| Room, in `maun.db` | The phone | Messages, contacts, the identity key, the Signal sessions, the outbox | That phone only |
| Durable Object SQLite | Cloudflare, one per account | The queue, pre-keys, the wake-up address, live shares, call usage | The relay, for that account |
| D1 | Cloudflare | The member list, login codes, the sealed backup | The relay; the administrator's page |
| R2 | Cloudflare | Encrypted files and pictures | Anyone the phone hands a key to |

Read at `acc3494` (app 0.29.0, protocol 2). Retention figures are constants in
`backend/src/protocol.ts`; the migration story is in the code named for each
store.

## The phone: `maun.db`

Room on top of SQLCipher (`data/AppDatabase.kt`), so every page on disk is
AES-256 encrypted with a random 32-byte key. That key is kept in
`noBackupFilesDir/db.key`, wrapped by an AES key that lives in Android Keystore
and never leaves it (`data/DatabaseKey.kt`). If the wrapped file is missing or
cannot be unwrapped — a restore from an old backup, a Keystore reset — the key
is made again and `maun.db` is deleted rather than left unreadable. That is why
the app is excluded from Android backups.

**Version 7**, `exportSchema = true`, so `android/app/schemas/org.maun.messenger.data.AppDatabase/<version>.json`
holds 2 through 7. Every step is a Room `AutoMigration`; what each added, read
from those committed schemas:

| Step | Added |
|---|---|
| 2 → 3 | `messages`, `outbox`, `blocked`, `signal_sessions`, `signal_prekeys`, `signal_kyber_used` |
| 3 → 4 | `contacts.username` |
| 4 → 5 | `messages.savedToGallery` |
| 5 → 6 | `outbox.wakes`, and the `reactions` table |
| 6 → 7 | `messages.reply_to` |

There is a destructive fallback **for version 1 only**
(`fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)`), because that
build held nothing worth keeping; from version 2 on the database holds the
identity key, so a later change needs a real migration or CI will refuse it.

| Table | Rows | Notes |
|---|---|---|
| `meta` | key/value | The last announced unread sequence, current/uploaded/rejected push tuples, and temporary multipart Signal ciphertext. The newest 96 part rows are retained at most. Completion and logout clear parts. |
| `account` | one | This phone's ID, its identity key pair (which exists nowhere else), the relay address, whether it has registered. |
| `contacts` | one per person | ID (the identity public key in hex), name, whether the ID was read from their screen with the camera, when it was added, their username if the relay has usernames. |
| `messages` | one per message | `seq` is the local order; `id` is the sender's. `reply_to` holds only an ID — the quote is drawn from this phone's own copy. `receipt` is the incoming receipt level, `status` the outgoing one, `unread` drives the badge, `saved_to_gallery` remembers a saved attachment. |
| `outbox` | pending rows | The plaintext payload **and** its sealed bytes; from then on exactly those bytes are sent until the relay confirms. `wakes` says whether this row should wake the other phone. |
| `reactions` | one per side per message | `(message_id, mine)`. Trimming a reaction deletes the row. Only a later `at` may replace an earlier one, which settles out-of-order reactions. |
| `blocked` | one per blocked person | Their ID and when. |
| `signal_sessions` | one per conversation | libsignal session records. |
| `signal_prekeys` | keyed by kind and key id | The private half of each of this phone's own pre-keys — signed, Kyber and one-time — as libsignal serializes them. |
| `signal_kyber_used` | one per accepted session start | Kept so a replay of a session start is refused. |

WorkManager may retain an inline record as input to expedited local work. The whole record is AES-GCM sealed under a separate Android Keystore key before it enters the scheduler's database. The partial records and presentation checkpoint reuse the existing SQLCipher `meta` table. The Room entity schema stays at version 7.

Files and pictures are not in the database. Sealed copies live in
`filesDir/media`, decrypted copies in `cacheDir/media`, and a sweep deletes what
no message refers to (`FileTransfers.kt` → `sweep`). Nothing is ever uploaded
except sealed bytes.

**Changing it.** Add the entity field, add an `AutoMigration` (a hand-written
`Migration` only when Room cannot infer it), commit the regenerated schema JSON),
and let `build-android.yml` run the schema check. A pull request that edits an
entity without the schema is a broken build, not a style matter.

## The relay: one Durable Object per account

Tables are created on first use in `ensureTables()` in `backend/src/mailbox.ts`.
Changes are additive `ALTER TABLE` guarded by a `pragma_table_info` check — the
`wake_retry.seen` column is the pattern to copy.

| Table | Holds | Goes away |
|---|---|---|
| `account` | The identity key, the registration nonce, the day it registered | Never; deleting it is what "leave" does |
| `keys` | Signed pre-key and Kyber pre-key, with their signatures | Replaced by the next `put_keys` |
| `otks` | One-time pre-keys | Used as handshakes take them; replenished by the phone |
| `queue` | One row per waiting message: sender, kind, sealed body, arrival time and wake class (quiet/chat/call) | On `ack`, on expiry (30 days), on ban, or when the queue is full |
| `push` | Where to wake this phone, its keys, last dispatch, daily count, recipient ID, inline capability, outstanding call flag and dispatch token | On `drop_push`, or when the push service says the address is dead |
| `push_since` | When that address was left | With it |
| `vapid` | The key pair wake-ups are signed with | With the mailbox |
| `push_result` | What the push service answered last | Overwritten each time |
| `wake_retry` | The moment the relay looks for a phone again, how many times it has woken it, wake class, eligible queue depth and ACK progress | Cleared at a retry after eligible rows are gone, or when the retry budget ends |
| `files` | The token for the HTTPS routes, and the day's upload count and bytes | Renewed at every login; the day's counts reset at midnight UTC |
| `turn_use` | How many sets of call credentials were handed out today | Reset daily |
| `retired` | One row while this ID's owner has moved to another one, or a member-only relay has thrown it out | Until this same ID successfully logs in again; its queue and shares were already deleted when it was retired |
| `shares` | One row per live share: viewer, expiry, the newest sealed point, whether it was stopped | The point is cleared the moment the share expires, and the row is forgotten a day later |

A mailbox that is emptied — its owner left, set Maun up again, or was banned —
takes its queue, pre-keys and shares with it at once, and answers `gone` for a
while afterwards so a friend's phone learns it is over rather than retrying.

## D1: the member list

Created on first use from `SCHEMA` in `backend/src/members.ts`, and only on a
relay deployed with a member list. Changes are `ALTER TABLE` additions guarded
the same way, in `ready()`.

| Table | Holds |
|---|---|
| `users` | One row per address: Maun ID, username, approved/pending/banned, when it was created and last used, the phone fingerprint and model, the app version, counts of logins and reinstalls |
| `devices` | One row per phone ever seen for an ID: fingerprint, maker, model, OS, first and last seen |
| `banned_devices` | Fingerprints the administrator banned, with the address that was attached and the moment |
| `admin_tries` | Wrong administrator passwords in a row, and until when the page is locked |
| `codes` | One login code per address in flight: the hash, when it expires, attempts, when it was sent, the day and count |
| `tickets` | A verified login's short-lived ticket, and whether it was issued for "start a new ID" |
| `recovery` | The sealed identity backup: salt, KDF name, ciphertext, when it was last replaced. The relay has no key for it |
| `sessions` | Live administrator sessions by hash |
| `mail_days` | How many mails the relay sent each day, against the daily cap |

The hourly cron (`scheduled` in `backend/src/index.ts`) sweeps expired files and,
on a member-only relay, expired codes, tickets and sessions.

## R2: the bucket

| Prefix | What | Life |
|---|---|---|
| `<64 hex>` (bucket root) | One sealed file, named by the relay on upload and handed back as `key` | Deleted after 24 hours, and swept hourly |
| `avatar/<mailbox id>` | One sealed picture per account, replaced on each upload | Until its owner explicitly deletes or replaces it; leaving or banning the account does not sweep the avatar |

Neither is readable without a credential, and neither is readable in a useful
sense without the key, which travels only inside an end-to-end encrypted
message. Quotas (250 MB and 200 uploads a day, 256 KB a picture) are enforced in
`backend/src/mailbox.ts` before anything is written.

## What is deliberately not stored

Message plaintext, contact names, file names, picture keys, share keys, the
identity key's private half off the phone, and any record of who talked to whom.
The relay keeps the sender's ID on each queued message while it waits — that is
the one piece of addressing it cannot do without, and it goes when the message
does. See [SECURITY.md](SECURITY.md) for what that leaves visible.
