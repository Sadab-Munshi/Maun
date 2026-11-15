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
//   - `turn` asks for the servers a call is carried through. The call itself
//     is set up with ordinary encrypted messages, sent `urgent`.
//   - `device` reports which phone this is (a hash and the model), which a
//     member-only relay keeps next to the member's name.
// Every request carries an `id` that comes back in its answer.
//
// A relay can be member-only. `hello` then says `access: true`, and an ID the
// relay has not seen before must bring a ticket (`access` in `register` or
// `login`): proof, from the relay's own email login, of an email address. The
// address is tied to the ID once; after that the ID's own signature is
// enough. A new member is closed with CLOSE_PENDING until the administrator
// approves them, a banned one with CLOSE_BANNED. Members have usernames and
// can look each other up with `find`.
//
// Files do not travel in frames. `ready` hands out a `files` token; with it
// the phone may POST an encrypted file to /v1/files and GET /v1/files/<key>.
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
/** A message marked urgent (a call that is ringing) may wake the phone again this soon after the last wake-up. */
export const PUSH_URGENT_REPEAT_MS = 10 * 1000;
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

/** Largest encrypted file the relay stores: 25 MB and the encryption's own overhead. */
export const MAX_FILE_BYTES = 25_000_000 + 65_536;
/** A file is refused, and deleted, once it is this old. */
export const FILE_TTL_MS = 24 * 60 * 60 * 1000;
/** What one account may upload in a day. */
export const FILE_DAILY_BYTES = 250_000_000;
export const FILE_DAILY_COUNT = 200;
/** The key of a stored file: 32 random bytes in lower-case hex. */
const FILE_KEY = /^[0-9a-f]{64}$/;
export function isFileKey(value: string): boolean {
  return FILE_KEY.test(value);
}

/** A username: 3 to 20 lower-case letters, digits or underscores, starting with a letter. */
const USERNAME = /^[a-z][a-z0-9_]{2,19}$/;
const RESERVED_NAMES = new Set(["admin", "administrator", "maun", "support", "system", "relay", "moderator"]);
export function isUsername(value: string): boolean {
  return USERNAME.test(value) && !RESERVED_NAMES.has(value);
}

/** How long a member's standing is trusted before the member list is asked again. */
export const MEMBER_CHECK_MS = 60 * 1000;

/** Close code sent to an older socket when the same account logs in again. */
export const CLOSE_REPLACED = 4001;
/** Close code after a failed registration or login. */
export const CLOSE_AUTH_FAILED = 4003;
/** Close code for a socket that did not log in in time. */
export const CLOSE_AUTH_TIMEOUT = 4008;
/** Close code on a member-only relay for an ID that has not been through the email login. */
export const CLOSE_ACCESS_REQUIRED = 4401;
/** Close code for a member who is waiting for the administrator's approval. */
export const CLOSE_PENDING = 4402;
/** Close code for a member the relay's owner has banned. */
export const CLOSE_BANNED = 4403;

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
  /** `access`: a ticket from the email login, for a member-only relay that does not know this ID yet. */
  /** `dev`: which phone this is. A member-only relay looks at it before letting the phone in. */
  | { t: "register"; deviceKey: Uint8Array; identitySig: Uint8Array; deviceSig: Uint8Array; access: string | null; dev: DeviceReport | null }
  | { t: "login"; sig: Uint8Array; access: string | null; dev: DeviceReport | null }
  | { t: "echo"; id: string; body: string }
  | { t: "put_keys"; id: string; regId: number; spk: SignedKey; kpk: SignedKey }
  | { t: "put_otks"; id: string; keys: OneTimeKey[] }
  | { t: "get_keys"; id: string; of: string }
  /** `urgent`: wake the other phone at once, even if it was woken a moment ago. For a call that is ringing. */
  | { t: "send"; id: string; to: string; kind: number; body: Uint8Array; urgent: boolean }
  /** Asks for the servers a call goes through. */
  | { t: "turn"; id: string }
  | { t: "ack"; seq: number }
  /** `key` and `auth` are the Web Push keys the phone's push app handed out. */
  | { t: "put_push"; id: string; url: string; key: Uint8Array; auth: Uint8Array }
  | { t: "drop_push"; id: string }
  /** Looks a member up, by username (`name`) or by ID (`user`). Member-only relays. */
  | { t: "find"; id: string; name: string | null; user: string | null }
  /** `hash`: SHA-256 of what the phone says about itself, in hex. */
  | ({ t: "device"; id: string } & DeviceReport);

/**
 * What a phone says about itself. `hash` is a SHA-256 of everything it can
 * tell (it changes with a system update); `did` is one of the things that
 * stay the same, and is what a ban on a phone goes by. `app` is the app's
 * version.
 */
export type DeviceReport = { hash: string; did: string | null; maker: string; model: string; os: string; app: string | null };

type WireSignedKey = { id: number; key: string; sig: string };

