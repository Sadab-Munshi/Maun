// Web Push message encryption (RFC 8291, with the aes128gcm coding of RFC 8188).
//
// A phone's push app gives it a public key and a shared secret. Whatever is
// sent to the push service is encrypted to those, so the push service carries
// bytes it cannot read. Maun only ever sends a fixed wake-up word, but
// UnifiedPush requires the encryption, and it keeps even that word private.

/** An uncompressed P-256 point: 0x04, then X and Y. */
export const PUSH_KEY_BYTES = 65;
export const PUSH_AUTH_BYTES = 16;
export const MAX_PUSH_URL_LENGTH = 1024;

const SALT_BYTES = 16;
/** Record size announced in the header. One record is all a wake-up needs. */
const RECORD_SIZE = 4096;
const P256 = { name: "ECDH", namedCurve: "P-256" } as const;
const encoder = new TextEncoder();

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

async function hkdf(salt: Uint8Array, secret: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
  const key = await crypto.subtle.importKey("raw", secret, "HKDF", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt, info }, key, length * 8);
  return new Uint8Array(bits);
}

/** Reads a phone's push public key, or returns null when it is not a point on the curve. */
export async function importPushKey(key: Uint8Array): Promise<CryptoKey | null> {
  if (key.length !== PUSH_KEY_BYTES || key[0] !== 0x04) return null;
  try {
    return await crypto.subtle.importKey("raw", key, P256, false, []);
  } catch {
    return null;
  }
}

/** Fixed inputs, for checking against the worked example in the RFC. Never set in production. */
export type FixedInputs = { salt: Uint8Array; senderKeys: CryptoKeyPair };

/**
 * Encrypts `plaintext` for the phone that owns `receiverKey` and `auth`.
 * Returns the request body to POST with `Content-Encoding: aes128gcm`.
 * Throws when the key is not usable.
 */
export async function encryptWebPush(
  plaintext: Uint8Array,
  receiverKey: Uint8Array,
  auth: Uint8Array,
  fixed?: FixedInputs,
): Promise<Uint8Array> {
  const receiver = await importPushKey(receiverKey);
  if (!receiver || auth.length !== PUSH_AUTH_BYTES) throw new Error("unusable push key");
  if (plaintext.length > RECORD_SIZE - 17) throw new Error("push message too long");

  // A new key pair and salt for every message.
  const sender = fixed?.senderKeys ?? ((await crypto.subtle.generateKey(P256, true, ["deriveBits"])) as CryptoKeyPair);
  const salt = fixed?.salt ?? crypto.getRandomValues(new Uint8Array(SALT_BYTES));
  const senderPublic = new Uint8Array((await crypto.subtle.exportKey("raw", sender.publicKey)) as ArrayBuffer);

  // The runtime reads the other side's key from `public`; its type
  // declarations spell the field differently, hence the cast.
  const ecdh = { name: "ECDH", public: receiver } as unknown as SubtleCryptoDeriveKeyAlgorithm;
  const shared = new Uint8Array(await crypto.subtle.deriveBits(ecdh, sender.privateKey, 256));
  // RFC 8291 section 3.4: mix in the shared secret and both public keys.
  const keyInfo = concat(encoder.encode("WebPush: info\0"), receiverKey, senderPublic);
  const material = await hkdf(auth, shared, keyInfo, 32);
  // RFC 8188 section 2.2: the content key and nonce for the single record.
  const contentKey = await hkdf(salt, material, encoder.encode("Content-Encoding: aes128gcm\0"), 16);
  const nonce = await hkdf(salt, material, encoder.encode("Content-Encoding: nonce\0"), 12);

  const aes = await crypto.subtle.importKey("raw", contentKey, "AES-GCM", false, ["encrypt"]);
  // 0x02 marks the last (here: only) record.
  const record = concat(plaintext, new Uint8Array([0x02]));
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, aes, record));

  const header = new Uint8Array(SALT_BYTES + 4 + 1 + senderPublic.length);
  header.set(salt, 0);
  new DataView(header.buffer).setUint32(SALT_BYTES, RECORD_SIZE);
  header[SALT_BYTES + 4] = senderPublic.length;
  header.set(senderPublic, SALT_BYTES + 5);
  return concat(header, sealed);
}

const PRIVATE_SUFFIXES = [".local", ".localhost", ".internal", ".lan", ".home.arpa", ".test", ".invalid", ".example"];

/**
 * Whether the relay is willing to send wake-ups to this address. It must be a
 * public https address by name: the relay will not be pointed at a private
 * network, a bare IP address or an unusual port.
 */
export function isPushUrl(text: string): boolean {
  if (text.length === 0 || text.length > MAX_PUSH_URL_LENGTH) return false;
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return false;
  }
  if (url.protocol !== "https:" || url.username !== "" || url.password !== "" || url.port !== "") return false;
  const host = url.hostname.toLowerCase();
  if (!host.includes(".") || host.endsWith(".") || host.startsWith("[")) return false;
  // The URL parser has already turned every spelling of an IPv4 address into digits and dots.
  if (/^[0-9.]+$/.test(host)) return false;
  return !PRIVATE_SUFFIXES.some((suffix) => host.endsWith(suffix));
}

// ---- Saying who is calling (VAPID, RFC 8292) --------------------------------
//
// Some push services take a wake-up only from the server the phone named when
// it asked for its address. The phone names a public key; the server signs a
// short-lived token with the private half and sends it along.

const ES256 = { name: "ECDSA", namedCurve: "P-256" } as const;
/** How long a token is good for. The RFC allows up to a day. */
const VAPID_LIFETIME_SECONDS = 12 * 60 * 60;

export type VapidKeys = {
  /** The public key as the phone passes it on: an uncompressed P-256 point, 65 bytes. */
  publicKey: Uint8Array;
  /** The private key, in the JSON form WebCrypto exports. */
  privateJwk: string;
};

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function createVapidKeys(): Promise<VapidKeys> {
  const pair = (await crypto.subtle.generateKey(ES256, true, ["sign", "verify"])) as CryptoKeyPair;
  const publicKey = new Uint8Array((await crypto.subtle.exportKey("raw", pair.publicKey)) as ArrayBuffer);
  const privateJwk = JSON.stringify(await crypto.subtle.exportKey("jwk", pair.privateKey));
  return { publicKey, privateJwk };
}

/**
 * Builds the `Authorization` header for a wake-up sent to `url`. `contact` is
 * where the push service's operator can reach whoever runs this relay.
 */
export async function vapidAuthorization(
  url: string,
  keys: VapidKeys,
  contact: string,
  nowMillis: number,
): Promise<string> {
  const header = base64Url(encoder.encode(JSON.stringify({ typ: "JWT", alg: "ES256" })));
  const claims = base64Url(
    encoder.encode(
      JSON.stringify({
        aud: new URL(url).origin,
        exp: Math.floor(nowMillis / 1000) + VAPID_LIFETIME_SECONDS,
        sub: contact,
      }),
    ),
  );
  const key = await crypto.subtle.importKey("jwk", JSON.parse(keys.privateJwk) as JsonWebKey, ES256, false, ["sign"]);
  const signature = new Uint8Array(
    await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, encoder.encode(`${header}.${claims}`)),
  );
  return `vapid t=${header}.${claims}.${base64Url(signature)}, k=${base64Url(keys.publicKey)}`;
}
