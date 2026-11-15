import { env, evictDurableObject, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { MAX_PENDING_SOCKETS, type Mailbox } from "../src/mailbox";
import { CLOSE_AUTH_TIMEOUT, CLOSE_REPLACED, toBase64 } from "../src/protocol";
import {
  connect,
  expectRefused,
  login,
  loginFrame,
  makeDevice,
  makeIdentity,
  register,
  registerFrame,
} from "./helpers";

function stubFor(mailboxId: string): DurableObjectStub<Mailbox> {
  return env.MAILBOX.get(env.MAILBOX.idFromName(mailboxId)) as DurableObjectStub<Mailbox>;
}

/** Number of rows in the account table, or 0 when the table was never created. */
async function accountRows(mailboxId: string): Promise<number> {
  return runInDurableObject(stubFor(mailboxId), (_instance, state) => {
    const tables = state.storage.sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray();
    if (!tables.some((t) => t.name === "account")) return 0;
    return state.storage.sql.exec("SELECT COUNT(*) AS n FROM account").one().n as number;
  });
}

function flip(text: string): string {
  const bytes = Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
  bytes[bytes.length - 1] = bytes[bytes.length - 1]! ^ 0x01;
  return toBase64(bytes);
}

describe("hello", () => {
  it("issues a different nonce on every connection", async () => {
    const { mailboxId } = await makeIdentity();
    const first = await connect(mailboxId);
    const second = await connect(mailboxId);
    expect(first.registered).toBe(false);
    expect(toBase64(first.nonce)).not.toBe(toBase64(second.nonce));
    first.inbox.ws.close(1000, "done");
    second.inbox.ws.close(1000, "done");
  });

  it("writes nothing for an address nobody registered", async () => {
    const { mailboxId } = await makeIdentity();
    const { inbox } = await connect(mailboxId);
    inbox.ws.close(1000, "done");
    const tables = await runInDurableObject(stubFor(mailboxId), (_instance, state) =>
      state.storage.sql
        .exec("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE '\\_cf\\_%' ESCAPE '\\'")
        .toArray(),
    );
    expect(tables).toEqual([]);
  });
});

describe("registration", () => {
  it("creates the account and logs the socket in", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const inbox = await register(identity, device);
    expect(await accountRows(identity.mailboxId)).toBe(1);
    inbox.ws.close(1000, "done");
  });

  it("stores the device key and the nonce it was created against", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const { inbox, nonce } = await connect(identity.mailboxId);
    inbox.sendJson(await registerFrame(identity, device, nonce));
    expect(await inbox.nextJson()).toEqual({ t: "ready", fresh: true });

    const row = await runInDurableObject(stubFor(identity.mailboxId), (_instance, state) =>
      state.storage.sql.exec("SELECT device_key, reg_nonce, created_day FROM account").one(),
    );
    expect(new Uint8Array(row.device_key as ArrayBuffer)).toEqual(device.spki);
    expect(new Uint8Array(row.reg_nonce as ArrayBuffer)).toEqual(nonce);
    expect(row.created_day).toBe(Math.floor(Date.now() / 86_400_000));
    inbox.ws.close(1000, "done");
  });

  it("rejects a forged identity signature", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const { inbox, nonce } = await connect(identity.mailboxId);
    const frame = await registerFrame(identity, device, nonce);
    inbox.sendJson({ ...frame, identitySig: flip(frame.identitySig) });
    await expectRefused(inbox, "bad_signature");
    expect(await accountRows(identity.mailboxId)).toBe(0);
  });

  it("rejects a forged device signature", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const { inbox, nonce } = await connect(identity.mailboxId);
    const frame = await registerFrame(identity, device, nonce);
    inbox.sendJson({ ...frame, deviceSig: toBase64(await (await makeDevice()).sign(nonce)) });
    await expectRefused(inbox, "bad_signature");
    expect(await accountRows(identity.mailboxId)).toBe(0);
  });

  it("rejects an address claimed with someone else's identity key", async () => {
    const victim = await makeIdentity();
    const attacker = await makeIdentity();
    const device = await makeDevice();
    const { inbox, nonce } = await connect(victim.mailboxId);
    // Signed correctly, but by a key that does not own this address.
    const frame = await registerFrame({ ...attacker, mailboxId: victim.mailboxId }, device, nonce);
    inbox.sendJson(frame);
    await expectRefused(inbox, "bad_signature");
    expect(await accountRows(victim.mailboxId)).toBe(0);
  });

  it("rejects a registration replayed on another connection", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const first = await connect(identity.mailboxId);
    const captured = await registerFrame(identity, device, first.nonce);
    first.inbox.ws.close(1000, "done");

    const second = await connect(identity.mailboxId);
    second.inbox.sendJson(captured);
    await expectRefused(second.inbox, "bad_signature");
    expect(await accountRows(identity.mailboxId)).toBe(0);
  });

  it("rejects a second registration for the same ID", async () => {
    const identity = await makeIdentity();
    const firstPhone = await makeDevice();
    const secondPhone = await makeDevice();
    (await register(identity, firstPhone)).ws.close(1000, "done");

    // Even with the real identity key, a second device cannot take the account.
    const { inbox, nonce, registered } = await connect(identity.mailboxId);
    expect(registered).toBe(true);
    inbox.sendJson(await registerFrame(identity, secondPhone, nonce));
    await expectRefused(inbox, "already_registered");
    expect(await accountRows(identity.mailboxId)).toBe(1);

    // The first phone is unaffected; the second cannot log in.
    (await login(identity, firstPhone)).ws.close(1000, "done");
    const intruder = await connect(identity.mailboxId);
    intruder.inbox.sendJson(await loginFrame(identity, secondPhone, intruder.nonce));
    await expectRefused(intruder.inbox, "bad_signature");
  });

  it("lets only one of two simultaneous registrations win", async () => {
    const identity = await makeIdentity();
    const a = await connect(identity.mailboxId);
    const b = await connect(identity.mailboxId);
    a.inbox.sendJson(await registerFrame(identity, await makeDevice(), a.nonce));
    b.inbox.sendJson(await registerFrame(identity, await makeDevice(), b.nonce));
    // The winner is told it is ready; the loser is either refused or closed
    // as the older connection, depending on which check finishes first.
    const outcome = (c: typeof a) =>
      Promise.race([c.inbox.nextJson().then((frame) => frame.t as string), c.inbox.closed.then(() => "closed")]);
    const outcomes = [await outcome(a), await outcome(b)];
    expect(outcomes.filter((o) => o === "ready")).toHaveLength(1);
    expect(await accountRows(identity.mailboxId)).toBe(1);
  });

  it("rejects malformed registration frames", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const bad: ((frame: Record<string, string>) => unknown)[] = [
      (frame) => ({ ...frame, deviceKey: frame.deviceKey!.slice(0, 40) }),
      (frame) => ({ ...frame, deviceKey: undefined }),
      (frame) => ({ ...frame, identitySig: "not base64!!" }),
      (frame) => ({ ...frame, deviceSig: "A".repeat(200) }),
      (frame) => ({ ...frame, identitySig: 7 }),
    ];
    for (const change of bad) {
      const { inbox, nonce } = await connect(identity.mailboxId);
      inbox.sendJson(change(await registerFrame(identity, device, nonce)));
      await expectRefused(inbox, "bad_frame");
    }
    expect(await accountRows(identity.mailboxId)).toBe(0);
  });
});

