# How a message reaches a sleeping phone

This is the path a message takes when Maun is not on screen, what was found
wrong with it, and what was changed. It was written from the code, not from a
phone: what has been *proved* and what is still *suspected* are kept apart.

## The path

1. **The sender's phone** encrypts the message (Signal/`libsignal`) and sends it
   over its WebSocket to the recipient's mailbox Durable Object.
2. **The mailbox** (`backend/src/mailbox.ts`) stores it in the `queue` table and,
   if the owner is not connected, wakes the phone: `store()` → `wake()` →
   `postWake()`.
3. **The wake-up** is a Web Push request: `POST https://fcm.googleapis.com/fcm/send/<token>`
   when Google's push service is what wakes the phone. It is signed with the
   mailbox's own VAPID key, the body is RFC 8291 encrypted for the phone, and it
   says one word: `message` (or `call`, for a call that is ringing). It carries
   no plaintext, no sender and no message bytes. Headers: `TTL: 86400`,
   `Urgency: high`.
4. **Google's push service** — there is no Firebase project and no Firebase SDK
   anywhere in Maun. `org.unifiedpush.android:embedded-fcm-distributor:3.1.0`
   asks Play services for a Web Push endpoint using the app's VAPID key, and
   Play services delivers to the app. This is why requests to
   `fcm.googleapis.com` show up in the Worker's metrics: they are step 3.
   (A UnifiedPush app such as Sunup, when one is installed, is the same step
   with a different operator.)
5. **`WakeService`** (`push/WakeService.kt`) is the app's `PushService`. On a
   wake-up it decrypts nothing itself — the connector hands it the plaintext
   only when the Web Push decryption succeeded, and anything that did not
   decrypt is dropped, so someone who merely knows the address cannot make the
   phone work. For a `call` it starts `RingService` first.
6. **The fetch.** `messenger.fetchSoon()` starts a connection in the process,
   and `FetchWorker.schedule()` enqueues expedited work, which is what Android
   lets finish if the process is killed. Either way: log in, the mailbox pumps
   the queue down the socket, the phone decrypts, stores, and `ack`s each
   message; the mailbox deletes what is acked.
7. **The notification** comes from the database, not from the push: `Messenger`
   collects the chat summaries, `announceUnread()` builds an `Announcement` and
   `Notifications.showMessages()` posts it — on a channel chosen by the user's
   sound setting, never quoting a message on a locked screen.

## Confirmed causes (visible in the code)

These are defects or gaps that can be pointed at in the source. Each is proved
by a test that fails without the change.

1. **A connection that looks alive but is not stopped the wake-up entirely.**
   `store()` only woke the phone `if (!this.connected())`, and "connected"
   means "a socket is logged in on this mailbox" — not "the phone is
   listening". Android freezing the process, a phone changing from Wi-Fi to
   mobile data, or a lost network can leave a socket that the mailbox still
   sees as ready; the message went into that socket and was never acknowledged,
   and no wake-up was sent at all. The message then waited for the *next*
   message, or for the phone to come back by itself (the hourly worker, or the
   user opening the app). This is the mechanism that produces "missing" and
   "very late" together.
   → Fixed: `wakeIfNotTaken()` — four seconds after handing a message to a
   connected phone, the queue is looked at again; if the message is still
   there, the phone is woken as it would have been. Test:
   *wakes a phone whose connection looks alive but has gone quiet*, and its
   converse *says nothing more once the phone on the connection has taken the
   message*.

2. **A wake-up could queue behind a retrying fetch, and later could cancel one.**
   Two rounds of trouble in the same place. First, a wake-up that arrived while
   an earlier fetch was in WorkManager's backoff waited for the backoff (30 s
   by default, growing), so two messages in a row could be a minute apart.
   The first fix made the new fetch *replace* the waiting one — which fixed the
   wait but introduced a worse failure: a wake-up arriving mid-fetch **cancelled
   the fetch in progress**, throwing away the decryption and acknowledgement of
   half a batch, so the phones re-did it from the start.
   → Fixed (this round): the worker no longer uses WorkManager's retry at all.
   A fetch that could not finish appends its own next attempt as it ends
   (`schedule(context, attempt + 1)`, `ExistingWorkPolicy.APPEND_OR_REPLACE`),
   so there is no backoff to wait behind; and a wake-up *appends* to whatever
   is queued or running instead of replacing it, so nothing in flight is ever
   cancelled. The relay still limits wake-ups for one phone to one per 5 s
   (`PUSH_REPEAT_MS`), which bounds how many fetches can pile up.

