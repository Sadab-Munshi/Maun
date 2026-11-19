# Maun

A private one-to-one messenger for Android with zero tracking: no ads, no
analytics, no phone number, and no chat history on any server.

- What it is and is not: [PROJECT.md](PROJECT.md)
- Original technical spec: [TECHNICAL.md](TECHNICAL.md)
- Build plan, decisions and progress: [PLAN.md](PLAN.md)
- How a phone is woken, and why it is built that way: [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md)
- Sharing where you are, and what that leaves on the relay: [docs/LOCATION.md](docs/LOCATION.md)
- Building, signing and publishing a version: [docs/RELEASE.md](docs/RELEASE.md)
- Working on this repository, with or without an AI agent: [AGENTS.md](AGENTS.md)

## Status: app 0.28.0 (files, voice messages, voice and video calls, profile pictures, tappable links, a location sent once or a live location for a quarter of an hour to a day, and an optional member-only mode with usernames)

Two phones can exchange end-to-end encrypted text, and are told about new
messages while the app is closed. The app can be locked behind the phone's
fingerprint or screen lock, and two people can compare a safety number to be
sure of each other. Files up to 25 MB can be sent; the relay keeps the
encrypted file for one day. A relay can be made member-only: people log in
once with a code sent to their email, pick a username, wait for the
administrator's approval on a web page, and can be banned. Voice messages
can be recorded and played in the conversation. Voice and video calls go
through the relay's call servers, so neither phone learns the other's address. A
profile picture is chosen by its owner, sealed on their phone, and shown to their
contacts without the relay being able to open it.

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
| Files: encrypted in pieces on the phone, stored a day on the relay | `android/.../crypto/FileCipher.kt`, `android/.../FileTransfers.kt`, `backend/src/index.ts` |
| Member-only mode: email codes, usernames, approval, bans | `backend/src/members.ts`, `backend/src/auth.ts`, `backend/src/mail.ts`, `android/.../ui/LoginScreen.kt`, `android/.../network/AuthApi.kt` |
| The administrator's web page | `backend/src/admin-page.ts` |
| Signed HTTP requests and noticing a ban | `android/.../network/FileApi.kt` |
| Phone fingerprint (member-only relays) | `android/.../security/DeviceFingerprint.kt` |
| Voice messages: recording and playing | `android/.../voice/VoiceRecorder.kt`, `android/.../voice/VoicePlayer.kt` |
| Location: a place sent once as a plain message, or a live share for a quarter of an hour to a day | `android/.../LiveLocation.kt`, `android/.../push/LocationService.kt`, `backend/src/mailbox.ts` |
| Voice calls: setting up, sound, ringing, staying alive | `android/.../webrtc/CallManager.kt`, `CallAudio.kt`, `CallService.kt`, `backend/src/turn.ts` |
| Safety number for checking a contact | `android/.../crypto/SafetyNumber.kt` |
| Profile pictures: sealed on the phone, one per account on the relay, told to contacts in a message | `android/.../ProfilePictures.kt`, `android/.../network/AvatarApi.kt`, `backend/src/index.ts` |
| Rate limits and mailbox caps | `backend/src/index.ts`, `backend/src/protocol.ts`, `backend/wrangler.toml` |
| CI: tests the relay, runs the app's messaging code against it, builds the APK | `.github/workflows/` |

What the relay stores: per account, the public half of the login key, the
public pre-keys others need to start a conversation, and — only if its owner
chose one — a sealed profile picture under `avatar/<id>`; per waiting message, the
encrypted bytes, the sender's ID and the arrival time, until the recipient's
phone collects it or 30 days pass; and, if the phone left one, the address at
which its push service takes wake-ups. It has no names, no phone numbers, no contact lists and
no message history.

What it can see: which ID sends to which, and when. It cannot read messages or
tell a message from a read receipt.

### Messages while the app is closed

