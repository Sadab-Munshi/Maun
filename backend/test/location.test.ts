import { SELF, env, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import {
  MAX_SHARE_POINT_BYTES,
  SHARE_KEEP_MS,
  SHARE_MAX_MINUTES,
  SHARE_MINUTES,
  SHARE_MIN_INTERVAL_MS,
} from "../src/protocol";
import { connect, freshIp, hangUp, loginFrame, randomBytes, registered, type User } from "./helpers";

// A share is kept in the mailbox of the phone that shares it, so these tests
// look at it from both sides: as the phone that shares, over the routes, and
// as the person watching, who is the only other account that may read it.

/** Logs in and returns the token for the HTTP routes. */
async function fileToken(user: User): Promise<string> {
  const { inbox, nonce } = await connect(user.id);
  inbox.sendJson(await loginFrame(user.identity, user.device, nonce));
  const ready = await inbox.nextJson();
  expect(ready.t).toBe("ready");
  await hangUp(inbox);
  return ready.files as string;
}

type Signed = { user: User; token: string };

async function signedIn(): Promise<Signed> {
  const user = await registered();
  return { user, token: await fileToken(user) };
}

function auth(who: Signed): Record<string, string> {
  return { Authorization: `Bearer ${who.user.id}.${who.token}`, "CF-Connecting-IP": freshIp() };
}

function start(who: Signed, to: string, minutes: number): Promise<Response> {
  return SELF.fetch("https://relay.test/v1/location", {
    method: "POST",
    headers: auth(who),
    body: JSON.stringify({ to, minutes }),
  });
}

function putPoint(who: Signed, share: string, point: Uint8Array): Promise<Response> {
  return SELF.fetch(`https://relay.test/v1/location/${share}`, { method: "POST", headers: auth(who), body: point });
}

function stopShare(who: Signed, share: string): Promise<Response> {
  return SELF.fetch(`https://relay.test/v1/location/${share}`, { method: "DELETE", headers: auth(who) });
}

function peek(who: Signed, sharer: string, share: string): Promise<Response> {
  return SELF.fetch(`https://relay.test/v1/location/${sharer}/${share}`, { headers: auth(who) });
}

type Live = { state: "live"; until: number; point: string | null; at: number | null };
type Answer = Live | { state: "ended"; until: number } | { state: "gone" };

/** Starts a share and returns its ID and the minute it runs out at. */
async function shared(who: Signed, viewer: string, minutes = 15): Promise<{ share: string; until: number }> {
  const response = await start(who, viewer, minutes);
  expect(response.status).toBe(201);
  return (await response.json()) as { share: string; until: number };
}

function mailbox(id: string) {
  return env.MAILBOX.get(env.MAILBOX.idFromName(id));
}

/** Runs [sql] against the mailbox as though it were the Durable Object. */
async function edit(id: string, sql: string, ...params: unknown[]): Promise<void> {
  await runInDurableObject(mailbox(id), async (_instance, state) => {
    state.storage.sql.exec(sql, ...params);
  });
}

function read<T>(id: string, sql: string, ...params: unknown[]): Promise<T[]> {
  return runInDurableObject(mailbox(id), async (_instance, state) => {
    return state.storage.sql.exec(sql, ...params).toArray() as T[];
  });
}

describe("live location", () => {
  it("hands one sealed point to the person the share was started for", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share, until } = await shared(alice, bob.user.id);

    expect(share).toMatch(/^[0-9a-f]{32}$/);
    // Fifteen minutes, counted by the relay rather than by either phone.
    expect(until - Date.now()).toBeGreaterThan(14 * 60_000);
    expect(until - Date.now()).toBeLessThanOrEqual(15 * 60_000);

    // Nothing has been sent yet.
    const empty = (await (await peek(bob, alice.user.id, share)).json()) as Answer;
    expect(empty).toMatchObject({ state: "live", point: null, at: null });

    const sealed = randomBytes(120);
    const stored = await putPoint(alice, share, sealed);
    expect(stored.status).toBe(200);

    const live = (await (await peek(bob, alice.user.id, share)).json()) as Live;
    expect(live.state).toBe("live");
    expect(live.point).toBe(btoa(String.fromCharCode(...sealed)));
    expect(live.at).toBeGreaterThan(0);
    expect(live.until).toBe(until);
  });

  it("tells anyone else only that there is no such share", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const carol = await signedIn();
    const { share } = await shared(alice, bob.user.id);
    await putPoint(alice, share, randomBytes(80));

    const answer = (await (await peek(carol, alice.user.id, share)).json()) as Answer;
    expect(answer.state).toBe("gone");
    // Not even whether it exists: a wrong ID and a stranger's look are alike.
    expect(await (await peek(carol, alice.user.id, "0".repeat(32))).json()).toEqual({ state: "gone" });
  });

  it("keeps the newest point and only the newest", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share } = await shared(alice, bob.user.id);

    const first = randomBytes(96);
    const second = randomBytes(96);
    expect((await putPoint(alice, share, first)).status).toBe(200);
    // A phone sends one every half minute; a test does not wait for that.
    await edit(alice.user.id, "UPDATE shares SET point_at = ? WHERE id = ?", Date.now() - SHARE_MIN_INTERVAL_MS, share);
    expect((await putPoint(alice, share, second)).status).toBe(200);

    const live = (await (await peek(bob, alice.user.id, share)).json()) as Live;
    expect(live.point).toBe(btoa(String.fromCharCode(...second)));

    const rows = await read<{ n: number }>(alice.user.id, "SELECT COUNT(*) AS n FROM shares");
    expect(rows[0]?.n).toBe(1);
  });

  it("drops a point that comes too soon after the last", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share } = await shared(alice, bob.user.id);

    const first = randomBytes(64);
    expect(await (await putPoint(alice, share, first)).json()).toEqual({ ok: true });

    const tooSoon = await putPoint(alice, share, randomBytes(64));
    expect(tooSoon.status).toBe(429);
    expect(await tooSoon.json()).toEqual({ error: "too_soon" });

    // What is stored is still the one that was allowed.
    const live = (await (await peek(bob, alice.user.id, share)).json()) as Live;
    expect(live.point).toBe(btoa(String.fromCharCode(...first)));
  });

  it("ends when the phone stops it, and the point goes with it", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share, until } = await shared(alice, bob.user.id);
    await putPoint(alice, share, randomBytes(90));

    expect((await stopShare(alice, share)).status).toBe(204);

    const answer = (await (await peek(bob, alice.user.id, share)).json()) as Answer;
    expect(answer).toEqual({ state: "ended", until });
    const rows = await read<{ point: unknown }>(alice.user.id, "SELECT point FROM shares");
    expect(rows[0]?.point).toBeNull();
    // The phone cannot hand over another point once it has stopped.
    expect((await putPoint(alice, share, randomBytes(80))).status).toBe(410);
  });

  it("ends by itself when its minutes are up", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share } = await shared(alice, bob.user.id, 60);
    await putPoint(alice, share, randomBytes(90));

    // The minute it was promised for has passed and the alarm has run.
    await edit(alice.user.id, "UPDATE shares SET expires = ? WHERE id = ?", Date.now() - 1000, share);
    await runInDurableObject(mailbox(alice.user.id), async (instance) => {
      await instance.alarm();
    });

    const answer = (await (await peek(bob, alice.user.id, share)).json()) as Answer;
    expect(answer.state).toBe("ended");
    const rows = await read<{ point: unknown; point_at: unknown }>(alice.user.id, "SELECT point, point_at FROM shares");
    expect(rows[0]?.point).toBeNull();
    expect(rows[0]?.point_at).toBeNull();
  });

  it("forgets a share a day after it ended", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share } = await shared(alice, bob.user.id);
    await edit(
      alice.user.id,
      "UPDATE shares SET expires = ? WHERE id = ?",
      Date.now() - SHARE_KEEP_MS - 60_000,
      share,
    );
    await runInDurableObject(mailbox(alice.user.id), async (instance) => {
      await instance.alarm();
    });

    const answer = (await (await peek(bob, alice.user.id, share)).json()) as Answer;
    expect(answer.state).toBe("gone");
    expect(await read(alice.user.id, "SELECT id FROM shares")).toHaveLength(0);
  });

  it("ends the share to the same person when a new one starts", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const first = await shared(alice, bob.user.id);
    const second = await shared(alice, bob.user.id);

    const ended = (await (await peek(bob, alice.user.id, first.share)).json()) as Answer;
    expect(ended.state).toBe("ended");
    const live = (await (await peek(bob, alice.user.id, second.share)).json()) as Answer;
    expect(live.state).toBe("live");
  });

  it("keeps the shares to two people apart", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const carol = await signedIn();
    const toBob = await shared(alice, bob.user.id);
    const toCarol = await shared(alice, carol.user.id);

    const forBob = randomBytes(70);
    const forCarol = randomBytes(70);
    await putPoint(alice, toBob.share, forBob);
    await edit(
      alice.user.id,
      "UPDATE shares SET point_at = ? WHERE id IN (?, ?)",
      Date.now() - SHARE_MIN_INTERVAL_MS,
      toBob.share,
      toCarol.share,
    );
    await putPoint(alice, toCarol.share, forCarol);

    expect(((await (await peek(bob, alice.user.id, toBob.share)).json()) as Live).point).toBe(
      btoa(String.fromCharCode(...forBob)),
    );
    expect(((await (await peek(carol, alice.user.id, toCarol.share)).json()) as Live).point).toBe(
      btoa(String.fromCharCode(...forCarol)),
    );
  });

  it("takes any length the app offers, up to a day, and clamps the rest", async () => {
    const alice = await signedIn();
    const bob = await signedIn();

    // Getting home at night is not over in a quarter of an hour.
    for (const minutes of SHARE_MINUTES) {
      const share = await shared(alice, bob.user.id, minutes);
      const length = share.until - Date.now();
      expect(length).toBeGreaterThan((minutes - 1) * 60_000);
      expect(length).toBeLessThanOrEqual(minutes * 60_000);
    }

    // A hand-written request for longer than a day is cut down to one.
    const day = await shared(alice, bob.user.id, 5_000);
    expect(day.until - Date.now()).toBeLessThanOrEqual(SHARE_MAX_MINUTES * 60_000);
    expect(day.until - Date.now()).toBeGreaterThan((SHARE_MAX_MINUTES - 1) * 60_000);

    const short = await shared(alice, bob.user.id, 1);
    expect(short.until - Date.now()).toBeGreaterThan(14 * 60_000);
  });

  it("refuses a share to oneself, or to an address that is not one", async () => {
    const alice = await signedIn();
    expect((await start(alice, alice.user.id, 15)).status).toBe(400);
    expect((await start(alice, "not-an-address", 15)).status).toBe(400);
    expect((await start(alice, "0".repeat(64), 15)).status).toBe(201);
  });

  it("needs a signed-in account, on every route", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share } = await shared(alice, bob.user.id);

    const bare = { "CF-Connecting-IP": freshIp() };
    const wrongToken = {
      Authorization: `Bearer ${alice.user.id}.${"A".repeat(43)}`,
      "CF-Connecting-IP": freshIp(),
    };
    expect(
      (
        await SELF.fetch("https://relay.test/v1/location", {
          method: "POST",
          headers: bare,
          body: JSON.stringify({ to: bob.user.id, minutes: 15 }),
        })
      ).status,
    ).toBe(401);
    expect((await SELF.fetch(`https://relay.test/v1/location/${share}`, { method: "POST", headers: bare, body: randomBytes(50) })).status).toBe(401);
    expect((await SELF.fetch(`https://relay.test/v1/location/${share}`, { method: "DELETE", headers: bare })).status).toBe(401);
    expect((await SELF.fetch(`https://relay.test/v1/location/${alice.user.id}/${share}`, { headers: bare })).status).toBe(401);
    expect(
      (
        await SELF.fetch(`https://relay.test/v1/location/${share}`, {
          method: "POST",
          headers: wrongToken,
          body: randomBytes(50),
        })
      ).status,
    ).toBe(401);
  });

  it("refuses a point that is bigger than a point", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share } = await shared(alice, bob.user.id);

    const tooBig = await putPoint(alice, share, randomBytes(MAX_SHARE_POINT_BYTES + 1));
    expect(tooBig.status).toBe(413);
    expect((await putPoint(alice, share, new Uint8Array(0))).status).toBe(411);
  });

  it("holds only so many shares, and says so", async () => {
    const alice = await signedIn();
    const viewers = [];
    for (let i = 0; i < 8; i += 1) viewers.push((await registered()).id);
    for (const viewer of viewers) expect((await start(alice, viewer, 15)).status).toBe(201);

    const ninth = await start(alice, (await registered()).id, 15);
    expect(ninth.status).toBe(429);
    expect(((await ninth.json()) as { error: string }).error).toBe("too_many");
  });

  it("a phone that has gone away takes its shares with it", async () => {
    const alice = await signedIn();
    const bob = await signedIn();
    const { share } = await shared(alice, bob.user.id);
    await putPoint(alice, share, randomBytes(60));

    await runInDurableObject(mailbox(alice.user.id), async (instance) => {
      // What the relay does when the owner sets Maun up again on another phone.
      await instance.retire();
    });

    const answer = (await (await peek(bob, alice.user.id, share)).json()) as Answer;
    expect(answer.state).toBe("gone");
    expect(await read(alice.user.id, "SELECT id FROM shares")).toHaveLength(0);
  });
});