describe("login", () => {
  it("logs in with the registered device key", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    (await register(identity, device)).ws.close(1000, "done");
    const inbox = await login(identity, device);
    inbox.sendJson({ t: "echo", id: "1", body: "in" });
    expect(await inbox.nextJson()).toMatchObject({ t: "echo", id: "1" });
    inbox.ws.close(1000, "done");
  });

  it("rejects a forged signature", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    (await register(identity, device)).ws.close(1000, "done");

    const { inbox, nonce } = await connect(identity.mailboxId);
    const frame = await loginFrame(identity, device, nonce);
    inbox.sendJson({ ...frame, sig: flip(frame.sig) });
    await expectRefused(inbox, "bad_signature");
  });

  it("rejects a signature made with the identity key instead of the device key", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    (await register(identity, device)).ws.close(1000, "done");

    const { inbox, nonce } = await connect(identity.mailboxId);
    inbox.sendJson({ t: "login", sig: toBase64(await identity.sign(nonce)) });
    await expectRefused(inbox, "bad_signature");
  });

  it("rejects a login replayed on another connection", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    (await register(identity, device)).ws.close(1000, "done");

    // An eavesdropper records a login that the relay accepted...
    const first = await connect(identity.mailboxId);
    const captured = await loginFrame(identity, device, first.nonce);
    first.inbox.sendJson(captured);
    expect(await first.inbox.nextJson()).toEqual({ t: "ready", fresh: false });

    // ...and sends the same bytes on a connection of their own.
    const second = await connect(identity.mailboxId);
    second.inbox.sendJson(captured);
    await expectRefused(second.inbox, "bad_signature");

    // The real phone was not disturbed by the failed attempt.
    first.inbox.sendJson({ t: "echo", id: "ok", body: "" });
    expect(await first.inbox.nextJson()).toMatchObject({ t: "echo", id: "ok" });
    first.inbox.ws.close(1000, "done");
  });

  it("rejects a registration signature reused as a login", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const first = await connect(identity.mailboxId);
    const frame = await registerFrame(identity, device, first.nonce);
    first.inbox.sendJson(frame);
    expect(await first.inbox.nextJson()).toEqual({ t: "ready", fresh: true });

    const second = await connect(identity.mailboxId);
    second.inbox.sendJson({ t: "login", sig: frame.deviceSig });
    await expectRefused(second.inbox, "bad_signature");
    first.inbox.ws.close(1000, "done");
  });

  it("gives one attempt per connection", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    (await register(identity, device)).ws.close(1000, "done");

    const { inbox, nonce } = await connect(identity.mailboxId);
    const good = await loginFrame(identity, device, nonce);
    inbox.sendJson({ ...good, sig: flip(good.sig) });
    inbox.sendJson(good); // Too late: the nonce was spent on the first attempt.
    await expectRefused(inbox, "bad_signature");
  });

  it("refuses a login for an address with no account", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const { inbox, nonce } = await connect(identity.mailboxId);
    inbox.sendJson(await loginFrame(identity, device, nonce));
    await expectRefused(inbox, "not_registered");
  });

  it("refuses everything else until login", async () => {
    const { mailboxId } = await makeIdentity();
    const first = await connect(mailboxId);
    first.inbox.sendJson({ t: "echo", id: "1", body: "let me in" });
    await expectRefused(first.inbox, "not_authenticated");

    const second = await connect(mailboxId);
    second.inbox.ws.send("not json");
    await expectRefused(second.inbox, "bad_frame");
  });

  it("works when the object is evicted between hello and login", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    (await register(identity, device)).ws.close(1000, "done");

    const { inbox, nonce } = await connect(identity.mailboxId);
    await evictDurableObject(stubFor(identity.mailboxId));
    inbox.sendJson(await loginFrame(identity, device, nonce));
    expect(await inbox.nextJson()).toEqual({ t: "ready", fresh: false });
    inbox.ws.close(1000, "done");
  });
});

