import { accessConfig, looksLikeAccessToken } from "./crypto/access";
import { type FileAnswer, type Mailbox, tooManyRequests } from "./mailbox";
import { FILE_TTL_MS, MAX_FILE_BYTES, PROTOCOL_VERSION, isFileKey, isMailboxId } from "./protocol";

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
        access: accessConfig(env) !== null,
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

    if (request.method === "GET" && url.pathname === "/v1/access/login") return accessPage(request, env);

    if (request.method === "POST" && url.pathname === "/v1/files") return upload(request, env);
    const file = /^\/v1\/files\/([^/]+)$/.exec(url.pathname);
    if (request.method === "GET" && file) return download(request, env, file[1] ?? "");

    return new Response("not found", { status: 404 });
  },

  /** Hourly: deletes every file older than a day. */
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    await deleteOldFiles(env, Date.now());
  },
} satisfies ExportedHandler<Env>;

// ---- Email login ---------------------------------------------------------------

/**
 * The page the app opens to log someone in. Cloudflare Access stands in front
 * of this path: it shows its email login and lets the request through only
 * with a token, which this page hands to the app. The token is checked later,
 * by the mailbox that is asked to accept it.
 */
function accessPage(request: Request, env: Env): Response {
  if (!accessConfig(env)) return new Response("not found", { status: 404 });
  const token = request.headers.get("Cf-Access-Jwt-Assertion");
  const headers = {
    "Content-Type": "text/html; charset=utf-8",
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
  };
  if (!looksLikeAccessToken(token)) {
    // Reached without passing through Access: it is not set up for this path.
    const body = page("Email login is not set up", "Cloudflare Access is not protecting this address yet.", "");
    return new Response(body, { status: 503, headers });
  }
  return new Response(page("You are logged in", "Return to Maun.", token), { headers });
}

function page(title: string, text: string, token: string): string {
  // The token is letters, digits, dots, dashes and underscores only.
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Maun</title>
<style>body{font-family:system-ui,sans-serif;margin:3rem 1.5rem;text-align:center;color:#17211d;background:#f7f4ee}
@media (prefers-color-scheme: dark){body{color:#e4ebe7;background:#0e1513}}</style></head>
<body data-maun-access="${token}"><h1>${title}</h1><p>${text}</p></body></html>`;
}

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

/** Deletes every stored file that is a day old. Returns how many went. */
export async function deleteOldFiles(env: Env, now: number): Promise<number> {
  let deleted = 0;
  let cursor: string | undefined;
  // At most 20,000 names a run; the next hour takes up what is left.
  for (let page = 0; page < 20; page++) {
    const listing = await env.FILES.list({ limit: 1000, ...(cursor ? { cursor } : {}) });
    const old = listing.objects.filter((object) => now - object.uploaded.getTime() >= FILE_TTL_MS).map((object) => object.key);
    if (old.length > 0) {
      await env.FILES.delete(old);
      deleted += old.length;
    }
    if (!listing.truncated) break;
    cursor = listing.cursor;
  }
  return deleted;
}
