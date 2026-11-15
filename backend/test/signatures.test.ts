import { describe, expect, it } from "vitest";
import {
  derToP1363,
  montgomeryToEdwards,
  verifyDeviceSignature,
  verifyIdentitySignature,
} from "../src/crypto/signatures";
import fixtures from "./fixtures/signatures.json";

function b64(text: string): Uint8Array {
  return Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
}

function flipBit(bytes: Uint8Array, index: number): Uint8Array {
  const copy = bytes.slice();
  copy[index] = copy[index]! ^ 0x01;
  return copy;
}

describe("identity key signatures (libsignal XEdDSA)", () => {
  it("accepts every signature libsignal produced", async () => {
    expect(fixtures.identity.length).toBeGreaterThan(0);
    for (const v of fixtures.identity) {
      expect(await verifyIdentitySignature(b64(v.publicKey), b64(v.message), b64(v.signature))).toBe(true);
    }
  });

  it("covers both values of the sign bit", () => {
    const bits = new Set(fixtures.identity.map((v) => b64(v.signature)[63]! >> 7));
    expect(bits).toEqual(new Set([0, 1]));
  });

  it("rejects a changed message, signature or key", async () => {
    for (const v of fixtures.identity) {
      const key = b64(v.publicKey);
      const message = b64(v.message);
      const signature = b64(v.signature);
      expect(await verifyIdentitySignature(key, new Uint8Array([...message, 0]), signature)).toBe(false);
      expect(await verifyIdentitySignature(key, message, flipBit(signature, 0))).toBe(false);
      expect(await verifyIdentitySignature(key, message, flipBit(signature, 40))).toBe(false);
      // Flipping the carried sign bit selects the other Edwards point.
      const otherSign = signature.slice();
      otherSign[63] = otherSign[63]! ^ 0x80;
      expect(await verifyIdentitySignature(key, message, otherSign)).toBe(false);
      expect(await verifyIdentitySignature(flipBit(key, 3), message, signature)).toBe(false);
    }
  });

  it("rejects a signature made by a different key", async () => {
    const [a, b] = fixtures.identity;
    expect(await verifyIdentitySignature(b64(b!.publicKey), b64(a!.message), b64(a!.signature))).toBe(false);
  });

  it("rejects wrong lengths without throwing", async () => {
    const v = fixtures.identity[0]!;
    expect(await verifyIdentitySignature(b64(v.publicKey).subarray(1), b64(v.message), b64(v.signature))).toBe(false);
    expect(await verifyIdentitySignature(b64(v.publicKey), b64(v.message), b64(v.signature).subarray(1))).toBe(false);
    expect(await verifyIdentitySignature(new Uint8Array(0), new Uint8Array(0), new Uint8Array(0))).toBe(false);
  });

  it("has no Edwards form for u = -1", () => {
    // p - 1, little-endian
    const minusOne = new Uint8Array(32).fill(0xff);
    minusOne[0] = 0xec;
    minusOne[31] = 0x7f;
    expect(montgomeryToEdwards(minusOne, 0)).toBeNull();
  });

  it("maps the Curve25519 base point to the Ed25519 base point", () => {
    const u = new Uint8Array(32);
    u[0] = 9;
    const hex = [...montgomeryToEdwards(u, 0)!].map((b) => b.toString(16).padStart(2, "0")).join("");
    expect(hex).toBe("5866666666666666666666666666666666666666666666666666666666666666");
  });
});

describe("device key signatures (P-256, DER)", () => {
  it("accepts every signature the JDK produced", async () => {
    expect(fixtures.device.length).toBeGreaterThan(0);
    for (const v of fixtures.device) {
      expect(await verifyDeviceSignature(b64(v.spki), b64(v.message), b64(v.signature))).toBe(true);
    }
  });

  it("rejects a changed message, signature or key", async () => {
    for (const v of fixtures.device) {
      const spki = b64(v.spki);
      const message = b64(v.message);
      const signature = b64(v.signature);
      expect(await verifyDeviceSignature(spki, new Uint8Array([...message, 0]), signature)).toBe(false);
      expect(await verifyDeviceSignature(spki, message, flipBit(signature, signature.length - 1))).toBe(false);
      expect(await verifyDeviceSignature(flipBit(spki, spki.length - 1), message, signature)).toBe(false);
    }
  });

  it("rejects a signature made by a different key", async () => {
    const [a, b] = fixtures.device;
    expect(await verifyDeviceSignature(b64(b!.spki), b64(a!.message), b64(a!.signature))).toBe(false);
  });

  it("rejects garbage keys and signatures without throwing", async () => {
    const v = fixtures.device[0]!;
    expect(await verifyDeviceSignature(new Uint8Array(10), b64(v.message), b64(v.signature))).toBe(false);
    expect(await verifyDeviceSignature(b64(v.spki), b64(v.message), new Uint8Array(64))).toBe(false);
    expect(await verifyDeviceSignature(b64(v.spki), b64(v.message), new Uint8Array(0))).toBe(false);
  });
});

describe("derToP1363", () => {
  const hex = (s: string) => Uint8Array.from(s.match(/../g)!.map((h) => parseInt(h, 16)));
  const r = "11".repeat(32);
  const s = "22".repeat(32);

  it("converts a plain signature", () => {
    expect(derToP1363(hex(`3044 0220${r} 0220${s}`.replace(/ /g, "")))).toEqual(hex(r + s));
  });

  it("strips the sign-padding zero and left-pads short integers", () => {
    const high = "80" + "11".repeat(31);
    const der = hex(`3026 022100${high} 0201 05`.replace(/ /g, ""));
    const expected = hex(high + "00".repeat(31) + "05");
    expect(derToP1363(der)).toEqual(expected);
  });

  it("rejects malformed encodings", () => {
    const bad = [
      `3044 0220${r} 0220${s} 00`, // trailing byte
      `3045 0220${r} 0220${s}`, // wrong outer length
      `3144 0220${r} 0220${s}`, // not a sequence
      `3044 0320${r} 0220${s}`, // not an integer
      `3044 0220${"80" + "11".repeat(31)} 0220${s}`, // negative r
      `3045 022100${"11".repeat(32)} 0220${s}`, // needless leading zero
      `3024 0220${r} 0200`, // empty s
      `3046 0222${"01" + "00".repeat(33)} 0220${s}`, // r too long
      `3023 0220${r}`, // s missing
    ];
    for (const b of bad) expect(derToP1363(hex(b.replace(/ /g, ""))), b).toBeNull();
  });
});
