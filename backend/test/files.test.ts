import { SELF, env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deleteOldFiles } from "../src/index";
import { FILE_DAILY_COUNT, FILE_TTL_MS, MAX_FILE_BYTES } from "../src/protocol";
import { connect, freshIp, hangUp, loginFrame, randomBytes, registered, type User } from "./helpers";

afterEach(() => vi.restoreAllMocks());

// The tests of one file share a bucket.
beforeEach(async () => {
  const names = (await env.FILES.list()).objects.map((object) => object.key);
  if (names.length > 0) await env.FILES.delete(names);
});

/** Logs in and returns the token for the file routes. */
async function filesToken(user: User): Promise<string> {
  const { inbox, nonce } = await connect(user.id);
  inbox.sendJson(await loginFrame(user.identity, user.device, nonce));
  const ready = await inbox.nextJson();
  expect(ready.t).toBe("ready");
  await hangUp(inbox);
  return ready.files as string;
}

function auth(user: User, token: string): Record<string, string> {
  return { Authorization: `Bearer ${user.id}.${token}`, "CF-Connecting-IP": freshIp() };
}

function post(headers: Record<string, string>, body: Uint8Array): Promise<Response> {
  return SELF.fetch("https://relay.test/v1/files", { method: "POST", headers, body });
}

function get(headers: Record<string, string>, key: string): Promise<Response> {
  return SELF.fetch(`https://relay.test/v1/files/${key}`, { headers });
}

async function uploaded(user: User, token: string, body: Uint8Array): Promise<string> {
  const response = await post(auth(user, token), body);
  expect(response.status).toBe(201);
  const answer = (await response.json()) as { key: string; size: number };
  expect(answer.size).toBe(body.length);
  expect(answer.key).toMatch(/^[0-9a-f]{64}$/);
  return answer.key;
}

