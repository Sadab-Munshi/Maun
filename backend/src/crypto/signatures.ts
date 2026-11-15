// Signature checks used at registration and login.
//
// Two kinds of key sign things in Maun:
//  - the identity key: Curve25519, signed by libsignal with its XEdDSA scheme;
//  - the device key: P-256, created inside Android Keystore, signed with
//    SHA256withECDSA.
//
// Both are verified with the runtime's native WebCrypto, so the work in
// JavaScript is a little byte shuffling and one modular inversion.

const P = (1n << 255n) - 19n;

function modPow(base: bigint, exponent: bigint): bigint {
  let result = 1n;
  let b = ((base % P) + P) % P;
  let e = exponent;
  while (e > 0n) {
    if (e & 1n) result = (result * b) % P;
    b = (b * b) % P;
    e >>= 1n;
  }
  return result;
}

function bytesToBigIntLE(bytes: Uint8Array): bigint {
  let value = 0n;
  for (let i = bytes.length - 1; i >= 0; i--) value = (value << 8n) | BigInt(bytes[i]!);
  return value;
}

function bigIntToBytesLE(value: bigint, length: number): Uint8Array {
  const out = new Uint8Array(length);
  let v = value;
  for (let i = 0; i < length; i++) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return out;
}

/**
 * Converts a Curve25519 (Montgomery) public key to the Ed25519 point libsignal
 * signs with. `sign` is the x-coordinate sign bit, which libsignal carries in
 * the top bit of the signature. Returns null for a key with no Edwards form.
 */
export function montgomeryToEdwards(publicKey: Uint8Array, sign: number): Uint8Array | null {
  if (publicKey.length !== 32) return null;
  const masked = publicKey.slice();
  masked[31] = masked[31]! & 0x7f; // Curve25519 ignores the top bit of u.
  const u = bytesToBigIntLE(masked) % P;
  const denominator = (u + 1n) % P;
  if (denominator === 0n) return null;
  // y = (u - 1) / (u + 1)
  const y = (((u - 1n + P) % P) * modPow(denominator, P - 2n)) % P;
  const out = bigIntToBytesLE(y, 32);
  out[31] = out[31]! | ((sign & 1) << 7);
  return out;
}

/**
 * Verifies a libsignal identity-key signature (`ECPrivateKey.calculateSignature`).
 * `publicKey` is the raw 32-byte key, without libsignal's 0x05 type byte.
 */
export async function verifyIdentitySignature(
  publicKey: Uint8Array,
  message: Uint8Array,
  signature: Uint8Array,
): Promise<boolean> {
  if (publicKey.length !== 32 || signature.length !== 64) return false;
  const edwards = montgomeryToEdwards(publicKey, signature[63]! >> 7);
  if (!edwards) return false;
  const plain = signature.slice();
  plain[63] = plain[63]! & 0x7f;
  try {
    const key = await crypto.subtle.importKey("raw", edwards, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, plain, message);
  } catch {
    return false;
  }
}

/**
 * Converts an ASN.1 DER ECDSA signature (what Java and Android Keystore
 * produce) to the fixed 64-byte r||s form WebCrypto expects. Strict: anything
 * that is not exactly SEQUENCE { INTEGER r, INTEGER s } is rejected.
 */
export function derToP1363(der: Uint8Array): Uint8Array | null {
  // P-256 signatures are at most 72 bytes, so every length fits in one byte.
  if (der.length < 8 || der.length > 72) return null;
  if (der[0] !== 0x30 || der[1] !== der.length - 2) return null;

  const out = new Uint8Array(64);
  let offset = 2;
  for (let part = 0; part < 2; part++) {
    if (der[offset] !== 0x02) return null;
    const length = der[offset + 1];
    if (length === undefined || length === 0 || length > 33) return null;
    const start = offset + 2;
    const end = start + length;
    if (end > der.length) return null;
    let digits = der.subarray(start, end);
    if (digits[0]! & 0x80) return null; // negative
    if (digits[0] === 0) {
      // A leading zero is only allowed when it keeps the number positive.
      if (digits.length > 1 && !(digits[1]! & 0x80)) return null;
      digits = digits.subarray(1);
    }
    if (digits.length > 32) return null;
    out.set(digits, part * 32 + (32 - digits.length));
    offset = end;
  }
  return offset === der.length ? out : null;
}

/**
 * Verifies a device-key signature. `spki` is the public key as Android hands it
 * out (`PublicKey.getEncoded()`); `derSignature` is SHA256withECDSA output.
 */
export async function verifyDeviceSignature(
  spki: Uint8Array,
  message: Uint8Array,
  derSignature: Uint8Array,
): Promise<boolean> {
  const signature = derToP1363(derSignature);
  if (!signature) return false;
  try {
    const key = await crypto.subtle.importKey(
      "spki",
      spki,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, signature, message);
  } catch {
    return false;
  }
}
