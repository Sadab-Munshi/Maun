import { handleAuth } from "./auth";
import { memberConfig, sweepMembers } from "./members";
import { askTurn, turnConfig } from "./turn";
import { type FileAnswer, type Mailbox, tooManyRequests } from "./mailbox";
import {
  AVATAR_PREFIX,
  FILE_TTL_MS,
  MAX_AVATAR_BYTES,
  MAX_FILE_BYTES,
  MAX_SHARE_POINT_BYTES,
  PROTOCOL_VERSION,
  SHARE_MIN_MINUTES,
  avatarKey,
  isFileKey,
  isMailboxId,
  isShareId,
} from "./protocol";

export { Mailbox } from "./mailbox";

/**
 * Stateless front door. It never logs. Every request is answered here, handed
 * to the Mailbox that owns the address, or (for files) checked with that
 * Mailbox and then served from the bucket.
 */
export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return Response.json({
        ok: true,
        service: "maun-relay",
        proto: PROTOCOL_VERSION,
        access: memberConfig(env) !== null,
        calls: turnConfig(env) !== null,
        files: true,
      });
    }

    const ws = /^\/v1\/ws\/([^/]+)$/.exec(url.pathname);
    if (request.method === "GET" && ws) {
      const mailboxId = ws[1] ?? "";
      if (!isMailboxId(mailboxId)) return new Response("bad mailbox id", { status: 400 });
      if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
        return new Response("expected websocket", { status: 426 });
      }

      // Checked here so that a flood is turned away before it reaches (and is
      // billed to) a mailbox. The address is used as a counter key only.
      const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
      const fromAddress = await env.CONNECT_LIMIT.limit({ key: ip });
      if (!fromAddress.success) return tooManyRequests();
      const toMailbox = await env.MAILBOX_LIMIT.limit({ key: mailboxId });
      if (!toMailbox.success) return tooManyRequests();

      const stub = env.MAILBOX.get(env.MAILBOX.idFromName(mailboxId));
      return stub.fetch(request);
    }

    // Whether calls work: asks the call service for credentials once and says
    // only whether it gave any. The admin page shows it. Nothing secret, and
    // not the credentials, are in the answer.
    if (request.method === "GET" && url.pathname === "/v1/calls/check") {
      const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
      if (!(await env.SIGNUP_LIMIT.limit({ key: `calls:${ip}` })).success) return tooManyRequests();
      const config = turnConfig(env);
      if (!config) return Response.json({ configured: false, working: false, status: null });
      const answer = await askTurn(config);
      return Response.json({ configured: true, working: answer.servers.length > 0, status: answer.status });
    }

    // The email login and the administrator's page of a member-only relay.
    const auth = await handleAuth(request, env, url);
    if (auth) return auth;

    if (url.pathname === "/v1/avatars") {
      if (request.method === "PUT") return putAvatar(request, env);
      if (request.method === "DELETE") return deleteAvatar(request, env);
    }
    const avatar = /^\/v1\/avatars\/([^/]+)$/.exec(url.pathname);
    if (request.method === "GET" && avatar) return getAvatar(request, env, avatar[1] ?? "");

    // Live location: one sealed point per share, replaced as it moves.
    if (request.method === "POST" && url.pathname === "/v1/location") return startShare(request, env);
    const oneShare = /^\/v1\/location\/([0-9a-f]{32})$/.exec(url.pathname);
    if (oneShare) {
      const share = oneShare[1]!;
      if (!isShareId(share)) return new Response("not found", { status: 404 });
      if (request.method === "POST") return putSharePoint(request, env, share);
      if (request.method === "DELETE") return stopShare(request, env, share);
    }
    const aShare = /^\/v1\/location\/([0-9a-f]{64})\/([0-9a-f]{32})$/.exec(url.pathname);
    if (request.method === "GET" && aShare) return peekShare(request, env, aShare[1]!, aShare[2]!);

    if (request.method === "POST" && url.pathname === "/v1/files") return upload(request, env);
    const file = /^\/v1\/files\/([^/]+)$/.exec(url.pathname);
    if (request.method === "GET" && file) return download(request, env, file[1] ?? "");

    return new Response("not found", { status: 404 });
  },

  /** Hourly: deletes every file older than a day. */
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    await deleteOldFiles(env, Date.now());
    if (memberConfig(env)) await sweepMembers(env, Date.now());
  },
} satisfies ExportedHandler<Env>;