describe("files", () => {
  it("stores a file and hands it to another account", async () => {
    const alice = await registered();
    const bob = await registered();
    // Bigger than one chunk of the app's encryption.
    const body = new Uint8Array(70_000);
    body.set(randomBytes(60_000));
    body.set(randomBytes(10_000), 60_000);
    const key = await uploaded(alice, await filesToken(alice), body);

    const response = await get(auth(bob, await filesToken(bob)), key);
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Length")).toBe(String(body.length));
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(body);
  });

  it("gives every upload a name of its own", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const first = await uploaded(alice, token, randomBytes(100));
    const second = await uploaded(alice, token, randomBytes(100));
    expect(second).not.toBe(first);
  });

  it("refuses anyone without a current token", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const key = await uploaded(alice, token, randomBytes(100));

    const nobody = { "CF-Connecting-IP": freshIp() };
    expect((await post(nobody, randomBytes(10))).status).toBe(401);
    expect((await get(nobody, key)).status).toBe(401);

    const wrong = { Authorization: `Bearer ${alice.id}.${"A".repeat(43)}`, "CF-Connecting-IP": freshIp() };
    expect((await post(wrong, randomBytes(10))).status).toBe(401);
    expect((await get(wrong, key)).status).toBe(401);

    // An address nobody registered has no token at all.
    const stranger = { Authorization: `Bearer ${"ab".repeat(32)}.${token}`, "CF-Connecting-IP": freshIp() };
    expect((await get(stranger, key)).status).toBe(401);

    // Logging in again replaces the token.
    const newer = await filesToken(alice);
    expect(newer).not.toBe(token);
    expect((await get(auth(alice, token), key)).status).toBe(401);
    expect((await get(auth(alice, newer), key)).status).toBe(200);
  });

  it("refuses a file over the limit before reading it", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    // 26 MB, as the plan asks. Only the announced length is sent in full.
    const response = await post(auth(alice, token), new Uint8Array(26_000_000));
    expect(response.status).toBe(413);
    expect(await response.json()).toEqual({ error: "too_large", max: MAX_FILE_BYTES });
    expect((await env.FILES.list()).objects.length).toBe(0);
  });

  it("takes a file of exactly the limit", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const key = await uploaded(alice, token, new Uint8Array(MAX_FILE_BYTES));
    expect((await env.FILES.head(key))?.size).toBe(MAX_FILE_BYTES);
  });

  it("refuses an upload that does not say how long it is", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const response = await SELF.fetch("https://relay.test/v1/files", { method: "POST", headers: auth(alice, token) });
    expect(response.status).toBe(411);
  });

  it("stops handing out a file after a day, and deletes it", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const key = await uploaded(alice, token, randomBytes(100));
    const real = Date.now();

    vi.spyOn(Date, "now").mockReturnValue(real + FILE_TTL_MS - 60_000);
    expect((await get(auth(alice, token), key)).status).toBe(200);

    vi.spyOn(Date, "now").mockReturnValue(real + FILE_TTL_MS + 60_000);
    const late = await get(auth(alice, token), key);
    expect(late.status).toBe(404);
    expect(await late.json()).toEqual({ error: "gone" });
    expect(await env.FILES.head(key)).toBeNull();
  });

  it("sweeps out day-old files every hour and leaves newer ones", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const old = await uploaded(alice, token, randomBytes(100));
    const real = Date.now();

    expect(await deleteOldFiles(env, real + FILE_TTL_MS - 60_000)).toBe(0);
    expect(await env.FILES.head(old)).not.toBeNull();

    expect(await deleteOldFiles(env, real + FILE_TTL_MS + 60_000)).toBe(1);
    expect(await env.FILES.head(old)).toBeNull();
    expect(await deleteOldFiles(env, real + FILE_TTL_MS + 60_000)).toBe(0);
  });

  it("allows only so many uploads a day", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    // Count them in the mailbox instead of sending two hundred files.
    const stub = env.MAILBOX.get(env.MAILBOX.idFromName(alice.id));
    for (let i = 0; i < FILE_DAILY_COUNT; i++) expect(await stub.fileAccess(alice.id, token, 10)).toBe("ok");
    const response = await post(auth(alice, token), randomBytes(10));
    expect(response.status).toBe(429);
    expect(await response.json()).toEqual({ error: "quota" });
    // Downloads are not counted.
    expect(await stub.fileAccess(alice.id, token, 0)).toBe("ok");
  });

  it("answers 404 for a name that is not a file's", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    expect((await get(auth(alice, token), "ff".repeat(32))).status).toBe(404);
    expect((await get(auth(alice, token), "not-a-key")).status).toBe(404);
  });
});

describe("an open relay", () => {
  it("says so, and has no login page", async () => {
    const health = await SELF.fetch("https://relay.test/health");
    expect(await health.json()).toMatchObject({ ok: true, access: false, files: true });
    const start = await SELF.fetch("https://relay.test/v1/auth/start", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: "someone@example.com" }),
    });
    expect(start.status).toBe(404);
    // The admin page explains how to switch member-only mode on, and offers nothing else.
    const page = await SELF.fetch("https://relay.test/admin");
    expect(page.status).toBe(200);
    const html = await page.text();
    expect(html).toContain("ADMIN_EMAIL");
    expect(html).not.toContain("<script");
    expect((await SELF.fetch("https://relay.test/v1/admin/members")).status).toBe(404);
    const alice = await registered();
    // There are no usernames to look up.
    const { inbox, nonce } = await connect(alice.id);
    inbox.sendJson(await loginFrame(alice.identity, alice.device, nonce));
    expect(await inbox.nextJson()).toMatchObject({ t: "ready", email: null, name: null });
    inbox.sendJson({ t: "find", id: "q1", name: "someone" });
    expect(await inbox.nextJson()).toEqual({ t: "failed", id: "q1", code: "no_such_user" });
    await hangUp(inbox);
  });
});
