import { SELF, env } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deleteOldFiles } from "../src/index";
import { AVATAR_PREFIX, FILE_TTL_MS, MAX_AVATAR_BYTES } from "../src/protocol";
import { connect, freshIp, hangUp, loginFrame, randomBytes, registered, type User } from "./helpers";

afterEach(() => vi.restoreAllMocks());

// The tests of one file share a bucket.
beforeEach(async () => {
  const names = (await env.FILES.list()).objects.map((object) => object.key);
  if (names.length > 0) await env.FILES.delete(names);
});

/** Logs in and returns the token for the file and picture routes. */
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

function put(headers: Record<string, string>, body: Uint8Array): Promise<Response> {
  return SELF.fetch("https://relay.test/v1/avatars", { method: "PUT", headers, body });
}

function get(headers: Record<string, string>, id: string): Promise<Response> {
  return SELF.fetch(`https://relay.test/v1/avatars/${id}`, { headers });
}

function remove(headers: Record<string, string>): Promise<Response> {
  return SELF.fetch("https://relay.test/v1/avatars", { method: "DELETE", headers });
}

/** Stores a picture and returns its version. */
async function stored(user: User, token: string, body: Uint8Array): Promise<string> {
  const response = await put(auth(user, token), body);
  expect(response.status).toBe(201);
  const answer = (await response.json()) as { ver: string; size: number };
  expect(answer.ver).toMatch(/^[0-9a-f]{16}$/);
  expect(answer.size).toBe(body.length);
  return answer.ver;
}

describe("profile pictures", () => {
  it("keeps a picture and hands it to another account with its version", async () => {
    const alice = await registered();
    const bob = await registered();
    const body = randomBytes(4096);
    const ver = await stored(alice, await filesToken(alice), body);

    const response = await get(auth(bob, await filesToken(bob)), alice.id);
    expect(response.status).toBe(200);
    expect(response.headers.get("X-Maun-Avatar-Version")).toBe(ver);
    expect(response.headers.get("Content-Type")).toBe("application/octet-stream");
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(body);
  });

  it("replaces a picture in place, with a new version each time", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const first = await stored(alice, token, randomBytes(1000));
    const second = await stored(alice, token, randomBytes(2000));
    expect(second).not.toBe(first);

    // One object per account, whatever number of pictures it has had.
    const keys = (await env.FILES.list()).objects.map((object) => object.key);
    expect(keys).toEqual([`${AVATAR_PREFIX}${alice.id}`]);

    const response = await get(auth(alice, token), alice.id);
    expect(response.status).toBe(200);
    expect(response.headers.get("X-Maun-Avatar-Version")).toBe(second);
    expect(new Uint8Array(await response.arrayBuffer()).length).toBe(2000);
  });

  it("says so plainly when there is no picture", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const response = await get(auth(alice, token), alice.id);
    expect(response.status).toBe(404);
    expect(await response.json()).toEqual({ error: "no_avatar" });
  });

  it("removes a picture, and does not mind doing it twice", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    await stored(alice, token, randomBytes(500));

    expect((await remove(auth(alice, token))).status).toBe(204);
    expect((await get(auth(alice, token), alice.id)).status).toBe(404);
    expect((await remove(auth(alice, token))).status).toBe(204);
  });

  it("refuses anyone without a current token", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    await stored(alice, token, randomBytes(500));

    const nobody = { "CF-Connecting-IP": freshIp() };
    expect((await get(nobody, alice.id)).status).toBe(401);
    expect((await put(nobody, randomBytes(10))).status).toBe(401);
    expect((await remove(nobody)).status).toBe(401);

    // Logging in again replaces the token; the old one stops working.
    const newer = await filesToken(alice);
    expect((await get(auth(alice, token), alice.id)).status).toBe(401);
    expect((await get(auth(alice, newer), alice.id)).status).toBe(200);
  });

  it("refuses a picture over the limit before reading it", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const response = await put(auth(alice, token), new Uint8Array(MAX_AVATAR_BYTES + 1));
    expect(response.status).toBe(413);
    expect(await response.json()).toEqual({ error: "too_large", max: MAX_AVATAR_BYTES });
    expect((await env.FILES.list()).objects.length).toBe(0);
  });

  it("refuses an upload that does not say how long it is", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const response = await SELF.fetch("https://relay.test/v1/avatars", {
      method: "PUT",
      headers: auth(alice, token),
    });
    expect(response.status).toBe(411);
  });

  it("does not hand out a picture for an address that is not a mailbox", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    expect((await get(auth(alice, token), "not-an-id")).status).toBe(404);
  });

  it("does not count a picture against the day's files", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    // Every one of these would be a file over the daily count if it counted.
    for (let i = 0; i < 3; i++) await stored(alice, token, randomBytes(300));
    expect((await get(auth(alice, token), alice.id)).status).toBe(200);
  });

  it("survives the hourly sweep of day-old files", async () => {
    const alice = await registered();
    const token = await filesToken(alice);
    const ver = await stored(alice, token, randomBytes(500));
    const real = Date.now();

    const later = real + FILE_TTL_MS * 3;
    expect(await deleteOldFiles(env, later)).toBe(0);
    vi.spyOn(Date, "now").mockReturnValue(later);
    const response = await get(auth(alice, token), alice.id);
    expect(response.status).toBe(200);
    expect(response.headers.get("X-Maun-Avatar-Version")).toBe(ver);
  });
});
