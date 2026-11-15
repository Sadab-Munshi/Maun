import { env, evictDurableObject, runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { DELIVERY_WINDOW, MAX_BODY_BYTES, QUEUE_CAP, QUEUE_CAP_PER_SENDER, QUEUE_TTL_MS, toBase64 } from "../src/protocol";
import {
  connect,
  expectRefused,
  hangUp,
  login,
  makeUser,
  randomBytes,
  register,
  registered,
  sendMessage,
  type Inbox,
  type User,
} from "./helpers";

function mailbox(id: string) {
  return env.MAILBOX.get(env.MAILBOX.idFromName(id));
}

/** What the mailbox is holding for its owner, read straight from its storage. */
async function queued(id: string): Promise<{ seq: number; sender: string }[]> {
  return runInDurableObject(mailbox(id), (_instance, state) =>
    state.storage.sql
      .exec("SELECT seq, sender FROM queue ORDER BY seq")
      .toArray()
      .map((row) => ({ seq: row.seq as number, sender: row.sender as string })),
  );
}

async function tables(id: string): Promise<string[]> {
  return runInDurableObject(mailbox(id), (_instance, state) =>
    state.storage.sql
      .exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE '\\_cf%' ESCAPE '\\'")
      .toArray()
      .map((row) => row.name as string),
  );
}

async function online(user: User): Promise<Inbox> {
  return login(user.identity, user.device);
}

function ack(inbox: Inbox, seq: unknown): void {
  inbox.sendJson({ t: "ack", seq });
}

/** Waits until an ack sent on `inbox` has been handled: the echo is answered after it. */
async function settle(inbox: Inbox): Promise<void> {
  inbox.sendJson({ t: "echo", id: "settle", body: "" });
  expect(await inbox.nextJson()).toMatchObject({ t: "echo", id: "settle" });
}

describe("store and forward", () => {
  it("keeps a message for someone who is offline, and deletes it once they acknowledge it", async () => {
    const alice = await registered();
    const bob = await registered();
    const body = randomBytes(300);

    const a = await online(alice);
    expect(await sendMessage(a, bob.id, body, 3)).toEqual({ t: "stored", id: expect.any(String) });
    expect(await queued(bob.id)).toHaveLength(1);
    expect(await queued(alice.id)).toHaveLength(0);

    const before = Date.now();
    const b = await online(bob);
    const delivered = await b.nextJson();
    expect(delivered).toMatchObject({ t: "msg", from: alice.id, kind: 3, body: toBase64(body) });
    expect(delivered.at).toBeLessThanOrEqual(before);
    // Delivered is not the same as deleted: the phone has not confirmed it yet.
    expect(await queued(bob.id)).toHaveLength(1);

    ack(b, delivered.seq);
    await settle(b);
    expect(await queued(bob.id)).toEqual([]);
    await hangUp(a);
    await hangUp(b);
  });

  it("passes a message straight on when the recipient is connected", async () => {
    const alice = await registered();
    const bob = await registered();
    const a = await online(alice);
    const b = await online(bob);

    const body = randomBytes(64);
    expect(await sendMessage(a, bob.id, body)).toMatchObject({ t: "stored" });
    const delivered = await b.nextJson();
    expect(delivered).toMatchObject({ t: "msg", from: alice.id, kind: 2, body: toBase64(body) });

    // And the other way, on the same two connections.
    expect(await sendMessage(b, alice.id, randomBytes(64))).toMatchObject({ t: "stored" });
    expect(await a.nextJson()).toMatchObject({ t: "msg", from: bob.id });
    await hangUp(a);
    await hangUp(b);
  });

  it("delivers again after a connection that never acknowledged", async () => {
    const alice = await registered();
    const bob = await registered();
    const a = await online(alice);
    const first = randomBytes(40);
    const second = randomBytes(40);
    await sendMessage(a, bob.id, first);
    await sendMessage(a, bob.id, second);

    // Bob's phone receives both, confirms only the first, then dies.
    let b = await online(bob);
    const one = await b.nextJson();
    const two = await b.nextJson();
    expect([one.body, two.body]).toEqual([toBase64(first), toBase64(second)]);
    ack(b, one.seq);
    await settle(b);
    await hangUp(b);

    b = await online(bob);
    const again = await b.nextJson();
    expect(again).toMatchObject({ t: "msg", seq: two.seq, body: toBase64(second) });
    ack(b, again.seq);
    await settle(b);
    expect(await queued(bob.id)).toEqual([]);

    // Nothing is left to deliver on the next connection.
    await hangUp(b);
    b = await online(bob);
    await settle(b);
    await hangUp(a);
    await hangUp(b);
  });

  it("delivers a backlog in order, a window at a time", async () => {
    const alice = await registered();
    const bob = await registered();
    const total = DELIVERY_WINDOW + 10;
    for (let i = 0; i < total; i++) {
      expect(await mailbox(bob.id).store(alice.id, 2, new Uint8Array([i]), "00".repeat(32))).toBe("stored");
    }

    const b = await online(bob);
    const seen: number[] = [];
    let lastSeq = 0;
    for (let i = 0; i < DELIVERY_WINDOW; i++) {
      const frame = await b.nextJson();
      expect(frame.seq as number).toBeGreaterThan(lastSeq);
      lastSeq = frame.seq as number;
      seen.push(lastSeq);
    }
    // The window is full: nothing more arrives until something is acknowledged.
    await settle(b);

    for (const seq of seen) ack(b, seq);
    const bodies: string[] = [];
    for (let i = 0; i < 10; i++) {
      const frame = await b.nextJson();
      expect(frame.seq as number).toBeGreaterThan(lastSeq);
      lastSeq = frame.seq as number;
      bodies.push(frame.body as string);
      ack(b, lastSeq);
    }
    expect(bodies).toEqual(Array.from({ length: 10 }, (_, i) => toBase64(new Uint8Array([DELIVERY_WINDOW + i]))));
    await settle(b);
    expect(await queued(bob.id)).toEqual([]);
    await hangUp(b);
  });

  it("never reuses a queue position, even after the queue has emptied", async () => {
    const alice = await registered();
    const bob = await registered();
    const a = await online(alice);
    const b = await online(bob);

    await sendMessage(a, bob.id, randomBytes(8));
    const first = await b.nextJson();
    ack(b, first.seq);
    await settle(b);
    expect(await queued(bob.id)).toEqual([]);

    // The mailbox forgets everything it held in memory.
    await evictDurableObject(mailbox(bob.id));

    await sendMessage(a, bob.id, randomBytes(8));
    const second = await b.nextJson();
    expect(second.seq as number).toBeGreaterThan(first.seq as number);

    // A late, repeated ack for the old position must not delete the new message.
    ack(b, first.seq);
    await settle(b);
    expect(await queued(bob.id)).toHaveLength(1);
    await hangUp(a);
    await hangUp(b);
  });

  it("ignores an acknowledgement for something this connection was never sent", async () => {
    const alice = await registered();
    const bob = await registered();
    await mailbox(bob.id).store(alice.id, 2, randomBytes(8), "00".repeat(32));
    const b = await online(bob);
    const delivered = await b.nextJson();

    ack(b, (delivered.seq as number) + 1000);
    ack(b, Number.MAX_SAFE_INTEGER);
    await settle(b);
    expect(await queued(bob.id)).toHaveLength(1);

    for (const bad of [0, -1, 1.5, "1", null]) {
      ack(b, bad);
      expect(await b.nextJson()).toEqual({ t: "error", code: "bad_frame" });
    }
    expect(await queued(bob.id)).toHaveLength(1);
    await hangUp(b);
  });

  it("names the real sender, whatever the frame claims", async () => {
    const alice = await registered();
    const bob = await registered();
    const mallory = await registered();
    const m = await online(mallory);
    m.sendJson({ t: "send", id: "x", to: bob.id, from: alice.id, kind: 2, body: toBase64(randomBytes(8)) });
    expect(await m.nextJson()).toEqual({ t: "stored", id: "x" });
    expect(await queued(bob.id)).toMatchObject([{ sender: mallory.id }]);
    await hangUp(m);
  });
});

describe("refusals", () => {
  it("does not store anything for an address nobody registered", async () => {
    const alice = await registered();
    const nobody = await makeUser();
    const a = await online(alice);
    expect(await sendMessage(a, nobody.id, randomBytes(8))).toMatchObject({ t: "failed", code: "no_account" });
    expect(await tables(nobody.id)).toEqual([]);
    await hangUp(a);
  });

  it("refuses a message to oneself", async () => {
    const alice = await registered();
    const a = await online(alice);
    expect(await sendMessage(a, alice.id, randomBytes(8))).toMatchObject({ t: "failed", code: "self" });
    await hangUp(a);
  });

  it("refuses to send before logging in", async () => {
    const alice = await registered();
    const bob = await registered();
    const { inbox } = await connect(alice.id);
    inbox.sendJson({ t: "send", id: "x", to: bob.id, kind: 2, body: toBase64(randomBytes(8)) });
    await expectRefused(inbox, "not_authenticated");
    expect(await queued(bob.id)).toEqual([]);
  });

  it("rejects malformed messages and keeps the connection", async () => {
    const alice = await registered();
    const bob = await registered();
    const a = await online(alice);
    const good = { t: "send", id: "x", to: bob.id, kind: 2, body: toBase64(randomBytes(8)) };
    const bad = [
      { ...good, kind: 1 },
      { ...good, kind: "2" },
      { ...good, to: "not-a-mailbox" },
      { ...good, to: bob.id.toUpperCase() },
      { ...good, id: "" },
      { ...good, id: "has spaces" },
      { ...good, id: 7 },
      { ...good, body: "" },
      { ...good, body: "****" },
      { ...good, body: toBase64(randomBytes(MAX_BODY_BYTES + 1)) },
    ];
    for (const frame of bad) {
      a.sendJson(frame);
      expect(await a.nextJson(), JSON.stringify(frame).slice(0, 80)).toEqual({ t: "error", code: "bad_frame" });
    }
    expect(await queued(bob.id)).toEqual([]);

    // The largest allowed message still fits in a frame.
    expect(await sendMessage(a, bob.id, randomBytes(MAX_BODY_BYTES))).toMatchObject({ t: "stored" });
    await hangUp(a);
  });

  it("stops one sender from filling a mailbox", async () => {
    const bob = await registered();
    const alice = await makeUser();
    const carol = await makeUser();
    const box = mailbox(bob.id);
    const tiny = new Uint8Array([1]);
    for (let i = 0; i < QUEUE_CAP_PER_SENDER; i++) expect(await box.store(alice.id, 2, tiny, "00".repeat(32))).toBe("stored");
    expect(await box.store(alice.id, 2, tiny, "00".repeat(32))).toBe("mailbox_full");
    // Someone else can still get through.
    expect(await box.store(carol.id, 2, tiny, "00".repeat(32))).toBe("stored");

    // The limit survives the mailbox being evicted, and eases as messages are collected.
    await evictDurableObject(box);
    expect(await box.store(alice.id, 2, tiny, "00".repeat(32))).toBe("mailbox_full");
    const b = await online(bob);
    const first = await b.nextJson();
    expect(first.from).toBe(alice.id);
    ack(b, first.seq);
    // More of the backlog follows; wait for the echo that comes after the ack.
    b.sendJson({ t: "echo", id: "after-ack", body: "" });
    while ((await b.nextJson()).t !== "echo");
    expect(await box.store(alice.id, 2, tiny, "00".repeat(32))).toBe("stored");
    await hangUp(b);
  });

  // A thousand stores, one after another: slow on a busy CI machine.
  it("caps the whole mailbox", { timeout: 60_000 }, async () => {
    const bob = await registered();
    const box = mailbox(bob.id);
    const tiny = new Uint8Array([1]);
    const senders = await Promise.all(Array.from({ length: Math.ceil(QUEUE_CAP / 250) }, () => makeUser()));
    let stored = 0;
    for (const sender of senders) {
      for (let i = 0; i < 250 && stored < QUEUE_CAP; i++) {
        expect(await box.store(sender.id, 2, tiny, "00".repeat(32))).toBe("stored");
        stored += 1;
      }
    }
    const late = await makeUser();
    expect(await box.store(late.id, 2, tiny, "00".repeat(32))).toBe("mailbox_full");
    expect(await queued(bob.id)).toHaveLength(QUEUE_CAP);
  });

  it("slows down an account that sends too fast", async () => {
    const alice = await registered();
    const bob = await registered();
    const a = await online(alice);
    const outcomes = new Set<string>();
    // The limit is 120 a minute, counted in whole minutes. Twice that, and a
    // little more, exceeds it even when the test straddles a minute boundary.
    for (let i = 0; i < 250 && !outcomes.has("rate_limited"); i++) {
      const answer = await sendMessage(a, bob.id, new Uint8Array([1]));
      outcomes.add(answer.t === "stored" ? "stored" : (answer.code as string));
    }
    expect([...outcomes].sort()).toEqual(["rate_limited", "stored"]);
    // The connection is still usable.
    a.sendJson({ t: "echo", id: "e", body: "" });
    expect(await a.nextJson()).toMatchObject({ t: "echo", id: "e" });
    await hangUp(a);
  });
});

describe("expiry", () => {
  it("deletes messages nobody collected within the time limit", async () => {
    const alice = await registered();
    const bob = await registered();
    const box = mailbox(bob.id);
    await box.store(alice.id, 2, new Uint8Array([1]), "00".repeat(32));
    await box.store(alice.id, 2, new Uint8Array([2]), "00".repeat(32));
    const [old, recent] = await queued(bob.id);

    // Make the first message look a little over the limit old.
    await runInDurableObject(box, (_instance, state) => {
      state.storage.sql.exec("UPDATE queue SET at = ? WHERE seq = ?", Date.now() - QUEUE_TTL_MS - 1000, old!.seq);
    });
    expect(await runDurableObjectAlarm(box)).toBe(true);
    expect(await queued(bob.id)).toEqual([recent]);

    // The clean-up is scheduled again for the message that is left.
    const next = await runInDurableObject(box, (_instance, state) => state.storage.getAlarm());
    expect(next).toBeGreaterThan(Date.now() + QUEUE_TTL_MS - 60_000);

    // The per-sender count was corrected too.
    expect(await box.store(alice.id, 2, new Uint8Array([3]), "00".repeat(32))).toBe("stored");
    expect(await queued(bob.id)).toHaveLength(2);
  });

  it("schedules no clean-up for an empty mailbox", async () => {
    const bob = await registered();
    const box = mailbox(bob.id);
    expect(await runInDurableObject(box, (_instance, state) => state.storage.getAlarm())).toBeNull();
  });
});