3. **The first announcement after a cold start could be swallowed.**
   `UnreadTracker` took its baseline from the first thing it was shown, and
   returned "nothing new" for it. If a message was already in the database when
   that first call happened — a process killed between fetching a message and
   announcing it, or a fetch that landed before the collector's first emission
   — the app was in the background, with unread messages, and posted no
   notification at all.
   → Fixed: the baseline is taken in `load()`, from the database, before
   anything can arrive.

4. **A phone that was pushed but did not come was never pushed again.**
   The relay allowed one outstanding wake-up (`pending`) and then waited for
   the phone. If the push service accepted it but the phone never connected —
   a dozing device, a distributor that dropped the message, a fetch that failed
   — nothing tried again until *the next message*, or until the queue's 7-day
   expiry. That is the shape of "the message sat at one tick until I opened the
   app": the phone had been told once, silently failed, and was never told
   again.
   → Fixed: every wake-up now arms a short retry chain on the relay's own
   alarm: **+20 s, +60 s, +180 s** for a message (`WAKE_RETRY_WAITS_MS`), and
   **+8 s, +20 s** for a call (`WAKE_RETRY_WAITS_CALL_MS`), because a call's
   wake-up dies with its 30 s TTL. A retry is skipped, and the chain dropped,
   when the queue is empty, when there is no address, or when the phone is
   connected; taking the messages deletes the retry row. The alarm is only ever
   moved *sooner* (`armAlarm`), so the queue-expiry appointment and the retry
   appointment coexist instead of overwriting each other.
   Tests: *wakes the phone again when it does not come, then stops*, *stops
   once the message has been taken, and not a moment before*, *looks for a
   ringing call sooner, and gives up sooner*.

   **What stops the chain is the acknowledgement, not the socket.** The first
   cut of this called the search off when a connection reported itself ready —
   the same mistake the stale-socket fix above exists for, since a dead
   connection looks exactly like a quiet live one. It no longer does: each try
   records how much was waiting (`wake_retry.seen`), and the next try compares
   it with how much is waiting then. A queue that went down means the phone is
   really taking messages, so it is left alone and the appointment moves to the
   next interval; a queue that did not move means another wake-up, whatever the
   socket says. Only an empty queue ends it. Test: *keeps looking while nothing
   is taken, even on a socket that says it is connected*.

5. **Nothing measured the part of the delay the relay cannot see.**
   The mailbox records the push service's HTTP status (`push_result`) and the
   phone learns it at the next login. Nothing
   recorded when the wake-up *arrived*, when the fetch ran, or when the
   notification was shown.
   → Fixed: `DeliveryTrace` — a random eight-character event name per wake-up,
   with timestamps for each step, written to logcat only:
   ```
   adb logcat -s MaunDelivery
   event=4f2a91c0 step=wake at=1763371200123
   event=4f2a91c0 step=fetch-start at=1763371200640 afterWake=517ms
   event=4f2a91c0 step=fetch-done at=1763371201811 afterWake=1688ms caughtUp=true attempt=0
   event=4f2a91c0 step=shown at=1763371201902 afterWake=1779ms alert=true count=1
   ```
   Nothing about the message, the sender or the phone is written.

## Suspected, not proved (no device here)

These are the reasons a delay can still appear between step 4 and step 5, where
neither this repository nor a build machine can see. They are listed so that the
trace above can settle them on a real phone.

* **Google's delivery under Doze and battery settings.** A high-urgency Web Push
  is meant to wake the device; an app whose battery use Android has restricted,
  or one in a low standby bucket, can have high-priority messages held for a
  maintenance window — which is the shape of "about five minutes, sometimes".
  `DeliveryUi.unrestricted` still drives the warning on the home screen. The trace's
  first timestamp is *after* Google's part, so a missing or late `wake` line
  puts the delay on Google or the OEM, and a slow `fetch-*` puts it in the app.
* **`Urgency: high` for every wake-up.** Google asks that high priority be kept
  for what the user can see and act on. Maun's wake-ups are exactly that
  (messages and ringing calls; receipts and catch-ups never wake a phone at
  all — `quiet` in `store()`, tested by *stays quiet while...*). The split is
  already right, but a phone that is woken very often may still be throttled by
  Google; nothing in this repository can change that, and lowering the urgency
  of message wake-ups would make Doze delays *worse*, so it was not done.
