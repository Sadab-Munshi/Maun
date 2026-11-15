import { describe, expect, it } from "vitest";
import { MAX_ONE_TIME_KEYS, toBase64 } from "../src/protocol";
import {
  connect,
  hangUp,
  keysFrame,
  login,
  loginFrame,
  makeUser,
  oneTimeKeys,
  randomBytes,
  register,
  registered,
  requestId,
} from "./helpers";

async function fetchKeys(inbox: Awaited<ReturnType<typeof login>>, of: string) {
  const id = requestId();
  inbox.sendJson({ t: "get_keys", id, of });
  const answer = await inbox.nextJson();
  expect(answer.id).toBe(id);
  return answer;
}

describe("pre-keys", () => {
  it("publishes keys once and reports them at the next login", async () => {
    const alice = await makeUser();
    const a = await register(alice.identity, alice.device);

    const frame = keysFrame();
    a.sendJson(frame);
    expect(await a.nextJson()).toEqual({ t: "saved", id: frame.id, otks: 0 });
    a.sendJson({ t: "put_otks", id: "o1", keys: oneTimeKeys(1, 30) });
    expect(await a.nextJson()).toEqual({ t: "saved", id: "o1", otks: 30 });
    await hangUp(a);

    const { inbox, nonce } = await connect(alice.id);
    inbox.sendJson(await loginFrame(alice.identity, alice.device, nonce));
    expect(await inbox.nextJson()).toEqual({ t: "ready", fresh: false, keys: true, otks: 30, push: false, queued: 0 });
    await hangUp(inbox);
  });

  it("hands each one-time key to one asker only", async () => {
    const alice = await makeUser();
    const a = await register(alice.identity, alice.device);
    const published = keysFrame();
    const oneTime = oneTimeKeys(7, 2);
    a.sendJson(published);
    await a.nextJson();
    a.sendJson({ t: "put_otks", id: "o", keys: oneTime });
    await a.nextJson();

    const bob = await registered();
    const b = await login(bob.identity, bob.device);
    const first = await fetchKeys(b, alice.id);
    expect(first).toEqual({
      t: "keys",
      id: first.id,
      regId: published.regId,
      spk: published.spk,
      kpk: published.kpk,
      otk: oneTime[0],
    });
    const second = await fetchKeys(b, alice.id);
    expect(second.otk).toEqual(oneTime[1]);

    // With none left the bundle is still usable, just without a one-time key.
    const third = await fetchKeys(b, alice.id);
    expect(third).toMatchObject({ t: "keys", spk: published.spk, kpk: published.kpk, otk: null });

    // The owner is told how many are left, so the phone can top up.
    a.sendJson({ t: "put_otks", id: "more", keys: oneTimeKeys(9, 1) });
    expect(await a.nextJson()).toEqual({ t: "saved", id: "more", otks: 1 });
    await hangUp(a);
    await hangUp(b);
  });

  it("keeps a bounded number of one-time keys and ignores repeats", async () => {
    const alice = await makeUser();
    const a = await register(alice.identity, alice.device);
    a.sendJson({ t: "put_otks", id: "a", keys: oneTimeKeys(1, 50) });
    expect(await a.nextJson()).toMatchObject({ otks: 50 });
    a.sendJson({ t: "put_otks", id: "b", keys: oneTimeKeys(1, 50) });
    expect(await a.nextJson()).toMatchObject({ otks: 50 });
    a.sendJson({ t: "put_otks", id: "c", keys: oneTimeKeys(51, 50) });
    expect(await a.nextJson()).toMatchObject({ otks: MAX_ONE_TIME_KEYS });
    a.sendJson({ t: "put_otks", id: "d", keys: oneTimeKeys(101, 50) });
    expect(await a.nextJson()).toMatchObject({ otks: MAX_ONE_TIME_KEYS });
    await hangUp(a);
  });

  it("replaces the signed keys when they are published again", async () => {
    const alice = await makeUser();
    const a = await register(alice.identity, alice.device);
    a.sendJson(keysFrame());
    await a.nextJson();
    const newer = { ...keysFrame(), regId: 77 };
    a.sendJson(newer);
    await a.nextJson();

    const bob = await registered();
    const b = await login(bob.identity, bob.device);
    expect(await fetchKeys(b, alice.id)).toMatchObject({ regId: 77, spk: newer.spk, kpk: newer.kpk });
    await hangUp(a);
    await hangUp(b);
  });

  it("says why keys cannot be fetched", async () => {
    const alice = await registered();
    const a = await login(alice.identity, alice.device);
    const stranger = await makeUser();
    const silent = await registered(); // has an account but never published keys

    expect(await fetchKeys(a, stranger.id)).toMatchObject({ t: "failed", code: "no_account" });
    expect(await fetchKeys(a, silent.id)).toMatchObject({ t: "failed", code: "no_keys" });
    expect(await fetchKeys(a, alice.id)).toMatchObject({ t: "failed", code: "self" });
    await hangUp(a);
  });

  it("rejects key material of the wrong shape", async () => {
    const alice = await makeUser();
    const a = await register(alice.identity, alice.device);
    const good = keysFrame();
    const bad = [
      { ...good, regId: 0 },
      { ...good, regId: 16384 },
      { ...good, spk: { ...good.spk, key: toBase64(randomBytes(32)) } },
      { ...good, spk: { ...good.spk, sig: toBase64(randomBytes(63)) } },
      { ...good, spk: { ...good.spk, id: 0 } },
      { ...good, spk: { ...good.spk, id: 0x1000000 } },
      { ...good, kpk: { ...good.kpk, key: toBase64(randomBytes(1568)) } },
      { ...good, kpk: undefined },
      { ...good, id: undefined },
      { t: "put_otks", id: "x", keys: [] },
      { t: "put_otks", id: "x", keys: oneTimeKeys(1, 51) },
      { t: "put_otks", id: "x", keys: [{ id: 1, key: toBase64(randomBytes(32)) }] },
      { t: "put_otks", id: "x", keys: [{ id: 1.5, key: toBase64(randomBytes(33)) }] },
      { t: "put_otks", id: "x", keys: ["nope"] },
      { t: "get_keys", id: "x", of: "short" },
    ];
    for (const frame of bad) {
      a.sendJson(frame);
      expect(await a.nextJson(), JSON.stringify(frame).slice(0, 60)).toEqual({ t: "error", code: "bad_frame" });
    }
    // Nothing was saved by any of them.
    a.sendJson(good);
    expect(await a.nextJson()).toEqual({ t: "saved", id: good.id, otks: 0 });
    await hangUp(a);
  });
});
