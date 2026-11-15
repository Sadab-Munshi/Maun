import { describe, expect, it } from "vitest";
import { encryptWebPush, importPushKey, isPushUrl } from "../src/crypto/webpush";
import { fromBase64Url, makePushKeys, randomBytes } from "./helpers";

function toBase64Url(bytes: Uint8Array): string {
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

// The worked example from RFC 8291, section 5 and appendix A.
const RFC = {
  plaintext: "When I grow up, I want to be a watermelon",
  receiverPublic: "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
  auth: "BTBZMqHH6r4Tts7J_aSIgg",
  senderPublic: "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
  senderPrivate: "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw",
  salt: "DGv6ra1nlYgDCS1FRnbzlw",
  body:
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml" +
    "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT" +
    "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
};

async function rfcSenderKeys(): Promise<CryptoKeyPair> {
  const point = fromBase64Url(RFC.senderPublic);
  const jwk = {
    kty: "EC",
    crv: "P-256",
    x: toBase64Url(point.subarray(1, 33)),
    y: toBase64Url(point.subarray(33, 65)),
  };
  const curve = { name: "ECDH", namedCurve: "P-256" };
  return {
    publicKey: await crypto.subtle.importKey("jwk", jwk, curve, true, []),
    privateKey: await crypto.subtle.importKey("jwk", { ...jwk, d: RFC.senderPrivate }, curve, true, ["deriveBits"]),
  };
}

describe("web push encryption", () => {
  it("produces exactly the message of the RFC's worked example", async () => {
    const body = await encryptWebPush(
      new TextEncoder().encode(RFC.plaintext),
      fromBase64Url(RFC.receiverPublic),
      fromBase64Url(RFC.auth),
      { salt: fromBase64Url(RFC.salt), senderKeys: await rfcSenderKeys() },
    );
    expect(toBase64Url(body)).toBe(RFC.body);
  });

  it("can be read by the phone it was encrypted for, and differs every time", async () => {
    const phone = await makePushKeys();
    const text = new TextEncoder().encode("wake");
    const first = await encryptWebPush(text, phone.publicKey, phone.auth);
    const second = await encryptWebPush(text, phone.publicKey, phone.auth);
    expect(new TextDecoder().decode(await phone.decrypt(first))).toBe("wake");
    expect(new TextDecoder().decode(await phone.decrypt(second))).toBe("wake");
    expect(toBase64Url(first)).not.toBe(toBase64Url(second));
  });

  it("cannot be read with another phone's keys, or with the wrong shared secret", async () => {
    const phone = await makePushKeys();
    const other = await makePushKeys();
    const text = new TextEncoder().encode("wake");
    await expect(other.decrypt(await encryptWebPush(text, phone.publicKey, phone.auth))).rejects.toThrow();
    await expect(phone.decrypt(await encryptWebPush(text, phone.publicKey, other.auth))).rejects.toThrow();
  });

  it("refuses keys that are not a point on the curve", async () => {
    const phone = await makePushKeys();
    expect(await importPushKey(phone.publicKey)).not.toBeNull();
    expect(await importPushKey(phone.publicKey.subarray(0, 64))).toBeNull();
    const compressed = phone.publicKey.slice();
    compressed[0] = 0x02;
    expect(await importPushKey(compressed)).toBeNull();
    const offCurve = phone.publicKey.slice();
    offCurve[64] = offCurve[64]! ^ 1;
    expect(await importPushKey(offCurve)).toBeNull();
    await expect(encryptWebPush(new Uint8Array([1]), offCurve, phone.auth)).rejects.toThrow();
    await expect(encryptWebPush(new Uint8Array([1]), phone.publicKey, randomBytes(15))).rejects.toThrow();
  });
});

describe("wake-up addresses", () => {
  it("accepts public https addresses", () => {
    for (const url of [
      "https://ntfy.sh/upA1b2C3d4E5f6?up=1",
      "https://push.example.org/UP?token=abc123",
      "https://updates.push.services.mozilla.com/wpush/v1/gAAAAABl" + "x".repeat(200),
    ]) {
      expect(isPushUrl(url), url).toBe(true);
    }
  });

  it("refuses anything that is not a public https address by name", () => {
    for (const url of [
      "",
      "not a url",
      "http://ntfy.sh/upA1b2C3d4E5f6",
      "ftp://ntfy.sh/up",
      "https://localhost/up",
      "https://intranet/up",
      "https://router.local/up",
      "https://db.internal/up",
      "https://127.0.0.1/up",
      "https://2130706433/up",
      "https://0x7f.0.0.1/up",
      "https://10.0.0.5/up",
      "https://169.254.169.254/latest/meta-data",
      "https://[::1]/up",
      "https://[fd00::1]/up",
      "https://ntfy.sh:8443/up",
      "https://user:secret@ntfy.sh/up",
      "https://ntfy.sh./up",
      "https://ntfy.sh/" + "a".repeat(1100),
    ]) {
      expect(isPushUrl(url), url).toBe(false);
    }
  });
});
