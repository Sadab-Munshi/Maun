# How this project is tested

Two suites, one of them in two halves, and a set of things that are only ever
checked on a phone. This page says what is covered by machine, what is not, and
how to run it. The numbers below were run at `acc3494` on 2026-11-16; treat them
as a snapshot, not a promise.

## What runs where

| Suite | Command | What it needs | Count at `acc3494` |
|---|---|---|---|
| Relay, open | `cd backend && npm test` | Node 22.12 or newer | 12 files, 156 tests |
| Relay, member-only | same command (its second half) | " | 1 file, 49 tests |
| App, unit and Robolectric | `cd android && ./gradlew :app:testDebugUnitTest` | JDK 21, Android SDK, NDK | 379 `@Test` methods |
| App, against a real relay | the same command with `MAUN_TEST_RELAY_URL` set | a relay running locally | 2 classes inside the 379 |
| App, screens | `./gradlew :app:testDebugUnitTest -Pmaun.screens=true` | " | 66 in `screens/ScreensTest.kt` |
| Signature benchmark | `cd backend && npm run bench` | Node | `bench/verify-bench.mjs` |

`npm test` is `vitest run && vitest run --config vitest.members.config.ts`: the
member-only suite has its own config because it needs a different `Env` binding
set. Both must pass; a change to `members.ts` that skips the second command has
not been tested at all.

## The relay

`backend/test/helpers.ts` boots the Worker inside Cloudflare's own runtime
(`@cloudflare/vitest-pool-workers`), so requests are real requests and Durable
Objects are real Durable Objects, with real storage, in-process. Nothing is
deployed and nothing is mocked except the outside world: the mail provider and
the push service are answered by functions the test sets.

| File | What it establishes |
|---|---|
| `protocol.test.ts` | Frame parsing, every length limit, base64 strictness |
| `relay.test.ts` | The front door: `/health`, the upgrade, bad ids, rate limits |
| `messages.test.ts` | Queue, acknowledgement, ordering, sender naming, both caps, expiry |
| `keys.test.ts` | Pre-key publication and removal as bundles are taken |
| `signatures.test.ts` | Registration and login transcripts, and what a bad signature does |
| `push.test.ts` | Wake-up encryption, the repeat and daily caps, the retry chain, a dead address |
| `webpush.test.ts` | RFC 8291 vectors |
| `files.test.ts` | Upload, download, the exact-size check, quotas, the sweep |
| `avatars.test.ts` | Replace, version, delete, size ceiling |
| `location.test.ts` | One sealed point, only the named viewer, expiry, "too soon", share caps |
| `calls.test.ts` | Credential minting, the daily cap, a refusal from the call service |
| `auth.test.ts` | Codes, attempts, tickets, the administrator's page and cookie |
| `members/members.test.ts` | Approval, bans, the sealed backup, `identity_conflict`, replace |

Two habits in these tests are deliberate and worth keeping: they assert on the
frame the relay sends rather than on internal state where they can, and they run
against the same `protocol.ts` constants the Worker uses, so a limit changed in
one place cannot drift.

## The app

JVM tests run under Robolectric with a plain `android.app.Application`
(`@Config(application = Application::class)`), no emulator. `support/FakeDb.kt`
is an in-memory stand-in for the Room DAO that really rolls back, so
`ChatEngine` can be tested end to end without a phone.

| Area | Test class | What it pins down |
|---|---|---|
| Conversations | `ChatEngineTest` (26) | Outbox and retries, receipts, requests, blocking, moves, reactions, replies |
| Payloads | `PayloadTest` (14) | Field limits, what is refused on decode, the reaction lists |
| Sessions | `SignalSessionsTest` (12) | Pre-key bundles, session setup, the Kyber replay check |
| Lists | `ChatListsTest` (17) | Ordering, badges, unread counts, search |
| Location | `LiveLocationTest` (21), `ui/LiveCardTextTest` | Share states, the wording of the card, distance |
| Updates | `UpdatesTest` (12) | Version comparison and the download check |
| Links | `LinksTest` (12) | What a tapped link may open |
| Announcements | `AnnouncementTest` (11) | The one-time notices and their memory key |
| Push | `push/*` | Channels, the delivery trace, the fetch decision |
| Connection | `network/RelayConnectionTest`, `network/RelayClientTest` | Back-off, resume, the real handshake |
| Screens | `screens/ScreensTest.kt` (66) | That each screen draws with made-up data, light and dark |

The last two rows are the ones that need a relay. They skip themselves unless
`MAUN_TEST_RELAY_URL` is set, and `build-android.yml` starts
`backend/scripts/test-relay.sh 8787` and then **fails if they were skipped** —
a skip there would mean the two halves were never run against each other.

Screens are drawn, not compared: `-Pmaun.screens=true` writes PNGs to
`app/build/screens` and the `screens` workflow keeps them as an artifact.
Nothing fails because a screen looks different; a person looks. Robolectric
cannot render MapLibre, so a map screenshot shows the surrounding chrome only.

## What no test covers

These need a phone, and every document that describes them is describing
something checked by hand, or not yet:

- Ringing, the call screen, and whether a call wakes a locked phone.
- Notification sound, vibration, and what a locked screen shows.
- Doze, battery optimisation, and whether "let Maun run in the background"
  actually keeps it alive.
- The camera scanner, saving to Gallery, and sharing into Maun from another app.
- MapLibre drawing, labels, and the offline look of the map.
- Anything about a specific phone's push service (Google's, Sunup, Mozilla's).

`README.md` describes these as intended behaviour with the reasons behind it.
Where a claim is not backed by a test, the document says so.

## Rules

- **Do not weaken a test to make a change pass.** If a test now fails, find out
  which of the two is wrong; that is the whole point of the test.
- **A new behaviour gets a test in the suite that owns it.** A new frame goes to
  `protocol.test.ts` and to the behaviour suite; a new screen goes to
  `ScreensTest.kt` with made-up data.
- **Never mark a check as passed without running it.** If it was not run, say
  "not run" and why.
- **Timing-based relay tests are sensitive to a slow machine.** `push.test.ts`
  uses short real waits (for example the retry test mocks the scheduler with a
  400 ms wait); a failure there is worth a second run before it is believed.
- One known flake: `test/push.test.ts` → "stops trying a new address once the
  phone has come by itself" has failed on a loaded CI runner before and passed
  unchanged on a re-run. Re-run it; do not re-bump a version because of it.

## CI

| Workflow | Runs | Fails on |
|---|---|---|
| `build-android.yml` | lint, unit tests against a local relay, debug APK, schema export | a test failure, a skipped relay test, a lint error, an APK over 40 MB |
| `deploy-backend.yml` | typecheck and both relay suites, then deploys from `main` | any failure; the deploy job needs the test job |
| `release.yml` | the signed release build on any `android/**` change to `main`, or by hand with `publish` | the signing certificate is not the expected one, or an APK is over 40 MB |
| `screens.yml` | the screen tests on any `ui/` change | only if they fail to draw |
| `classpath.yml` | by hand: exports everything the app compiles against as one artifact | nothing; it is for type-checking on a machine too small for the full build |
