import { env, runInDurableObject } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  CONNECTED_GRACE_MS,
  WAKE_RETRY_WAITS_CALL_MS,
  WAKE_RETRY_WAITS_MS,
  PUSH_CALL_TTL_SECONDS,
  PUSH_CONTACT,
  PUSH_DAILY_CAP,
  PUSH_PAYLOAD,
  PUSH_PAYLOAD_CALL,
  PUSH_REPEAT_MS,
  PUSH_SETTLE_MS,
  PUSH_SETTLE_WAITS_MS,
  PUSH_TTL_SECONDS,
  toBase64,
} from "../src/protocol";
import {
  connect,
  expectRefused,
  fromBase64Url,
  hangUp,
  login,
  loginFrame,
  makePushKeys,
  randomBytes,
  registered,
  requestId,
  sendMessage,
  type Inbox,
  type PushKeys,
  type User,
} from "./helpers";

function mailbox(id: string) {
  return env.MAILBOX.get(env.MAILBOX.idFromName(id));
}

type Wake = { url: string; headers: Headers; body: Uint8Array };

/** Everything the relay sent to a push service, and what the push service answers. */
let wakes: Wake[] = [];
let pushServiceAnswer: () => Response | Promise<Response> = () => new Response(null, { status: 201 });

beforeEach(() => {
  wakes = [];
  pushServiceAnswer = () => new Response(null, { status: 201 });
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const request = new Request(input as RequestInfo, init);
    wakes.push({ url: request.url, headers: request.headers, body: new Uint8Array(await request.arrayBuffer()) });
    return pushServiceAnswer();
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** Waits for wake-ups in flight, then returns how many there have been. */
async function wakeCount(expected: number): Promise<number> {
  if (expected > wakes.length) {
    await vi.waitFor(() => expect(wakes.length).toBeGreaterThanOrEqual(expected), { timeout: 2000 });
  }
  // Give one that should not have been sent the chance to show up.
  await new Promise((resolve) => setTimeout(resolve, 30));
  return wakes.length;
}

let topicCounter = 0;

function pushUrl(): string {
  topicCounter += 1;
  return `https://push.example.org/up${topicCounter}?up=1`;
}

/** Leaves a wake-up address. Returns the relay's answer. */
async function putPush(inbox: Inbox, url: string, keys: { publicKey: Uint8Array; auth: Uint8Array }) {
  const id = requestId();
  inbox.sendJson({ t: "put_push", id, url, key: toBase64(keys.publicKey), auth: toBase64(keys.auth) });
  // Waiting messages may be delivered first.
  for (;;) {
    const answer = await inbox.nextJson();
    if (answer.t !== "msg") {
      expect(answer.id).toBe(id);
      return answer;
    }
  }
}

type Phone = User & { url: string; keys: PushKeys };

/** An account that has left a wake-up address and gone offline. */
async function withPush(): Promise<Phone> {
  const user = await registered();
  const keys = await makePushKeys();
  const url = pushUrl();
  const inbox = await login(user.identity, user.device);
  expect(await putPush(inbox, url, keys)).toMatchObject({ t: "saved" });
  await hangUp(inbox);
  return { ...user, url, keys };
}

async function online(user: User): Promise<Inbox> {
  return login(user.identity, user.device);
}

/** Logs in and returns the `ready` frame's view of the account. */
async function readyOf(user: User): Promise<Record<string, unknown>> {
  const { inbox, nonce } = await connect(user.id);
  inbox.sendJson(await loginFrame(user.identity, user.device, nonce));
  const ready = await inbox.nextJson();
  await hangUp(inbox);
  return ready;
}

async function pushRow(id: string): Promise<Record<string, unknown> | undefined> {
  return runInDurableObject(mailbox(id), (_instance, state) =>
    state.storage.sql.exec("SELECT url, last, pending, day, sent FROM push WHERE id = 1").toArray()[0],
  );
}

/** The appointment to look for a woken phone again, if there is one. */
async function retryRow(id: string): Promise<Record<string, unknown> | undefined> {
  return runInDurableObject(mailbox(id), (_instance, state) =>
    state.storage.sql.exec("SELECT at, count, seen, urgent FROM wake_retry WHERE id = 1").toArray()[0],
  );
}

/** Runs the mailbox's alarm, as Cloudflare would when its moment comes. */
async function runAlarm(id: string): Promise<void> {
  await runInDurableObject(mailbox(id), async (instance: { alarm(): Promise<void> }) => {
    await instance.alarm();
  });
}

async function editPush(id: string, sql: string, ...args: unknown[]): Promise<void> {
  await runInDurableObject(mailbox(id), (_instance, state) => {
    state.storage.sql.exec(sql, ...args);
  });
}

describe("leaving a wake-up address", () => {
  it("is reported at the next login, and can be withdrawn", async () => {
    const bob = await registered();
    expect(await readyOf(bob)).toMatchObject({ push: false });

    const inbox = await online(bob);
    expect(await putPush(inbox, pushUrl(), await makePushKeys())).toMatchObject({ t: "saved" });
    await hangUp(inbox);
    expect(await readyOf(bob)).toMatchObject({ push: true });

    const again = await online(bob);
    again.sendJson({ t: "drop_push", id: "d1" });
    expect(await again.nextJson()).toMatchObject({ t: "saved", id: "d1" });
    await hangUp(again);
    expect(await readyOf(bob)).toMatchObject({ push: false });
    expect(await pushRow(bob.id)).toBeUndefined();
  });

  it("refuses an address the relay will not call, with a reason, and keeps the connection", async () => {
    const bob = await registered();
    const keys = await makePushKeys();
    const inbox = await online(bob);
    for (const url of ["http://push.example.org/up1", "https://127.0.0.1/up", "https://localhost/up", "https://push.example.org:8443/up"]) {
      expect(await putPush(inbox, url, keys), url).toMatchObject({ t: "failed", code: "bad_push" });
    }
    // Keys of the wrong shape, or not on the curve.
    expect(await putPush(inbox, pushUrl(), { publicKey: randomBytes(65), auth: keys.auth })).toMatchObject({ code: "bad_push" });
    expect(await putPush(inbox, pushUrl(), { publicKey: keys.publicKey, auth: randomBytes(12) })).toMatchObject({ code: "bad_push" });
    inbox.sendJson({ t: "echo", id: "e", body: "" });
    expect(await inbox.nextJson()).toMatchObject({ t: "echo", id: "e" });
    await hangUp(inbox);
    expect(await pushRow(bob.id)).toBeUndefined();
  });

  it("cannot be done before logging in", async () => {
    const bob = await registered();
    const keys = await makePushKeys();
    const { inbox } = await connect(bob.id);
    inbox.sendJson({ t: "put_push", id: "p", url: pushUrl(), key: toBase64(keys.publicKey), auth: toBase64(keys.auth) });
    await expectRefused(inbox, "not_authenticated");
    expect(await pushRow(bob.id)).toBeUndefined();
  });
});

describe("waking a phone", () => {
  it("sends one encrypted, content-free wake-up when a message arrives for someone offline", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    const secret = randomBytes(40);
    expect(await sendMessage(a, bob.id, secret)).toMatchObject({ t: "stored" });

    expect(await wakeCount(1)).toBe(1);
    const wake = wakes[0]!;
    expect(wake.url).toBe(bob.url);
    expect(wake.headers.get("content-encoding")).toBe("aes128gcm");
    expect(wake.headers.get("ttl")).toBe(String(PUSH_TTL_SECONDS));
    expect(wake.headers.get("urgency")).toBe("high");
    // Bob's push app can read it, and all it says is "wake".
    expect(new TextDecoder().decode(await bob.keys.decrypt(wake.body))).toBe(PUSH_PAYLOAD);
    // Nothing about the sender or the message travels with it.
    const sent = wake.url + [...wake.headers].join() + new TextDecoder("latin1").decode(wake.body);
    expect(sent).not.toContain(alice.id);
    expect(sent).not.toContain(bob.id);
    expect(wake.body.length).toBeLessThan(150);
    await hangUp(a);
  });

  it("signs each wake-up with the key it told the phone about, and with nothing that names anyone", async () => {
    const alice = await registered();
    const bob = await withPush();
    const carol = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    await sendMessage(a, carol.id, randomBytes(8));
    expect(await wakeCount(2)).toBe(2);
    await hangUp(a);

    const forBob = wakes.find((w) => w.url === bob.url)!;
    const match = /^vapid t=([\w-]+)\.([\w-]+)\.([\w-]+), k=([\w-]+)$/.exec(forBob.headers.get("authorization") ?? "");
    expect(match).not.toBeNull();
    const [, header, claims, signature, key] = match!;

    // The key is the one Bob's phone is given at login, to pass to its push app.
    const ready = await readyOf(bob);
    expect(toBase64(fromBase64Url(key!))).toBe(ready.vapid);
    expect(fromBase64Url(key!).length).toBe(65);

    // The token is signed by that key (RFC 8292: ES256 over "header.claims").
    const publicKey = await crypto.subtle.importKey("raw", fromBase64Url(key!), { name: "ECDSA", namedCurve: "P-256" }, false, [
      "verify",
    ]);
    const signed = new TextEncoder().encode(`${header}.${claims}`);
    expect(
      await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, fromBase64Url(signature!), signed),
    ).toBe(true);
    expect(JSON.parse(new TextDecoder().decode(fromBase64Url(header!)))).toEqual({ typ: "JWT", alg: "ES256" });

    // It is addressed to the push service, good for less than a day, and says how to reach the relay's operator.
    const said = JSON.parse(new TextDecoder().decode(fromBase64Url(claims!))) as Record<string, unknown>;
    expect(Object.keys(said).sort()).toEqual(["aud", "exp", "sub"]);
    expect(said.aud).toBe("https://push.example.org");
    const lifetime = (said.exp as number) - Date.now() / 1000;
    expect(lifetime).toBeGreaterThan(60);
    expect(lifetime).toBeLessThanOrEqual(24 * 60 * 60);
    expect(said.sub).toBe(PUSH_CONTACT);

    // Every mailbox has its own key: the push service cannot tell from it that Bob and Carol share a relay.
    const forCarol = wakes.find((w) => w.url === carol.url)!;
    expect(forCarol.headers.get("authorization")).not.toContain(key!);
    expect((await readyOf(carol)).vapid).not.toBe(ready.vapid);
    // And it stays the same from one login to the next.
    expect((await readyOf(bob)).vapid).toBe(ready.vapid);
  });

  it("does not repeat itself while the phone has yet to come", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    for (let i = 0; i < 5; i++) await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);

    // Much later, with the phone still away, a new message tries once more.
    await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(2)).toBe(2);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(2)).toBe(2);
    await hangUp(a);
  });

  it("wakes again for the next message once the phone has been and gone", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);

    const b = await online(bob);
    const msg = await b.nextJson();
    b.sendJson({ t: "ack", seq: msg.seq });
    b.sendJson({ t: "echo", id: "settle", body: "" });
    expect(await b.nextJson()).toMatchObject({ t: "echo" });
    await hangUp(b);
    expect(await wakeCount(1)).toBe(1);

    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(2)).toBe(2);
    await hangUp(a);
  });

  it("wakes a phone whose connection looks alive but has gone quiet", async () => {
    // Android can freeze an app, and a phone can change network, leaving a
    // socket this relay still sees as ready. The message goes down that socket
    // and is never acknowledged. Without a wake-up it would wait for the next
    // message, or for the phone to come by itself.
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    const b = await online(bob);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await b.nextJson()).toMatchObject({ t: "msg" });
    // Bob's phone never acknowledges it, and never goes away.
    await vi.waitFor(() => expect(wakes.length).toBe(1), { timeout: CONNECTED_GRACE_MS + 4000 });
    expect(wakes[0]!.url).toBe(bob.url);
    await hangUp(b);
    await hangUp(a);
  }, 20_000);

  it("says nothing more once the phone on the connection has taken the message", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    const b = await online(bob);
    await sendMessage(a, bob.id, randomBytes(8));
    const msg = await b.nextJson();
    b.sendJson({ t: "ack", seq: msg.seq });
    b.sendJson({ t: "echo", id: "settle", body: "" });
    expect(await b.nextJson()).toMatchObject({ t: "echo" });
    // Long enough for the second look at the queue to have happened.
    await new Promise((resolve) => setTimeout(resolve, CONNECTED_GRACE_MS + 1000));
    expect(wakes.length).toBe(0);
    await hangUp(b);
    await hangUp(a);
  }, 20_000);

  it("stays quiet while the phone is connected", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    const b = await online(bob);
    await sendMessage(a, bob.id, randomBytes(8));
    const msg = await b.nextJson();
    expect(msg).toMatchObject({ t: "msg", from: alice.id });
    b.sendJson({ t: "ack", seq: msg.seq });
    b.sendJson({ t: "echo", id: "settle", body: "" });
    expect(await b.nextJson()).toMatchObject({ t: "echo" });
    await hangUp(b);
    await hangUp(a);
    expect(await wakeCount(0)).toBe(0);
  });

  it("wakes the phone again when it does not come, then stops", async () => {
    // The push service answering is not the phone answering: an accepted
    // wake-up can be one Google or the phone never acted on.
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);

    // Each retry is due 20 s, 60 s and 180 s after the one before it, which
    // is longer than the relay's own guard against repeating itself.
    for (let i = 0; i < WAKE_RETRY_WAITS_MS.length; i++) {
      await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeDefined());
      await editPush(bob.id, "UPDATE wake_retry SET at = ?", Date.now() - 1);
      await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
      await runAlarm(bob.id);
      expect(await wakeCount(i + 2)).toBe(i + 2);
    }
    // And then it is left alone: the phone's own reconnects take over.
    await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeUndefined());
    await runAlarm(bob.id);
    expect(await wakeCount(WAKE_RETRY_WAITS_MS.length + 1)).toBe(WAKE_RETRY_WAITS_MS.length + 1);
    await hangUp(a);
  }, 30_000);

  it("keeps looking while nothing is taken, even on a socket that says it is connected", async () => {
    // A connection that merely reports being alive is not proof of anything:
    // a stale one looks exactly like that. Only a message actually taken
    // counts, which is why a live socket no longer stops the chain.
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    // Bob's phone is on the connection, and takes nothing.
    const b = await online(bob);
    await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeDefined());
    await editPush(bob.id, "UPDATE wake_retry SET at = ?", Date.now() - 1);
    await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
    await runAlarm(bob.id);
    expect(await wakeCount(2)).toBe(2);

    // Now it takes the message. The next look sees the queue went down, and
    // leaves it alone.
    const msg = await b.nextJson();
    b.sendJson({ t: "ack", seq: msg.seq });
    b.sendJson({ t: "echo", id: "settle", body: "" });
    expect(await b.nextJson()).toMatchObject({ t: "echo" });
    await editPush(bob.id, "UPDATE wake_retry SET at = ?", Date.now() - 1);
    await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
    await runAlarm(bob.id);
    expect(await wakeCount(2)).toBe(2);
    await hangUp(b);
    await hangUp(a);
  }, 30_000);

  it("counts one message taken of two as progress, and still delivers the other", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    // A second is left while the first wake-up is still unanswered, so the
    // relay now knows that two were waiting when it last looked.
    await sendMessage(a, bob.id, randomBytes(8));
    await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeDefined());
    await editPush(bob.id, "UPDATE wake_retry SET at = ?", Date.now() - 1);
    await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
    await runAlarm(bob.id);
    expect(await wakeCount(2)).toBe(2);
    await vi.waitFor(async () => expect((await retryRow(bob.id))?.seen).toBe(2));

    // Bob's phone comes and takes one of the two, and leaves without the other.
    const b = await online(bob);
    const first = await b.nextJson();
    const second = await b.nextJson();
    b.sendJson({ t: "ack", seq: first.seq });
    b.sendJson({ t: "echo", id: "settle", body: "" });
    expect(await b.nextJson()).toMatchObject({ t: "echo" });
    await hangUp(b);

    // The next look sees that something was taken. That is progress, and it
    // does not wake the phone again for it.
    await editPush(bob.id, "UPDATE wake_retry SET at = ?", Date.now() - 1);
    await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
    await runAlarm(bob.id);
    expect(await wakeCount(2)).toBe(2);

    // What was not taken is still there, and is still delivered.
    const again = await online(bob);
    expect(await again.nextJson()).toMatchObject({ t: "msg", seq: second.seq });
    await hangUp(again);
    await hangUp(a);
  }, 30_000);

  it("stops once the message has been taken, and not a moment before", async () => {
    const alice = await registered();
    let bob = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);

    // Bob's phone logs in and takes the message.
    const b = await online(bob);
    const msg = await b.nextJson();
    b.sendJson({ t: "ack", seq: msg.seq });
    b.sendJson({ t: "echo", id: "settle", body: "" });
    expect(await b.nextJson()).toMatchObject({ t: "echo" });
    await hangUp(b);

    // The appointment made by the first wake-up still stands, and running it
    // now finds nothing to do: the message was acknowledged, so there is
    // nothing left to look for.
    await editPush(bob.id, "UPDATE wake_retry SET at = ?", Date.now() - 1);
    await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
    await runAlarm(bob.id);
    expect(await wakeCount(1)).toBe(1);
    await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeUndefined());
    await hangUp(a);
  }, 30_000);

  it("looks for a ringing call sooner, and gives up sooner", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    // A ringing call is sent urgently; the relay treats it as one in every way.
    const call = JSON.stringify({ callId: "c1", type: "offer", sdp: "v=0", video: false });
    const id = requestId();
    a.sendJson({ t: "send", id, to: bob.id, kind: 2, body: toBase64(new TextEncoder().encode(call)), urgent: true });
    expect(await a.nextJson()).toMatchObject({ t: "stored" });
    expect(await wakeCount(1)).toBe(1);

    await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeDefined());
    const row = await retryRow(bob.id);
    // A call is looked for in 8 seconds, not 20, and only twice.
    expect(row!.at as number).toBeLessThanOrEqual(Date.now() + WAKE_RETRY_WAITS_CALL_MS[0]!);
    expect(row!.urgent).toBe(1);

    for (let i = 0; i < WAKE_RETRY_WAITS_CALL_MS.length; i++) {
      await editPush(bob.id, "UPDATE wake_retry SET at = ?", Date.now() - 1);
      await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
      await runAlarm(bob.id);
      expect(await wakeCount(i + 2)).toBe(i + 2);
    }
    await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeUndefined());
    await hangUp(a);
  }, 30_000);

  it("still says call when a call follows a message within the same second", async () => {
    // A message leaves a wake-up on its way to the phone. A call that arrives
    // before that one has been answered is still a call: the phone has to be
    // told to get ready to ring, and the call has to be looked for again at the
    // call's own interval rather than the message's slower one.
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);

    const id = requestId();
    const call = JSON.stringify({ callId: "c1", type: "offer", sdp: "v=0", video: false });
    a.sendJson({ t: "send", id, to: bob.id, kind: 2, body: toBase64(new TextEncoder().encode(call)), urgent: true });
    expect(await a.nextJson()).toMatchObject({ t: "stored" });

    // A second wake-up goes out, and it is the call one.
    expect(await wakeCount(2)).toBe(2);
    expect(new TextDecoder().decode(await bob.keys.decrypt(wakes[1]!.body))).toBe(PUSH_PAYLOAD_CALL);
    expect(wakes[1]!.headers.get("ttl")).toBe(String(PUSH_CALL_TTL_SECONDS));

    // And the phone is looked for again in 8 seconds, not 20.
    await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeDefined());
    const row = await retryRow(bob.id);
    expect(row!.urgent).toBe(1);
    expect(row!.at as number).toBeLessThanOrEqual(Date.now() + WAKE_RETRY_WAITS_CALL_MS[0]!);
    await hangUp(a);
  }, 30_000);

  it("leaves the appointment made for a call alone when a message follows it", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    const id = requestId();
    const call = JSON.stringify({ callId: "c1", type: "offer", sdp: "v=0", video: false });
    a.sendJson({ t: "send", id, to: bob.id, kind: 2, body: toBase64(new TextEncoder().encode(call)), urgent: true });
    expect(await a.nextJson()).toMatchObject({ t: "stored" });
    expect(await wakeCount(1)).toBe(1);
    await vi.waitFor(async () => expect(await retryRow(bob.id)).toBeDefined());
    const before = await retryRow(bob.id);
    expect(before!.urgent).toBe(1);

    // A message arrives once the relay's own guard against repeating itself has
    // passed, so a wake-up for it does go out.
    await editPush(bob.id, "UPDATE push SET last = last - ?", PUSH_REPEAT_MS + 1000);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(2)).toBe(2);

    // The call is still being looked for, and still at the moment it was
    // promised: the message's own, later appointment does not replace it.
    const after = await retryRow(bob.id);
    expect(after!.urgent).toBe(1);
    expect(after!.at as number).toBe(before!.at as number);
    await hangUp(a);
  }, 30_000);

  it("stays quiet for someone who left no address", async () => {
    const alice = await registered();
    const bob = await registered();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    await hangUp(a);
    expect(await wakeCount(0)).toBe(0);
  });

  it("wakes the phone for a message that was lost as it disconnected", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    const b = await online(bob);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await b.nextJson()).toMatchObject({ t: "msg" });
    // Bob's phone drops off without acknowledging it.
    await hangUp(b);
    expect(await wakeCount(1)).toBe(1);
    await hangUp(a);
  });

  it("does not wake a phone over and over for a message it never accepts", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    await hangUp(a);

    // Bob's phone comes, receives the message, and leaves without acknowledging. Twice.
    for (let i = 0; i < 2; i++) {
      const b = await online(bob);
      expect(await b.nextJson()).toMatchObject({ t: "msg" });
      await hangUp(b);
    }
    expect(await wakeCount(1)).toBe(1);
  });

  it("tries again when the push service says a new address is gone, and keeps it", async () => {
    // Google's push service does this for some seconds after handing out an address.
    const waits: number[] = [];
    vi.spyOn(scheduler, "wait").mockImplementation(async (delay) => {
      waits.push(delay);
    });
    const alice = await registered();
    const bob = await withPush();
    let refusals = 2;
    pushServiceAnswer = () => new Response(null, { status: refusals-- > 0 ? 410 : 201 });
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(3)).toBe(3);
    expect(waits).toEqual(PUSH_SETTLE_WAITS_MS.slice(0, 2));
    await vi.waitFor(async () => expect(await readyOf(bob)).toMatchObject({ push: true, pushStatus: 201 }));

    // It never comes round: the address is still kept, and the next message tries again.
    pushServiceAnswer = () => new Response(null, { status: 410 });
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(3 + 1 + PUSH_SETTLE_WAITS_MS.length)).toBe(3 + 1 + PUSH_SETTLE_WAITS_MS.length);
    await vi.waitFor(async () => expect((await pushRow(bob.id))?.pending).toBe(0));
    await hangUp(a);
    expect(await readyOf(bob)).toMatchObject({ push: true, pushStatus: 410 });
  });

  it("stops trying a new address once the phone has come by itself", async () => {
    // Long enough for Bob to log in during the first wait, short enough for a test.
    vi.spyOn(scheduler, "wait").mockImplementation(() => new Promise<void>((resolve) => setTimeout(resolve, 400)));
    const alice = await registered();
    const bob = await withPush();
    pushServiceAnswer = () => new Response(null, { status: 404 });
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    const b = await online(bob);
    await new Promise((resolve) => setTimeout(resolve, 600));
    expect(await wakeCount(1)).toBe(1);
    await hangUp(b);
    await hangUp(a);
    expect(await pushRow(bob.id)).toMatchObject({ url: bob.url });
  });

  it("forgets an address the push service says is gone", async () => {
    const alice = await registered();
    const bob = await withPush();
    // The address has been in use for a while.
    await editPush(bob.id, "UPDATE push_since SET at = at - ?", PUSH_SETTLE_MS + 1000);
    pushServiceAnswer = () => new Response(null, { status: 410 });
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    await vi.waitFor(async () => expect(await pushRow(bob.id)).toBeUndefined());
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    await hangUp(a);
    expect(await readyOf(bob)).toMatchObject({ push: false, queued: 2 });
  });

  it("tells the phone what the push service answered to the last wake-up", async () => {
    const alice = await registered();
    const bob = await withPush();
    // Nothing has been sent yet.
    expect(await readyOf(bob)).toMatchObject({ push: true, pushStatus: null });

    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    expect(await readyOf(bob)).toMatchObject({ push: true, pushStatus: 201 });

    // The push service starts turning the relay away.
    pushServiceAnswer = () => new Response("slow down", { status: 429 });
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(2)).toBe(2);
    await vi.waitFor(async () => expect((await pushRow(bob.id))?.pending).toBe(0));
    expect(await readyOf(bob)).toMatchObject({ push: true, pushStatus: 429 });

    // It cannot be reached at all.
    pushServiceAnswer = () => {
      throw new Error("no route");
    };
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(3)).toBe(3);
    await vi.waitFor(async () => expect((await pushRow(bob.id))?.pending).toBe(0));
    expect(await readyOf(bob)).toMatchObject({ pushStatus: 0 });

    // A new address starts with a clean slate.
    const b = await online(bob);
    expect(await putPush(b, pushUrl(), await makePushKeys())).toMatchObject({ t: "saved" });
    await hangUp(b);
    expect(await readyOf(bob)).toMatchObject({ push: true, pushStatus: null });
    await hangUp(a);
  });

  it("tries again with the next message when the push service was unreachable", async () => {
    const alice = await registered();
    const bob = await withPush();
    pushServiceAnswer = () => {
      throw new Error("connection refused");
    };
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    await vi.waitFor(async () => expect((await pushRow(bob.id))?.pending).toBe(0));

    pushServiceAnswer = () => new Response(null, { status: 503 });
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(2)).toBe(2);
    await vi.waitFor(async () => expect((await pushRow(bob.id))?.pending).toBe(0));

    pushServiceAnswer = () => new Response(null, { status: 201 });
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(3)).toBe(3);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(3)).toBe(3);
    await hangUp(a);
  });

  it("stops for the day at the daily limit, whatever address is left", async () => {
    const alice = await registered();
    const bob = await withPush();
    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);

    await editPush(bob.id, "UPDATE push SET sent = ?, pending = 0", PUSH_DAILY_CAP);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);

    // A new address does not reset the count.
    const b = await online(bob);
    expect(await putPush(b, pushUrl(), await makePushKeys())).toMatchObject({ t: "saved" });
    await hangUp(b);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);

    // The next day it works again.
    await editPush(bob.id, "UPDATE push SET day = day - 1");
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(2)).toBe(2);
    expect(await pushRow(bob.id)).toMatchObject({ sent: 1, pending: 1 });
    await hangUp(a);
  });

  it("gives a mailbox written before the call word the column it needs, and keeps its address", async () => {
    // A mailbox that was woken by the last release has a push table without
    // `call_wake`. The first request after the deploy has to add it without
    // losing the address that is already in the row.
    const alice = await registered();
    const bob = await withPush();
    await runInDurableObject(mailbox(bob.id), (doInstance, state) => {
      const instance = doInstance as unknown as { tablesReady: boolean; ensureTables(): void };
      const row = state.storage.sql.exec("SELECT url, key, auth, last, pending, day, sent FROM push WHERE id = 1").toArray()[0]!;
      state.storage.sql.exec("DROP TABLE push");
      state.storage.sql.exec(
        `CREATE TABLE push (
           id INTEGER PRIMARY KEY CHECK (id = 1),
           url TEXT NOT NULL, key BLOB NOT NULL, auth BLOB NOT NULL,
           last INTEGER NOT NULL, pending INTEGER NOT NULL,
           day INTEGER NOT NULL, sent INTEGER NOT NULL
         )`,
      );
      state.storage.sql.exec(
        "INSERT INTO push (id, url, key, auth, last, pending, day, sent) VALUES (1, ?, ?, ?, ?, ?, ?, ?)",
        row.url,
        row.key,
        row.auth,
        row.last,
        row.pending,
        row.day,
        row.sent,
      );
      instance.tablesReady = false;
      instance.ensureTables();
    });

    const columns = await runInDurableObject(mailbox(bob.id), (_instance, state) =>
      state.storage.sql.exec("SELECT name FROM pragma_table_info('push')").toArray().map((row) => row.name as string),
    );
    expect(columns).toContain("call_wake");
    // The address the phone left is still the one it is woken at.
    expect((await pushRow(bob.id))?.url).toBe(bob.url);

    const a = await online(alice);
    await sendMessage(a, bob.id, randomBytes(8));
    expect(await wakeCount(1)).toBe(1);
    expect(wakes[0]!.url).toBe(bob.url);

    // And a call after it is still promoted, on a mailbox that was migrated.
    const id = requestId();
    const call = JSON.stringify({ callId: "c1", type: "offer", sdp: "v=0", video: false });
    a.sendJson({ t: "send", id, to: bob.id, kind: 2, body: toBase64(new TextEncoder().encode(call)), urgent: true });
    expect(await a.nextJson()).toMatchObject({ t: "stored" });
    expect(await wakeCount(2)).toBe(2);
    expect(new TextDecoder().decode(await bob.keys.decrypt(wakes[1]!.body))).toBe(PUSH_PAYLOAD_CALL);
    await hangUp(a);
  }, 30_000);

  it("says how many messages are waiting when the phone logs in", async () => {
    const alice = await registered();
    const bob = await registered();
    const a = await online(alice);
    for (let i = 0; i < 3; i++) await sendMessage(a, bob.id, randomBytes(8));
    await hangUp(a);
    expect(await readyOf(bob)).toMatchObject({ queued: 3 });
    expect(await readyOf(alice)).toMatchObject({ queued: 0 });
  });
});
