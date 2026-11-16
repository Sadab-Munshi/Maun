import { SELF } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  PUSH_CALL_TTL_SECONDS,
  PUSH_PAYLOAD,
  PUSH_PAYLOAD_CALL,
  PUSH_REPEAT_MS,
  PUSH_TTL_SECONDS,
  PUSH_URGENT_REPEAT_MS,
  toBase64,
} from "../src/protocol";
import { TURN_DAILY_CAP, TURN_TTL_SECONDS, forgetTurnOrder, turnConfig } from "../src/turn";
import { hangUp, login, makePushKeys, randomBytes, registered, requestId, type Inbox, type User } from "./helpers";

// This file runs with TURN_KEY_ID and TURN_API_TOKEN set (vitest.config.ts).

type Asked = { url: string; authorization: string | null; body: string };

/** What the relay asked the call service, what it sent to push services, and what the call service answers. */
let asked: Asked[] = [];
let wakes: string[] = [];
let wakeHeaders: Headers[] = [];
let wakeBodies: Uint8Array[] = [];
let service: () => Response = () => new Response(null, { status: 500 });

const CLOUDFLARE_ANSWER = {
  iceServers: [
    { urls: ["stun:stun.cloudflare.com:3478"] },
    {
      urls: ["turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:443?transport=tcp"],
      username: "user-for-this-call",
      credential: "secret-for-this-call",
    },
  ],
};

beforeEach(() => {
  forgetTurnOrder();
  asked = [];
  wakes = [];
  wakeHeaders = [];
  wakeBodies = [];
  service = () => Response.json(CLOUDFLARE_ANSWER, { status: 201 });
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const request = new Request(input as RequestInfo, init);
    if (request.url.startsWith("https://rtc.live.cloudflare.com/")) {
      asked.push({ url: request.url, authorization: request.headers.get("Authorization"), body: await request.text() });
      return service();
    }
    wakes.push(request.url);
    wakeHeaders.push(request.headers);
    wakeBodies.push(new Uint8Array(await request.arrayBuffer()));
    return new Response(null, { status: 201 });
  });
});

afterEach(() => vi.restoreAllMocks());

async function ask(inbox: Inbox, frame: Record<string, unknown>): Promise<Record<string, unknown>> {
  const id = requestId();
  inbox.sendJson({ ...frame, id });
  for (;;) {
    const answer = await inbox.nextJson();
    if (answer.t !== "msg") {
      expect(answer.id).toBe(id);
      return answer;
    }
  }
}

const online = (user: User) => login(user.identity, user.device);

