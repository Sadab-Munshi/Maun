# The wire: routes, frames, limits

An index of what the relay answers, what the phone sends, and where each one is
enforced. `backend/src/protocol.ts` is the canonical definition — every frame
shape, limit, timing and close code lives there with the reasoning next to it.
This page exists so you can find the right part of that file quickly, and so a
change to one side can be checked against the other.

Read at `acc3494` (protocol 2, app 0.29.0). If this page and `protocol.ts`
disagree, `protocol.ts` is right and this page is a bug.

## Two halves of an API

| Half | Carried by | Defined in |
|---|---|---|
| The live connection: register, login, send, receive, keys, wake-ups, calls, recovery | One WebSocket per phone, `wss://<relay>/v1/ws/<mailbox id>` | `backend/src/protocol.ts`, `backend/src/mailbox.ts` |
| Everything that does not fit a socket: files, pictures, live location, the email login, the administrator's page | Plain HTTPS requests | `backend/src/index.ts`, `backend/src/auth.ts`, `backend/src/members.ts` |

The phone's side of both is `android/.../network/`: `RelayConnection.kt` (the
socket and its back-off), `RelayClient.kt` and `RelayProtocol.kt` (frames),
`FileApi.kt`, `AvatarApi.kt`, `LocationApi.kt`, `AuthApi.kt`, `Addresses.kt`.

## HTTPS routes

All paths are absolute on the relay's origin. `member-only` means the relay was
deployed with a member list; on an open relay those routes answer `no_members`.

### Public

