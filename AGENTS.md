# AGENTS.md

Instructions for an AI agent working in this repository. The [README](README.md)
is for people who use Maun; this file is for whoever maintains it. Read it
before changing anything, and keep it true: when a command, a workflow or a rule
here stops matching the repository, fix the file in the same change.

## What this is

Maun is a private one-to-one messenger for Android with no tracking: no ads, no
analytics, no phone number, and no message history on any server.

Two parts, in one repository:

| Part | What it is | Runs on |
|---|---|---|
| `backend/` | The relay: one Cloudflare Worker plus a Durable Object per account. TypeScript. | Cloudflare Workers, tested in Cloudflare's local runtime |
| `android/` | The app: Kotlin, Jetpack Compose, libsignal, WebRTC, MapLibre. | Android 10 and newer |

The version is `versionCode` and `versionName` in
`android/app/build.gradle.kts`. Nothing else is authoritative.

## Before you change anything

**Read the code that does the thing.** Every document here was written by
reading it, and documents drift; the code does not. Then read the document that
owns the area, because most of the non-obvious parts of this app are
non-obvious on purpose and the reason is written down.

| A task about… | Read first | The code is |
|---|---|---|
| How the pieces fit at all | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | — |
| Waking a sleeping phone, notifications, background work | [docs/NOTIFICATIONS.md](docs/NOTIFICATIONS.md) | `android/.../push/`, `Mailbox.wake` in `backend/src/mailbox.ts` |
| Sending, receiving, receipts, requests, blocking, reactions, replies | [docs/CHAT.md](docs/CHAT.md) | `ChatEngine.kt`, `Payload.kt`, `ui/ChatScreen.kt` |
| Live location, the map, a place sent once | [docs/LOCATION.md](docs/LOCATION.md) | `LiveLocation.kt`, `network/LocationApi.kt`, the `/v1/location` routes in `backend/src/index.ts` |
| Calls | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §4 | `webrtc/`, `backend/src/turn.ts` |
| What a route, frame or limit is | [docs/API.md](docs/API.md) | `backend/src/protocol.ts` (canonical) |
| Stored data, migrations, retention | [docs/DATABASE.md](docs/DATABASE.md) | `data/`, `ensureTables()` in `mailbox.ts`, `SCHEMA` in `members.ts` |
| Anything cryptographic, or a boundary | [docs/SECURITY.md](docs/SECURITY.md) | `crypto/`, `backend/src/crypto/` |
| Logging in, members, banning, the admin page, recovery | [docs/RECOVERY.md](docs/RECOVERY.md) | `backend/src/{auth,members,admin-page,mail}.ts` |
| Tests, or what is not tested | [docs/TESTING.md](docs/TESTING.md) | `backend/test/`, `android/app/src/test/` |
| Versioning, signing, publishing, the updater | [docs/RELEASE.md](docs/RELEASE.md) | `.github/workflows/release.yml`, `updates/` |
| Why something was built the way it was | [PLAN.md](PLAN.md), [TECHNICAL.md](TECHNICAL.md) — both historical | the code |

`PLAN.md` and `TECHNICAL.md` are records of decisions, not descriptions of the
app. Do not "fix" them to match today; add a line to the deviations table in
`TECHNICAL.md` or to the decision log in `PLAN.md` if a decision has changed.

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
- **Never change behaviour while claiming to change documentation.** Separate
  commits, and say which is which.

## Commands

Backend. `wrangler` needs Node 22.12 or newer; CI uses 24.

```sh
cd backend
npm ci
npm run typecheck                  # wrangler types && tsc --noEmit
npm test                           # open relay, then member mode
npx vitest run --config vitest.config.ts test/push.test.ts     # one file
npx vitest run --config vitest.config.ts test/push.test.ts -t "part of a name"
npm run dev                        # local relay on http://127.0.0.1:8787
npm run bench                      # signature verification benchmark
```

`npm test` runs **two** configurations: `vitest.config.ts` (an open relay) and
`vitest.members.config.ts` (a member-only relay). Anything that touches
`mailbox.ts`, `members.ts` or `auth.ts` has to pass both.