describe("servers for a call", () => {
  it("says in the health check that calls are set up", async () => {
    expect(await (await SELF.fetch("https://relay.test/health")).json()).toMatchObject({ calls: true });
  });

  it("are made from the relay's key for one call, and only relays are passed on", async () => {
    const inbox = await online(await registered());
    expect(await ask(inbox, { t: "turn" })).toEqual({
      t: "ice",
      id: expect.any(String),
      ttl: TURN_TTL_SECONDS,
      servers: [
        {
          urls: ["turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:443?transport=tcp"],
          username: "user-for-this-call",
          credential: "secret-for-this-call",
        },
      ],
    });
    expect(asked).toEqual([
      {
        url: "https://rtc.live.cloudflare.com/v1/turn/keys/test-turn-key/credentials/generate-ice-servers",
        authorization: "Bearer test-turn-token",
        body: JSON.stringify({ ttl: TURN_TTL_SECONDS }),
      },
    ]);
    await hangUp(inbox);
  });

  it("are refused when the call service refuses, cannot be reached, or answers nonsense", async () => {
    const inbox = await online(await registered());
    service = () => new Response("no", { status: 401 });
    expect(await ask(inbox, { t: "turn" })).toMatchObject({ t: "failed", code: "calls_refused" });
    service = () => {
      throw new Error("network down");
    };
    expect(await ask(inbox, { t: "turn" })).toMatchObject({ t: "failed", code: "calls_refused" });
    service = () => Response.json({ iceServers: [{ urls: ["stun:stun.cloudflare.com:3478"] }] }, { status: 201 });
    expect(await ask(inbox, { t: "turn" })).toMatchObject({ t: "failed", code: "calls_refused" });
    await hangUp(inbox);
  });

  it("can be checked without a login, and the check gives nothing away", async () => {
    const check = async () => (await SELF.fetch("https://relay.test/v1/calls/check", { headers: { "CF-Connecting-IP": "203.0.113.9" } })).json();
    expect(await check()).toEqual({ configured: true, working: true, status: 201 });
    forgetTurnOrder();
    // A key the service does not know, as when the ID of something else was pasted.
    service = () => Response.json({ error: "cannot find specified key" }, { status: 404 });
    const failing = await SELF.fetch("https://relay.test/v1/calls/check", { headers: { "CF-Connecting-IP": "203.0.113.9" } });
    const text = await failing.text();
    expect(JSON.parse(text)).toEqual({ configured: true, working: false, status: 404 });
    expect(text).not.toContain("test-turn");
    expect(text).not.toContain("secret-for-this-call");
  });

  it("are still found when the key and the token were put into each other's place", async () => {
    // The service knows the pair only the other way round.
    service = () => new Response("no", { status: 401 });
    const right = "https://rtc.live.cloudflare.com/v1/turn/keys/test-turn-token/credentials/generate-ice-servers";
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      const request = new Request(input as RequestInfo, init);
      asked.push({ url: request.url, authorization: request.headers.get("Authorization"), body: "" });
      const swappedPair = request.url === right && request.headers.get("Authorization") === "Bearer test-turn-key";
      return swappedPair ? Response.json(CLOUDFLARE_ANSWER, { status: 201 }) : new Response("no", { status: 401 });
    });
    const inbox = await online(await registered());
    expect((await ask(inbox, { t: "turn" })).t).toBe("ice");
    expect(asked).toHaveLength(2);
    // The second time it goes straight to what worked.
    expect((await ask(inbox, { t: "turn" })).t).toBe("ice");
    expect(asked).toHaveLength(3);
    expect(asked[2]!.url).toBe(right);
    await hangUp(inbox);
  });

  it("are handed out only so often in a day", async () => {
    const inbox = await online(await registered());
    for (let i = 0; i < TURN_DAILY_CAP; i++) expect((await ask(inbox, { t: "turn" })).t).toBe("ice");
    expect(await ask(inbox, { t: "turn" })).toMatchObject({ t: "failed", code: "rate_limited" });
    expect(asked).toHaveLength(TURN_DAILY_CAP);
    await hangUp(inbox);
  });

  it("need a login", async () => {
    const response = await SELF.fetch(`https://relay.test/v1/ws/${"ab".repeat(32)}`, { headers: { Upgrade: "websocket" } });
    const ws = response.webSocket!;
    const frames: string[] = [];
    ws.addEventListener("message", (event) => frames.push(event.data as string));
    ws.accept();
    ws.send(JSON.stringify({ t: "turn", id: "x" }));
    await vi.waitFor(() => expect(frames.some((frame) => frame.includes("not_authenticated"))).toBe(true));
    expect(asked).toHaveLength(0);
  });

  it("are off when the relay has no key", () => {
    expect(turnConfig({})).toBeNull();
    expect(turnConfig({ TURN_KEY_ID: "key", TURN_API_TOKEN: "  " })).toBeNull();
    expect(turnConfig({ TURN_KEY_ID: " key ", TURN_API_TOKEN: "token" })).toEqual({ keyId: "key", token: "token" });
  });
});