| Route | Answers |
|---|---|
| `GET /health` | `{ok, service:"maun-relay", proto, access, calls, files}`. `access` is true on a member-only relay, `calls` when TURN is configured, `files` is always true. No secrets and no counts. |
| `GET /v1/calls/check` | `{configured, working, status}` — asks the call service for credentials once and reports only whether it answered. Rate-limited per address under the `SIGNUP_LIMIT` name. |
| `GET /v1/ws/<64 hex>` | The only WebSocket route. `400` for a malformed id, `426` without an `Upgrade` header, `429` when either rate limit refuses. |
| `GET /admin` (and `/` when the request arrives on the administrator's own hostname) | The administrator's page, one self-contained HTML document (`backend/src/admin-page.ts`). On any other host `/` is `404`. |

### Signed in with `Authorization: Bearer <mailbox id>.<files token>`

The token is handed to the phone in the `ready` frame as `files`, and is valid
until the next login. Every route below also passes the per-address connection
rate limit (`CONNECT_LIMIT`), and the mailbox's own ban and quota state.

| Route | Body | Answers |
|---|---|---|
| `PUT /v1/avatars` | Picture bytes, `Content-Length` required | `201 {ver, size}`; replaces the previous picture. 256 KB ceiling. |
| `DELETE /v1/avatars` | — | `204`. |
| `GET /v1/avatars/<id>` | — | The bytes with `X-Maun-Avatar-Version`, or `404`. Needs a valid signed-in credential; any signed-in account can fetch any picture. |
| `POST /v1/location` | `{to, minutes}` | `201 {share, until}`. Names the viewer and the length; the relay clamps the length to `SHARE_MINUTES`. |
| `POST /v1/location/<32 hex>` | One sealed point, at most `MAX_SHARE_POINT_BYTES` | `{ok:true}`, `429 too_soon` (closer than `SHARE_MIN_INTERVAL_MS`), `410 ended`, `404 gone`. |
| `DELETE /v1/location/<32 hex>` | — | `204`; the point goes with the share. |
| `GET /v1/location/<64 hex sharer>/<32 hex share>` | — | `{state:"live", until, point, at}`, `{state:"ended", until}` or `{state:"gone"}`. Only the named viewer ever sees `live`. |
| `POST /v1/files` | Ciphertext, `Content-Length` required | `201 {key, size}`; at most `MAX_FILE_BYTES` (25 MB + 65 536), against the day's 250 MB / 200 file allowance. The name is made by the relay, so nobody can aim at a file that exists. |
| `GET /v1/files/<64 hex key>` | — | The bytes, or `404` after the 24-hour life or once swept. |

Refusals are JSON: `{error:"denied"|"banned"|"too_large"|"quota"|"bad_request"|"gone"|…}`,
with `max` alongside `too_large`, and `429` with `Retry-After` for rate limits.

### Member-only

| Route | Purpose |
|---|---|
| `POST /v1/auth/start` | `{email}` → sends a six-character login code by mail. At most one code a minute, four a day per address, 90 mails a day per relay. |
| `POST /v1/auth/verify` | `{email, code}` → `{ticket, username, id, hasRecovery}`. Five wrong codes burn the code (`CODE_ATTEMPTS`). |
| `POST /v1/auth/recovery` | `{ticket}` → `{salt, kdf, blob}`: the sealed identity backup the relay holds. |
| `POST /v1/auth/replace` | `{ticket}`: the member has agreed to start a new ID. |
| `POST /v1/admin/start`, `/v1/admin/login`, `/v1/admin/members`, `/v1/admin/logout` | The administrator's page. POSTs must carry `Origin` equal to the relay's own origin. The session is a `maun_admin` cookie, `Path=/v1/admin`, `HttpOnly; Secure; SameSite=Strict`, 12 hours. Five wrong passwords lock it for 15 minutes. |

Error codes on these routes: `wrong_code`, `wrong_password`, `locked`,
`pending`, `banned`, `no_members`, `identity_conflict`, `rate_limited`.

## WebSocket frames

One socket, one mailbox, one JSON object per frame. `MAX_FRAME_BYTES` is
16 384; anything larger is refused before parsing.

### The relay speaks first

| Frame | Meaning |
|---|---|
| `hello` | `{proto, nonce, registered, access}`. The phone checks `proto` and signs `nonce`. |
| `ready` | `{fresh, keys, otks, push, pushStatus, vapid, queued, email, name, verified, files}`. Sent after a successful register or login; the app uses `files` for the HTTPS routes and `vapid` for its push app. |

### Phone → relay

| `t` | What it does |
|---|---|
| `register` | Identity key, Keystore device key, and two signatures over the registration transcript, plus an access ticket on a member-only relay. |
| `login` | A signature over the login transcript by the device key. |
| `echo` | Round-trip check used by the diagnostics and the tests. |
| `put_keys`, `put_otks` | Publish the signed pre-key, the Kyber pre-key and a batch of one-time pre-keys. |
| `get_keys` | Fetch another account's pre-key bundle. |
| `send` | `{to, kind, body, urgent, quiet}` — one sealed message. `urgent` asks for an immediate wake-up, `quiet` asks for none. |
| `ack` | `{seq}` — this message has been taken; delete it. |
| `turn` | Ask for call credentials. |
| `put_push`, `drop_push` | Leave or remove the wake-up address and its keys. |
| `put_recovery`, `drop_recovery` | Store or remove the sealed identity backup. |
| `find` | Look a username up (`{name}`) or ask what a given ID is called (`{user}`). |
| `verified` | Ask whether a list of IDs wears the administrator's badge. |
| `device` | Report this phone's make, model and OS, for the administrator's member list. |

### Relay → phone

| `t` | Meaning |
|---|---|
| `msg` | `{seq, from, kind, body, at, age}` — one queued or forwarded message. `from` is always the true sender. |
| `stored` | `{id}` — the `send` with that request id is in the queue (or delivered). |
| `saved` | `{id, otks}` — pre-keys accepted, with how many one-time keys are left. |
| `keys` | The pre-key bundle for a `get_keys`. |
| `found` | The answer to a `find`. |
| `verified` | The answer to `verified`. |
| `ice` | `{servers, ttl}` — call credentials. |
| `echo` | Round-trip answer. |
| `failed` | `{id, code}` — a specific request failed. The codes are in `FailureCode` in `protocol.ts`. |
| `error` | `{code}` — the connection itself is in trouble. The codes are in `ErrorCode`. |

### Close codes

| Code | Meaning |
|---|---|
| `4001` | Another connection replaced this one for the same ID. |
| `4003` | Authentication failed. |
| `4008` | Nothing arrived in time, or the phone did not authenticate. |
| `4401` | Member-only relay: this ID has to go through the email login. |
| `4402` | Member-only relay: the administrator has not approved this member yet. |
| `4403` | This phone has been banned. |

## Limits and timings worth knowing

All from `backend/src/protocol.ts` unless stated.

| Thing | Value |
|---|---|
| Frame / message body | 16 384 / 8 192 bytes |
| Queue | 1 000 messages, 300 from one sender, 30 days |
| Delivery window per connection | 50 messages before the next `fetch` |
| Wake-up repeat | 5 s (2 s for a call), 2 000 a day, 10 minutes of settling before a retry |
| Wake retries | 20 s, 60 s, 180 s; a call 8 s, 20 s |
| File | 25 MB + 65 536, one day, 250 MB and 200 uploads a day |
| Avatar | 256 KB |
| Live share | 15 minutes to a day, 8 at once, one point per 5 s, 512 bytes each, forgotten a day after it ends |
| Call credentials | 4 hours, 60 sets a day |
| Registration rate limits | `CONNECT_LIMIT` 120 a minute per address, `MAILBOX_LIMIT` 20 a minute per mailbox, `SIGNUP_LIMIT` 10 a minute, `SEND_LIMIT` 120 a minute (`backend/wrangler.toml`) |

## Changing it

1. Change `protocol.ts` first, with the reason in the comment.
2. Change both sides in the same pull request: the phone's frames in
   `android/.../network/RelayProtocol.kt` and `ChatEngine`, the relay's in
   `mailbox.ts`.
3. Tests: `backend/test/protocol.test.ts` for parsing and limits, plus the suite
   that owns the behaviour (`messages`, `keys`, `push`, `files`, `location`,
   `calls`). On the phone, `PayloadTest`, `SignalSessionsTest` and
   `ChatEngineTest`.
4. Bump `PROTOCOL_VERSION` only for a change an older phone cannot survive.
   `hello.proto` is checked by the phone, so a bump is a hard cut-off.
5. Update this page and `docs/ARCHITECTURE.md` in the same pull request.
