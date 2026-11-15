import { SELF, env } from "cloudflare:test";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { forgetAccessKeys } from "../../src/crypto/access";
import { forgetSchema } from "../../src/members";
import { CLOSE_ACCESS_REQUIRED, CLOSE_BANNED, MEMBER_CHECK_MS } from "../../src/protocol";
import {
  connect,
  freshIp,
  hangUp,
  keysFrame,
  loginFrame,
  makeUser,
  randomBytes,
  registerFrame,
  requestId,
  type Inbox,
  type User,
} from "../helpers";

// This file runs with ACCESS_TEAM and ACCESS_AUD set (vitest.members.config.ts):
// the relay is member-only.

const TEAM = "team.test";
const AUD = "aud-of-this-relay";

let signer: CryptoKeyPair;
let stranger: CryptoKeyPair;
let jwks: { keys: unknown[] };

function base64Url(bytes: Uint8Array | string): string {
  const data = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  return btoa(String.fromCharCode(...data)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

type Claims = { email?: string; aud?: unknown; iss?: string; exp?: number; nbf?: number };

/** An Access token as Cloudflare would issue it, signed with `key`. */
async function token(email: string, claims: Claims = {}, key: CryptoKey = signer.privateKey, kid = "key-1"): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", kid, typ: "JWT" }));
  const payload = base64Url(
    JSON.stringify({ aud: [AUD], iss: `https://${TEAM}`, exp: now + 3600, iat: now, nbf: now, email, type: "app", ...claims }),
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${payload}`));
  return `${header}.${payload}.${base64Url(new Uint8Array(signature))}`;
}

async function rsa(): Promise<CryptoKeyPair> {
  return (await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
}

beforeAll(async () => {
  signer = await rsa();
  stranger = await rsa();
  const jwk = (await crypto.subtle.exportKey("jwk", signer.publicKey)) as JsonWebKey;
  jwks = { keys: [{ kid: "key-1", kty: "RSA", alg: "RS256", use: "sig", n: jwk.n, e: jwk.e }] };
});

let keyFetches = 0;

beforeEach(async () => {
  forgetAccessKeys();
  // The tests of one file share a database: start each with an empty one.
  await env.DB.batch([env.DB.prepare("DROP TABLE IF EXISTS users"), env.DB.prepare("DROP TABLE IF EXISTS devices")]);
  forgetSchema();
  keyFetches = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (url === `https://${TEAM}/cdn-cgi/access/certs`) {
      keyFetches += 1;
      return Response.json(jwks);
    }
    return new Response("unexpected", { status: 500 });
  });
});

afterEach(() => vi.restoreAllMocks());

/** Sends `register` and returns whatever comes back first. */
async function tryRegister(user: User, access?: string): Promise<{ inbox: Inbox; answer: Record<string, unknown> }> {
  const { inbox, nonce, registered } = await connect(user.id);
  expect(registered).toBe(false);
  inbox.sendJson({ ...(await registerFrame(user.identity, user.device, nonce)), ...(access ? { access } : {}) });
  return { inbox, answer: await inbox.nextJson() };
}

async function tryLogin(user: User, access?: string): Promise<{ inbox: Inbox; answer: Record<string, unknown> }> {
  const { inbox, nonce } = await connect(user.id);
  inbox.sendJson({ ...(await loginFrame(user.identity, user.device, nonce)), ...(access ? { access } : {}) });
  return { inbox, answer: await inbox.nextJson() };
}

/** A member: registered with a token for `email`, and offline again. */
async function member(email: string): Promise<User> {
  const user = await makeUser();
  const { inbox, answer } = await tryRegister(user, await token(email));
  expect(answer).toMatchObject({ t: "ready", fresh: true, email: email.toLowerCase() });
  await hangUp(inbox);
  return user;
}

/** The member list's row for an address; null too when the list was never written to. */
async function row(email: string): Promise<Record<string, unknown> | null> {
  try {
    return await env.DB.prepare("SELECT * FROM users WHERE email = ?").bind(email).first();
  } catch {
    return null;
  }
}

async function ban(email: string): Promise<void> {
  await env.DB.prepare("UPDATE users SET is_banned = 1 WHERE email = ?").bind(email).run();
}

