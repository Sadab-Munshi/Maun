// Wire protocol shared by the Worker and the Mailbox Durable Object.
//
// Frames are JSON text. Binary values travel as standard base64.
//
// A connection goes: the relay sends `hello` with a fresh nonce; the client
// answers with `register` (first time) or `login`; the relay answers `ready`.
// Until `ready`, nothing else is accepted.
//
// After `ready`:
//   - the relay sends every waiting message as `msg`; the client answers each
//     with `ack`, and only then is the message deleted;
//   - `put_keys` and `put_otks` publish the pre-keys other people need to start
//     an encrypted session; `get_keys` fetches someone else's;
//   - `send` hands a message to another mailbox, answered by `stored`;
//   - `put_push` tells the relay where to send a wake-up when a message arrives
//     and the phone is not connected; `drop_push` withdraws it.
// Every request carries an `id` that comes back in its answer.
//
// The relay never sees plaintext: `body` is a Signal ciphertext.

/**
 * Bumped whenever a frame changes in a way old clients cannot read. Wake-ups
 * (`put_push`, `drop_push`, and `push` and `queued` in `ready`) were added
 * without a bump: a client that does not know them is not affected.
 */
export const PROTOCOL_VERSION = 2;

/** Largest frame the mailbox will read. Chat envelopes are small; files go to R2. */
export const MAX_FRAME_BYTES = 16_384;

/** Largest ciphertext in one message. Leaves room for the base64 and the rest of the frame. */
export const MAX_BODY_BYTES = 8_192;

/** A Curve25519 public key as libsignal serializes it: one type byte, then the key. */
export const EC_KEY_BYTES = 33;
/** A Kyber-1024 public key as libsignal serializes it: one type byte, then the key. */
export const KYBER_KEY_BYTES = 1569;
export const SIGNATURE_BYTES = 64;

/** One-time pre-keys a mailbox keeps, and how many one frame may add. */
export const MAX_ONE_TIME_KEYS = 100;
export const MAX_ONE_TIME_KEYS_PER_FRAME = 50;

/** Messages a mailbox holds for its owner, in total and from any one sender. */
export const QUEUE_CAP = 1000;
export const QUEUE_CAP_PER_SENDER = 300;
/** A message nobody collects is deleted after this long. */
export const QUEUE_TTL_MS = 30 * 24 * 60 * 60 * 1000;
/** Messages sent to a phone and not yet acknowledged, at most. */
export const DELIVERY_WINDOW = 50;

/** libsignal's message types: a normal message, and one that also carries the key exchange. */
export const KIND_MESSAGE = 2;
export const KIND_PREKEY_MESSAGE = 3;

/**
 * Wake-ups. One is sent when a message arrives and the phone is not connected.
 * No more follow until the phone has logged in, except one every
 * PUSH_REPEAT_MS in case the first was lost. PUSH_DAILY_CAP bounds what a
 * mailbox can make the relay send to an address of its choosing.
 */
export const PUSH_REPEAT_MS = 10 * 60 * 1000;
export const PUSH_DAILY_CAP = 200;
/** How long the push service should keep a wake-up for a phone it cannot reach, in seconds. */
export const PUSH_TTL_SECONDS = 24 * 60 * 60;
/** What a wake-up says once decrypted. It names nobody and carries nothing. */
export const PUSH_PAYLOAD = "wake";
/**
 * Where a push service's operator can reach whoever runs this relay. Sent with
 * every wake-up, as Web Push asks. Change it if you run your own relay.
 */
export const PUSH_CONTACT = "https://github.com/Sadab-Munshi/Maun";
/**
 * A push service may turn away an address it handed out moments ago. Google's
 * answered "gone" for some seconds after creating one, then accepted it. So
 * for PUSH_SETTLE_MS after an address is saved, "gone" is taken to mean "not
 * yet": the wake-up is tried again after each of PUSH_SETTLE_WAITS_MS, and the
 * address is kept whatever the outcome.
 */
export const PUSH_SETTLE_MS = 10 * 60 * 1000;
export const PUSH_SETTLE_WAITS_MS = [5_000, 10_000, 15_000];
/** Recorded as the outcome of a wake-up that has been started and has not finished. */
export const PUSH_UNFINISHED = -1;

export const NONCE_BYTES = 32;

/** A P-256 public key in SubjectPublicKeyInfo form is always this long. */
export const DEVICE_KEY_BYTES = 91;

/** Close code sent to an older socket when the same account logs in again. */
export const CLOSE_REPLACED = 4001;
/** Close code after a failed registration or login. */
export const CLOSE_AUTH_FAILED = 4003;
/** Close code for a socket that did not log in in time. */
export const CLOSE_AUTH_TIMEOUT = 4008;

/**
 * A mailbox ID is the user's raw 32-byte Curve25519 identity public key in
 * lower-case hex. The ID is the address: there is no user table to look up.
 */