Android. In CI, not locally:

```sh
./gradlew :app:testDebugUnitTest              # unit tests, Robolectric
./gradlew :app:testDebugUnitTest -Pmaun.screens=true   # draw the screens to app/build/screens
./gradlew :app:lintDebug                      # lint fails the build on errors
./gradlew :app:assembleDebug                  # app/build/outputs/apk/debug/
```

The connection and messaging tests need a relay: start
`backend/scripts/test-relay.sh` and run them with
`MAUN_TEST_RELAY_URL=http://127.0.0.1:8787`. Without it they are skipped, which
is why CI runs them with a relay started first — and fails if they skipped.

Counts, what each suite covers and what no test can cover are in
[docs/TESTING.md](docs/TESTING.md). Do not copy numbers out of it into a report
as if you had run them; run them, or say "not run".

## Which workflow answers what

| Workflow | Answers |
|---|---|
| **Android** (`build-android.yml`) | Do the app's unit tests pass, do the relay tests really run, does lint pass, does the debug APK build, is it under the size limit, is the Room schema committed |
| **Backend** (`deploy-backend.yml`) | Do the relay tests pass in both modes, does TypeScript type-check; deploys on `main` **if** `CLOUDFLARE_API_TOKEN` is set |
| **Release** (`release.yml`) | Does the signed release APK build, is it signed with the permanent key, is the version right, is it under 40 MB; publishes only when started by hand with `publish` ticked |
| **Screens** (`screens.yml`) | Draws the main screens with made-up people, for looking at. Nothing is compared. |
| **Compile classpath** (`classpath.yml`) | By hand: packs the app's compile classpath for type-checking on a small machine |

Read the run's steps, not just the colour: a green run with a skipped step is
not a step that passed.

## How to approach a change

**A bug.** Reproduce it first, in a test if the bug is in code a test can reach
(`backend/test/`, `android/app/src/test/`), otherwise in a written account of
what was seen and on which build. Then find the layer the bug is really in
before editing: a message that arrives late can be the relay's retry chain, the
push address, the fetch worker, or the notification — `docs/NOTIFICATIONS.md`
lists what has been blamed for that symptom and what it turned out to be. Fix
the cause, keep the shape of the surrounding code, and say in the commit body
what was seen before and after.

**A feature.** Establish what already exists. Several features have been
half-built twice here because the second attempt did not read the first. Then
decide where it belongs: the phone, the relay, or both. If it changes what
crosses the wire, `protocol.ts` is edited first, both sides in the same pull
request, and [docs/API.md](docs/API.md) with them. If it changes what is stored,
[docs/DATABASE.md](docs/DATABASE.md) and the Room schema as well.

**A refactor.** Change one thing. Nothing in this repository is refactored
"while I am here": the value of a refactor is that a test failure afterwards
has one possible cause. If a rename or a move touches a canonical implementation
listed below, it is not a refactor — it is a change that needs its own test.

**A documentation change.** Do not touch code, configuration, schemas,
dependencies or workflows. Verify every claim against the code and say where it
was checked; mark anything that would need a phone as **unverified**; keep
historical decisions in `PLAN.md` rather than rewriting them out of existence.

## Finish and report

- Backend: run `npm run typecheck` and both halves of `npm test` when relevant.
- Android: the Android workflow must run; do not build it locally. Device-only
  behavior stays **unverified** until a person tests it on a phone.
- Add a test for behavior changes; update the owning docs in the same change.
- Report exactly what ran, what did not, what remains device-only, and anything
  that needs the owner. Never call an unrun check successful.
- Deploys and releases require the owner's explicit approval. The backend
  deploy is skipped unless `CLOUDFLARE_API_TOKEN` is configured; signing uses
  GitHub secrets.

## Canonical implementations

- Wake-ups: `Mailbox.wake` in `backend/src/mailbox.ts`; timings in
  `backend/src/protocol.ts`; rationale in `docs/NOTIFICATIONS.md`.