// ---- Files ---------------------------------------------------------------------
//
// A file arrives already encrypted, with a key that travels only inside an
// end-to-end encrypted message. The relay stores it under a random name for
// one day and never learns what it is, or for whom.

/** Reads "Authorization: Bearer <mailbox id>.<files token>". */
function credentials(request: Request): { mailboxId: string; token: string } | null {
  const header = request.headers.get("Authorization") ?? "";
  const match = /^Bearer ([0-9a-f]{64})\.([A-Za-z0-9_-]{20,64})$/.exec(header);
  return match ? { mailboxId: match[1]!, token: match[2]! } : null;
}

async function allowed(request: Request, env: Env, size: number): Promise<FileAnswer | "limited"> {
  const who = credentials(request);
  if (!who) return "denied";
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  if (!(await env.CONNECT_LIMIT.limit({ key: ip })).success) return "limited";
  const stub = env.MAILBOX.get(env.MAILBOX.idFromName(who.mailboxId)) as DurableObjectStub<Mailbox>;
  return stub.fileAccess(who.mailboxId, who.token, size);
}

function refusal(answer: Exclude<FileAnswer, "ok"> | "limited"): Response {
  switch (answer) {
    case "limited":
      return tooManyRequests();
    case "banned":
      return Response.json({ error: "banned" }, { status: 403 });
    case "too_large":
      return Response.json({ error: "too_large", max: MAX_FILE_BYTES }, { status: 413 });
    case "quota":
      return Response.json({ error: "quota" }, { status: 429, headers: { "Retry-After": "3600" } });
    default:
      return Response.json({ error: "denied" }, { status: 401 });
  }
}

async function upload(request: Request, env: Env): Promise<Response> {
  const declared = Number(request.headers.get("Content-Length") ?? "");
  if (!Number.isSafeInteger(declared) || declared <= 0 || !request.body) {
    return Response.json({ error: "length_required" }, { status: 411 });
  }
  // Refused before a byte is read, and before the mailbox is asked.
  if (declared > MAX_FILE_BYTES) return refusal("too_large");
  const answer = await allowed(request, env, declared);
  if (answer !== "ok") return refusal(answer);

  // The name is made here, so nobody can aim at a file that exists.
  const key = [...crypto.getRandomValues(new Uint8Array(32))].map((b) => b.toString(16).padStart(2, "0")).join("");
  let stored: R2Object | null;
  try {
    // Exactly the declared number of bytes: more or fewer fails the upload.
    const exact = new FixedLengthStream(declared);
    const copied = request.body.pipeTo(exact.writable);
    [stored] = await Promise.all([env.FILES.put(key, exact.readable), copied]);
  } catch {
    await env.FILES.delete(key).catch(() => {});
    return Response.json({ error: "incomplete" }, { status: 400 });
  }
  if (!stored) return Response.json({ error: "incomplete" }, { status: 400 });
  if (stored.size > MAX_FILE_BYTES) {
    await env.FILES.delete(key);
    return refusal("too_large");
  }
  return Response.json({ key, size: stored.size }, { status: 201 });
}

async function download(request: Request, env: Env, key: string): Promise<Response> {
  if (!isFileKey(key)) return new Response("not found", { status: 404 });
  const answer = await allowed(request, env, 0);
  if (answer !== "ok") return refusal(answer);
  const object = await env.FILES.get(key);
  if (!object) return Response.json({ error: "gone" }, { status: 404 });
  if (Date.now() - object.uploaded.getTime() >= FILE_TTL_MS) {
    // Too old to hand out, even if the hourly sweep has not come by yet.
    await env.FILES.delete(key);
    return Response.json({ error: "gone" }, { status: 404 });
  }
  return new Response(object.body, {
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Length": String(object.size),
      "Cache-Control": "no-store",
    },
  });
}

// ---- Live location ----------------------------------------------------------------
//
// A share is kept in the mailbox of the phone that shares it, and is read by
// exactly one other account, the one it was started for. What travels through
// here is a sealed point: the latitude, the longitude and the accuracy,
// encrypted on the phone with a key that reached the watcher inside an
// end-to-end encrypted message. The relay cannot read a point, cannot tell one
// place from another, and keeps only the newest one of each share, until the
// minute the share was promised for. Then it throws the point away.

