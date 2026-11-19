# AGENTS.md

Instructions for an AI agent working in this repository. The [README](README.md)
is for people who use Maun; this file is for whoever maintains it. Read it before
changing anything, and keep it true: when a command, a workflow or a rule here
stops matching the repository, fix the file in the same change.

## What this is

Maun is a private one-to-one messenger for Android with no tracking: no ads, no
analytics, no phone number, and no message history on any server.

Two parts, in one repository:

| Part | What it is | Runs on |
|---|---|---|
| `backend/` | The relay: one Cloudflare Worker plus a Durable Object per account. TypeScript. | Cloudflare Workers, tested in Cloudflare's local runtime |
| `android/` | The app: Kotlin, Jetpack Compose, libsignal, WebRTC. | Android 10 and newer |

The version is `versionCode` and `versionName` in
`android/app/build.gradle.kts`. Nothing else is authoritative.

## Never

- **Never build Android on your own machine.** The Android build, its unit
  tests, lint, APK and the signed release all run in GitHub Actions. Pushing a
  branch and reading the run *is* the build. This machine has no Android SDK,
  and a local build proves nothing that CI will not prove better.
- **Never force-push `main`.** History that has been published stays. Undo a
  mistake with `git revert <squash-commit>`.
- **Never publish a release or deploy the relay on your own initiative.** Both
  need the owner's explicit go-ahead in the conversation. A published release
  is visible to every installed phone through the in-app update check, and a
  deployed relay changes what every user's phone talks to.
- **Never put a secret in the repository, in a doc, in a commit message or in a
  log line.** Release keys, Cloudflare tokens and mail API keys live in GitHub
  secrets and in Cloudflare, nowhere else.
- **Never weaken a check to make a build pass.** No `continue-on-error` on a
  gate, no lint baseline added for a new error, no skipped test.
- **Never claim something was verified on a phone when it was not.** Ringing,
  notifications, battery and background behaviour need a handset. Say
  "pending on a device" and mean it.

## Commands

Backend. Node 22.12 or newer is required by `wrangler`; CI uses 24.

```sh
cd backend
npm ci
npm run typecheck                  # wrangler types && tsc --noEmit
npm test                           # open relay, then member mode
npx vitest run --config vitest.config.ts test/push.test.ts     # one file
npx vitest run --config vitest.config.ts test/push.test.ts -t "part of a name"
npm run dev                        # local relay on http://127.0.0.1:8787
```

`npm test` runs **two** configurations: `vitest.config.ts` (an open relay) and
`vitest.members.config.ts` (a member-only relay). Anything that touches
`mailbox.ts`, `members.ts` or `auth.ts` has to pass both.

Android. In CI, not locally:

```sh
./gradlew :app:testDebugUnitTest   # unit tests, Robolectric
./gradlew :app:lintDebug           # lint fails the build on errors
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

The connection and messaging tests need a relay: start
`backend/scripts/test-relay.sh` and run them with
`MAUN_TEST_RELAY_URL=http://127.0.0.1:8787`. Without it they are skipped, which
is why CI runs them with a relay started first.

## Which workflow answers what

| Workflow | Answers |
|---|---|
| **Android** (`build-android.yml`) | Do the app's unit tests pass, does lint pass, does the debug APK build, is it under the size limit, is the Room schema committed |
| **Backend** (`deploy-backend.yml`) | Do the relay tests pass in both modes, does TypeScript type-check; deploys on `main` **if** `CLOUDFLARE_API_TOKEN` is set |
| **Release** (`release.yml`) | Does the signed release APK build, is it signed with the permanent key, is the version right, is it under 40 MB; publishes only when started by hand with `publish` ticked |
| **Screens** (`screens.yml`) | Draws the main screens with made-up people, for looking at. Nothing is compared. |
| **Compile classpath** (`classpath.yml`) | By hand: packs the app's compile classpath for type-checking on a small machine |

Read the run's steps, not just the colour: a green run with a skipped step is
not a step that passed.

## Definition of done

1. A behaviour change comes with a test that **fails before** it and **passes
   after** it, and the report quotes both outputs. Adding the test and the fix
   in one commit is fine only when the test is a pure-function test of something
   that did not exist before; otherwise land the test first and show it red.
2. Backend: `npm run typecheck` and `npm test` both clean.
3. Android: the **Android** workflow green on the branch you are proposing.
4. Docs that the change makes wrong are updated — `README.md` for what users
   see, `docs/` for the deep explanations, this file if a command or a rule moved.
5. The report says: what you ran, what you did not run, what still needs a
   phone, and anything you could not do because it needs the owner (deploy,
   publish, a device).

## Things only the owner can do

- **Deploy the relay.** This repository has no `CLOUDFLARE_API_TOKEN`, so the
  Backend workflow's deploy step is *skipped* and says so. Do not report a
  deploy as done. The command, if they want it, is `cd backend && npx wrangler deploy`.