export type ServerFrame =
  /** `access`: this relay is member-only. */
  | { t: "hello"; proto: number; nonce: string; registered: boolean; access: boolean }
  /**
   * `keys`: pre-keys are published. `otks`: one-time pre-keys left.
   * `push`: a wake-up address is on file. `queued`: messages waiting right now.
   * `pushStatus`: what the push service answered to the last wake-up sent to
   * that address: an HTTP status, 0 when it could not be reached,
   * PUSH_UNFINISHED when the attempt never finished, null when none was sent.
   * `vapid`: the public key this mailbox signs its wake-ups with. The phone
   * passes it to its push app, for push services that ask who will be calling.
   * `email` and `name`: on a member-only relay, the address this ID is tied to and its username.
   * `files`: the token for uploading and downloading files until the next login.
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
      email: string | null;
      name: string | null;
      files: string;
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
  | { t: "found"; id: string; user: string; name: string }
  | { t: "failed"; id: string; code: FailureCode }
  /** `age`: how long the message has been waiting, in milliseconds. A call that rang a minute ago is over. */
  | { t: "msg"; seq: number; from: string; kind: number; body: string; at: number; age: number }
  /** Servers for one call, good for `ttl` seconds. */
  | { t: "ice"; id: string; servers: { urls: string[]; username?: string; credential?: string }[]; ttl: number }
  | { t: "error"; code: ErrorCode };

/** Why a request could not be carried out. The connection stays open. */
export type FailureCode =
  | "no_account"
  | "no_keys"
  | "mailbox_full"
  | "rate_limited"
  | "self"
  | "unavailable"
  /** Nobody has that username, or the relay keeps no usernames. */
  | "no_such_user"
  /** This relay has no calls set up, or the call service refused. */
  | "no_calls"
  /** The ID written to is no longer anybody's: its owner set Maun up again, or left. */
  | "gone"
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
  | "not_registered"
  /** Member-only relay: go through the email login and come back with the ticket. */
  | "access_required"
  /** Member-only relay: the administrator has not approved this member yet. */
  | "pending"
  | "banned";

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
      const access = accessToken(frame.access);
      const dev = frame.dev === undefined || frame.dev === null ? null : deviceReport(frame.dev);
      if (access === undefined || dev === undefined) return "bad_frame";
      return { t: "register", deviceKey, identitySig, deviceSig, access, dev };
    }
    case "login": {
      const sig = fromBase64(frame.sig, 72);
      if (!sig) return "bad_frame";
      const access = accessToken(frame.access);
      const dev = frame.dev === undefined || frame.dev === null ? null : deviceReport(frame.dev);
      if (access === undefined || dev === undefined) return "bad_frame";
      return { t: "login", sig, access, dev };
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
      return { t: "send", id: frame.id, to: frame.to, kind: frame.kind, body, urgent: frame.urgent === true };
    }
    case "turn":
      if (!isRequestId(frame.id)) return "bad_frame";
      return { t: "turn", id: frame.id };
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
    case "find": {
      if (!isRequestId(frame.id)) return "bad_frame";
      const name = typeof frame.name === "string" ? frame.name.trim().toLowerCase().replace(/^@/, "") : null;
      const user = typeof frame.user === "string" ? frame.user : null;
      if (name !== null ? !isUsername(name) || user !== null : user === null || !isMailboxId(user)) return "bad_frame";
      return { t: "find", id: frame.id, name, user };
    }
    case "device": {
      const report = deviceReport(frame);
      if (!isRequestId(frame.id) || !report) return "bad_frame";
      return { t: "device", id: frame.id, ...report };
    }
    default:
      return "unknown_type";
  }
}

const HASH = /^[0-9a-f]{64}$/;

/** Reads a device report. Undefined when it is not one. */
function deviceReport(value: unknown): DeviceReport | undefined {
  if (typeof value !== "object" || value === null) return undefined;
  const report = value as Record<string, unknown>;
  if (typeof report.hash !== "string" || !HASH.test(report.hash)) return undefined;
  const maker = shortText(report.maker);
  const model = shortText(report.model);
  const os = shortText(report.os);
  if (maker === null || model === null || os === null) return undefined;
  const did = report.did === undefined || report.did === null ? null : report.did;
  if (did !== null && (typeof did !== "string" || !HASH.test(did))) return undefined;
  const app = report.app === undefined || report.app === null ? null : shortText(report.app);
  if (report.app !== undefined && report.app !== null && app === null) return undefined;
  return { hash: report.hash, did, maker, model, os, app };
}

/** A token, null when none was sent, undefined when what was sent cannot be one. */
function accessToken(value: unknown): string | null | undefined {
  if (value === undefined || value === null) return null;
  return typeof value === "string" && value.length > 0 && value.length <= 4096 ? value : undefined;
}

/** A make or model name: short, and without control characters. */
function shortText(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 64) return null;
  // eslint-disable-next-line no-control-regex
  return /[\u0000-\u001f\u007f]/.test(value) ? null : value;
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