const MAILBOX_ID = /^[0-9a-f]{64}$/;

export function isMailboxId(value: string): boolean {
  return MAILBOX_ID.test(value);
}

/** A pre-key signed by its owner's identity key. */
export type SignedKey = { id: number; key: Uint8Array; sig: Uint8Array };
export type OneTimeKey = { id: number; key: Uint8Array };

/** What someone needs to start an encrypted session with a mailbox's owner. */
export type KeyBundle = { regId: number; spk: SignedKey; kpk: SignedKey; otk: OneTimeKey | null };

export type ClientFrame =
  | { t: "register"; deviceKey: Uint8Array; identitySig: Uint8Array; deviceSig: Uint8Array }
  | { t: "login"; sig: Uint8Array }
  | { t: "echo"; id: string; body: string }
  | { t: "put_keys"; id: string; regId: number; spk: SignedKey; kpk: SignedKey }
  | { t: "put_otks"; id: string; keys: OneTimeKey[] }
  | { t: "get_keys"; id: string; of: string }
  | { t: "send"; id: string; to: string; kind: number; body: Uint8Array }
  | { t: "ack"; seq: number }
  /** `key` and `auth` are the Web Push keys the phone's push app handed out. */
  | { t: "put_push"; id: string; url: string; key: Uint8Array; auth: Uint8Array }
  | { t: "drop_push"; id: string };

type WireSignedKey = { id: number; key: string; sig: string };

export type ServerFrame =
  | { t: "hello"; proto: number; nonce: string; registered: boolean }
  /**
   * `keys`: pre-keys are published. `otks`: one-time pre-keys left.
   * `push`: a wake-up address is on file. `queued`: messages waiting right now.
   * `pushStatus`: what the push service answered to the last wake-up sent to
   * that address: an HTTP status, 0 when it could not be reached,
   * PUSH_UNFINISHED when the attempt never finished, null when none was sent.
   * `vapid`: the public key this mailbox signs its wake-ups with. The phone
   * passes it to its push app, for push services that ask who will be calling.
   */
  | {
      t: "ready";
      fresh: boolean;
      keys: boolean;
      otks: number;
      push: boolean;
      pushStatus: number | null;
      vapid: string;
      queued: number;
    }
  | { t: "echo"; id: string; body: string; at: number }
  | { t: "saved"; id: string; otks: number }
  | {
      t: "keys";
      id: string;
      regId: number;
      spk: WireSignedKey;
      kpk: WireSignedKey;
      otk: { id: number; key: string } | null;
    }
  | { t: "stored"; id: string }
  | { t: "failed"; id: string; code: FailureCode }
  | { t: "msg"; seq: number; from: string; kind: number; body: string; at: number }
  | { t: "error"; code: ErrorCode };

/** Why a request could not be carried out. The connection stays open. */
export type FailureCode =
  | "no_account"
  | "no_keys"
  | "mailbox_full"
  | "rate_limited"
  | "self"
  | "unavailable"
  /** The wake-up address or its keys are not ones the relay will use. */
  | "bad_push";

export type ErrorCode =
  | "bad_frame"
  | "too_large"
  | "unknown_type"
  | "not_authenticated"
  | "already_authenticated"
  | "bad_signature"
  | "already_registered"
  | "not_registered";

const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;

export function fromBase64(text: unknown, maxBytes: number): Uint8Array | null {
  if (typeof text !== "string" || text.length === 0) return null;
  if (text.length % 4 !== 0 || text.length > Math.ceil(maxBytes / 3) * 4) return null;
  if (!BASE64.test(text)) return null;
  try {
    const bytes = Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
    return bytes.length <= maxBytes ? bytes : null;
  } catch {
    return null;
  }
}

export function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