/** The account in the credential, if the relay still lets it in. */
async function signedIn(
  request: Request,
  env: Env,
): Promise<{ id: string; stub: DurableObjectStub<Mailbox> } | Response> {
  const answer = await allowed(request, env, 0);
  if (answer !== "ok") return refusal(answer);
  const who = credentials(request);
  if (!who) return refusal("denied");
  const stub = env.MAILBOX.get(env.MAILBOX.idFromName(who.mailboxId)) as DurableObjectStub<Mailbox>;
  return { id: who.mailboxId, stub };
}

/** Reads a small JSON body, or null when it is not one. */
async function smallJson(request: Request, maxBytes: number): Promise<Record<string, unknown> | null> {
  let text: string;
  try {
    text = await request.text();
  } catch {
    return null;
  }
  if (text.length === 0 || text.length > maxBytes) return null;
  try {
    const value: unknown = JSON.parse(text);
    if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
    return value as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** Starts a share: who may watch, and for how long. */
async function startShare(request: Request, env: Env): Promise<Response> {
  const me = await signedIn(request, env);
  if (me instanceof Response) return me;
  const body = await smallJson(request, 2048);
  if (!body) return Response.json({ error: "bad_request" }, { status: 400 });
  const to = typeof body.to === "string" ? body.to : "";
  if (!isMailboxId(to)) return Response.json({ error: "bad_viewer" }, { status: 400 });
  const minutes = typeof body.minutes === "number" && Number.isFinite(body.minutes) ? body.minutes : SHARE_MIN_MINUTES;
  const answer = await me.stub.shareStart(me.id, to, minutes);
  if (answer === "bad_viewer") return Response.json({ error: "bad_viewer" }, { status: 400 });
  if (answer === "full") return Response.json({ error: "too_many", max: 8 }, { status: 429 });
  if (answer === "no_account") return Response.json({ error: "no_account" }, { status: 409 });
  return Response.json(answer, { status: 201 });
}

/** Handing over the newest point of a share. */
async function putSharePoint(request: Request, env: Env, share: string): Promise<Response> {
  const declared = Number(request.headers.get("Content-Length") ?? "");
  if (!Number.isSafeInteger(declared) || declared <= 0 || !request.body) {
    return Response.json({ error: "length_required" }, { status: 411 });
  }
  if (declared > MAX_SHARE_POINT_BYTES) {
    return Response.json({ error: "too_large", max: MAX_SHARE_POINT_BYTES }, { status: 413 });
  }
  const me = await signedIn(request, env);
  if (me instanceof Response) return me;
  const point = new Uint8Array(await request.arrayBuffer());
  if (point.length === 0 || point.length > MAX_SHARE_POINT_BYTES) {
    return Response.json({ error: "too_large", max: MAX_SHARE_POINT_BYTES }, { status: 413 });
  }
  const answer = await me.stub.sharePoint(me.id, share, point);
  switch (answer) {
    case "ok":
      return Response.json({ ok: true });
    case "too_soon":
      return Response.json({ error: "too_soon" }, { status: 429 });
    case "ended":
      return Response.json({ error: "ended" }, { status: 410 });
    case "gone":
      return Response.json({ error: "gone" }, { status: 404 });
    case "too_large":
      return Response.json({ error: "too_large", max: MAX_SHARE_POINT_BYTES }, { status: 413 });
    default:
      return Response.json({ error: "no_account" }, { status: 409 });
  }
}

/** Stopping a share before its time is up. */
async function stopShare(request: Request, env: Env, share: string): Promise<Response> {
  const me = await signedIn(request, env);
  if (me instanceof Response) return me;
  const answer = await me.stub.shareStop(me.id, share);
  if (answer === "gone") return Response.json({ error: "gone" }, { status: 404 });
  if (answer === "no_account") return Response.json({ error: "no_account" }, { status: 409 });
  return new Response(null, { status: 204 });
}

/** What the account a share was started for may see of it. */
async function peekShare(request: Request, env: Env, sharer: string, share: string): Promise<Response> {
  const me = await signedIn(request, env);
  if (me instanceof Response) return me;
  // A share is never read by the phone that made it: it knows where it is.
  if (sharer === me.id) return Response.json({ state: "gone" });
  const stub = env.MAILBOX.get(env.MAILBOX.idFromName(sharer)) as DurableObjectStub<Mailbox>;
  return Response.json(await stub.sharePeek(sharer, share, me.id));
}

// ---- Profile pictures ------------------------------------------------------------
//
// One picture per account, kept under `avatar/<mailbox id>` and replaced in
// place. Like a file, it arrives already encrypted, and its key travels only
// inside end-to-end encrypted messages between people who have each other as
// contacts; the relay stores, and can read, nothing but ciphertext. It is not
// a message and never expires on its own, so the hourly sweep leaves it be:
// the only thing that removes a picture is its owner.

/** Stores, or replaces, the picture of the account in the credential. */
async function putAvatar(request: Request, env: Env): Promise<Response> {
  const declared = Number(request.headers.get("Content-Length") ?? "");
  if (!Number.isSafeInteger(declared) || declared <= 0 || !request.body) {
    return Response.json({ error: "length_required" }, { status: 411 });
  }
  if (declared > MAX_AVATAR_BYTES) {
    return Response.json({ error: "too_large", max: MAX_AVATAR_BYTES }, { status: 413 });
  }
  // A picture does not count against the day's allowance for files: it is
  // small, it replaces itself, and it is not part of a conversation.
  const answer = await allowed(request, env, 0);
  if (answer !== "ok") return refusal(answer);
  const who = credentials(request);
  if (!who) return refusal("denied");

  const ver = [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, "0")).join("");
  const key = avatarKey(who.mailboxId);
  let stored: R2Object | null;
  try {
    const exact = new FixedLengthStream(declared);
    const copied = request.body.pipeTo(exact.writable);
    [stored] = await Promise.all([
      env.FILES.put(key, exact.readable, { customMetadata: { ver } }),
      copied,
    ]);
  } catch {
    await env.FILES.delete(key).catch(() => {});
    return Response.json({ error: "incomplete" }, { status: 400 });
  }
  if (!stored || stored.size > MAX_AVATAR_BYTES) {
    await env.FILES.delete(key).catch(() => {});
    return Response.json({ error: "too_large", max: MAX_AVATAR_BYTES }, { status: 413 });
  }
  return Response.json({ ver, size: stored.size }, { status: 201 });
}

/**
 * Hands out the encrypted picture of [id] to any account that can log in. A
 * picture is useless without the key from an encrypted message, and only
 * people one has as contacts are ever given one.
 */
async function getAvatar(request: Request, env: Env, id: string): Promise<Response> {
  if (!isMailboxId(id)) return Response.json({ error: "no_avatar" }, { status: 404 });
  const answer = await allowed(request, env, 0);
  if (answer !== "ok") return refusal(answer);
  const object = await env.FILES.get(avatarKey(id));
  if (!object) return Response.json({ error: "no_avatar" }, { status: 404 });
  return new Response(object.body, {
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Length": String(object.size),
      "Cache-Control": "no-store",
      "X-Maun-Avatar-Version": object.customMetadata?.ver ?? "",
    },
  });
}

