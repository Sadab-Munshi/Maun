import { SELF } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PUSH_REPEAT_MS, PUSH_URGENT_REPEAT_MS, toBase64 } from "../src/protocol";
import { TURN_DAILY_CAP, TURN_TTL_SECONDS, forgetTurnOrder, turnConfig } from "../src/turn";
import { hangUp, login, makePushKeys, randomBytes, registered, requestId, type Inbox, type User } from "./helpers";

// This file runs with TURN_KEY_ID and TURN_API_TOKEN set (vitest.config.ts).

type Asked = { url: string; authorization: string | null; body: string };

/** What the relay asked the call service, what it sent to push services, and what the call service answers. */
let asked: Asked[] = [];
let wakes: string[] = [];
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
  service = () => Response.json(CLOUDFLARE_ANSWER, { status: 201 });
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const request = new Request(input as RequestInfo, init);
    if (request.url.startsWith("https://rtc.live.cloudflare.com/")) {
      asked.push({ url: request.url, authorization: request.headers.get("Authorization"), body: await request.text() });
      return service();
    }
    wakes.push(request.url);
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
    expect(await ask(inbox, { t: "turn" })).toMatchObject({ t: "failed", code: "no_calls" });
    service = () => {
      throw new Error("network down");
    };
    expect(await ask(inbox, { t: "turn" })).toMatchObject({ t: "failed", code: "no_calls" });
    service = () => Response.json({ iceServers: [{ urls: ["stun:stun.cloudflare.com:3478"] }] }, { status: 201 });
    expect(await ask(inbox, { t: "turn" })).toMatchObject({ t: "failed", code: "no_calls" });
    await hangUp(inbox);
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

    // An ordinary message a minute later: the phone was told already.
    vi.spyOn(Date, "now").mockReturnValue(real + 60_000);
    await send(alice, bob.id, false);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(wakes).toHaveLength(1);

    // A call: it is told again, now.
    await send(alice, bob.id, true);
    await vi.waitFor(() => expect(wakes).toHaveLength(2));

    // But not twice within a few seconds: the pieces of one call come close together.
    vi.spyOn(Date, "now").mockReturnValue(real + 60_000 + PUSH_URGENT_REPEAT_MS - 1000);
    await send(alice, bob.id, true);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(wakes).toHaveLength(2);
    expect(PUSH_URGENT_REPEAT_MS).toBeLessThan(PUSH_REPEAT_MS);
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
