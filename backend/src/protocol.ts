// Wire protocol shared by the Worker and the Mailbox Durable Object.
//
// Frames are JSON text. Binary values travel as standard base64.
//
// A connection goes: the relay sends `hello` with a fresh nonce; the client
// answers with `register` (first time) or `login`; the relay answers `ready`.
// Until `ready`, nothing else is accepted.

/** Bumped whenever a frame changes in a way old clients cannot read. */
export const PROTOCOL_VERSION = 1;

/** Largest frame the mailbox will read. Chat envelopes are small; files go to R2. */
export const MAX_FRAME_BYTES = 4096;

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

export type ClientFrame =
  | { t: "register"; deviceKey: Uint8Array; identitySig: Uint8Array; deviceSig: Uint8Array }
  | { t: "login"; sig: Uint8Array }
  | { t: "echo"; id: string; body: string };

export type ServerFrame =
  | { t: "hello"; proto: number; nonce: string; registered: boolean }
  | { t: "ready"; fresh: boolean }
  | { t: "echo"; id: string; body: string; at: number }
  | { t: "error"; code: ErrorCode };

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
    default:
      return "unknown_type";
  }
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