/** Removes the picture of the account in the credential. Doing so twice is not an error. */
async function deleteAvatar(request: Request, env: Env): Promise<Response> {
  const answer = await allowed(request, env, 0);
  if (answer !== "ok") return refusal(answer);
  const who = credentials(request);
  if (!who) return refusal("denied");
  await env.FILES.delete(avatarKey(who.mailboxId));
  return new Response(null, { status: 204 });
}

/** Deletes every stored file that is a day old. Returns how many went. */
export async function deleteOldFiles(env: Env, now: number): Promise<number> {
  let deleted = 0;
  let cursor: string | undefined;
  // At most 20,000 names a run; the next hour takes up what is left.
  for (let page = 0; page < 20; page++) {
    const listing = await env.FILES.list({ limit: 1000, ...(cursor ? { cursor } : {}) });
    // A profile picture is not a file of a conversation: it has no day after which it goes.
    const old = listing.objects
      .filter((object) => !object.key.startsWith(AVATAR_PREFIX) && now - object.uploaded.getTime() >= FILE_TTL_MS)
      .map((object) => object.key);
    if (old.length > 0) {
      await env.FILES.delete(old);
      deleted += old.length;
    }
    if (!listing.truncated) break;
    cursor = listing.cursor;
  }
  return deleted;
}