export function fromHex(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** Parses a client frame. Returns an error code instead of throwing. */
export function parseClientFrame(raw: string | ArrayBuffer): ClientFrame | ErrorCode {
  if (typeof raw !== "string") return "bad_frame";
  // Length in UTF-16 units is a cheap lower bound; the encoder gives the real size.
  if (raw.length > MAX_FRAME_BYTES || new TextEncoder().encode(raw).length > MAX_FRAME_BYTES) {
    return "too_large";
  }
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return "bad_frame";
  }
  if (typeof value !== "object" || value === null) return "bad_frame";
  const frame = value as Record<string, unknown>;
  if (typeof frame.t !== "string") return "bad_frame";

  switch (frame.t) {
    case "register": {
      const deviceKey = fromBase64(frame.deviceKey, DEVICE_KEY_BYTES);
      const identitySig = fromBase64(frame.identitySig, 64);
      // An ASN.1 DER P-256 signature is at most 72 bytes.
      const deviceSig = fromBase64(frame.deviceSig, 72);
      if (!deviceKey || deviceKey.length !== DEVICE_KEY_BYTES) return "bad_frame";
      if (!identitySig || identitySig.length !== 64 || !deviceSig) return "bad_frame";
      return { t: "register", deviceKey, identitySig, deviceSig };
    }
    case "login": {
      const sig = fromBase64(frame.sig, 72);
      if (!sig) return "bad_frame";
      return { t: "login", sig };
    }
    case "echo":
      if (typeof frame.id !== "string" || typeof frame.body !== "string") return "bad_frame";
      return { t: "echo", id: frame.id, body: frame.body };
    case "put_keys": {
      const spk = signedKey(frame.spk, EC_KEY_BYTES);
      const kpk = signedKey(frame.kpk, KYBER_KEY_BYTES);
      if (!isRequestId(frame.id) || !isRegistrationId(frame.regId) || !spk || !kpk) return "bad_frame";
      return { t: "put_keys", id: frame.id, regId: frame.regId, spk, kpk };
    }
    case "put_otks": {
      if (!isRequestId(frame.id) || !Array.isArray(frame.keys)) return "bad_frame";
      if (frame.keys.length === 0 || frame.keys.length > MAX_ONE_TIME_KEYS_PER_FRAME) return "bad_frame";
      const keys: OneTimeKey[] = [];
      for (const entry of frame.keys) {
        if (typeof entry !== "object" || entry === null) return "bad_frame";
        const { id, key } = entry as Record<string, unknown>;
        const bytes = fromBase64(key, EC_KEY_BYTES);
        if (!isKeyId(id) || !bytes || bytes.length !== EC_KEY_BYTES) return "bad_frame";
        keys.push({ id, key: bytes });
      }
      return { t: "put_otks", id: frame.id, keys };
    }
    case "get_keys":
      if (!isRequestId(frame.id) || typeof frame.of !== "string" || !isMailboxId(frame.of)) return "bad_frame";
      return { t: "get_keys", id: frame.id, of: frame.of };
    case "send": {
      if (!isRequestId(frame.id) || typeof frame.to !== "string" || !isMailboxId(frame.to)) return "bad_frame";
      if (frame.kind !== KIND_MESSAGE && frame.kind !== KIND_PREKEY_MESSAGE) return "bad_frame";
      const body = fromBase64(frame.body, MAX_BODY_BYTES);
      if (!body) return "bad_frame";
      return { t: "send", id: frame.id, to: frame.to, kind: frame.kind, body };
    }
    case "ack":
      if (typeof frame.seq !== "number" || !Number.isSafeInteger(frame.seq) || frame.seq <= 0) return "bad_frame";
      return { t: "ack", seq: frame.seq };
    case "put_push": {
      // Only the shape is checked here. Whether the address is acceptable is
      // answered with a reason, so the app can tell the user.
      const key = fromBase64(frame.key, 128);
      const auth = fromBase64(frame.auth, 128);
      if (!isRequestId(frame.id) || typeof frame.url !== "string" || !key || !auth) return "bad_frame";
      return { t: "put_push", id: frame.id, url: frame.url, key, auth };
    }
    case "drop_push":
      if (!isRequestId(frame.id)) return "bad_frame";
      return { t: "drop_push", id: frame.id };
    default:
      return "unknown_type";
  }
}

const REQUEST_ID = /^[A-Za-z0-9_-]{1,64}$/;

function isRequestId(value: unknown): value is string {
  return typeof value === "string" && REQUEST_ID.test(value);
}

/** libsignal numbers pre-keys with 24-bit IDs, starting at 1. */
function isKeyId(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= 0xffffff;
}

/** libsignal registration IDs are 14 bits. */
function isRegistrationId(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= 0x3fff;
}

function signedKey(value: unknown, keyBytes: number): SignedKey | null {
  if (typeof value !== "object" || value === null) return null;
  const { id, key, sig } = value as Record<string, unknown>;
  const keyData = fromBase64(key, keyBytes);
  const signature = fromBase64(sig, SIGNATURE_BYTES);
  if (!isKeyId(id) || !keyData || keyData.length !== keyBytes) return null;
  if (!signature || signature.length !== SIGNATURE_BYTES) return null;
  return { id, key: keyData, sig: signature };
}

const REGISTER_LABEL = new TextEncoder().encode("maun:register:v1\n");
const LOGIN_LABEL = new TextEncoder().encode("maun:login:v1\n");

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

/**
 * What both keys sign to create an account: it ties this identity, this device
 * key and this connection's nonce together. Every field before the device key
 * has a fixed length, so the layout cannot be read two ways.
 */
export function registerTranscript(identity: Uint8Array, nonce: Uint8Array, deviceKey: Uint8Array): Uint8Array {
  return concat(REGISTER_LABEL, identity, nonce, deviceKey);
}

/** What the device key signs to log in on this connection. */
export function loginTranscript(identity: Uint8Array, nonce: Uint8Array): Uint8Array {
  return concat(LOGIN_LABEL, identity, nonce);
}