Nothing of Maun runs while nothing is happening. The path a message takes
to a phone whose app is closed, with the screen locked:

1. The relay stores the encrypted message and sends the phone a **wake-up**
   through the push service: a high-priority, data-only push that says only
   "message" (or "call"), encrypted for the phone. It never carries anything
   of the message itself.
2. The push wakes Maun, which starts an **expedited job** at once. Android
   gives such a job the network straight away, also in Doze.
3. The job connects to the relay, fetches what is waiting, decrypts it and
   stores it in the phone's encrypted database.
4. It sends the **delivered** receipt back, so the sender's one tick becomes
   two right then, without the app being opened.
5. It shows the notification, and Maun goes back to sleep.

The ticks, then: a clock while the message is only on the sender's phone;
one tick when the relay has it; two when the other phone has fetched and
decrypted it; two blue ones when that conversation was opened and looked at.

Messages that come within five seconds of each other share one wake-up.
Receipts ("delivered", "read") never wake a phone: they have nothing to
show, and are handed over the next time the phone is connected, which is
the moment its owner opens the app to look at the ticks. A notification
shows who wrote and what, like other messengers do; the text can be turned
off in Settings, and a locked screen is only ever told that a message
arrived.

Two notes on how this is built. The wake-up reaches Google's push service
as standard Web Push, which that service delivers exactly like a
high-priority data message of its own; it needs no Firebase project and no
Google code in the app. And the fetch runs over the same encrypted
connection the open app uses, because each message is deleted from the relay
only once the phone has confirmed it over that connection.

What the push service (Google's, or the push app's) learns from this: that
this phone was woken, and when, once for every burst of messages. It does
not learn by whom, and the wake-up carries nothing of the message.

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
6. Tap the microphone (it is there while the message field is empty) to
   record a voice message. While it records you can pause it, throw it away
   with the bin, or send it. Tap a voice message to play or pause it.
7. Tap the telephone at the top of a conversation for a voice call, or the
   camera next to it for a video call. The other phone rings, also when Maun
   is closed there. See "Calls" below.
8. Tap the paperclip to send a file of up to 25 MB. Pictures show in the
   conversation; tap any other file to save it. The relay deletes its
   encrypted copy after one day, so a file not fetched by then is gone.
9. Tap the name at the top of a conversation to rename the contact or to
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

## Calls

A call is set up with a few ordinary encrypted messages between the two
phones, and then runs through Cloudflare's TURN service and nothing else. The
phones are told to connect only through it, so neither learns the other's
internet address. The sound is encrypted between the two phones by WebRTC;
the TURN service passes on what it cannot read. The relay itself never
carries a call: it only hands each phone short-lived credentials for the
TURN service.

To switch calls on, create a TURN key (Cloudflare dashboard > **Realtime** >
**TURN Server** > **Create**) and add two Secrets to the Worker:
`TURN_KEY_ID` (the "Turn Token ID") and `TURN_API_TOKEN` (the "API Token").