* **OEM background killers** (Xiaomi, Oppo, Vivo, Samsung and others) can drop
  the wake-up or freeze the app afterwards. Settings still links to the phone's
  own notification page; the trace makes an OEM problem visible.
* **Force-stop.** When the user force-stops an app, Android removes it from the
  push allow-list until it is opened again. No design can work around this;
  UnifiedPush apps have the same limitation.

## What was deliberately not changed

* **The live connection.** It is kept exactly as it was; foreground messages
  still arrive over the socket, and a foreground message still makes its own
  sound and no notification (`announceUnread`).
* **The wake-up payload and encryption.** Still one word, still RFC 8291
  encrypted, still the mailbox's own VAPID key, still no plaintext in FCM.
* **Calls.** Already `CallStyle`, `CATEGORY_CALL`, full-screen intent through
  Telecom, `RingService` with a 30-second wake-up TTL and cancellation, and
  urgent repeat at 2 s; no always-on call service. Nothing needed changing.
* **Delivery and read receipts, `ack`, queue deletion.** Untouched.
* **No polling was added.** No battery-optimisation bypass is requested by the
  app; the only background schedule is the existing hourly catch-up worker.
* **"Stay connected"** stays off by default and remains a fallback, not a
  requirement. It is no longer offered in Settings (Round 8: the technical
  push/background controls came out, and a plain Notifications section went in
  their place); the fallback still exists inside the app for phones where
  nothing can wake Maun.

## Messages that cannot leave yet (Round 10)

An outgoing message is saved with everything else and then handed to the
connection. It used to be that a message written while the phone had no network
waited for the *next* connection — which, with the app closed and no wake-up
coming (nobody writes to you to cause one), could be the hourly catch-up worker
an hour later, or the app being opened.

Now the outbox is counted from storage (`ChatEngine.unsent`, read at start-up
and after every pass), and a count above zero while the app is not on screen
asks Android for the one fetch that is already used for wake-ups. That request
carries a network constraint, so it runs the moment the phone has a network,
with the app closed, and it survives the process being killed because Android
keeps it. The worker tries again (20 s, then 40 s) while anything is still
unsent, and the hourly worker remains the last resort.

Nothing is sent twice: the queue is emptied row by row as the relay confirms
each one, the sealed bytes of a row are kept so a retry sends the same message
with the same ID, and the receiving phone ignores a message ID it already has
(unique index on peer, direction and ID; covered by
`the same delivery twice is saved once`).

## Other devices

* **Phones without Google Play services (HMS-only).** Huawei's Push Kit needs a
  Huawei developer account, an AppGallery build of the app, and Huawei's SDK —
  none of which can be built or tested from this repository. Instead, Maun
  already accepts *any* UnifiedPush distributor, which is what HMS-only phones
  would need to install (Sunup or ntfy work there). Adding a Huawei provider was
  therefore not attempted rather than added untested.
* **De-Googled phones.** UnifiedPush is the supported path today, and Settings
  lets the user pick a distributor and turn Google's service off. Nothing new
  was needed.
* **`fcm.googleapis.com` is Google's own Web Push endpoint**, reached only
  because Play services hands out the address; Google sees the timing, the size
  of the wake-up and the mailbox's VAPID key — never the message.

## What a device test should look at

For each case, `adb logcat -s MaunDelivery` gives the four steps; compare the
`afterWake` of `fetch-done` with the wall clock of the notification.

| Case | What to expect |
| --- | --- |
| Chat open | no wake-up at all; message over the socket; no notification |
| App in background, alive | `wake` → `shown` within a second or two |
| Screen locked | same, notification discreet on the lock screen |
| Doze (screen off, still, ~30 min) | `wake` still arrives; if it is late, Google held it |
| Process killed (`adb shell am kill`) | `wake` cold-starts the process; `shown` follows |
| Force-stop | nothing until the app is opened — Android's rule, documented |
| Offline, then reconnect | phone logs in, mailbox pumps, one notification for the batch |
| Written offline, app then closed (Round 10) | the hourly worker or the one asked for at the moment the message was queued connects, the outbox empties, and the message arrives once — no need to open the app |
| Token refresh (new endpoint) | relay's `push` row replaced at next login; one wake-up per message still |
| Many messages at once | one notification, counted; at most one wake-up per 5 s |
| Incoming call | `step=call`; ring within the 30 s TTL, or nothing |
