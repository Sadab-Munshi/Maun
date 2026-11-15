import { describe, expect, it } from "vitest";
import { fromBase64, loginTranscript, parseClientFrame, registerTranscript, toBase64 } from "../src/protocol";

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return [...digest].map((b) => b.toString(16).padStart(2, "0")).join("");
}

describe("signed transcripts", () => {
  const identity = new Uint8Array(32).fill(1);
  const nonce = new Uint8Array(32).fill(2);
  const deviceKey = new Uint8Array(91).fill(3);

  // The same inputs and digests are pinned in the app's AuthTranscriptTest.kt.
  // If either side changes the layout, one of the two tests fails.
  it("layout matches the app", async () => {
    expect(await sha256Hex(registerTranscript(identity, nonce, deviceKey))).toBe(
      "5ce8e4a95844ed9fe43cf0148e371125e1516c6b610b75c94f900abbf3d2145f",
    );
    expect(await sha256Hex(loginTranscript(identity, nonce))).toBe(
      "d4ac6228d3fb0b55b4d37dfbcb508eeaede2eb95621b518af1fbf07cc7243b44",
    );
  });

  it("starts with a label that tells the two apart", () => {
    const text = (bytes: Uint8Array) => new TextDecoder().decode(bytes);
    expect(text(registerTranscript(identity, nonce, deviceKey).subarray(0, 17))).toBe("maun:register:v1\n");
    expect(text(loginTranscript(identity, nonce).subarray(0, 14))).toBe("maun:login:v1\n");
    expect(registerTranscript(identity, nonce, deviceKey).length).toBe(17 + 32 + 32 + 91);
    expect(loginTranscript(identity, nonce).length).toBe(14 + 32 + 32);
  });
});

describe("base64", () => {
  it("round-trips", () => {
    const bytes = crypto.getRandomValues(new Uint8Array(91));
    expect(fromBase64(toBase64(bytes), 91)).toEqual(bytes);
  });

  it("rejects anything that is not plain base64 of the allowed size", () => {
    expect(fromBase64("", 8)).toBeNull();
    expect(fromBase64(7, 8)).toBeNull();
    expect(fromBase64("AAA", 8)).toBeNull(); // not a multiple of four
    expect(fromBase64("AA-_", 8)).toBeNull(); // URL-safe alphabet
    expect(fromBase64("AA A", 8)).toBeNull();
    expect(fromBase64("A===", 8)).toBeNull();
    expect(fromBase64(toBase64(new Uint8Array(9)), 8)).toBeNull();
    expect(fromBase64(toBase64(new Uint8Array(8)), 8)).toEqual(new Uint8Array(8));
  });
});

describe("frame parsing", () => {
  const key = toBase64(new Uint8Array(91));
  const sig64 = toBase64(new Uint8Array(64));

  it("reads a registration", () => {
    const frame = parseClientFrame(JSON.stringify({ t: "register", deviceKey: key, identitySig: sig64, deviceSig: sig64 }));
    expect(frame).toMatchObject({ t: "register" });
  });

  it("insists on exact key and identity-signature sizes", () => {
    const parse = (frame: unknown) => parseClientFrame(JSON.stringify(frame));
    const short = toBase64(new Uint8Array(90));
    expect(parse({ t: "register", deviceKey: short, identitySig: sig64, deviceSig: sig64 })).toBe("bad_frame");
    expect(parse({ t: "register", deviceKey: key, identitySig: toBase64(new Uint8Array(63)), deviceSig: sig64 })).toBe(
      "bad_frame",
    );
    expect(parse({ t: "register", deviceKey: key, identitySig: sig64, deviceSig: toBase64(new Uint8Array(73)) })).toBe(
      "bad_frame",
    );
    expect(parse({ t: "login" })).toBe("bad_frame");
    expect(parse({ t: "login", sig: toBase64(new Uint8Array(73)) })).toBe("bad_frame");
    expect(parse([])).toBe("bad_frame");
    expect(parse(null)).toBe("bad_frame");
  });
});