describe("a call that is ringing", () => {
  /** An account that has left a wake-up address and gone offline. */
  async function asleep(): Promise<User> {
    const user = await registered();
    const keys = await makePushKeys();
    const inbox = await online(user);
    const saved = await ask(inbox, { t: "put_push", url: `https://push.example.org/up${requestId()}`, key: toBase64(keys.publicKey), auth: toBase64(keys.auth) });
    expect(saved.t).toBe("saved");
    await hangUp(inbox);
    return user;
  }

  const send = (inbox: Inbox, to: string, urgent: boolean) =>
    ask(inbox, { t: "send", to, kind: 2, body: toBase64(randomBytes(40)), ...(urgent ? { urgent: true } : {}) });

  it("wakes the other phone again, where an ordinary message would wait", async () => {
    const bob = await asleep();
    const alice = await online(await registered());
    const real = Date.now();

    expect((await send(alice, bob.id, false)).t).toBe("stored");
    await vi.waitFor(() => expect(wakes).toHaveLength(1));

    // An ordinary message right behind it rides on the same wake-up.
    const soon = real + PUSH_URGENT_REPEAT_MS + 500;
    expect(soon - real).toBeLessThan(PUSH_REPEAT_MS);
    vi.spyOn(Date, "now").mockReturnValue(soon);
    await send(alice, bob.id, false);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(wakes).toHaveLength(1);

    // A call at that moment does not wait: the phone is told again, now.
    await send(alice, bob.id, true);
    await vi.waitFor(() => expect(wakes).toHaveLength(2));

    // But not twice within a second or two: the pieces of one call come close together.
    vi.spyOn(Date, "now").mockReturnValue(soon + PUSH_URGENT_REPEAT_MS - 500);
    await send(alice, bob.id, true);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(wakes).toHaveLength(2);
    await hangUp(alice);
  });

  it("is announced as a call, urgently, and not kept by the push service once it has rung out", async () => {
    const user = await registered();
    const keys = await makePushKeys();
    const inbox = await online(user);
    const saved = await ask(inbox, { t: "put_push", url: `https://push.example.org/up${requestId()}`, key: toBase64(keys.publicKey), auth: toBase64(keys.auth) });
    expect(saved.t).toBe("saved");
    await hangUp(inbox);
    const alice = await online(await registered());
    const real = Date.now();

    await send(alice, user.id, false);
    await vi.waitFor(() => expect(wakes).toHaveLength(1));
    vi.spyOn(Date, "now").mockReturnValue(real + 30_000);
    await send(alice, user.id, true);
    await vi.waitFor(() => expect(wakes).toHaveLength(2));

    const said = async (i: number) => new TextDecoder().decode(await keys.decrypt(wakeBodies[i]!));
    // An ordinary message: the push service may hold the wake-up for a day if the phone is off.
    expect(await said(0)).toBe(PUSH_PAYLOAD);
    expect(wakeHeaders[0]!.get("ttl")).toBe(String(PUSH_TTL_SECONDS));
    expect(wakeHeaders[0]!.get("urgency")).toBe("high");
    // A call: half a minute, then it is dropped, so a phone that comes back later does not ring for nothing.
    expect(await said(1)).toBe(PUSH_PAYLOAD_CALL);
    expect(wakeHeaders[1]!.get("ttl")).toBe(String(PUSH_CALL_TTL_SECONDS));
    expect(wakeHeaders[1]!.get("urgency")).toBe("high");
    await hangUp(alice);
  });

  it("is not what a receipt is: one marked quiet waits for the phone without waking it", async () => {
    const bob = await asleep();
    const alice = await online(await registered());
    const quiet = await ask(alice, { t: "send", to: bob.id, kind: 2, body: toBase64(randomBytes(40)), quiet: true });
    expect(quiet.t).toBe("stored");
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(wakes).toHaveLength(0);
    // It is there when the phone next comes, and an ordinary message still wakes it.
    await send(alice, bob.id, false);
    await vi.waitFor(() => expect(wakes).toHaveLength(1));
    const inbox = await online(bob);
    expect((await inbox.nextJson()).t).toBe("msg");
    expect((await inbox.nextJson()).t).toBe("msg");
    await hangUp(inbox);
    await hangUp(alice);
  });

  it("wakes the phone for each new message once the last wake-up is a few seconds old", async () => {
    const bob = await asleep();
    const alice = await online(await registered());
    const real = Date.now();
    await send(alice, bob.id, false);
    await vi.waitFor(() => expect(wakes).toHaveLength(1));
    // The phone never came for the first one. A minute later the next message still tells it.
    vi.spyOn(Date, "now").mockReturnValue(real + 60_000);
    await send(alice, bob.id, false);
    await vi.waitFor(() => expect(wakes).toHaveLength(2));
    await hangUp(alice);
  });

  it("tells the phone how long each message has been waiting", async () => {
    const bob = await registered();
    const alice = await online(await registered());
    const real = Date.now();
    await send(alice, bob.id, true);
    vi.spyOn(Date, "now").mockReturnValue(real + 70_000);
    const inbox = await online(bob);
    const message = await inbox.nextJson();
    expect(message.t).toBe("msg");
    expect(message.age).toBeGreaterThanOrEqual(69_000);
    expect(message.age).toBeLessThan(80_000);
    await hangUp(inbox);
    await hangUp(alice);
  });
});
