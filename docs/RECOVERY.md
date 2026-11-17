# What survives a phone, and what has to be recovered on purpose

Round 8, item 4. This document records what this build actually keeps where,
the gap it leaves on a reinstall, and the design of the opt-in encrypted
backup that closes it. It is deliberately explicit about which parts are
**shipped** and which are **not shipped yet**, because a half-built recovery
path is worse than none: it teaches people to trust it.

Everything below marked *verified* was read out of the code in this repository
at commit `7f091de` and, where it says so, exercised by a test. Nothing here
was confirmed on a real phone.

---

## 1. What is where today (verified)

| Thing | Where it lives | Survives Log out | Survives Clear data / reinstall |
| --- | --- | --- | --- |
| Database (chats, contacts, meta, identity row) | Room, encrypted; key is 32 random bytes wrapped by an AES key in Android Keystore, file `noBackupFilesDir/db.key` (`data/DatabaseKey.kt`) | yes | **no** — the Keystore key is gone with the install, so the wrapped key can never be unwrapped |
| Long-term identity (`IdentityKeyPair`) | serialized in the `account` row inside the encrypted DB (`crypto/Identity.kt`) | yes | **no** |
| Profile picture, sealed | R2 through the Worker, under the Maun ID; the record with its key is in Room `meta` (`ProfilePictures.KEY_SELF`) | yes | picture file may survive in `filesDir` only if the OS kept it; **its key does not**, and R2 still holds it under the **old** ID |
| Username, membership, approval, tick | D1 `users` (`email` primary key, `maun_id`, `username`, `status`, `is_banned`, `verified`, `device_hash`, …) | yes | yes, on the relay |
| Devices seen | D1 `devices` per `maun_id` | yes | old rows stay under the old ID |
| Wake-up address | relay per mailbox, dropped on Log out | dropped | not restored until login |

Two consequences worth stating plainly:

* **Log out is a session action.** It keeps the DB key, the identity, the chats
  and the picture. Nothing is deleted, on the phone or on the relay.
* **Clear data / reinstall destroys the ability to read anything that was on
  the phone.** No server-side operation can bring it back, because the server
  never had a key. Only an export made *before* the loss can.

## 2. The gap on a reinstall (verified, with references)

A fresh install makes a new identity by itself (that is how first run works).
If the person then logs in with the same email address on a member-only relay:

1. `admit()` in `backend/src/members.ts` (around lines 423–449) looks up
   `users` by email, keeps the row's **`username`**, overwrites **`maun_id` with
   the new identity**, increments `reinstalls`, and returns the old ID as
   `movedFrom`.
2. `Mailbox.admit()` in `backend/src/mailbox.ts` (around lines 479–486) sees
   `movedFrom !== mailboxId` and **retires the old mailbox**: its queue is
   destroyed and anyone writing to it is told the ID has moved.
3. The Android app is told only `email` and `username`. It has **no reference to
   `movedFrom` anywhere** (grep over `android/app/src/main/java`), so the person
   is never told that their account was re-pointed; their contacts learn it
   through the ordinary "they moved" notice instead (`ChatEngine.moved`).
4. The administrator's tick (`verified`) is keyed by `maun_id`, so it is lost
   with the old ID; the R2 picture, also keyed by ID, is orphaned.

So today a reinstall **does** quietly give a new crypto identity the old
username. Contacts get a new safety number, and everything queued for the old
ID is dropped. That is exactly the outcome item 4 of the brief says must not
happen silently.

Nothing about this is a bug in the encryption: it is a *product* decision that
was never made explicit. Two ways out, and this document proposes the second:

* **(A) Refuse.** A login whose email already has a `maun_id` fails with
  "this address already has a Maun ID; restore its key, or start a new
  username". Correct, but it locks out every reinstall until recovery exists
  and is written into the app, so it cannot ship alone.
* **(B) Consent + recover.** Keep the login working, but (i) require the app to
  say what is about to happen and get an explicit answer, (ii) offer the
  encrypted backup below so that answering "keep my old ID" is actually
  possible, and (iii) stop calling the result the same account: the relay tells
  the app it moved, the app says so, and the new ID carries a new safety number
  and no old history.

**(B) is the recommendation.** It needs a `moved: true` field on the relay's
login answer, a one-time screen in the app, and the backup below. None of that
is in this build yet; the relay half is a dozen lines, the app half is the work.

## 3. Opt-in encrypted identity backup — design

Goal: after Clear data or a new phone, the *same* Maun ID, the *same* safety
number and the *same* profile picture can come back — and only when the person
can decrypt the backup they made. The relay stores ciphertext and nothing else.

### 3.1 What is in the backup

One blob, versioned, sealed with a key derived from something only the person
knows:

```
identity-backup v1
  maun_id              the ID the key belongs to (checked after decryption)
  identity_key_pair    the serialized long-term key pair (crypto/Identity.serialize)
  signal_registration_id
  avatar_record        the sealed picture record and its key (ProfilePictures.KEY_SELF)
  created_at, version
```

Nothing else. Chats are not in it — see 3.3. The picture *file* is not in it
either; the R2 object stays where it is, under the same ID, and the record
above is what makes it readable again.

### 3.2 Crypto

* **KDF**: PBKDF2-HMAC-SHA256, 600 000 iterations, 32-byte output, random
  16-byte salt. (Argon2id is better but is not on Android without a new native
  dependency; PBKDF2 at that count is the honest, dependency-free choice, and
  the count is stored in the blob header so it can be raised later.)