The Realtime page also offers to create an **app** (for Cloudflare's SFU).
That is something else: its App ID and secret look just like a TURN key's two
values, and calls do not work with them. `https://<relay>/v1/calls/check`
asks the call service once and answers `"working":true` when the key is
right; the admin page shows the same in a line under its heading.

Cloudflare gives 1,000 GB of TURN traffic a month free and charges beyond
it, with no cap of its own. A voice call uses about 0.3 MB a minute, a video
call up to about 7 MB a minute (the app sends 640 by 480 at no more than
800 kbit/s, on purpose: everything goes through a relay). The
relay limits each account to 60 sets of credentials a day (a call needs one
on each side) and each set works for four hours.

In a video call the camera can be turned off and on and changed between
front and back, and the call goes on with sound if the camera may not be
used. A voice call stays a voice call. The button for the sound switches between the earpiece and the
loudspeaker, and lists a wired or Bluetooth headset when one is connected
(on Android 12 and newer; a headset that is connected during a call is used
at once). Calls can be started from the top of a conversation and from the
contact's page. Both phones need app 0.11.0 or newer; with an older one a
call may not ring.

**Ringing.** The wake-up for a call says "call" and is dropped by the push
service after 30 seconds, so a phone that comes back later does not ring for
a call that is over. The moment it arrives, a short-lived service of the
phone-call kind starts; it exists only until the call is answered, declined
or given up. The call then rings through Android's own incoming-call
notification (caller, Answer, Decline) on a channel that carries the
phone's ringtone and a vibration, repeated for as long as it rings, and it
lights up a locked screen and shows the call there. From Android 14 on,
showing over the lock screen is a setting of its own; where it is off, Maun's
Settings says so and leads to it.

Calls are also handed to Android's call system (Telecom), so that a
headset's or a car's buttons answer and hang up and an ordinary phone call
does not talk over a Maun call. Phones without it (many tablets) and calls it
turns down run as before; a switch in Settings turns it off. The phone's silent and vibrate settings apply, as for any call. On
phones that put apps to sleep hard (many OnePlus, Oppo, Xiaomi and Vivo
models), calls may still not ring until Maun is allowed to run in the
background: Settings in Maun offers that when the phone's battery saving
applies to it, and the phone's own settings usually have an "Auto-launch"
switch as well.

## A member-only relay

By default a relay is open: anyone who knows its address can make an ID, and
the relay knows nobody's name. Its owner can instead keep it to people they
approve. This changes what the relay knows, so it is off until switched on.

**What changes for members.** After making an ID, the app asks for an email
address, sends a 6-digit code there, and lets a new member choose a unique
username. They then wait until the administrator approves them. All of this
happens once per phone: afterwards the phone logs in with its hardware key as
before, and the relay checks the member list every time.

**Usernames.** Members add each other by typing a username (Add contact >
By username); scanning a code still works. The relay tells the phone which ID
a username belongs to, so a dishonest relay could point it at the wrong
person. Comparing safety numbers when you meet settles that.

**What the administrator can see**: each member's email address, username,
Maun ID, when they last logged in, and which phone they use (make, model,
Android version and a SHA-256 fingerprint of the phone). Together with what
any relay sees (who writes to whom, and when), the administrator of a
member-only relay knows which *person* writes to which. Message contents and
files stay end-to-end encrypted and unreadable to the relay. The app says so
on its login screen and in Settings.

### Switch it on

1. Make a free account at [resend.com](https://resend.com) and create an
   **API key**.
2. In Resend, add and verify a **domain** you own (Domains > Add domain, then
   add the DNS records it shows). Resend only delivers to other people from a
   verified domain.
   No domain? Use [Brevo](https://www.brevo.com) instead: it lets you verify a
   single sender address. Set `BREVO_API_KEY` in place of `RESEND_API_KEY`.
3. Cloudflare dashboard > **Workers & Pages > maun > Settings > Variables and
   Secrets > Add**, then **Deploy**:

   | Name | Type | Value |
   |---|---|---|
   | `ADMIN_EMAIL` | Text | your own email address |
   | `ADMIN_PASSWORD` | Secret | a password of 10 characters or more, for the admin page |
   | `RESEND_API_KEY` | Secret | the key from step 1 |
   | `MAIL_FROM` | Text | `Maun <login@your-domain.com>`, on the domain from step 2 |
   | `ADMIN_HOST` | Text | optional, see "The admin page" |

`https://<relay>/health` now says `"access":true`. Phones that already have
an ID on the relay are asked to log in the next time they connect; nothing
they have is lost.

### The admin page

Open `https://<relay>/admin` in any browser. Logging in takes three things:
the administrator's email address, the password (`ADMIN_PASSWORD`), and the
6-digit code that is then sent to that address. Without a password set, the
page lets nobody in. Five wrong passwords close it for 15 minutes.

**On a host name of its own.** To serve the page at, say,
`admin.example.com` instead:

1. Workers & Pages > maun > Settings > **Domains & Routes > Add > Custom
   domain**, and enter that name. The domain has to be on Cloudflare.
2. Add the variable `ADMIN_HOST` = `admin.example.com` and deploy.

The page is then at `https://admin.example.com/` and exists nowhere else:
`/admin` on the relay's own address answers 404.

**What it shows.** A table you can search, with four tabs (Waiting, Members,
Banned people, Banned phones). For each person: username and email, state,
phone (make, model, Android version) and app version, the phone's ID, last
login, number of logins, how often they set Maun up again, how many phones
they have used, when they asked and when they were approved, and their Maun
ID. You get an email when somebody asks to join.

**What it does.**

- **Approve** or **Refuse** someone who is waiting.
- **Add** an email address in advance, so its owner is let straight in.
- **Ban** a person: their email address is shut out. Also works for an
  address that has never joined. **Lift ban** undoes it.
- **Ban phone**: that phone is shut out whichever email address is used on
  it, also after the app's data is deleted or the app is reinstalled.
  **Lift phone ban** undoes it.
- **Remove** a member: they would have to log in and be approved again.

A banned phone or person disconnects within a minute, deletes its login and
every message and file it holds, and shows "You have been banned". Its
contacts and ID stay, so lifting the ban and tapping **Check again** brings
the account back, empty.

A phone's ID is a SHA-256 of Android's ID for Maun on that phone and its
make and model. It is the same after the app's data is deleted. It is what
an unmodified app reports: someone who rebuilds the app can make it report
another, so a phone ban stops ordinary use, not a determined programmer.
What cannot be faked is the login key in the phone's secure hardware, and
the email code.

The relay sends at most 90 emails a day (Resend's free plan allows 100), at
most 4 codes a day to one address, and one a minute.

### When a member sets Maun up again

Deleting the app's data, reinstalling, or moving to another phone destroys
the keys that were the member's ID. Logging in again with the same email
address gives the same username on a **new ID**, without waiting for
approval again. Messages and contacts that were on the phone are gone:
they were stored nowhere else.

Other members do not have to do anything:

- The relay answers "gone" for the old ID. A phone that writes to it asks
  which ID the username has now, moves the contact and the conversation to
  it, and sends again whatever the relay had taken and never handed over.
- If the member who started over writes first, the other phone recognises
  the username and joins the two.
- The conversation gets a line saying so, and the contact counts as
  unchecked until safety numbers are compared again: the relay is being
  trusted for which ID the username belongs to.
- A member who writes to someone who has not added them is added under their
  username instead of showing up as an unknown ID.

## Working on the code

`AGENTS.md` at the root is the working agreement for anyone — person or AI agent
— changing this repository: the commands that are the build, what counts as
done, which workflow answers which question, the parts that only the owner can
do (deploying, signing, anything on a phone), and the mistakes that have already
been made once. Each half has its own notes as well: [backend/README.md](backend/README.md)
for the relay, [android/README.md](android/README.md) for the app.

The short version: **the Android build is GitHub Actions**, a behaviour change
comes with a test that fails before it and passes after, and nothing is
published or deployed without the owner saying so.

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

The app is built for one relay, whose address is in `android/app/build.gradle.kts`
(`https://maun.sadab.workers.dev` in this repository). A fresh install makes
its ID there without asking. To build for your own relay, pass
`-Pmaun.relayUrl=https://...` or set `MAUN_RELAY_URL`; with an empty address
the app shows a welcome screen that asks for one.

Debug builds are signed with `android/debug.keystore`, which is committed on
purpose so that each debug build installs over the last one. It protects
nothing. Release signing keys never go in the repository.

## Licence

AGPL-3.0-only. See [LICENSE](LICENSE). Maun uses
[libsignal](https://github.com/signalapp/libsignal), which is also AGPL-3.0.