- Relay connection: `network/RelayConnection.kt`. No second socket client.
- Messages and payloads: `ChatEngine.kt`, `Payload.kt`, and
  `backend/src/protocol.ts`; change both ends together.
- Encryption: `crypto/Sessions.kt` and libsignal. Other sealed objects use
  `crypto/FileCipher.kt` or `crypto/Recovery.kt`; do not invent ciphers.
- Live location: `LiveLocation.kt` and `share*` in `mailbox.ts`; read
  `docs/LOCATION.md` first.
- Notifications: `push/Notifications.kt`; the live-location foreground service
  posts its own ongoing notification in `push/LocationService.kt`.
- Tests: copy the nearest test's style; sentence-case test names.

## Security-sensitive areas

Any change in these needs the whole path read, not the diff:
`crypto/`, `Payload.kt`, `backend/src/protocol.ts`, `backend/src/mailbox.ts`
(auth, queue, wake-ups, shares), `backend/src/{auth,members}.ts`,
`data/DatabaseKey.kt`, `data/AppDatabase.kt`, `network/`, `updates/`, and
`push/Notifications.kt` (what a locked screen may say). The rules those areas
must keep — nothing readable by the relay, no identifiers in logs, limits as
named constants with tests, strict parsing of every frame — are written out in
[docs/SECURITY.md](docs/SECURITY.md) §"If you are changing something
security-sensitive". A change there is not done until that list has been
checked line by line.

## Gotchas

- `npm run typecheck` regenerates `backend/worker-configuration.d.ts`; review
  the diff rather than blindly reverting it.
- CI uses Node 24; Wrangler needs Node 22.12 or newer.
- Robolectric uses a plain `Application`, not `MaunApp`; its background work
  does not run in a unit test.
- Notification-channel sound is fixed when created; a sound change needs a new
  channel (`docs/NOTIFICATIONS.md`).
- Keep `versionCode` increasing: the updater refuses an equal or lower version.
- Room schemas live at
  `android/app/schemas/org.maun.messenger.data.AppDatabase/`; commit any
  generated schema change with the migration.
- Backend local integration tests use `backend/scripts/test-relay.sh`; per-IP
  limits are lifted because all test phones share one address.

## Conventions

Branch from `main`; one idea per PR; squash-merge; never force-push or rewrite
published history. Commit subjects say what changed, in a sentence; the body
says why and what was observed. Follow the nearby code and tests. Keep writing
plain and specific: no marketing, no unsupported security or privacy claims,
and say **unverified** when a handset or outside review would be needed.

## The docs, and what each is for

| File | For |
|---|---|
| `README.md` | A person deploying the relay and using the app. The product, in their words |
| `PROJECT.md` | What Maun is and is not, in one page |
| `TECHNICAL.md` | The original specification, plus a table of where the build differed |
| `PLAN.md` | Build plan, decisions taken, progress as it was recorded |
| `docs/ARCHITECTURE.md` | The system: parts, boundaries, data flows, module responsibilities |
| `docs/API.md` | Routes, frames, limits, close codes — an index into `protocol.ts` |
| `docs/DATABASE.md` | Every table in every store, the migration history, retention |
| `docs/SECURITY.md` | What is encrypted with what, who sees what, and the honest limits |
| `docs/TESTING.md` | What is tested by machine, what needs a phone, how to run it |
| `docs/NOTIFICATIONS.md` | How a phone is woken, and why every part of it is shaped that way |
| `docs/CHAT.md` | Selection, reactions, replies, scrolling, what a notification targets |
| `docs/LOCATION.md` | Live location and a place sent once: what the relay keeps, what it cannot |
| `docs/RECOVERY.md` | What survives an uninstall, a reinstall, a new phone |
| `docs/RELEASE.md` | Building, signing and publishing a version |
| `docs/PLAY_PROTECT.md`, `docs/PLAY_PROTECT_APPEAL.md` | The Play Protect warning, and the appeal |
| `docs/PERFORMANCE.md` | What the conversation screen's work costs, and what was fixed |
| `docs/AUTHORS.md` | Who wrote what |
