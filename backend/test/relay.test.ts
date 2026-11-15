import { SELF, env, evictDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { MAX_FRAME_BYTES, PROTOCOL_VERSION } from "../src/protocol";
import { connect, makeDevice, makeIdentity, register } from "./helpers";

const MAILBOX_A = "a".repeat(64);

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

describe("logged-in socket", () => {
  it("echoes a frame back", async () => {
    const inbox = await register(await makeIdentity(), await makeDevice());
    inbox.sendJson({ t: "echo", id: "1", body: "namaste" });
    const reply = await inbox.nextJson();
    expect(reply).toMatchObject({ t: "echo", id: "1", body: "namaste" });
    expect(typeof reply.at).toBe("number");
    inbox.ws.close(1000, "done");
  });

  it("reports bad frames without closing the socket", async () => {
    const inbox = await register(await makeIdentity(), await makeDevice());

    inbox.ws.send("not json");
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });

    inbox.sendJson({ t: "echo", id: 1, body: "wrong id type" });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });

    inbox.ws.send(new Uint8Array([1, 2, 3]));
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "bad_frame" });

    inbox.sendJson({ t: "launch" });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "unknown_type" });

    inbox.sendJson({ t: "echo", id: "big", body: "x".repeat(MAX_FRAME_BYTES) });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "too_large" });

    // Multi-byte text is measured in bytes, not characters.
    inbox.sendJson({ t: "echo", id: "big", body: "म".repeat(MAX_FRAME_BYTES / 2) });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "too_large" });

    inbox.sendJson({ t: "login", sig: "AAAA" });
    expect(await inbox.nextJson()).toEqual({ t: "error", code: "already_authenticated" });

    inbox.sendJson({ t: "echo", id: "2", body: "still open" });
    expect(await inbox.nextJson()).toMatchObject({ t: "echo", id: "2" });
    inbox.ws.close(1000, "done");
  });

  it("keeps mailboxes separate", async () => {
    const a = await register(await makeIdentity(), await makeDevice());
    const b = await register(await makeIdentity(), await makeDevice());

    b.sendJson({ t: "echo", id: "b", body: "b" });
    a.sendJson({ t: "echo", id: "a", body: "a" });
    expect(await b.nextJson()).toMatchObject({ id: "b" });
    expect(await a.nextJson()).toMatchObject({ id: "a" });
    a.ws.close(1000, "done");
    b.ws.close(1000, "done");
  });

  it("stays logged in after the object is evicted from memory", async () => {
    const identity = await makeIdentity();
    const inbox = await register(identity, await makeDevice());
    await evictDurableObject(env.MAILBOX.get(env.MAILBOX.idFromName(identity.mailboxId)));

    inbox.sendJson({ t: "echo", id: "woke", body: "after hibernation" });
    expect(await inbox.nextJson()).toMatchObject({ t: "echo", id: "woke", body: "after hibernation" });
    inbox.ws.close(1000, "done");
  });
});

describe("rate limits", () => {
  it("turns away a flood of connections from one address", async () => {
    const ip = "203.0.113.7";
    const statuses: number[] = [];
    // Every one of these is to an address with no account: a possible sign-up.
    for (let i = 0; i < 16; i++) {
      const { mailboxId } = await makeIdentity();
      const response = await SELF.fetch(`https://relay.test/v1/ws/${mailboxId}`, {
        headers: { Upgrade: "websocket", "CF-Connecting-IP": ip },
      });
      statuses.push(response.status);
      response.webSocket?.accept();
      response.webSocket?.close(1000, "done");
    }
    expect(statuses[0]).toBe(101);
    expect(statuses).toContain(429);
  });

  it("turns away a flood of connections to one mailbox", async () => {
    const { mailboxId } = await makeIdentity();
    const statuses: number[] = [];
    for (let i = 0; i < 26; i++) {
      // A different address each time: only the per-mailbox limit can trip.
      const response = await SELF.fetch(`https://relay.test/v1/ws/${mailboxId}`, {
        headers: { Upgrade: "websocket", "CF-Connecting-IP": `203.0.114.${i + 1}` },
      });
      statuses.push(response.status);
      response.webSocket?.accept();
      response.webSocket?.close(1000, "done");
    }
    expect(statuses[0]).toBe(101);
    expect(statuses).toContain(429);
    const refused = await SELF.fetch(`https://relay.test/v1/ws/${mailboxId}`, {
      headers: { Upgrade: "websocket", "CF-Connecting-IP": "203.0.114.200" },
    });
    expect(refused.status).toBe(429);
    expect(refused.headers.get("Retry-After")).toBe("60");
  });

  it("does not limit a different address or mailbox", async () => {
    const { inbox } = await connect((await makeIdentity()).mailboxId);
    inbox.ws.close(1000, "done");
  });
});