* **Cipher**: AES-256-GCM, random 12-byte IV, AAD = `"maun.identity.v1"` + the
  Maun ID, so a blob cannot be moved between accounts.
* **Recovery key shown to the person**: the 32-byte KDF output written as a
  24-word BIP-39-style phrase is *rejected* here — it invites people to
  transcribe a key they cannot check. The passphrase is the person's own, with
  a minimum length and a plain warning that a weak one can be attacked offline.
* **Where the key is not**: never in D1, never in R2 metadata, never logged
  (the app's rule already: no keys, no tokens, no plaintext in logcat).

### 3.3 Chats, separately

Chat history backup is a **separate, independent opt-in**, encrypted with the
same passphrase but a different key and a different object, described as what
it is: a sealed copy of the message database, uploaded in chunks, as large as
the history. It costs R2 storage per person and restores nothing about identity
by itself. It is out of scope until the identity half is proved on real phones.

### 3.4 Relay endpoints (proposed)

| Frame | Who may call it | What it does |
| --- | --- | --- |
| `put_recovery` | a logged-in mailbox, for its own ID only | stores `{salt, kdf, blob}`; replaces the previous one; rate-limited |
| `get_recovery` | a logged-in mailbox **whose proven email maps to this ID** | returns `{salt, kdf, blob}`; nothing else |
| `drop_recovery` | a logged-in mailbox, its own ID | deletes the blob; the account is no longer recoverable |

A new table `recovery (maun_id TEXT PRIMARY KEY, salt TEXT, kdf TEXT, blob TEXT,
updated_at INTEGER)`, with the blob size capped (identity blobs are ~1–2 KB;
cap at 8 KB so the endpoint cannot be used as free file storage).

Two properties this gives and one it does not:

* The relay can hand the blob back only to someone who has already proved the
  email address — so a stranger cannot download and crack it offline. *(This is
  the whole reason retrieval sits behind the member login rather than behind
  the public ID.)*
* A stolen D1 dump is ciphertext, and a stolen blob plus a weak passphrase is
  still crackable offline. That risk is the passphrase's, not the design's, and
  the UI has to say so.
* The relay still learns *that* a member recovered, and when. It already learns
  logins; this adds no new content.

### 3.5 Restore flow (fresh install)

1. First run shows "**Sign in to an existing account**" next to "Set up Maun".
2. Email address → code from the relay, exactly as the current member login does.
3. If the address has a `maun_id` and that ID has a recovery blob, the app says:
   "This address has Maun ID `ab12…`. Restoring it needs the recovery
   passphrase you set on the old phone."
4. Passphrase → blob downloaded → decrypted **on the phone** → the ID inside is
   checked against the relay's claim (`maun_id`) before anything is installed.
   A wrong passphrase fails the GCM tag, not a guess.
5. The identity is written into the (new, empty) database; the app restarts into
   the account: same ID, same safety number, same picture (its record decrypts
   and re-fetches the R2 object), pending messages that the relay still holds
   arrive, chats are empty unless a chat backup was also restored.
6. The old device, if it exists, is retired: `devices` already lists phones per
   ID and the relay already shows them to the administrator.

If there is **no** blob, the app must say the limitation instead of glossing
over it: "No recovery backup was made for this ID, so its keys cannot be
brought here. You can start a new Maun ID with a new username; your contacts
will see a new safety number and your old chats cannot be restored."

### 3.6 Keeping it honest in the UI

* The switch lives in **Profile → Account**, not in the notification settings,
  and it is **off** until the person turns it on and confirms the passphrase by
  typing it twice.
* While it is on, the account screen says when the last backup was made.
* Turning it off deletes the blob on the relay (`drop_recovery`) and says so.
* Nothing about it appears in Settings → Notifications. Log out, "Remove this
  account from this phone", "Delete account" and "Recovery" stay four separate,
  separately-worded actions, and the destructive one is never the default.

## 4. The four actions, as they must read

| Action | Stops the session | Deletes local data | Deletes relay data | Needs the recovery key |
| --- | --- | --- | --- | --- |
| **Log out** (shipped, this round) | yes | no | no | no — everything is still on the phone |
| **Remove account from this phone** (not built) | yes | yes | no | only to come back with the same ID |
| **Delete account** (admin-side today) | yes | yes | yes | no, and it must not be reachable from the same dialog as Log out |
| **Recovery** (not built) | — | — | — | yes |

## 5. Status, and what is left

**Shipped in this round:** Log out (session only, everything kept, wake-ups
withdrawn), the signed-out screen that states what is kept, and the separation
of that action from anything destructive. Verified by `SignOutTest` (the
connection decision), by `ScreensTest` pictures of the two new screens, and by
CI run 44078799334 compiling and testing the app.

**Not shipped:** the `put_recovery` / `get_recovery` / `drop_recovery`
endpoints, the KDF+cipher helper, the restore screen, `moved: true` on the login
answer. They are specified above rather than half-built, for two reasons: the
reinstall decision in §2 needs to be taken with the product in view, and none of
this can be called done without testing on real phones (a restore that half
works destroys the only copy of a key).

**Order of work proposed:** (1) `moved` on the login answer + the consent
screen, so nothing is silent any more; (2) the backup helper and the three
frames with relay tests; (3) the restore flow; (4) chat backup.
