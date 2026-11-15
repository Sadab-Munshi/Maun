import { SELF, env, evictDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { CLOSE_REPLACED } from "../src/mailbox";
import { MAX_FRAME_BYTES, PROTOCOL_VERSION } from "../src/protocol";

const MAILBOX_A = "a".repeat(64);
const MAILBOX_B = "b".repeat(64);

/** Queues everything a socket receives so tests can await frames in order. */
class Inbox {
  private queue: string[] = [];
  private waiters: ((value: string) => void)[] = [];
  closed: Promise<{ code: number; reason: string }>;

  constructor(readonly ws: WebSocket) {
    ws.addEventListener("message", (event) => {
      const text = typeof event.data === "string" ? event.data : "<binary>";
      const waiter = this.waiters.shift();
      if (waiter) waiter(text);
      else this.queue.push(text);
    });
    this.closed = new Promise((resolve) => {
      ws.addEventListener("close", (event) => resolve({ code: event.code, reason: event.reason }));
    });
    ws.accept();
  }

  next(): Promise<string> {
    const queued = this.queue.shift();
    if (queued !== undefined) return Promise.resolve(queued);
    return new Promise((resolve) => this.waiters.push(resolve));
  }

  async nextJson(): Promise<Record<string, unknown>> {
    return JSON.parse(await this.next());
  }
}

async function connect(mailboxId: string): Promise<Inbox> {
  const response = await SELF.fetch(`https://relay.test/v1/ws/${mailboxId}`, {
    headers: { Upgrade: "websocket" },
  });
  expect(response.status).toBe(101);
  const inbox = new Inbox(response.webSocket!);
  expect(await inbox.nextJson()).toEqual({ t: "hello", proto: PROTOCOL_VERSION });
  return inbox;
}

describe("http routes", () => {
  it("answers the health check", async () => {
    const response = await SELF.fetch("https://relay.test/health");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ ok: true, service: "maun-relay", proto: PROTOCOL_VERSION });
  });

  it("returns 404 for unknown paths", async () => {
    expect((await SELF.fetch("https://relay.test/")).status).toBe(404);
    expect((await SELF.fetch("https://relay.test/v1/ws")).status).toBe(404);
  });

  it("rejects a malformed mailbox id", async () => {
    for (const bad of ["abc", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65)]) {
      const response = await SELF.fetch(`https://relay.test/v1/ws/${bad}`, {
        headers: { Upgrade: "websocket" },
      });
      expect(response.status, bad).toBe(400);
    }
  });

  it("refuses a plain GET on the socket route", async () => {
    const response = await SELF.fetch(`https://relay.test/v1/ws/${MAILBOX_A}`);
    expect(response.status).toBe(426);
  });
});

describe("mailbox socket", () => {
  it("echoes a frame back", async () => {
    const inbox = await connect(MAILBOX_A);
    inbox.ws.send(JSON.stringify({ t: "echo", id: "1", body: "namaste" }));
    const reply = await inbox.nextJson();
    expect(reply).toMatchObject({ t: "echo", id: "1", body: "namaste" });
    expect(typeof reply.at).toBe("number");
    inbox.ws.close(1000, "done");
  });

  it("reports bad frames without closing the socket", async () => {
    const inbox = await connect(MAILBOX_A);

    inbox.ws.send("not json");
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });

    inbox.ws.send(JSON.stringify({ t: "echo", id: 1, body: "wrong id type" }));
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });

    inbox.ws.send(new Uint8Array([1, 2, 3]));
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });

    inbox.ws.send(JSON.stringify({ t: "launch" }));
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "unknown_type" });

    inbox.ws.send(JSON.stringify({ t: "echo", id: "big", body: "x".repeat(MAX_FRAME_BYTES) }));
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "too_large" });

    // Multi-byte text is measured in bytes, not characters.
    inbox.ws.send(JSON.stringify({ t: "echo", id: "big", body: "म".repeat(MAX_FRAME_BYTES / 2) }));
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "too_large" });

    inbox.ws.send(JSON.stringify({ t: "echo", id: "2", body: "still open" }));
    expect(await inbox.nextJson()).toMatchObject({ t: "echo", id: "2" });
    inbox.ws.close(1000, "done");
  });

  it("keeps mailboxes separate and replaces an older connection", async () => {
    const first = await connect(MAILBOX_A);
    const other = await connect(MAILBOX_B);
    const second = await connect(MAILBOX_A);

    expect((await first.closed).code).toBe(CLOSE_REPLACED);

    other.ws.send(JSON.stringify({ t: "echo", id: "b", body: "b" }));
    second.ws.send(JSON.stringify({ t: "echo", id: "a", body: "a" }));
    expect(await other.nextJson()).toMatchObject({ id: "b" });
    expect(await second.nextJson()).toMatchObject({ id: "a" });
    other.ws.close(1000, "done");
    second.ws.close(1000, "done");
  });

  it("keeps the socket usable after the object is evicted from memory", async () => {
    const inbox = await connect(MAILBOX_A);
    const stub = env.MAILBOX.get(env.MAILBOX.idFromName(MAILBOX_A));
    await evictDurableObject(stub);

    inbox.ws.send(JSON.stringify({ t: "echo", id: "woke", body: "after hibernation" }));
    expect(await inbox.nextJson()).toMatchObject({ t: "echo", id: "woke", body: "after hibernation" });
    inbox.ws.close(1000, "done");
  });
});