describe("a member-only relay", () => {
  it("says so before login", async () => {
    const user = await makeUser();
    const response = await SELF.fetch(`https://relay.test/v1/ws/${user.id}`, {
      headers: { Upgrade: "websocket", "CF-Connecting-IP": freshIp() },
    });
    const ws = response.webSocket!;
    const hello = new Promise<Record<string, unknown>>((resolve) =>
      ws.addEventListener("message", (event) => resolve(JSON.parse(event.data as string))),
    );
    ws.accept();
    expect(await hello).toMatchObject({ t: "hello", access: true });
    ws.close(1000, "done");
    expect(await (await SELF.fetch("https://relay.test/health")).json()).toMatchObject({ access: true });
  });

  it("turns away an ID that has not been through the email login, and keeps nothing of it", async () => {
    const user = await makeUser();
    const { inbox, answer } = await tryRegister(user);
    expect(answer).toEqual({ t: "error", code: "access_required" });
    expect((await inbox.closed).code).toBe(CLOSE_ACCESS_REQUIRED);

    // No account was made: the address still counts as unregistered.
    const again = await connect(user.id);
    expect(again.registered).toBe(false);
    await hangUp(again.inbox);
    expect(await row("anyone@example.com")).toBeNull();
  });

  it("ties the email address to the ID, once, and then needs no token", async () => {
    const user = await member("Asha@Example.com");
    expect(await row("asha@example.com")).toMatchObject({ maun_id: user.id, is_banned: 0 });

    const { inbox, answer } = await tryLogin(user);
    expect(answer).toMatchObject({ t: "ready", fresh: false, email: "asha@example.com" });
    await hangUp(inbox);
    // The team's keys were fetched for the one token, not for every login.
    expect(keyFetches).toBe(1);
  });

  it("lets an account from before member-only mode in with a token at login", async () => {
    // Registered as a member, then removed from the list: the same as an
    // account made while the relay was open.
    const user = await member("old@example.com");
    await env.DB.prepare("DELETE FROM users").run();

    const without = await tryLogin(user);
    expect(without.answer).toEqual({ t: "error", code: "access_required" });
    expect((await without.inbox.closed).code).toBe(CLOSE_ACCESS_REQUIRED);

    const withToken = await tryLogin(user, await token("old@example.com"));
    expect(withToken.answer).toMatchObject({ t: "ready", email: "old@example.com" });
    await hangUp(withToken.inbox);
  });

  it("refuses tokens that are not this relay's", async () => {
    const now = Math.floor(Date.now() / 1000);
    const bad: [string, Promise<string>][] = [
      ["signed by someone else", token("a@example.com", {}, stranger.privateKey)],
      ["for another application", token("a@example.com", { aud: ["another"] })],
      ["from another team", token("a@example.com", { iss: "https://other.test" })],
      ["expired", token("a@example.com", { exp: now - 10 })],
      ["without an email address", token("")],
      ["naming an unknown key", token("a@example.com", {}, signer.privateKey, "key-9")],
      ["not a token", Promise.resolve("not.a.token")],
    ];
    for (const [what, made] of bad) {
      const user = await makeUser();
      const { inbox, answer } = await tryRegister(user, await made);
      expect(answer, what).toEqual({ t: "error", code: "access_required" });
      expect((await inbox.closed).code, what).toBe(CLOSE_ACCESS_REQUIRED);
    }
    expect(await row("a@example.com")).toBeNull();
  });

  it("does not let a token tie an email address to an ID its bearer does not hold", async () => {
    const victim = await makeUser();
    const thief = await makeUser();
    const { inbox, nonce } = await connect(victim.id);
    // Signed with the thief's keys, for the victim's address.
    const frame = await registerFrame(thief.identity, thief.device, nonce);
    inbox.sendJson({ ...frame, access: await token("thief@example.com") });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_signature" });
    await inbox.closed;
    expect(await row("thief@example.com")).toBeNull();
  });

  it("moves an email address to the ID of a newly set up phone", async () => {
    const first = await member("zoya@example.com");
    const second = await member("zoya@example.com");
    expect(await row("zoya@example.com")).toMatchObject({ maun_id: second.id });

    const old = await tryLogin(first);
    expect(old.answer).toEqual({ t: "error", code: "access_required" });
    await old.inbox.closed;
  });

  it("shuts a banned member out at login", async () => {
    const user = await member("rohan@example.com");
    await ban("rohan@example.com");
    const { inbox, answer } = await tryLogin(user);
    expect(answer).toEqual({ t: "error", code: "banned" });
    expect((await inbox.closed).code).toBe(CLOSE_BANNED);
  });

  it("does not let a banned email address start over with a new ID", async () => {
    await member("rohan@example.com");
    await ban("rohan@example.com");
    const fresh = await makeUser();
    const { inbox, answer } = await tryRegister(fresh, await token("rohan@example.com"));
    expect(answer).toEqual({ t: "error", code: "banned" });
    expect((await inbox.closed).code).toBe(CLOSE_BANNED);
    const again = await connect(fresh.id);
    expect(again.registered).toBe(false);
    await hangUp(again.inbox);
  });

  it("honours a ban made before the address ever logged in", async () => {
    await member("someone@example.com"); // creates the tables
    await env.DB.prepare("INSERT INTO users (email, is_banned) VALUES ('early@example.com', 1)").run();
    const user = await makeUser();
    const { inbox, answer } = await tryRegister(user, await token("early@example.com"));
    expect(answer).toEqual({ t: "error", code: "banned" });
    await inbox.closed;
  });

  it("closes a connected member's phone soon after a ban", async () => {
    const user = await member("imran@example.com");
    const { inbox, answer } = await tryLogin(user);
    expect(answer).toMatchObject({ t: "ready" });

    await ban("imran@example.com");
    // Within the minute the last answer is still trusted.
    inbox.sendJson(keysFrame());
    expect(await inbox.nextJson()).toMatchObject({ t: "saved" });

    const real = Date.now();
    vi.spyOn(Date, "now").mockReturnValue(real + MEMBER_CHECK_MS + 1000);
    inbox.sendJson(keysFrame());
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "banned" });
    expect((await inbox.closed).code).toBe(CLOSE_BANNED);
  });

  it("answers a banned member's file requests with 403", async () => {
    const user = await member("meera@example.com");
    const { inbox, answer } = await tryLogin(user);
    const headers = { Authorization: `Bearer ${user.id}.${answer.files as string}`, "CF-Connecting-IP": freshIp() };
    const ok = await SELF.fetch("https://relay.test/v1/files", { method: "POST", headers, body: randomBytes(50) });
    expect(ok.status).toBe(201);
    const { key } = (await ok.json()) as { key: string };

    await ban("meera@example.com");
    const upload = await SELF.fetch("https://relay.test/v1/files", { method: "POST", headers, body: randomBytes(50) });
    expect(upload.status).toBe(403);
    expect(await upload.json()).toEqual({ error: "banned" });
    const download = await SELF.fetch(`https://relay.test/v1/files/${key}`, { headers });
    expect(download.status).toBe(403);
    // And the open connection is closed with it.
    expect((await inbox.closed).code).toBe(CLOSE_BANNED);
  });

  it("records the phone a member uses, and notices another one", async () => {
    const user = await member("asha@example.com");
    const { inbox } = await tryLogin(user);
    const first = "11".repeat(32);
    const second = "22".repeat(32);

    inbox.sendJson({ t: "device", id: requestId(), hash: first, maker: "Google", model: "Pixel 8", os: "16" });
    expect(await inbox.nextJson()).toMatchObject({ t: "saved" });
    expect(await row("asha@example.com")).toMatchObject({ device_hash: first, device_model: "Google Pixel 8", device_changed_at: null });

    // The same phone again changes nothing.
    inbox.sendJson({ t: "device", id: requestId(), hash: first, maker: "Google", model: "Pixel 8", os: "16" });
    await inbox.nextJson();
    expect(await row("asha@example.com")).toMatchObject({ device_changed_at: null });

    inbox.sendJson({ t: "device", id: requestId(), hash: second, maker: "Samsung", model: "SM-A546", os: "15" });
    await inbox.nextJson();
    const changed = await row("asha@example.com");
    expect(changed).toMatchObject({ device_hash: second, device_model: "Samsung SM-A546" });
    expect(changed?.device_changed_at).toBeGreaterThan(0);
    expect(await env.DB.prepare("SELECT COUNT(*) AS n FROM devices WHERE maun_id = ?").bind(user.id).first("n")).toBe(2);

    inbox.sendJson({ t: "device", id: requestId(), hash: "nope", maker: "x", model: "y", os: "z" });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });
    await hangUp(inbox);
  });
});

describe("the login page", () => {
  it("hands the app the token Cloudflare Access let through", async () => {
    const jwt = await token("asha@example.com");
    const response = await SELF.fetch("https://relay.test/v1/access/login", { headers: { "Cf-Access-Jwt-Assertion": jwt } });
    expect(response.status).toBe(200);
    expect(response.headers.get("Cache-Control")).toBe("no-store");
    expect(await response.text()).toContain(`data-maun-access="${jwt}"`);
  });

  it("says so when Access is not in front of it, and reflects nothing odd", async () => {
    const none = await SELF.fetch("https://relay.test/v1/access/login");
    expect(none.status).toBe(503);
    const odd = await SELF.fetch("https://relay.test/v1/access/login", {
      headers: { "Cf-Access-Jwt-Assertion": 'a.b."><script>alert(1)</script>' },
    });
    expect(odd.status).toBe(503);
    expect(await odd.text()).not.toContain("<script>");
  });
});
