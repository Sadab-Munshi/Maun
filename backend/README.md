# The relay

One Cloudflare Worker and one Durable Object per account. TypeScript, no
application framework. It holds a bounded, expiring queue of ciphertext and
routing metadata it can read (IDs, timing and sizes), but no message contents;
it wakes phones that are not connected.

Read [../docs/NOTIFICATIONS.md](../docs/NOTIFICATIONS.md) before touching the
wake-up path, and [../AGENTS.md](../AGENTS.md) for how work is done here.
[../docs/API.md](../docs/API.md) is the index of every route and frame;
[../docs/DATABASE.md](../docs/DATABASE.md) is what the two stores hold;
[../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) is how it fits together.

## Commands

Node 22.12 or newer is needed by `wrangler`; CI uses 24.

```sh
npm ci
npm run typecheck      # wrangler types && tsc --noEmit
npm test               # open relay, then member mode
npm run dev            # local relay on http://127.0.0.1:8787
npm run bench          # CPU cost of the signature checks
npm run deploy         # npx wrangler deploy — needs a logged-in wrangler
```

Two test configurations, both required:

| Config | Relay it stands up |
|---|---|
| `vitest.config.ts` | An open relay: anyone may make an ID |
| `vitest.members.config.ts` | A member-only relay: email codes, usernames, approvals, bans |

Tests run inside Cloudflare's local runtime, Durable Objects and all, so they
exercise the real storage rather than a mock.

```sh
npx vitest run --config vitest.config.ts test/push.test.ts
npx vitest run --config vitest.config.ts test/push.test.ts -t "wake"
```

## Where things are

| File | What it does |
|---|---|
| `src/index.ts` | The stateless front door: health, the WebSocket upgrade, avatars, files, live-location routes |
| `src/mailbox.ts` | One account's Durable Object: the encrypted queue, wake-ups and their retries, push address, limits, retirement |
| `src/protocol.ts` | Frame shapes and every shared number: queue limits, timings, byte sizes |
| `src/crypto/webpush.ts` | Encrypting and signing a wake-up the way a push service expects |
| `src/auth.ts`, `src/members.ts`, `src/mail.ts` | Member-only mode |
| `src/admin-page.ts` | The administrator's page, as one self-contained document |
| `src/turn.ts` | Short-lived TURN credentials for calls |
| `test/` | Everything above, tested |
| `scripts/test-relay.sh` | The relay with per-address connection limits lifted, for the app's tests |
| `wrangler.toml` | The Worker, its Durable Object, buckets, queues, rate limits |

## Deploying

`npx wrangler deploy` from this directory, with a relay name in
`wrangler.toml` that matches the Worker in the Cloudflare dashboard. On `main`
the Backend workflow will do it if `CLOUDFLARE_API_TOKEN` is set — in this
repository it is not, so that step is skipped and says so.

Member-only mode, TURN keys and the mail provider are Cloudflare variables and
secrets; the README explains them and nothing of the sort belongs in this
repository.