- **Sign a release.** The release key is in GitHub secrets. CI signs with it;
  nothing on a laptop can produce an APK that installs over an existing one.
- **Run anything on a phone.**

## Where things are

| Path | What lives there |
|---|---|
| `backend/src/protocol.ts` | Frame shapes, limits, the timings the app and relay both rely on |
| `backend/src/mailbox.ts` | One account's Durable Object: the queue, wake-ups, retries, limits, retirement |
| `backend/src/index.ts` | The stateless front door, WebSocket upgrade, files, avatars |
| `backend/src/auth.ts`, `members.ts`, `mail.ts` | Member-only mode: email codes, usernames, approvals, bans |
| `backend/src/admin-page.ts` | The administrator's page |
| `backend/src/turn.ts` | Short-lived TURN credentials for calls |
| `backend/test/` | Relay tests, run in Cloudflare's local runtime |
| `android/app/src/main/java/org/maun/messenger/` | The app. `ChatEngine.kt` and `Messenger.kt` are the centre; `crypto/`, `network/`, `push/`, `webrtc/`, `ui/`, `security/`, `voice/` around it |
| `android/app/src/test/java/org/maun/messenger/` | Robolectric and plain unit tests, mirroring the packages above |
| `android/app/schemas/` | Room schema JSON, committed. A database change without it fails CI. |
| `docs/` | The long explanations: notifications, releases, recovery, Play Protect, performance |

## Canonical implementations — follow these, do not invent a second way

- **A wake-up.** `Mailbox.wake` in `backend/src/mailbox.ts`, with the timing
  constants in `protocol.ts`, and `docs/NOTIFICATIONS.md` for why. The chain
  of retries, the `seen` count and the call word are subtle; read all three
  before touching any of them.
- **A notification.** `push/Notifications.kt` makes every one; nothing else
  calls `NotificationManager.notify` directly.
- **The app's connection.** `network/RelayConnection.kt`. Nothing opens a
  socket to the relay on its own.
- **Encryption.** `crypto/Sessions.kt` and libsignal. Nothing hand-rolls a
  ratchet or a key exchange.
- **Tests.** Look at the nearest existing test file and match its shape,
  including how it names cases (a sentence, no prefix).

## Gotchas

- `npm run typecheck` regenerates `backend/worker-configuration.d.ts`. Commit
  it if it changed, or the next person's type-check disagrees with yours.
- `wrangler` refuses to run on Node older than 22.12. CI pins 24.
- Android lint fails the build on errors, not warnings — but a new
  `MissingPermission` around a notification is an error. Do what
  `Notifications.incomingCall` does: check, and say so.
- Robolectric tests run with a plain `Application`
  (`@Config(application = Application::class)`). The real `MaunApp` starts
  background work a unit test cannot have.
- A notification channel's sound is fixed when the channel is created. Changing
  it means a new channel; see `docs/NOTIFICATIONS.md`.
- The app's update check reads the repository's latest published release. A
  `versionCode` that does not increase means phones refuse the update.
- `backend/scripts/test-relay.sh` is the normal relay with per-address
  connection limits lifted, because every test phone connects from one address.
- Debug builds are signed with the committed `android/debug.keystore` on
  purpose: it protects nothing, and it lets one debug build install over the last.

## Git

- Branch from `main`, one idea per branch, one PR per idea.
- Commit subjects are a sentence in the present tense saying what the change
  does, not a ticket number: "A push app that did not answer is not a phone
  without one." The body says why, and what was seen before and after.
- Squash-merge. Roll back with `git revert <squash-commit>`; that is one step
  and needs no re-deploy of anything else.
- Do not merge your own PR, and do not deploy or publish, unless the owner has
  said so in so many words.

## Writing

The repository has a voice: plain, specific, and honest about limits. No
marketing, no exclamation marks, no "simply" or "just". Say what a thing does
and what it cannot do. When something is unproven, write "pending" and say what
would prove it.

## The docs, and what each is for

| File | For |
|---|---|
| `README.md` | A person deploying the relay and using the app |
| `PROJECT.md` | What Maun is and is not, in one page |
| `TECHNICAL.md` | The original technical specification |
| `PLAN.md` | Build plan, decisions taken, progress |
| `docs/NOTIFICATIONS.md` | How a phone is woken, and why every part of it is shaped that way |
| `docs/CHAT.md` | Sessions, receipts, message requests, blocking |
| `docs/RECOVERY.md` | What survives an uninstall, a reinstall, a new phone |
| `docs/RELEASE.md` | Building, signing and publishing a version |
| `docs/PLAY_PROTECT.md`, `docs/PLAY_PROTECT_APPEAL.md` | The Play Protect warning, and the appeal |
| `docs/PERFORMANCE.md` | What was measured and what it cost |
| `docs/AUTHORS.md` | Who wrote what |