describe("one connection per account", () => {
  it("a new login replaces the older connection", async () => {
    const identity = await makeIdentity();
    const device = await makeDevice();
    const first = await register(identity, device);
    const second = await login(identity, device);
    expect((await first.closed).code).toBe(CLOSE_REPLACED);
    second.sendJson({ t: "echo", id: "2", body: "" });
    expect(await second.nextJson()).toMatchObject({ t: "echo", id: "2" });
    second.ws.close(1000, "done");
  });

  it("a stranger who only connects cannot disconnect the owner", async () => {
    const identity = await makeIdentity();
    const owner = await register(identity, await makeDevice());

    const stranger = await connect(identity.mailboxId);
    stranger.inbox.sendJson({ t: "login", sig: toBase64(new Uint8Array(70)) });
    await expectRefused(stranger.inbox, "bad_signature");

    owner.sendJson({ t: "echo", id: "still here", body: "" });
    expect(await owner.nextJson()).toMatchObject({ t: "echo", id: "still here" });
    owner.ws.close(1000, "done");
  });

  it("keeps only a few sockets waiting at the login step", async () => {
    const { mailboxId } = await makeIdentity();
    const waiting = [];
    for (let i = 0; i < MAX_PENDING_SOCKETS + 2; i++) waiting.push(await connect(mailboxId));

    // The two oldest were closed to make room; the newest are still open.
    expect((await waiting[0]!.inbox.closed).code).toBe(CLOSE_AUTH_TIMEOUT);
    expect((await waiting[1]!.inbox.closed).code).toBe(CLOSE_AUTH_TIMEOUT);
    const open = await runInDurableObject(stubFor(mailboxId), (_instance, state) => state.getWebSockets().length);
    expect(open).toBe(MAX_PENDING_SOCKETS);
    for (const { inbox } of waiting.slice(2)) inbox.ws.close(1000, "done");
  });
});
